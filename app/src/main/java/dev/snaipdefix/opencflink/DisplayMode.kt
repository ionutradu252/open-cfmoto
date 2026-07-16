package dev.snaipdefix.opencflink

import android.content.Context

/**
 * How the Android Auto image is fitted to the bike panel, as a user override of the profile default:
 *   fill      = cover the whole panel, cropping the overflow (no bars).
 *   letterbox = aspect-preserved, centered, black bars (nothing cropped).
 *
 * [override] is null until the user flips the in-app switch, in which case [effective] follows the
 * active [BikeProfile.fillCanvas]. Toggling applies live (see AaCompositor.refreshViewport) so the
 * two can be compared on the dash without a rebuild. Persisted so the choice survives restarts.
 */
object DisplayMode {
    private const val PREF = "display_mode"
    private const val KEY = "fill"

    /** null = use the profile default; true = fill; false = letterbox. In-memory, read by the compositor. */
    @Volatile var override: Boolean? = null
        private set

    /** The fill setting the compositor should use right now. */
    fun effective(): Boolean = override ?: BikeProfileHolder.active.fillCanvas

    /** Load any saved override (called once at startup). */
    fun load(context: Context) {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        override = if (p.contains(KEY)) p.getBoolean(KEY, true) else null
    }

    fun set(context: Context, fill: Boolean) {
        override = fill
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY, fill).apply()
    }
}
