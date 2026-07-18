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

    @Volatile var top = 0; private set
    @Volatile var bottom = 0; private set
    @Volatile var left = 0; private set
    @Volatile var right = 0; private set

    val any: Boolean get() = top != 0 || bottom != 0 || left != 0 || right != 0

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        top = p.getInt("top", 0); bottom = p.getInt("bottom", 0)
        left = p.getInt("left", 0); right = p.getInt("right", 0)
    }

    fun set(context: Context, t: Int, b: Int, l: Int, r: Int) {
        top = t.coerceIn(0, MAX); bottom = b.coerceIn(0, MAX)
        left = l.coerceIn(0, MAX); right = r.coerceIn(0, MAX)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt("top", top).putInt("bottom", bottom)
            .putInt("left", left).putInt("right", right)
            .apply()
    }

    fun summary(): String =
        if (!any) "None — Android Auto uses the whole dash."
        else "Top $top · bottom $bottom · left $left · right $right px"
}
