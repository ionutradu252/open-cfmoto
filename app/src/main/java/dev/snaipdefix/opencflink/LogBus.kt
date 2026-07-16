package dev.snaipdefix.opencflink

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-wide log sink so components running in the foreground service (Android Auto receiver,
 * video pipeline) and in [MainActivity] all funnel into one timestamped buffer that the on-screen
 * log view observes and the Share button exports. Every stage logs here (prefixed `[AA]`,
 * `[VIDEO]`, `[:10922]`, …) per the project's single-log-per-session debugging convention.
 */
object LogBus {
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val sb = StringBuilder(64 * 1024)

    /** Receives each already-timestamped line (MainActivity appends it to the TextView). */
    @Volatile var listener: ((String) -> Unit)? = null

    @Synchronized
    fun log(msg: String) {
        val line = "${ts.format(Date())}  $msg"
        sb.append(line).append('\n')
        if (sb.length > 512 * 1024) sb.delete(0, sb.length - 256 * 1024)
        try { listener?.invoke(line) } catch (_: Exception) {}
    }

    @Synchronized fun snapshot(): String = sb.toString()

    @Synchronized fun clear() { sb.setLength(0) }
}
