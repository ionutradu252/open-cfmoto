package dev.coletz.opencfmoto

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Keeps the bike connection alive across ignition cycles, and gives up (to save battery) when the
 * bike is really gone.
 *
 * Turning the bike off kills its Wi-Fi AP → [BikeWifi] fires onLost and every PXC socket dies.
 * Previously that was terminal: the user had to Stop + Start AA by hand once the bike came back.
 * Here we own the join instead: on loss we tear the dead prober down and re-request the same network
 * every [RETRY_MS]; when the bike powers back up the join succeeds and the PXC flow restarts by
 * itself. (The Wi-Fi approval is remembered per app+SSID, so re-joining doesn't re-prompt.)
 *
 * If the bike doesn't come back within [MAX_TRIES] attempts we stop Android Auto entirely — that
 * releases the wake lock, the encoder and the foreground service, so a phone left in a pocket after
 * the ride doesn't quietly drain.
 *
 * Process-global (like [BikeLink]) because the activity can be destroyed while the AA service runs.
 */
object BikeReconnector {
    private const val MAX_TRIES = 24          // ~2 min of retries
    private const val RETRY_MS = 5_000L

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var qr: QrData? = null
    @Volatile private var tries = 0
    @Volatile private var active = false

    /** Begin (or restart) the managed bike connection for [bike]. */
    fun connect(context: Context, bike: QrData) {
        val ctx = context.applicationContext
        handler.removeCallbacksAndMessages(null)
        qr = bike
        tries = 0
        active = true
        join(ctx)
    }

    /** User pressed Stop (or we're tearing down): cancel any pending retry. */
    fun cancel() {
        active = false
        tries = 0
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
                LogBus.log("→ Wi-Fi bound; starting EasyConn PXC flow …")
                try {
                    BikeLink.prober?.start(BikeWifi.currentNetwork)
                        ?: LogBus.log("no prober available — cannot start PXC flow")
                } catch (e: Exception) {
                    LogBus.log("prober start failed: $e")
                }
            },
            onLost = { onLost(ctx) },
            log = LogBus::log,
        )
    }

    private fun onLost(ctx: Context) {
        if (!active) return
        LogBus.log("bike network lost (ignition off?) — tearing down dead sockets")
        try { BikeLink.prober?.stop() } catch (_: Exception) {}

        // If Android Auto was stopped by the user, there's nothing to reconnect for.
        if (!AndroidAutoService.isRunning) { active = false; return }

        if (!AppPrefs.isReconnect(ctx)) {
            LogBus.log("auto-rejoin is off — stopping Android Auto. Tap Start when you're back on the bike.")
            active = false
            AndroidAutoService.stop(ctx)
            BikeWifi.leave(ctx, LogBus::log)
            return
        }

        if (tries >= MAX_TRIES) {
            LogBus.log("bike didn't return after $MAX_TRIES tries (~${MAX_TRIES * RETRY_MS / 1000}s) — " +
                "stopping Android Auto to save battery. Tap Start when you're back on the bike.")
            active = false
            AndroidAutoService.stop(ctx)
            BikeWifi.leave(ctx, LogBus::log)
            return
        }
        tries++
        LogBus.log("→ bike reconnect attempt $tries/$MAX_TRIES in ${RETRY_MS / 1000}s …")
        handler.postDelayed({ join(ctx) }, RETRY_MS)
    }
}
