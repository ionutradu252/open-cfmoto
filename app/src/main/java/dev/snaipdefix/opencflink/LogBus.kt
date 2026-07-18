package dev.snaipdefix.opencflink

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * one log buffer for everything, the service, the video pipeline, the activity. the log tab reads
 * it and share/report exports it. every stage prefixes itself: [AA], [VIDEO], [:10922] etc.
 */
object LogBus {
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val sb = StringBuilder(64 * 1024)

    /** gets each timestamped line; MainActivity appends it to the log view */
    @Volatile var listener: ((String) -> Unit)? = null

    @Synchronized
    fun log(msg: String) {
        // redact on write, not on share. with a one-tap report button nobody reads the log first,
        // so the buffer itself has to be safe to post. see LogRedactor.
        val line = "${ts.format(Date())}  ${LogRedactor.redact(msg)}"
        sb.append(line).append('\n')
        if (sb.length > 512 * 1024) sb.delete(0, sb.length - 256 * 1024)
        try { listener?.invoke(line) } catch (_: Exception) {}
    }

    @Synchronized fun snapshot(): String = sb.toString()

    @Synchronized fun clear() { sb.setLength(0) }
}
