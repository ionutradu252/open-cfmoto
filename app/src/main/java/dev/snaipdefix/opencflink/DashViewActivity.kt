package dev.snaipdefix.opencflink

import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import dev.snaipdefix.opencflink.aa.AaInput

/**
 * the dash, live, on the phone, with the phone's screen as the touchpad.
 *
 * for setting a destination or picking music while stopped, and on a bike whose dash has no
 * touchscreen (the 450SR) it's the only way to drive Android Auto directly instead of stepping
 * through it with the handlebar buttons.
 *
 * this shows the SAME composited frame the bike gets, drawn a second time by AaCompositor, so the
 * phone and the dash can't disagree. touches are scaled back into bike-canvas coordinates and pushed
 * through the same sink the dash's own touchscreen uses, so the margin/inset mapping is shared
 * rather than reimplemented, and any fix to it fixes both at once.
 */
class DashViewActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    private lateinit var hint: TextView
    private var attached = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_dash_view)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.dash_root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        // you're looking at it and tapping it; letting it sleep would be silly
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        hint = findViewById(R.id.tv_dash_hint)
        surfaceView = findViewById(R.id.dash_surface)
        surfaceView.holder.addCallback(this)
        surfaceView.setOnTouchListener { _, e -> forward(e) }

        sizeToCanvas()
    }

    /** re-fit in case the bike negotiated its canvas after this screen was opened */
    override fun onResume() {
        super.onResume()
        sizeToCanvas()
    }

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    /**
     * lock the view to the dash's shape so the picture isn't skewed and a tap maps with one uniform
     * scale. done once the container has been measured.
     */
    private fun sizeToCanvas() {
        val container = findViewById<FrameLayout>(R.id.dash_container)
        container.post {
            val canvas = AaVideoBridge.pipeline?.bikeCanvasSize()
            if (canvas == null) {
                hint.text = "Connect to the bike first — the dash view mirrors what it's showing."
                return@post
            }
            val (cw, ch) = canvas
            val availW = container.width
            val availH = container.height
            if (availW == 0 || availH == 0 || cw == 0 || ch == 0) return@post
            // fit the canvas inside the container, keeping its aspect
            val scale = minOf(availW.toFloat() / cw, availH.toFloat() / ch)
            surfaceView.layoutParams = (surfaceView.layoutParams as FrameLayout.LayoutParams).apply {
                width = (cw * scale).toInt()
                height = (ch * scale).toInt()
            }
            surfaceView.requestLayout()
            hint.text = "Tap, swipe and pinch here to drive Android Auto. ${cw}×$ch, same as the dash."
        }
    }

    // ─────────────────────────── surface ───────────────────────────

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val pipeline = AaVideoBridge.pipeline
        if (pipeline == null) {
            hint.text = "No Android Auto session — start it on the Connect tab."
            return
        }
        pipeline.setDashPreview(holder.surface, width, height)
        attached = true
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = detach()

    /** must complete before the Surface goes away, so setPreview(null) blocks. */
    private fun detach() {
        if (!attached) return
        attached = false
        AaVideoBridge.pipeline?.setDashPreview(null, 0, 0)
    }

    // ─────────────────────────── touch ───────────────────────────

    /**
     * phone touch -> bike-canvas coords -> the dash's own touch sink.
     *
     * the view is locked to the canvas aspect, so this is one uniform scale. everything downstream
     * (the margin inset, the black-bar rejection) is the code the dash already uses. the dash's
     * ghost filter lives in EasyConnProber and isn't in this path, which is right: a phone digitizer
     * doesn't invent contacts.
     */
    private fun forward(event: MotionEvent): Boolean {
        val canvas = AaVideoBridge.pipeline?.bikeCanvasSize() ?: return false
        val sink = AaVideoBridge.touchSink ?: return false
        if (surfaceView.width == 0 || surfaceView.height == 0) return false

        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> AaInput.ACTION_DOWN
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> AaInput.ACTION_UP
            MotionEvent.ACTION_MOVE -> AaInput.ACTION_MOVE
            else -> return false
        }

        val (cw, ch) = canvas
        val sx = cw.toFloat() / surfaceView.width
        val sy = ch.toFloat() / surfaceView.height
        val pointers = (0 until event.pointerCount).map { i ->
            Triple(
                event.getPointerId(i),
                (event.getX(i) * sx).toInt().coerceIn(0, cw - 1),
                (event.getY(i) * sy).toInt().coerceIn(0, ch - 1),
            )
        }
        if (pointers.isEmpty()) return false
        sink(action, event.actionIndex.coerceIn(0, pointers.size - 1), pointers)
        return true
    }
}
