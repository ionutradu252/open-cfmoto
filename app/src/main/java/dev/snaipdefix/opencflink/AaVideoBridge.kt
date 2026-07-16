package dev.snaipdefix.opencflink

/**
 * Shared hand-off between the Android Auto receiver (which owns the H.264 encoder/[VideoPipeline]
 * fed by AA's decoded video) and [EasyConnProber] (the bike PXC pipeline that pulls encoded
 * frames). When [pipeline] is non-null, the prober uses it as the video source instead of
 * creating its own Presentation/mirror pipeline — this is how Android Auto reaches the dash.
 */
object AaVideoBridge {
    @Volatile var pipeline: VideoPipeline? = null

    /**
     * Fired once (by the AA receiver) when decoded Android Auto video reaches a steady frame
     * rate — MainActivity uses this to auto-open the bike QR scanner so the AA→bike hand-off
     * doesn't depend on the user remembering to scan after starting the receiver.
     */
    @Volatile var onSteadyVideo: (() -> Unit)? = null

    /**
     * Bike-touchscreen → Android Auto input bridge. [EasyConnProber] decodes the dash's touch frames
     * (PXC media cmdType 32) and calls this with the raw bike-canvas coordinates and a normalised
     * action (0=DOWN, 1=UP, 2=MOVE). The live AA session ([AaReceiver]) installs a sink that
     * letterbox-maps the point into AA's video space and sends it over the AAP INPUT channel. Null
     * when no AA session is active (touches are then dropped).
     */
    @Volatile var touchSink: ((action: Int, canvasX: Int, canvasY: Int) -> Unit)? = null

    /**
     * Phone D-pad → Android Auto input bridge. MainActivity's on-screen buttons call this with an
     * Android keycode (see [dev.snaipdefix.opencflink.aa.AaInput] KEY_* constants); the live AA session
     * ([AaReceiver]) installs a sink that forwards it over the AAP INPUT channel so Maps/Waze can be
     * navigated from the phone (needed because this dash is non-touch). Null when no AA session is
     * active (presses are then dropped with a log line).
     */
    @Volatile var keySink: ((keycode: Int) -> Unit)? = null

    /**
     * Rotary-knob → Android Auto bridge. AA treats this dash as a rotary head unit, where the KNOB
     * (not the D-pad) steps focus through list items. delta -1 = rotate back, +1 = forward. Wired to
     * the app's ⟲/⟳ buttons and the bike's track-skip buttons. Null when no AA session is active.
     */
    @Volatile var scrollSink: ((delta: Int) -> Unit)? = null
}
