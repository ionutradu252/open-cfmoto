package dev.snaipdefix.opencflink

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Keeps the bike connection alive across ignition cycles, and gives up (to save battery) when the
 * bike is really gone.
 *
 * Turning the bike off kills its Wi-Fi AP → [BikeWifi] fires onLost and every PXC socket dies. We
 * own the join, so we re-request the same network every [RETRY_MS]; when the bike powers back up the
 * join succeeds and the PXC flow restarts by itself. (The Wi-Fi approval is remembered per app+SSID,
 * so re-joining doesn't re-prompt.)
 *
 * If the bike doesn't come back within [GIVE_UP_AFTER_MS] we stop Android Auto entirely — that
 * releases the wake lock, the encoder and the foreground service, so a phone left in a pocket after
 * the ride doesn't quietly drain.
 *
 * **The give-up budget is wall-clock, not a retry count**, and a [watchdog] double-checks it
 * independently of any callback. The previous version counted retries and re-armed itself only from
 * onLost — but onLost can't fire for a network that never came back, so after exactly one attempt
 * the chain went silent and nothing ever stopped (2026-07-16 logs: 7+ min of encoding into the void,
 * ~780 pointless resyncs, wake lock held). Anything that depends on a callback arriving can strand
 * the same way; a deadline can't.
 *
 * Process-global (like [BikeLink]) because the activity can be destroyed while the AA service runs.
 */
object BikeReconnector {
    private const val RETRY_MS = 5_000L
    /** How long the bike may stay away before we shut everything down. */
    private const val GIVE_UP_AFTER_MS = 120_000L
    /** Independent safety net, in case a Wi-Fi callback never lands. */
    private const val WATCHDOG_MS = 20_000L

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var qr: QrData? = null
    @Volatile private var tries = 0
    @Volatile private var active = false
    /** When the bike went away (elapsedRealtime), or 0 while it's present. */
    @Volatile private var goneSinceMs = 0L

    /** Begin (or restart) the managed bike connection for [bike]. */
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

    /** User pressed Stop (or we're tearing down): cancel any pending retry. */
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
            // The join timed out — the bike is off or out of range. Without this the retry chain
            // simply stopped; see the class doc.
            onUnavailable = { retry(ctx, "bike AP didn't answer") },
            log = LogBus::log,
        )
    }

    private fun retry(ctx: Context, reason: String) {
        if (!active) return
        LogBus.log("$reason — tearing down dead sockets")
        try { BikeLink.prober?.stop() } catch (_: Exception) {}

        // If Android Auto was stopped by the user, there's nothing to reconnect for.
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

    /**
     * Belt and braces: re-check the deadline on a timer, whatever the callbacks are doing.
     *
     * The bug this exists for was invisible precisely because it was a callback that never came.
     */
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
