package dev.snaipdefix.opencflink

/**
 * hand-off between the AA receiver (which owns the encoder / VideoPipeline fed by AA's video) and
 * EasyConnProber (the bike side that pulls encoded frames). when pipeline is set the prober uses it
 * as the source instead of making its own mirror pipeline. this is how AA reaches the dash.
 */
object AaVideoBridge {
    @Volatile var pipeline: VideoPipeline? = null

    /** fires once when AA video is steady. MainActivity uses it to start the bike join. */
    @Volatile var onSteadyVideo: (() -> Unit)? = null

    /**
     * bike touchscreen -> AA. EasyConnProber decodes the dash's touch frames (pxc cmdType 32) and
     * calls this with a normalised action (0=down, 1=up, 2=move), the index of the pointer that
     * changed, and every pointer currently down as (id, bikeX, bikeY).
     *
     * all of them every time, because that's what aap's TouchEvent wants, and it's what makes pinch
     * work. AaReceiver installs a sink that maps each point into AA video space. null when there's
     * no session, touches are dropped.
     */
    @Volatile var touchSink: ((action: Int, actionIndex: Int, pointers: List<Triple<Int, Int, Int>>) -> Unit)? = null

    /** phone d-pad -> AA. the on-screen buttons call this with an android keycode (AaInput KEY_*).
     * null when there's no session. */
    @Volatile var keySink: ((keycode: Int) -> Unit)? = null

    /** rotary knob -> AA. we tell AA we're a rotary head unit, so the knob (not the d-pad) is what
     * steps focus through lists. -1 = back, +1 = forward. */
    @Volatile var scrollSink: ((delta: Int) -> Unit)? = null
}
