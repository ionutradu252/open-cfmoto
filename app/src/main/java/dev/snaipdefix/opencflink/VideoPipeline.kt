package dev.snaipdefix.opencflink

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * H.264 video pipeline for MotoPlay mirroring.
 *
 *   VirtualDisplay  ──hosts──▶  Presentation ("Hello World", live clock)
 *        │ output Surface = encoder input
 *        ▼
 *   MediaCodec (video/avc, 800x384)  ──encoded access units──▶  frameQueue
 *
 * The data socket pulls one frame per REQ_RV_DATA_NEXT(114) via [pollFrame].
 * Frames are Annex-B; SPS/PPS (codec config) is prepended to the first keyframe so the
 * decoder on the bike can start.
 *
 * No MediaProjection needed: we render our OWN content onto a private VirtualDisplay
 * (the same approach as MotoPlay's SplitScreenPresentation).
 */
class VideoPipeline(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val log: (String) -> Unit,
    /**
     * When true, the pipeline only runs the H.264 encoder and exposes [encoderInputSurface] for
     * an EXTERNAL producer (the Android Auto video decoder) to render into. No Presentation /
     * MediaProjection source is created. See [encoderInputSurface] and AaVideoBridge.
     */
    private val externalSource: Boolean = false,
    /**
     * When true, an [AaCompositor] sits between the AA decoder and the encoder: the decoder renders
     * into [decoderInputSurface] and the compositor letterboxes it (aspect-preserved) into the
     * encoder canvas. The encoder is created lazily in [configureBikeCanvas] once the bike reports
     * its canvas size (so the encoder matches the bike, not a hardcoded resolution).
     */
    private val compositor: Boolean = false,
) {
    private val main = Handler(Looper.getMainLooper())
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var drainThread: Thread? = null
    private var aaCompositor: AaCompositor? = null
    private var encoderW = 0
    private var encoderH = 0
    @Volatile private var running = false

    private val frameQueue = LinkedBlockingDeque<ByteArray>(8)
    @Volatile private var codecConfig: ByteArray? = null   // SPS/PPS
    // When set, the drain loop discards encoder output until the next keyframe, so the first frame a
    // freshly-attached bike client receives is a full SPS+PPS+IDR. See onBikeDataStart().
    @Volatile private var awaitKeyframe = false
    private var resyncs = 0   // count of "bike behind" resyncs (drain thread only)

    // Diagnostic: dump the exact Annex-B H.264 access units we send to the bike into a .h264 file so
    // the stream can be inspected off-device (ffprobe/ffmpeg). Bounded to DUMP_CAP frames so it never
    // grows unbounded or stalls the send path for long. Filename is tagged by source (aa/mirror/own)
    // so an AA capture and a mirror capture can be compared directly. See docs/05-DEBUG-KNOWLEDGE.md.
    private var dumpOut: java.io.OutputStream? = null
    private var dumpFrames = 0
    private var dumpPath: String? = null

    fun start() {
        if (running) return
        running = true

        if (compositor) {
            // Android Auto (letterbox) mode: bring up the compositor now so the AA decoder has an
            // input surface and can reach steady video; the encoder is created later, once the bike
            // tells us its canvas size (see [configureBikeCanvas]).
            aaCompositor = AaCompositor(log).also { it.start() }
            log("[VIDEO] COMPOSITOR mode — decoder input ready; awaiting bike canvas")
            return
        }

        if (!createEncoder(width, height)) { stop(); return }

        if (externalSource) {
            // Android Auto mode: the AA VideoDecoder renders into inputSurface (see
            // encoderInputSurface()). No Presentation/MediaProjection source here.
            log("[VIDEO] EXTERNAL source mode (Android Auto) — encoder input surface ready")
            return
        }

        val projection = ProjectionHolder.projection
        if (projection != null) {
            log("[VIDEO] FULL-SCREEN mirror mode (MediaProjection)")
            setupProjectionDisplay(projection)
        } else {
            log("[VIDEO] own-content mode (Presentation)")
            main.post { setupDisplayAndPresentation() }
        }
    }

    /** Create + start the H.264 encoder at [w]x[h] and its drain thread. Returns false on failure. */
    private fun createEncoder(w: Int, h: Int): Boolean {
        try {
            val quality = VideoQuality.get(context)
            log("[VIDEO] quality=${quality.label} (${quality.bitrate / 1000}kbps), keyframes every ${I_FRAME_INTERVAL_S}s")
            fun baseFormat() = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                // Fewer bits here = more 2.4 GHz airtime for the Bluetooth audio. See [VideoQuality].
                setInteger(MediaFormat.KEY_BIT_RATE, quality.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_S)
                // Surface-input encoders only emit on new buffers; a STATIC screen (e.g. mirror of
                // an idle app) then produces zero frames and the bike times out. Repeat the last
                // frame if nothing new arrives so output is continuous even when the screen is still.
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L) // 100ms → ≥10fps floor
                // Force strictly-ascending output with no B-frame reordering: the bike wire format
                // sends raw access units with NO timestamps, so a decoder that received reordered
                // (B-)frames could never reassemble display order → black/garbage. Baseline already
                // forbids B-frames; this also covers the Main/High fallback path. Hint only — encoders
                // that don't support it ignore it.
                setInteger(MediaFormat.KEY_LATENCY, 1)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val forceBaseline = BikeProfileHolder.active.forceBaseline
            try {
                if (forceBaseline) {
                    val fmt = baseFormat().apply {
                        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                        setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                    }
                    c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    log("[VIDEO] configured Baseline@3.1")
                } else {
                    c.configure(baseFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    log("[VIDEO] configured default profile (Main/High)")
                }
            } catch (e: Exception) {
                log("[VIDEO] encoder configure failed ($e) — retrying default profile")
                c.reset()
                c.configure(baseFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            inputSurface = c.createInputSurface()
            c.start()
            codec = c
            encoderW = w; encoderH = h
            log("[VIDEO] encoder started ${w}x${h} h264 30fps")
            maybeStartDump()
            if (drainThread == null) drainThread = thread(name = "video-drain", isDaemon = true) { drainLoop() }
            return true
        } catch (e: Exception) {
            log("[VIDEO] createEncoder failed: $e")
            return false
        }
    }

    /**
     * Compositor mode only: create the encoder at the bike's canvas size and point the compositor
     * at it. Called when the bike's REQ_CONFIG_CAPTURE dimensions are known. Idempotent; a later
     * different size is not re-applied live (logged instead).
     */
    fun configureBikeCanvas(w: Int, h: Int) {
        if (!compositor) return
        if (codec != null) {
            if (encoderW != w || encoderH != h) {
                log("[VIDEO] bike canvas changed ${encoderW}x$encoderH → ${w}x$h — live resize unsupported, keeping ${encoderW}x$encoderH")
            }
            return
        }
        if (!createEncoder(w, h)) return
        val src = BikeProfileHolder.active.aaVideo
        val surf = inputSurface
        if (surf != null) {
            aaCompositor?.setOutput(surf, w, h, src.width, src.height)
            // Say which mode is actually in effect — this used to always claim "letterboxed", which
            // contradicted the [COMPOSITOR] line right underneath it whenever fill was on.
            log("[VIDEO] bike canvas ${w}x$h configured; AA source ${src.width}x${src.height} → " +
                if (DisplayMode.effective()) "fill" else "letterbox")
        }
    }

    /** Full-screen mirror: capture the real device display into the encoder surface. */
    private fun setupProjectionDisplay(projection: android.media.projection.MediaProjection) {
        try {
            // Required on API 34+: register a callback before creating the virtual display.
            projection.registerCallback(object : android.media.projection.MediaProjection.Callback() {
                override fun onStop() { log("[VIDEO] MediaProjection stopped") }
            }, main)
            val flags = android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            virtualDisplay = projection.createVirtualDisplay(
                "OpenCFLinkMirror", width, height, 160, flags, inputSurface, null, main,
            )
            log("[VIDEO] mirroring device screen → ${width}x${height} (letterboxed to fit)")
        } catch (e: Exception) {
            log("[VIDEO] projection display failed: $e")
        }
    }

    private fun setupDisplayAndPresentation() {
        try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            val vd = dm.createVirtualDisplay("OpenCFLink", width, height, 160, inputSurface, flags)
            virtualDisplay = vd
            val display = vd?.display ?: run { log("[VIDEO] virtualDisplay.display null"); return }

            val pres = Presentation(context, display)
            val root = LinearLayout(pres.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#0D47A1"))
            }
            val title = TextView(pres.context).apply {
                text = "Hacked by Coletz :P"
                setTextColor(Color.WHITE)
                textSize = 28f
                gravity = Gravity.CENTER
            }
            val clock = TextView(pres.context).apply {
                setTextColor(Color.parseColor("#80D8FF"))
                textSize = 20f
                gravity = Gravity.CENTER
            }
            root.addView(title)
            root.addView(clock)
            pres.setContentView(root)
            pres.show()
            presentation = pres
            log("[VIDEO] presentation shown on virtual display")

            // Animate so the stream is visibly live (forces continuous frames).
            val ticker = object : Runnable {
                var n = 0
                override fun run() {
                    if (!running) return
                    clock.text = "frame tick ${n++}"
                    main.postDelayed(this, 100)
                }
            }
            main.post(ticker)
        } catch (e: Exception) {
            log("[VIDEO] display/presentation failed: $e")
        }
    }

    private fun drainLoop() {
        val codec = this.codec ?: return
        val info = MediaCodec.BufferInfo()
        while (running) {
            val idx = try { codec.dequeueOutputBuffer(info, 100_000) } catch (e: Exception) {
                log("[VIDEO] dequeue failed: $e"); break
            }
            if (idx < 0) continue
            val buf = try { codec.getOutputBuffer(idx) } catch (e: Exception) { null }
            if (buf != null && info.size > 0) {
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                val bytes = ByteArray(info.size)
                buf.get(bytes)

                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    codecConfig = bytes   // SPS/PPS — hold, prepend to next keyframe
                    log("[VIDEO] got codec config (SPS/PPS) ${bytes.size}b")
                } else {
                    val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    if (awaitKeyframe && !isKey) {
                        // A bike client just attached but this is a P-frame — it references frames the
                        // client never received, so serving it would leave the dash decoder uninitialised
                        // (black). Drop until the next keyframe. (Buffer still released below.)
                    } else {
                        if (isKey) awaitKeyframe = false
                        val out = if (isKey && codecConfig != null) codecConfig!! + bytes else bytes
                        writeDump(out)
                        if (!frameQueue.offerLast(out)) {
                            // Queue full → the bike is consuming slower than we encode. Blindly dropping
                            // the oldest frame would break the H.264 P-frame chain and leave the dash
                            // GREEN until the next scheduled keyframe (~1s). Instead resync cleanly: drop
                            // the whole backlog and force an immediate IDR, so the bike restarts decoding
                            // on a fresh keyframe within a frame or two. Guarded so one overflow episode
                            // requests just one keyframe (awaitKeyframe drops P-frames until it lands).
                            frameQueue.clear()
                            if (!awaitKeyframe) {
                                awaitKeyframe = true
                                requestSyncFrameNow()
                                resyncs++
                                if (resyncs <= 3 || resyncs % 30 == 0)
                                    log("[VIDEO] bike behind → resync #$resyncs (drop backlog + force IDR)")
                            }
                        }
                    }
                }
            }
            try { codec.releaseOutputBuffer(idx, false) } catch (_: Exception) {}
        }
    }

    /**
     * The encoder's input Surface. In [externalSource] mode the Android Auto video decoder is
     * pointed at this surface (`VideoDecoder.setSurface`), so decoded AA frames are re-encoded to
     * the bike's 800x384 H.264 and pulled by the PXC data socket via [pollFrame]. Valid only
     * after [start].
     */
    fun encoderInputSurface(): android.view.Surface? = inputSurface

    /** Compositor mode: the surface the AA decoder renders into (letterboxed before the encoder). */
    fun decoderInputSurface(): android.view.Surface? = aaCompositor?.inputSurface

    /** Re-apply the fill/letterbox display mode live (from the in-app toggle). */
    fun refreshDisplayMode() { aaCompositor?.refreshViewport() }

    /** Compositor mode: map a bike-canvas touch point to Android Auto source coords (letterbox-aware);
     *  null if the point is in a black bar. See AaCompositor.mapCanvasToSource. */
    fun mapBikeTouchToSource(cx: Int, cy: Int): Pair<Int, Int>? = aaCompositor?.mapCanvasToSource(cx, cy)

    /** Called by the data socket on each REQ_RV_DATA_NEXT(114). Returns one access unit. */
    fun pollFrame(timeoutMs: Long): ByteArray? =
        try { frameQueue.pollFirst(timeoutMs, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { null }

    /**
     * A bike video client is (re)attaching and about to pull frames (REQ_RV_DATA_START). Guarantee the
     * FIRST access unit it receives is a full keyframe (SPS+PPS+IDR):
     *  1. flush stale frames already in the queue (they may be mid-GOP P-frames),
     *  2. drop further encoder output until the next keyframe (awaitKeyframe), and
     *  3. ask the encoder for an immediate sync frame so that keyframe arrives right away.
     *
     * Without this, Android Auto mode is black: its encoder is created at REQ_CONFIG_CAPTURE — up to a
     * second before the bike opens the data socket — so the initial IDR is long evicted from the small
     * queue and the bike starts on a P-frame with no SPS/PPS, leaving the dash decoder uninitialised.
     * (Mirror mode avoided this only by creating its encoder lazily right as the bike attaches.)
     */
    fun onBikeDataStart() {
        frameQueue.clear()
        awaitKeyframe = true
        try {
            codec?.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
            log("[VIDEO] bike attached → flushed queue + requested IDR (first frame will be a keyframe)")
        } catch (e: Exception) {
            log("[VIDEO] requestKeyframe failed: $e")
        }
    }

    /** Ask the encoder to emit an immediate keyframe (IDR) on its next output buffer. */
    private fun requestSyncFrameNow() {
        try {
            codec?.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (e: Exception) {
            log("[VIDEO] requestSyncFrame failed: $e")
        }
    }

    private fun maybeStartDump() {
        if (!DUMP_H264 || dumpOut != null) return
        try {
            val base = context.getExternalFilesDir(null) ?: run { log("[DUMP] no external files dir"); return }
            val dir = java.io.File(base, "video").apply { mkdirs() }
            val tag = when {
                compositor -> "aa"
                ProjectionHolder.projection != null -> "mirror"
                else -> "own"
            }
            val f = java.io.File(dir, "opencflink-video-$tag.h264")
            dumpOut = java.io.BufferedOutputStream(java.io.FileOutputStream(f))
            dumpFrames = 0
            dumpPath = f.absolutePath
            log("[DUMP] recording H.264 → ${f.absolutePath} (first $DUMP_CAP frames, then auto-stops)")
        } catch (e: Exception) {
            log("[DUMP] open failed: $e"); dumpOut = null
        }
    }

    /** Append one Annex-B access unit to the diagnostic dump until the cap, then close. */
    private fun writeDump(au: ByteArray) {
        val d = dumpOut ?: return
        try {
            d.write(au)
            dumpFrames++
            if (dumpFrames >= DUMP_CAP) {
                d.flush(); d.close(); dumpOut = null
                log("[DUMP] complete: $dumpPath ($dumpFrames frames) — Share Log to send it")
            }
        } catch (e: Exception) {
            try { d.close() } catch (_: Exception) {}
            dumpOut = null
            log("[DUMP] write failed: $e")
        }
    }

    fun stop() {
        running = false
        try { dumpOut?.flush(); dumpOut?.close() } catch (_: Exception) {}
        if (dumpOut != null) log("[DUMP] stopped: $dumpPath ($dumpFrames frames) — Share Log to send it")
        dumpOut = null
        drainThread?.interrupt(); drainThread = null
        try { aaCompositor?.release() } catch (_: Exception) {}
        aaCompositor = null
        main.post {
            try { presentation?.dismiss() } catch (_: Exception) {}
            presentation = null
            try { virtualDisplay?.release() } catch (_: Exception) {}
            virtualDisplay = null
        }
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
        frameQueue.clear()
        codecConfig = null
    }

    companion object {
        /**
         * Seconds between automatic keyframes.
         *
         * Was 1 — a full IDR every single second, "for late joiners". On the 2026-07-16 ride an IDR
         * measured ~20 KB against ~500 B for a P-frame, so periodic keyframes alone were roughly a
         * quarter of everything we put on the air, in bursts big enough to elbow the helmet's
         * Bluetooth audio out of the way.
         *
         * They were also redundant. The media plane is TCP, so frames don't arrive corrupted — they
         * arrive late. The only two moments a keyframe is genuinely needed are a bike attaching and
         * a resync after we drop a backlog, and both explicitly call requestSyncFrameNow(). So this
         * can be long without risking the green flashing that periodic IDRs were papering over.
         */
        const val I_FRAME_INTERVAL_S = 5

        /** Diagnostic build flag: dump the H.264 we send to the bike (bounded). Turn on to capture a
         *  .h264 of the exact wire stream for offline ffprobe analysis; off for normal use. */
        const val DUMP_H264 = false
        /** How many access units to capture before auto-stopping the dump (~20s at 30fps). */
        const val DUMP_CAP = 600
    }
}
