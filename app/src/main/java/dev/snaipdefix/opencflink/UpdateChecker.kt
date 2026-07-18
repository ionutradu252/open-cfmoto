package dev.snaipdefix.opencflink

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * "is there a newer apk on github?"
 *
 * sideloaded builds have no store to update them, so people end up riding on whatever they installed
 * months ago and reporting bugs that were fixed weeks back. this reads the latest github release,
 * shows its notes, and hands the apk url to the browser. we never install anything ourselves: the
 * download goes through the browser and Android's own installer, so there's no scary
 * REQUEST_INSTALL_PACKAGES on an app that's already asking for a lot.
 *
 * NOTE the bike's wifi has no internet. when the projection is running the phone is bound to that
 * network and this will simply fail, which is why the automatic check only runs before connecting
 * and failures are silent. the manual button in settings says so out loud.
 */
object UpdateChecker {

    /** change this if the project moves. */
    const val REPO = "ionutradu252/open-cflink"

    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val PREF = "updates"
    private const val KEY_LAST_CHECK = "lastCheckMs"
    private const val KEY_SKIPPED = "skippedVersion"

    /** don't nag: at most one automatic check a day. the manual button ignores this. */
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    data class Release(
        val version: String,
        val notes: String,
        /** direct .apk asset, null if the release only has source archives */
        val apkUrl: String?,
        val pageUrl: String,
    ) {
        /** what to open: the apk if there is one, else the release page */
        val downloadUrl: String get() = apkUrl ?: pageUrl
    }

    /** blocking; call off the main thread. null = no release, no internet, or nothing newer. */
    fun check(context: Context, manual: Boolean): Release? {
        if (!manual && !dueForCheck(context)) return null
        val release = fetch() ?: return null
        markChecked(context)
        if (!isNewer(release.version, BuildConfig.VERSION_NAME)) return null
        if (!manual && release.version == skipped(context)) return null
        return release
    }

    private fun fetch(): Release? = try {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            // github refuses requests with no user agent
            setRequestProperty("User-Agent", "OpenCFLink")
        }
        try {
            if (conn.responseCode != 200) null else {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val assets = json.optJSONArray("assets")
                var apk: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                            apk = a.optString("browser_download_url"); break
                        }
                    }
                }
                Release(
                    version = json.optString("tag_name").ifBlank { json.optString("name") },
                    notes = json.optString("body").trim(),
                    apkUrl = apk,
                    pageUrl = json.optString("html_url", "https://github.com/$REPO/releases"),
                )
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null   // offline, on the bike's wifi, rate limited: all the same to us
    }

    /**
     * is [latest] a newer version than [current]?
     *
     * compares the numbers in order and ignores everything else, so "v0.1.3", "0.1.3" and
     * "0.1.3-beta" all read the same. a missing part counts as 0, which makes 0.1.2.1 newer than
     * 0.1.2 (that's exactly how this project numbers its hotfixes).
     */
    internal fun isNewer(latest: String, current: String): Boolean {
        val a = numbers(latest)
        val b = numbers(current)
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(v: String): List<Int> =
        Regex("\\d+").findAll(v).map { it.value.toIntOrNull() ?: 0 }.toList()

    // ---- nagging control ----

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun dueForCheck(context: Context): Boolean =
        System.currentTimeMillis() - prefs(context).getLong(KEY_LAST_CHECK, 0L) > CHECK_INTERVAL_MS

    private fun markChecked(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
    }

    private fun skipped(context: Context): String? = prefs(context).getString(KEY_SKIPPED, null)

    /** "don't tell me about this one again" — the next release still prompts. */
    fun skip(context: Context, version: String) {
        prefs(context).edit().putString(KEY_SKIPPED, version).apply()
    }
}
