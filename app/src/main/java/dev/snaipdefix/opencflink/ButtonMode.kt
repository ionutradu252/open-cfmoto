package dev.snaipdefix.opencflink

import android.content.Context

/**
 * What the bike's Bluetooth media buttons (track/play-pause) should do:
 *   false (default) = control MEDIA — buttons skip tracks / pause music as normal.
 *   true            = control ANDROID AUTO — [MediaButtonBridge] grabs the media session and remaps
 *                     the buttons to AA D-pad navigation (music keeps playing, but the buttons no
 *                     longer skip tracks while this is on).
 *
 * Volume buttons are a separate system path and are unaffected either way. Persisted so the choice
 * survives restarts.
 */
object ButtonMode {
    private const val PREF = "button_mode"
    private const val KEY = "controlAa"

    fun isControlAa(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun set(context: Context, controlAa: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY, controlAa).apply()
    }
}
