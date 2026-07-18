package dev.snaipdefix.opencflink

import android.content.Context

/**
 * which part of AA's canvas the dash shows when we're cropping (fill mode).
 *
 * CENTER assumes AA insets its ui symmetrically inside the margin, TOP assumes it puts the ui at
 * the top and leaves the whole margin at the bottom.
 *
 * this was a wrong guess, kept as an escape hatch. it was added to explain the 800NK's taps landing
 * in the wrong place. TOP just moved the picture up and the touch went with it, obvious in
 * hindsight, since the same viewport drives both, so shifting it can't make them agree. the real
 * cause was the touch frame parse (EasyConnProber.handleTouch, y was read as u32 and ate the
 * pointer index).
 *
 * CENTER stays the default. no bike is known to want TOP; it survives because it's free and a dash
 * that really does put its margin at one edge would look exactly like the bug this was built for.
 */
object DisplayAlign {

    enum class Mode(val id: String, val label: String) {
        CENTER("center", "Centred"),
        TOP("top", "Top-aligned"),
        ;

        companion object {
            fun byId(id: String?): Mode? = entries.firstOrNull { it.id == id }
        }
    }

    private const val PREF = "display_align"
    private const val KEY = "mode"

    fun mode(context: Context): Mode =
        Mode.byId(context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null))
            ?: Mode.CENTER

    fun set(context: Context, m: Mode) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, m.id).apply()
    }

    /** read by the compositor on the gl thread, so cache it instead of hitting prefs per frame */
    @Volatile private var cached: Mode = Mode.CENTER

    fun load(context: Context) { cached = mode(context) }

    fun effective(): Mode = cached
}
