package dev.coletz.opencfmoto

/**
 * Process-wide, lightweight live status for the on-screen status row. Components update these
 * volatiles as things happen; MainActivity polls them ~once a second to render a glanceable line
 * (no need to read the log). Kept dead simple on purpose — best-effort, not a source of truth.
 */
object AppStatus {
    /** Android Auto decode frame rate (set by AaReceiver.onFpsChanged). */
    @Volatile var aaFps = 0
    /** True while an Android Auto session is connected (handshake done → transport quit). */
    @Volatile var aaConnected = false
}
