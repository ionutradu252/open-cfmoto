package dev.snaipdefix.opencflink

import android.content.Context

/**
 * remembers the screen size each bike asks for, so an unknown dash fits itself on the 2nd connect.
 *
 * chicken and egg: AA's video size is fixed when AA connects, which is before we join the bike's
 * wifi. at that point all we have is the qr (ssid + model id). the dash only says its real panel
 * later, in REQ_CONFIG_CAPTURE. and model ids aren't unique, 1000 MT-X, 800MT and 800NK all say
 * 37426, and the qr can't tell them apart.
 *
 * so: write the panel down the first time, keyed by ssid, and use it next time.
 */
object LearnedPanels {
    private const val PREF = "learned_panels"

    fun get(context: Context, ssid: String?): Pair<Int, Int>? {
        val key = key(ssid) ?: return null
        val v = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(key, null)
            ?: return null
        val parts = v.split("x")
        if (parts.size != 2) return null
        val w = parts[0].toIntOrNull() ?: return null
        val h = parts[1].toIntOrNull() ?: return null
        return if (w in 1..8192 && h in 1..8192) w to h else null
    }

    fun remember(context: Context, ssid: String?, w: Int, h: Int, log: (String) -> Unit) {
        val key = key(ssid) ?: return
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val now = "${w}x$h"
        if (prefs.getString(key, null) == now) return
        prefs.edit().putString(key, now).apply()
        log("[panel] learned: this bike's screen is $now — next connect will fit AA to it exactly")
    }

    fun forget(context: Context, ssid: String?) {
        val key = key(ssid) ?: return
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(key).apply()
    }

    private fun key(ssid: String?): String? = ssid?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * pick the AA resolution that carries a w x h panel at 1:1, or null.
     *
     * the exact match isn't a nice-to-have, it's required. margins are declared to AA as a plain
     * pixel subtraction (spec.width - panel.width in ServiceDiscoveryResponse), and that's only
     * true if the compositor maps AA 1:1. its fill scale is max(canvasW/srcW, canvasH/srcH), which
     * is exactly 1.0 only when one side matches and the other is bigger.
     *
     * get it wrong and the margins are silently off, e.g. 800x951 inside 1080x1920 scales 0.74, so
     * the "800px wide" ui band lands 593px wide. so return null instead and let the profile keep
     * its own guess. both known bikes fit the rule: 800x400 in 800x480, 720x712 in 720x1280.
     *
     * compared against the rounded canvas, since that's what the encoder gets.
     */
    fun bestFit(w: Int, h: Int): AaResolution? {
        val cw = w and 0xFFF0
        val ch = h and 0xFFF0
        val exact = AaResolution.entries.filter { r ->
            (r.w == cw && r.h >= ch) || (r.h == ch && r.w >= cw)
        }
        // least leftover canvas
        return exact.minByOrNull { (it.w - cw).toLong() * (it.h - ch) + (it.w - cw) + (it.h - ch) }
    }
}
