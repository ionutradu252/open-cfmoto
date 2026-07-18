package dev.snaipdefix.opencflink

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GPU letterbox compositor for the Android Auto → bike video path.
 *
 * the AA video decoder renders into [inputSurface] (backed by a [SurfaceTexture]). Each decoded
 * frame is drawn, aspect-preserved and centered, on a black background, into the encoder's input
 * surface (set later via [setOutput], once the bike tells us its canvas size). This decouples the
 * AA source resolution (e.g. portrait 720x1280) from the bike canvas (e.g. 800x944): the source no
 * longer gets stretched to fill a different-shaped canvas, it's letterboxed.
 *
 * [inputSurface] exists immediately (before the bike connects) so AA can reach steady video, which
 * is what triggers the bike hand-off in the first place. Until [setOutput] is called the render
 * thread just drains decoded frames (keeps AA flowing) without drawing anywhere.
 *
 * all GL work happens on a dedicated thread with the EGL context current. Based on the standard
 * SurfaceTexture→encoder pattern (Grafika).
 */
class AaCompositor(private val log: (String) -> Unit) {

    private val thread = HandlerThread("aa-compositor").apply { start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE   // keeps a current surface before output exists
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    // second output: the in-app dash view (DashViewActivity). same frame, drawn again for the phone.
    private var previewSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    @Volatile private var previewW = 0
    @Volatile private var previewH = 0
    /** someone is watching in the app, so don't drop to the idle rate even if the dash stops pulling */
    @Volatile private var previewAttached = false

    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexMatrix = 0
    private var textureId = 0
    private lateinit var surfaceTexture: SurfaceTexture

    /** where the AA decoder renders. Valid after [start]. */
    @Volatile var inputSurface: Surface? = null
        private set

    // output canvas (bike) + source (AA) dims; viewport is derived from these.
    @Volatile private var canvasW = 0
    @Volatile private var canvasH = 0
    @Volatile private var srcW = 0
    @Volatile private var srcH = 0
    @Volatile private var vpX = 0
    @Volatile private var vpY = 0
    @Volatile private var vpW = 0
    @Volatile private var vpH = 0

    // the area the picture is allowed into, i.e. the canvas minus ScreenMargins. in fill mode the
    // viewport is deliberately BIGGER than the canvas, and glViewport does not clip, so without a
    // scissor the picture would still cover the strip a margin is supposed to blank out.
    @Volatile private var clipX = 0
    @Volatile private var clipY = 0
    @Volatile private var clipW = 0
    @Volatile private var clipH = 0

    private val texMatrix = FloatArray(16)

    // Keep-alive: once we have a decoded frame, re-emit it to the encoder at ~15fps whenever the AA
    // decoder goes quiet, so the bike's media socket never times out during AA video pauses.
    @Volatile private var hasContent = false
    private var lastDrawMs = 0L
    // how long the AA decoder has been silent, and how many frames we've re-sent to cover for it
    private var keepAliveDraws = 0
    private var quietSinceMs = 0L
    private val KEEPALIVE_INTERVAL_MS = 66L  // ~15fps floor to the bike
    // cap what we hand the encoder to ~22fps. the bike pulls at ~24fps, so feeding it the decoder's
    // full ~30 overruns the send queue, and a dropped p-frame turns the dash green until the next
    // keyframe. staying just under the pull rate stops the queue backing up. updateTexImage still
    // runs every frame so the decoder never stalls, we just skip the draw for frames that are early.
    private val MIN_DRAW_INTERVAL_MS = 45L

    // ---- idle throttle ----
    /** last REQ_RV_DATA_NEXT from the dash, 0 until it has ever pulled */
    @Volatile private var lastPullMs = 0L
    @Volatile private var idle = false
    /** comfortably longer than a real gap between pulls at ~24fps */
    private val IDLE_AFTER_MS = 3_000L
    /** ~2fps while idle, enough to keep the encoder and the media socket alive */
    private val IDLE_DRAW_INTERVAL_MS = 500L
    /** set by VideoPipeline, forces a keyframe when the dash comes back */
    @Volatile var onResumeFromIdle: (() -> Unit)? = null

    // Full-screen quad (triangle strip): pos.xy + tex.uv interleaved.
    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f,
            ))
            position(0)
        }

    fun start() {
        val latch = java.util.concurrent.CountDownLatch(1)
        handler.post {
            try {
                initEgl()
                initGl()
                val spec = BikeProfileHolder.active.aaVideo
                surfaceTexture = SurfaceTexture(textureId)
                surfaceTexture.setDefaultBufferSize(spec.width, spec.height)
                surfaceTexture.setOnFrameAvailableListener({ handler.post { onFrame() } }, handler)
                inputSurface = Surface(surfaceTexture)
                handler.postDelayed(keepAlive, KEEPALIVE_INTERVAL_MS)
                log("[COMPOSITOR] ready (buffer size ${spec.width}x${spec.height}) — AA decoder input surface up (no output canvas yet)")
            } catch (e: Exception) {
                log("[COMPOSITOR] init failed: $e")
            } finally {
                latch.countDown()
            }
        }
        latch.await()
    }

    /** Recompute the fit (fill ↔ letterbox) and redraw the last frame, for the live in-app toggle. */
    fun refreshViewport() {
        handler.post {
            computeViewport()
            val mode = if (DisplayMode.effective()) "fill(crop)" else "letterbox"
            log("[COMPOSITOR] display mode → $mode rect=${vpW}x$vpH @($vpX,$vpY)")
            if (hasContent && (windowSurface != EGL14.EGL_NO_SURFACE ||
                    previewSurface != EGL14.EGL_NO_SURFACE)) drawFrame()
        }
    }

    /**
     * attach or detach the in-app dash view. pass null to detach.
     *
     * the phone gets a second copy of the frame the bike is already getting, composited exactly the
     * same way, so what's on screen is what's on the dash and a tap can be scaled straight back into
     * bike-canvas coordinates. see DashViewActivity.
     *
     * detach is synchronous on purpose: the caller is surfaceDestroyed, and the Surface must not be
     * torn down while egl still owns a window surface on it.
     */
    fun setPreview(surface: Surface?, w: Int, h: Int) {
        val latch = java.util.concurrent.CountDownLatch(1)
        handler.post {
            try {
                if (previewSurface != EGL14.EGL_NO_SURFACE) {
                    // move off it before destroying, or the driver may still have it current
                    EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
                    EGL14.eglDestroySurface(eglDisplay, previewSurface)
                    previewSurface = EGL14.EGL_NO_SURFACE
                }
                if (surface != null && surface.isValid && w > 0 && h > 0) {
                    previewSurface = EGL14.eglCreateWindowSurface(
                        eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0,
                    )
                    previewW = w; previewH = h
                    previewAttached = true
                    log("[COMPOSITOR] in-app dash view attached (${w}x$h)")
                } else {
                    previewAttached = false
                    log("[COMPOSITOR] in-app dash view detached")
                }
                if (hasContent) drawFrame()
            } catch (e: Exception) {
                log("[COMPOSITOR] setPreview failed: $e")
                previewAttached = false
            } finally {
                latch.countDown()
            }
        }
        latch.await(1, java.util.concurrent.TimeUnit.SECONDS)
    }

    /** the bike canvas we composite into, or null before the dash has negotiated one. */
    fun canvasSize(): Pair<Int, Int>? = if (canvasW > 0 && canvasH > 0) canvasW to canvasH else null

    /** Point the compositor at the encoder's input surface, sized to the bike canvas. */
    fun setOutput(encoderSurface: Surface, cw: Int, ch: Int, sw: Int, sh: Int) {
        handler.post {
            try {
                if (windowSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, windowSurface)
                    windowSurface = EGL14.EGL_NO_SURFACE
                }
                val attrs = intArrayOf(EGL14.EGL_NONE)
                windowSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, encoderSurface, attrs, 0)
                canvasW = cw; canvasH = ch; srcW = sw; srcH = sh
                computeViewport()
                val mode = if (DisplayMode.effective()) "fill(crop)" else "letterbox"
                log("[COMPOSITOR] output set canvas=${cw}x$ch src=${sw}x$sh → $mode " +
                    "align=${DisplayAlign.effective().id} rect=${vpW}x$vpH @($vpX,$vpY)")
                if (ScreenMargins.any) {
                    log("[COMPOSITOR] screen margins: ${ScreenMargins.summary()} " +
                        "(black there, and taps in it are ignored)")
                }
                // which slice of AA's canvas the dash actually shows. when taps miss, this band vs
                // where AA put its ui is the whole answer.
                // what AA was actually told, not what the (possibly refined) profile says now
                val panel = BikeProfileHolder.declaredPanel ?: BikeProfileHolder.active.panelSize
                val now = BikeProfileHolder.active.panelSize
                if (panel != null && now != null && panel != now) {
                    log("[COMPOSITOR] note: AA was told ${panel.first}x${panel.second} at service " +
                        "discovery, the refined profile wants ${now.first}x${now.second}. AA keeps " +
                        "the first one until it reconnects.")
                }
                if (panel != null) {
                    val top = mapCanvasToSource(cw / 2, 0)
                    val bottom = mapCanvasToSource(cw / 2, ch - 1)
                    log("[COMPOSITOR] dash shows AA rows ${top?.second}..${bottom?.second} of $sh " +
                        "(margins ${sw - panel.first}x${sh - panel.second} → AA keeps its UI in " +
                        "${panel.first}x${panel.second}); taps outside that band do nothing")
                }
            } catch (e: Exception) {
                log("[COMPOSITOR] setOutput failed: $e")
            }
        }
    }

    /**
     * Inverse of the letterbox: map a point in the bike canvas (the surface whose size we reported to
     * the bike, [canvasW]x[canvasH]) to the Android Auto source video space ([srcW]x[srcH]). Returns
     * null if the point falls in a black bar (outside the drawn AA rect) so the caller can drop it.
     * used to translate dashboard touch coordinates into AA input coordinates.
     */
    fun mapCanvasToSource(cx: Int, cy: Int): Pair<Int, Int>? {
        if (vpW == 0 || vpH == 0 || srcW == 0 || srcH == 0) return null
        // inside a margin: the rider sees black there, so it must not reach AA. checked before the
        // viewport, because in fill mode the viewport covers the margins.
        if (clipW > 0 && clipH > 0 &&
            (cx < clipX || cy < clipY || cx >= clipX + clipW || cy >= clipY + clipH)
        ) return null
        val rx = cx - vpX
        val ry = cy - vpY
        if (rx < 0 || ry < 0 || rx >= vpW || ry >= vpH) return null
        val sx = (rx.toLong() * srcW / vpW).toInt().coerceIn(0, srcW - 1)
        val sy = (ry.toLong() * srcH / vpH).toInt().coerceIn(0, srcH - 1)
        return sx to sy
    }

    /**
     * same, but into the space AA expects touch in: the ui area inside the margins, not the canvas.
     *
     * this is what the 800NK's "touch doesn't match the picture" actually was, after the frame
     * parse was fixed. AA scales the declared touchscreen onto video-minus-margins, so sending
     * canvas coords against a canvas-sized declaration got every y rescaled by 712/1280: taps
     * landed well above the finger and the bottom half of the panel mapped past the ui edge.
     * openauto-style units with margins declare the inset size and send inset coords, and their
     * touch works, so that's the contract. the touchscreen declaration lives in
     * ServiceDiscoveryResponse and has to stay the same size as what this returns.
     *
     * AA renders the ui centered in the canvas (the 800NK's fill+center picture is right and TOP
     * looked shifted, see DisplayAlign), so the ui origin is half the margin.
     */
    fun mapCanvasToUi(cx: Int, cy: Int): Pair<Int, Int>? {
        val (sx, sy) = mapCanvasToSource(cx, cy) ?: return null
        val panel = BikeProfileHolder.active.panelSize ?: return sx to sy
        return sourceToUi(sx, sy, srcW, srcH, panel.first, panel.second)
    }

    companion object {

        /**
         * the rect the AA frame is drawn into, in bike-canvas pixels: x, y, w, h.
         *
         * the insets shrink the area first, then the usual fit happens inside what's left, so the
         * inset edges end up plain black. this same rect backs mapCanvasToSource, which is why a tap
         * in an inset is dropped without any extra code, and why the picture and the touch can never
         * disagree with each other.
         *
         * letterbox fits inside (picture just gets smaller). fill covers (the opposite edge crops a
         * little more, which is what an inset costs you in that mode).
         */
        fun viewportFor(
            canvasW: Int, canvasH: Int, srcW: Int, srcH: Int,
            fill: Boolean, topAlign: Boolean,
            mTop: Int, mBottom: Int, mLeft: Int, mRight: Int,
        ): IntArray {
            val availW = (canvasW - mLeft - mRight).coerceAtLeast(1)
            val availH = (canvasH - mTop - mBottom).coerceAtLeast(1)
            val srcAspect = srcW.toFloat() / srcH
            val availAspect = availW.toFloat() / availH
            // letterbox fits the limiting side, fill fits the other one
            val fitWidth = if (fill) srcAspect < availAspect else srcAspect >= availAspect
            val w: Int; val h: Int
            if (fitWidth) { w = availW; h = Math.round(availW / srcAspect) }
            else { h = availH; w = Math.round(availH * srcAspect) }
            // where we crop decides whether taps land on AA's controls. see DisplayAlign.
            val x = if (topAlign) mLeft else mLeft + (availW - w) / 2
            val y = if (topAlign) mTop else mTop + (availH - h) / 2
            return intArrayOf(x, y, w, h)
        }

        /**
         * the area the picture is allowed into: the canvas minus the margins. x, y, w, h, top-down.
         *
         * separate from the viewport because in fill mode the viewport overflows the canvas on
         * purpose; this is what actually gets scissored, and what a touch is tested against.
         */
        fun clipFor(
            canvasW: Int, canvasH: Int, mTop: Int, mBottom: Int, mLeft: Int, mRight: Int,
        ): IntArray = intArrayOf(
            mLeft, mTop,
            (canvasW - mLeft - mRight).coerceAtLeast(1),
            (canvasH - mTop - mBottom).coerceAtLeast(1),
        )

        /** taps this close outside the ui band snap to its edge; further out (a letterbox black
         * bar over the margin) they're dropped. the fill crop sits 4px off the ui band on the
         * 800NK (crop offset 288, ui origin 284), so the very top of the panel needs the snap. */
        const val EDGE_SNAP_PX = 12

        /** pure so TouchUiSpaceTest can pin it without an egl context */
        fun sourceToUi(sx: Int, sy: Int, srcW: Int, srcH: Int, uiW: Int, uiH: Int): Pair<Int, Int>? {
            val ux = sx - (srcW - uiW) / 2
            val uy = sy - (srcH - uiH) / 2
            if (ux < -EDGE_SNAP_PX || uy < -EDGE_SNAP_PX ||
                ux >= uiW + EDGE_SNAP_PX || uy >= uiH + EDGE_SNAP_PX
            ) return null
            return ux.coerceIn(0, uiW - 1) to uy.coerceIn(0, uiH - 1)
        }
    }

    /**
     * the viewport that maps the AA source onto the bike canvas. two modes:
     *   letterbox, fit inside, keeps aspect, black bars
     *   fill     , cover the canvas, keeps aspect, glViewport clips the overflow so no bars
     */
    private fun computeViewport() {
        if (canvasW == 0 || canvasH == 0 || srcW == 0 || srcH == 0) return
        val r = viewportFor(
            canvasW, canvasH, srcW, srcH,
            fill = DisplayMode.effective(),
            topAlign = DisplayAlign.effective() == DisplayAlign.Mode.TOP,
            mTop = ScreenMargins.top, mBottom = ScreenMargins.bottom,
            mLeft = ScreenMargins.left, mRight = ScreenMargins.right,
        )
        vpX = r[0]; vpY = r[1]; vpW = r[2]; vpH = r[3]
        val c = clipFor(
            canvasW, canvasH,
            ScreenMargins.top, ScreenMargins.bottom, ScreenMargins.left, ScreenMargins.right,
        )
        clipX = c[0]; clipY = c[1]; clipW = c[2]; clipH = c[3]
    }

    private fun onFrame() {
        try {
            surfaceTexture.updateTexImage()
        } catch (e: Exception) {
            return
        }
        hasContent = true
        keepAliveDraws = 0
        // rate cap (see MIN_DRAW_INTERVAL_MS): take every decoded frame but only draw the newest at
        // ~22fps so the bike's slower pull never backs the queue up. updateTexImage above still runs
        // for every frame so the decoder never stalls.
        if (android.os.SystemClock.uptimeMillis() - lastDrawMs < drawIntervalMs()) return
        drawFrame()
    }

    /**
     * the dash asked for a frame, so it's showing the projection. called from pollFrame.
     *
     * if it was idle, go back to full rate and force a keyframe: after minutes at 2fps the dash's
     * decoder is way behind, and giving it a p-frame chain off a stale reference is the green screen
     * we already fought once.
     */
    fun noteBikePull() {
        val was = idle
        lastPullMs = android.os.SystemClock.uptimeMillis()
        if (was) {
            idle = false
            log("[VIDEO] dash is pulling again → back to full rate")
            onResumeFromIdle?.invoke()
        }
    }

    /**
     * how often we're allowed to draw right now.
     *
     * when the rider switches to the gauges screen the dash stops pulling but leaves the socket
     * open, so nothing tells us to stop and we keep compositing 22fps into a queue nobody drains,
     * resync after resync, burning gpu and battery for a screen that isn't on. dropping to 2fps
     * keeps the pipeline warm for a fraction of the cost and the next pull snaps it back.
     */
    private fun drawIntervalMs(): Long {
        // the in-app view is a real consumer; throttling to 2fps while someone is using the phone
        // as the touchscreen would make it feel broken
        if (previewAttached) return MIN_DRAW_INTERVAL_MS
        val since = android.os.SystemClock.uptimeMillis() - lastPullMs
        if (!idle && lastPullMs != 0L && since > IDLE_AFTER_MS) {
            idle = true
            log("[VIDEO] dash stopped pulling ${since / 1000}s ago (gauges screen?) → throttling encoder to save battery")
        }
        return if (idle) IDLE_DRAW_INTERVAL_MS else MIN_DRAW_INTERVAL_MS
    }

    /**
     * re-send the last frame if the AA decoder goes quiet, so the bike keeps getting video and never
     * hits its media socket timeout (~9s, from CLIENT_INFO socketTimeoutPeriodWifi). AA legitimately
     * pauses video during ui transitions and decoder restarts, without this the encoder starves,
     * the bike disconnects and the whole projection drops, which looks like a crash. instead the
     * dash freezes on the last frame until video comes back. runs on the gl thread.
     */
    private val keepAlive = object : Runnable {
        override fun run() {
            if (hasContent && (windowSurface != EGL14.EGL_NO_SURFACE ||
                    previewSurface != EGL14.EGL_NO_SURFACE)) {
                val idleMs = android.os.SystemClock.uptimeMillis() - lastDrawMs
                if (idleMs >= KEEPALIVE_INTERVAL_MS) {
                    // this is the path that's meant to stop the bike timing out (~9s) when the AA
                    // decoder stalls. a session that dropped anyway means this never ran or blocked,
                    // so leave a trail: silence here in the next log is itself the answer.
                    if (keepAliveDraws == 0) quietSinceMs = android.os.SystemClock.uptimeMillis()
                    keepAliveDraws++
                    if (keepAliveDraws % 15 == 0) {
                        val quietMs = android.os.SystemClock.uptimeMillis() - quietSinceMs
                        log("[COMPOSITOR] AA decoder quiet ${quietMs / 1000}s — re-sending the last " +
                            "frame to hold the bike link (${keepAliveDraws} so far)")
                    }
                    drawFrame()
                }
            }
            handler.postDelayed(this, KEEPALIVE_INTERVAL_MS)
        }
    }

    /**
     * Draw the last decoded frame into the encoder, letterboxed, and into the in-app view if it's
     * open. One decode, two blits: the phone shows exactly what the dash shows.
     */
    private fun drawFrame() {
        // nothing consuming frames yet — just drain the decoder
        if (windowSurface == EGL14.EGL_NO_SURFACE && previewSurface == EGL14.EGL_NO_SURFACE) return
        surfaceTexture.getTransformMatrix(texMatrix)

        if (windowSurface != EGL14.EGL_NO_SURFACE) {
            drawInto(windowSurface, canvasW, canvasH, vpX, vpY, vpW, vpH, clipX, clipY, clipW, clipH)
        }

        if (previewSurface != EGL14.EGL_NO_SURFACE && previewW > 0 && previewH > 0) {
            val cw = canvasW; val ch = canvasH
            if (cw > 0 && ch > 0) {
                // same composition, scaled to the view, so a tap on the phone lands where it would
                // on the dash. DashViewActivity locks the view to the canvas aspect, so this is a
                // uniform scale and nothing skews. margins scale with it, so the phone shows the
                // same black strips the dash does.
                val sx = previewW.toFloat() / cw
                val sy = previewH.toFloat() / ch
                drawInto(
                    previewSurface, previewW, previewH,
                    Math.round(vpX * sx), Math.round(vpY * sy),
                    Math.round(vpW * sx), Math.round(vpH * sy),
                    Math.round(clipX * sx), Math.round(clipY * sy),
                    Math.round(clipW * sx), Math.round(clipH * sy),
                )
            } else {
                // no bike yet, so there's no canvas to match: just show the whole AA frame
                drawInto(previewSurface, previewW, previewH, 0, 0, previewW, previewH,
                    0, 0, previewW, previewH)
            }
        }
        lastDrawMs = android.os.SystemClock.uptimeMillis()
    }

    /**
     * rects come in top-down (the space touch is mapped in); GL wants them bottom-up, so the y is
     * flipped here. these agreed as long as everything was centered, and stopped agreeing the moment
     * margins could be lopsided.
     */
    private fun drawInto(
        target: EGLSurface, targetW: Int, targetH: Int,
        vx: Int, vy: Int, vw: Int, vh: Int,
        cx: Int, cy: Int, cw: Int, ch: Int,
    ) {
        EGL14.eglMakeCurrent(eglDisplay, target, target, eglContext)

        // Black background (the letterbox bars, and any margins).
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // keep the picture out of the margins. glViewport does NOT clip, and in fill mode the
        // viewport is bigger than the canvas, so without this a margin would blank nothing at all.
        val clipped = cw in 1 until targetW || ch in 1 until targetH
        if (clipped) {
            GLES20.glScissor(cx, targetH - (cy + ch), cw, ch)
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        }

        GLES20.glViewport(vx, targetH - (vy + vh), vw, vh)
        GLES20.glUseProgram(program)

        quad.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPosition)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTexCoord)

        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
        if (clipped) GLES20.glDisable(GLES20.GL_SCISSOR_TEST)

        // Monotonic presentation time so repeated (keep-alive) frames aren't dropped as duplicate PTS.
        EGLExt.eglPresentationTimeANDROID(eglDisplay, target, System.nanoTime())
        // swap blocks when the encoder has no free input buffer. if that happens the whole gl thread
        // stops, keep-alive included, and the bike starves with nothing in the log to show why.
        val t0 = android.os.SystemClock.uptimeMillis()
        EGL14.eglSwapBuffers(eglDisplay, target)
        val swapMs = android.os.SystemClock.uptimeMillis() - t0
        if (swapMs > 250) log("[COMPOSITOR] encoder back-pressure: swap blocked ${swapMs}ms")
    }

    fun release() {
        handler.removeCallbacks(keepAlive)
        handler.post {
            try { inputSurface?.release() } catch (_: Exception) {}
            try { if (::surfaceTexture.isInitialized) surfaceTexture.release() } catch (_: Exception) {}
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (windowSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, windowSurface)
                if (previewSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, previewSurface)
                if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, pbuffer)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
        }
        thread.quitSafely()
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
        val cfgAttrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfig = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, cfgAttrs, 0, configs, 0, 1, numConfig, 0)
        eglConfig = configs[0]
        val ctxAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttrs, 0)
        // 1x1 pbuffer so the context can be current before we have an output window surface.
        pbuffer = EGL14.eglCreatePbufferSurface(
            eglDisplay, eglConfig, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
    }

    private fun initGl() {
        val vs = """
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """.trimIndent()
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() { gl_FragColor = texture2D(sTexture, vTexCoord); }
        """.trimIndent()
        program = linkProgram(vs, fs)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        Matrix.setIdentityM(texMatrix, 0)

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun linkProgram(vsSrc: String, fsSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val err = GLES20.glGetProgramInfoLog(p)
            throw RuntimeException("program link failed: $err")
        }
        return p
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val err = GLES20.glGetShaderInfoLog(s)
            throw RuntimeException("shader compile failed: $err")
        }
        return s
    }
}
