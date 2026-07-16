package dev.coletz.opencfmoto

import android.content.Context

/** App-level preferences that aren't tied to a specific subsystem. Persisted across relaunches. */
object AppPrefs {
    private const val PREF = "app_prefs"
    private const val KEY_AUTO_CONNECT = "autoConnect"
    private const val KEY_RECONNECT = "reconnect"
    private const val KEY_AA_AUDIO = "aaAudio"

    /** Start Android Auto automatically when the app opens (once a bike has been saved). */
    fun isAutoConnect(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_CONNECT, true)

    fun setAutoConnect(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTO_CONNECT, on).apply()

    /** Rejoin the bike by itself when its AP returns after an ignition cycle — see [BikeReconnector]. */
    fun isReconnect(context: Context): Boolean = prefs(context).getBoolean(KEY_RECONNECT, true)

    fun setReconnect(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_RECONNECT, on).apply()

    /**
     * Play Android Auto's own audio (nav voice, media) through the bike link.
     *
     * NOT IMPLEMENTED — we advertise only a system-sound sink and discard its PCM
     * ([ServiceDiscoveryResponse]); AA's audio comes out of the phone, which reaches the helmet over
     * Bluetooth anyway. The pref exists so the plumbing is ready; the switch stays disabled until
     * there's something behind it.
     */
    fun isAaAudio(context: Context): Boolean = prefs(context).getBoolean(KEY_AA_AUDIO, false)

    fun setAaAudio(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_AA_AUDIO, on).apply()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
