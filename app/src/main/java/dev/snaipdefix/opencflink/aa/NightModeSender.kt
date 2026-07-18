// keeps AA's day/night theme in sync with the actual sky
package dev.snaipdefix.opencflink.aa

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.snaipdefix.opencflink.NightMode

/**
 * answers AA's NIGHT sensor subscription, and keeps answering.
 *
 * AA subscribes once per session and then waits to be told when it changes, it never polls, so one
 * missed update leaves the dash wrong until the next connect. riding through sunset is exactly the
 * case that matters, hence the tick instead of a single send at connect.
 *
 * only sends on change, or the log fills up with a night line every minute.
 */
class NightModeSender(
    private val context: Context,
    private val transport: AapTransport,
    private val log: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var last: Boolean? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        instance = this
        handler.post(tick)
    }

    fun stop() {
        running = false
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
    }

    /** AA just subscribed, or the setting changed. tell it now even if it's the same. */
    fun pushNow() {
        handler.post { push(force = true) }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            push(force = false)
            handler.postDelayed(this, CHECK_MS)
        }
    }

    private fun push(force: Boolean) {
        if (!running) return
        val night = try { NightMode.isNight(context) } catch (e: Exception) {
            log("[AA] night mode check failed: $e"); return
        }
        if (!force && night == last) return
        last = night
        try {
            transport.send(NightModeEvent(night))
            log("[AA] night mode → ${if (night) "NIGHT" else "DAY"} (${NightMode.reason(context)})")
        } catch (e: Exception) {
            log("[AA] night mode send failed: $e")
        }
    }

    companion object {
        /** sunset doesn't sneak up on you, a minute of lag is nothing */
        private const val CHECK_MS = 60_000L

        /** the live sender, so the settings override can push straight away */
        @Volatile var instance: NightModeSender? = null
            private set
    }
}
