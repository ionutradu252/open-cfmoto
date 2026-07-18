package dev.snaipdefix.opencflink

import android.content.Context

/** app settings that don't belong to any one subsystem */
object AppPrefs {
    private const val PREF = "app_prefs"
    private const val KEY_AUTO_CONNECT = "autoConnect"
    private const val KEY_RECONNECT = "reconnect"
    private const val KEY_AA_AUDIO = "aaAudio"

    /** start android auto when the app opens, once a bike is saved */
    fun isAutoConnect(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_CONNECT, true)

    fun setAutoConnect(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTO_CONNECT, on).apply()

    /** rejoin by itself when the bike's ap comes back, see BikeReconnector */
    fun isReconnect(context: Context): Boolean = prefs(context).getBoolean(KEY_RECONNECT, true)

    fun setReconnect(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_RECONNECT, on).apply()

    /**
     * play AA's own audio (nav voice, media) over the bike link.
     *
     * not implemented. we advertise a system-sound sink and throw the pcm away; AA's audio comes out
     * of the phone and reaches the helmet over bluetooth anyway. the pref is here so the plumbing
     * exists, the switch stays disabled until there's something behind it.
     */
    fun isAaAudio(context: Context): Boolean = prefs(context).getBoolean(KEY_AA_AUDIO, false)

    fun setAaAudio(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_AA_AUDIO, on).apply()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
