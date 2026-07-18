package dev.snaipdefix.opencflink

import android.content.Context

/**
 * black borders held back from the edges of the dash, in dash pixels. all zero by default.
 *
 * the 800NK draws MotoPlay's own pull-down handle across the top of the panel. that strip belongs to
 * the dash, not to us: a swipe starting there yanks the projection away and there is nothing we can
 * send that stops it. what we can do is stop Android Auto putting anything under it, so the rider
 * never has a reason to touch there in the first place. inset the top and the strip is just black.
 *
 * per edge, not one switch, because every dash puts its furniture somewhere different, and the next
 * bike nobody has tested yet can be fixed from the settings screen instead of a new build.
 *
 * touch comes along for free: the same viewport draws the picture and backs mapCanvasToSource, so a
 * tap in an inset falls outside the drawn rect and is dropped without any extra code.
 *
 * what it costs depends on the fit mode. letterbox just draws the picture smaller. fill still covers
 * what is left, so the opposite edge crops a little more, which is the trade for getting the strip.
 */
object ScreenMargins {
    private const val PREF = "screen_margins"

    /** a sane ceiling so a typo can't inset the picture out of existence */
    const val MAX = 200

    @Volatile private var userTop = 0
    @Volatile private var userBottom = 0
    @Volatile private var userLeft = 0
    @Volatile private var userRight = 0

    /** false until the rider saves their own, so a known dash's default can apply instead */
    @Volatile var customised = false; private set

    // the profile's default until someone overrides it, so a dash we already understand arrives set
    // up rather than needing every owner to rediscover the same number.
    val top: Int get() = if (customised) userTop else BikeProfileHolder.active.defaultMargins[0]
    val bottom: Int get() = if (customised) userBottom else BikeProfileHolder.active.defaultMargins[1]
    val left: Int get() = if (customised) userLeft else BikeProfileHolder.active.defaultMargins[2]
    val right: Int get() = if (customised) userRight else BikeProfileHolder.active.defaultMargins[3]

    val any: Boolean get() = top != 0 || bottom != 0 || left != 0 || right != 0

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        customised = wasCustomised(
            hasFlag = p.contains("customised"),
            flag = p.getBoolean("customised", false),
            hasEdgeKeys = p.contains("top") || p.contains("bottom") ||
                p.contains("left") || p.contains("right"),
        )
        userTop = p.getInt("top", 0); userBottom = p.getInt("bottom", 0)
        userLeft = p.getInt("left", 0); userRight = p.getInt("right", 0)
    }

    /**
     * did the rider set these themselves?
     *
     * 0.1.3 stored the four edges with no flag, so reading a missing flag as "no" would quietly drop
     * an upgrader's margins back to the profile default. if the edge keys are there at all, someone
     * saved them, including if they saved zeros.
     */
    internal fun wasCustomised(hasFlag: Boolean, flag: Boolean, hasEdgeKeys: Boolean): Boolean =
        if (hasFlag) flag else hasEdgeKeys

    fun set(context: Context, t: Int, b: Int, l: Int, r: Int) {
        userTop = t.coerceIn(0, MAX); userBottom = b.coerceIn(0, MAX)
        userLeft = l.coerceIn(0, MAX); userRight = r.coerceIn(0, MAX)
        customised = true
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("customised", true)
            .putInt("top", userTop).putInt("bottom", userBottom)
            .putInt("left", userLeft).putInt("right", userRight)
            .apply()
    }

    /** back to whatever this dash's profile suggests, not necessarily zero */
    fun reset(context: Context) {
        customised = false
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("customised", false).apply()
    }

    fun summary(): String = when {
        !any -> "None — Android Auto uses the whole dash."
        !customised -> "Top $top · bottom $bottom · left $left · right $right px (default for this dash)"
        else -> "Top $top · bottom $bottom · left $left · right $right px"
    }
}
