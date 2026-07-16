package dev.coletz.opencfmoto

import android.content.Context

/**
 * Persists the bike's Wi-Fi credentials (from the pairing QR) so the app can auto-connect on the
 * next launch instead of re-scanning every ride. The SSID/password are stable across scans (only the
 * `sn` nonce changes), so a single saved copy is reusable. Stored in a private SharedPreferences.
 */
object BikeCreds {
    private const val PREF = "bike_creds"

    fun save(context: Context, qr: QrData) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("ssid", qr.ssid)
            .putString("pwd", qr.pwd)
            .putString("modelId", qr.modelId)
            .putInt("action", qr.action)
            .apply()
    }

    /** The saved bike as a minimal [QrData] (enough for profile selection + Wi-Fi join), or null. */
    fun load(context: Context): QrData? {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val ssid = p.getString("ssid", null) ?: return null
        val pwd = p.getString("pwd", null) ?: return null
        return QrData(
            ssid = ssid, pwd = pwd, auth = null, mac = null, name = null,
            action = p.getInt("action", 0), modelId = p.getString("modelId", null),
            sn = null, channel = null,
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
