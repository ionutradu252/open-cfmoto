package dev.coletz.opencfmoto

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
 * The AA video decoder renders into [inputSurface] (backed by a [SurfaceTexture]). Each decoded
 * frame is drawn — aspect-preserved and centered, on a black background — into the encoder's input
 * surface (set later via [setOutput], once the bike tells us its canvas size). This decouples the
 * AA source resolution (e.g. portrait 720x1280) from the bike canvas (e.g. 800x944): the source no
 * longer gets stretched to fill a different-shaped canvas — it's letterboxed.
 *
 * [inputSurface] exists immediately (before the bike connects) so AA can reach steady video, which
 * is what triggers the bike hand-off in the first place. Until [setOutput] is called the render
 * thread just drains decoded frames (keeps AA flowing) without drawing anywhere.
 *
 * All GL work happens on a dedicated thread with the EGL context current. Based on the standard
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

    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexMatrix = 0
    private var textureId = 0
    private lateinit var surfaceTexture: SurfaceTexture

    /** Where the AA decoder renders. Valid after [start]. */
    @Volatile var inputSurface: Surface? = null
        private set

    // Output canvas (bike) + source (AA) dims; viewport is derived from these.
    @Volatile private var canvasW = 0
    @Volatile private var canvasH = 0
    @Volatile private var srcW = 0
    @Volatile private var srcH = 0
    @Volatile private var vpX = 0
    @Volatile private var vpY = 0
    @Volatile private var vpW = 0
    @Volatile private var vpH = 0

    private val texMatrix = FloatArray(16)

    // Keep-alive: once we have a decoded frame, re-emit it to the encoder at ~15fps whenever the AA
    // decoder goes quiet, so the bike's media socket never times out during AA video pauses.
    @Volatile private var hasContent = false
    private var lastDrawMs = 0L
    private val KEEPALIVE_INTERVAL_MS = 66L  // ~15 fps floor to the bike

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
                log("[COMPOSITOR] output set canvas=${cw}x$ch src=${sw}x$sh → letterbox rect=${vpW}x$vpH @($vpX,$vpY)")
            } catch (e: Exception) {
                log("[COMPOSITOR] setOutput failed: $e")
            }
        }
    }

    /**
     * Inverse of the letterbox: map a point in the bike canvas (the surface whose size we reported to
     * the bike, [canvasW]x[canvasH]) to the Android Auto source video space ([srcW]x[srcH]). Returns
     * null if the point falls in a black bar (outside the drawn AA rect) so the caller can drop it.
     * Used to translate dashboard touch coordinates into AA input coordinates.
     */
    fun mapCanvasToSource(cx: Int, cy: Int): Pair<Int, Int>? {
        if (vpW == 0 || vpH == 0 || srcW == 0 || srcH == 0) return null
        val rx = cx - vpX
        val ry = cy - vpY
        if (rx < 0 || ry < 0 || rx >= vpW || ry >= vpH) return null
        val sx = (rx.toLong() * srcW / vpW).toInt().coerceIn(0, srcW - 1)
        val sy = (ry.toLong() * srcH / vpH).toInt().coerceIn(0, srcH - 1)
        return sx to sy
    }

    /** Fit src aspect inside the canvas, centered (letterbox). */
    private fun computeViewport() {
        if (canvasW == 0 || canvasH == 0 || srcW == 0 || srcH == 0) return
        val srcAspect = srcW.toFloat() / srcH
        val canvasAspect = canvasW.toFloat() / canvasH
        if (srcAspect < canvasAspect) {
            // source narrower than canvas → fit height, black bars left/right
            vpH = canvasH
            vpW = Math.round(canvasH * srcAspect)
        } else {
            // source wider → fit width, black bars top/bottom
            vpW = canvasW
            vpH = Math.round(canvasW / srcAspect)
        }
        vpX = (canvasW - vpW) / 2
        vpY = (canvasH - vpH) / 2
    }

    private fun onFrame() {
        try {
            surfaceTexture.updateTexImage()
        } catch (e: Exception) {
            return
        }
        hasContent = true
        drawFrame()
    }

    /**
     * Re-emit the last decoded frame to the encoder if the Android Auto decoder has gone quiet, so the
     * bike keeps receiving video and never hits its media-socket timeout (CLIENT_INFO
     * socketTimeoutPeriodWifi, ~9s). AA legitimately pauses video during UI transitions (opening the
     * app launcher, an incoming call) and while the decoder restarts/recovers — without this the
     * encoder starves, the bike disconnects, and the whole projection drops (looks like a crash).
     * The dash instead shows a frozen last frame until live video resumes. Runs on the GL thread.
     */
    private val keepAlive = object : Runnable {
        override fun run() {
            if (hasContent && windowSurface != EGL14.EGL_NO_SURFACE) {
                val idleMs = android.os.SystemClock.uptimeMillis() - lastDrawMs
                if (idleMs >= KEEPALIVE_INTERVAL_MS) drawFrame()
            }
            handler.postDelayed(this, KEEPALIVE_INTERVAL_MS)
        }
    }

    /** Draw the current SurfaceTexture content (last decoded frame) into the encoder, letterboxed. */
    private fun drawFrame() {
        if (windowSurface == EGL14.EGL_NO_SURFACE) return  // no output canvas yet — just drain
        surfaceTexture.getTransformMatrix(texMatrix)

        EGL14.eglMakeCurrent(eglDisplay, windowSurface, windowSurface, eglContext)

        // Black background (the letterbox bars).
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glViewport(vpX, vpY, vpW, vpH)
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

        // Monotonic presentation time so repeated (keep-alive) frames aren't dropped as duplicate PTS.
        EGLExt.eglPresentationTimeANDROID(eglDisplay, windowSurface, System.nanoTime())
        EGL14.eglSwapBuffers(eglDisplay, windowSurface)
        lastDrawMs = android.os.SystemClock.uptimeMillis()
    }

    fun release() {
        handler.removeCallbacks(keepAlive)
        handler.post {
            try { inputSurface?.release() } catch (_: Exception) {}
            try { if (::surfaceTexture.isInitialized) surfaceTexture.release() } catch (_: Exception) {}
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (windowSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, windowSurface)
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
