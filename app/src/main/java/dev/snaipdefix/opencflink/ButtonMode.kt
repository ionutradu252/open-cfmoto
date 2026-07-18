package dev.snaipdefix.opencflink

import android.content.Context

/**
 * what the bike's media buttons do:
 *   false (default) = normal media, they skip tracks / pause music
 *   true = drive android auto. MediaButtonBridge takes the media session and remaps them, so your
 *   music player pauses for as long as it's on.
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
