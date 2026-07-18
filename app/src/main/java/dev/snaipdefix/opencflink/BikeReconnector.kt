package dev.snaipdefix.opencflink

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * keeps the bike connection alive across ignition cycles, and gives up when the bike is really gone
 * so the phone doesn't drain.
 *
 * ignition off kills the bike's ap, BikeWifi fires onLost and every pxc socket dies. we own the join
 * so we re-request the same network every RETRY_MS; when the bike comes back the join succeeds and
 * the pxc flow restarts itself. (the wifi approval is remembered per app+ssid so it doesn't
 * re-prompt.)
 *
 * if it doesn't come back within GIVE_UP_AFTER_MS we stop android auto, which releases the wake
 * lock, the encoder and the service.
 *
 * the budget is wall clock, not a retry count, and the watchdog checks it independently of any
 * callback. the old version counted retries and only re-armed from onLost, which can't fire for a
 * network that never came back, so after one attempt it went silent and nothing ever stopped
 * (2026-07-16 logs: 7+ min encoding into nothing, ~780 pointless resyncs, wake lock held). anything
 * that waits on a callback can strand like that; a deadline can't.
 *
 * process global (like BikeLink) because the activity can die while the service runs.
 */
object BikeReconnector {
    private const val RETRY_MS = 5_000L
    /** how long the bike can be gone before we shut everything down */
    private const val GIVE_UP_AFTER_MS = 120_000L
    /** safety net in case a wifi callback never lands */
    private const val WATCHDOG_MS = 20_000L

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var qr: QrData? = null
    @Volatile private var tries = 0
    @Volatile private var active = false
    /** when the bike went away (elapsedRealtime), 0 while it's here */
    @Volatile private var goneSinceMs = 0L

    /** start (or restart) the managed connection */
    fun connect(context: Context, bike: QrData) {
        val ctx = context.applicationContext
        handler.removeCallbacksAndMessages(null)
        qr = bike
        tries = 0
        goneSinceMs = 0L
        active = true
        join(ctx)
        handler.postDelayed(watchdog(ctx), WATCHDOG_MS)
    }

    /** stop pressed, or we're tearing down: cancel any pending retry */
    fun cancel() {
        active = false
        tries = 0
        goneSinceMs = 0L
        qr = null
        handler.removeCallbacksAndMessages(null)
    }

    private fun join(ctx: Context) {
        val bike = qr ?: return
        if (!active) return
        BikeWifi.join(
            context = ctx,
            ssid = bike.ssid,
            psk = bike.pwd,
            onAvailable = {
                tries = 0
                goneSinceMs = 0L
                LogBus.log("→ Wi-Fi bound; starting EasyConn PXC flow …")
                try {
                    BikeLink.prober?.start(BikeWifi.currentNetwork)
                        ?: LogBus.log("no prober available — cannot start PXC flow")
                } catch (e: Exception) {
                    LogBus.log("prober start failed: $e")
                }
            },
            onLost = { retry(ctx, "bike network lost (ignition off?)") },
            // join timed out, bike is off or out of range. without this the retry chain just
            // stopped, see the class doc.
            onUnavailable = { retry(ctx, "bike AP didn't answer") },
            log = LogBus::log,
        )
    }

    private fun retry(ctx: Context, reason: String) {
        if (!active) return
        LogBus.log("$reason — tearing down dead sockets")
        try { BikeLink.prober?.stop() } catch (_: Exception) {}

        // if the user stopped AA there's nothing to reconnect for
        if (!AndroidAutoService.isRunning) { active = false; return }

        if (!AppPrefs.isReconnect(ctx)) {
            giveUp(ctx, "auto-rejoin is off")
            return
        }

        if (goneSinceMs == 0L) goneSinceMs = SystemClock.elapsedRealtime()
        val goneMs = SystemClock.elapsedRealtime() - goneSinceMs
        if (goneMs >= GIVE_UP_AFTER_MS) {
            giveUp(ctx, "bike didn't come back within ${GIVE_UP_AFTER_MS / 1000}s")
            return
        }

        tries++
        LogBus.log(
            "→ bike reconnect attempt $tries (gone ${goneMs / 1000}s of ${GIVE_UP_AFTER_MS / 1000}s) " +
                "in ${RETRY_MS / 1000}s …"
        )
        handler.postDelayed({ join(ctx) }, RETRY_MS)
    }

    /** re-check the deadline on a timer whatever the callbacks do. the bug this exists for was a
     * callback that never came, so it was invisible. */
    private fun watchdog(ctx: Context): Runnable = object : Runnable {
        override fun run() {
            if (!active) return
            if (!AndroidAutoService.isRunning) { active = false; return }
            val connected = BikeWifi.currentNetwork != null
            if (connected) {
                goneSinceMs = 0L
            } else {
                if (goneSinceMs == 0L) goneSinceMs = SystemClock.elapsedRealtime()
                val goneMs = SystemClock.elapsedRealtime() - goneSinceMs
                if (goneMs >= GIVE_UP_AFTER_MS) {
                    giveUp(ctx, "watchdog: bike gone ${goneMs / 1000}s with no working link")
                    return
                }
            }
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    private fun giveUp(ctx: Context, why: String) {
        LogBus.log("$why — stopping Android Auto to save battery. Tap Start when you're back on the bike.")
        active = false
        goneSinceMs = 0L
        handler.removeCallbacksAndMessages(null)
        AndroidAutoService.stop(ctx)
        BikeWifi.leave(ctx, LogBus::log)
    }
}
