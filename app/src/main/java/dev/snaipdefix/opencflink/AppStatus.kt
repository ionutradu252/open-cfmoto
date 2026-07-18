package dev.snaipdefix.opencflink

/**
 * live status for the header row. components set these as things happen, MainActivity reads them
 * once a second. best effort, not a source of truth.
 */
object AppStatus {
    /** AA decode frame rate, set by AaReceiver.onFpsChanged */
    @Volatile var aaFps = 0
    /** true while an AA session is up (handshake done until transport quit) */
    @Volatile var aaConnected = false
    /** bike wifi frequency in mhz, 0 if unknown. 2.4ghz means the video shares the band with the
     * helmet's bluetooth, first thing to check on an audio complaint. */
    @Volatile var wifiMhz = 0
    /** capture size the dash last asked for, e.g. "800x400" */
    @Volatile var panelRequested: String? = null
}
