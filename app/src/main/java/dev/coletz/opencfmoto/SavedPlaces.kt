package dev.coletz.opencfmoto

import android.content.Context

/**
 * Destinations you can start turn-by-turn to from a handlebar button, without touching the phone.
 *
 * Three slots, each a name ("Home") and whatever Google Maps accepts as a query (an address, a
 * place name, or `lat,lng`). Mapped to a gesture via [ButtonAction.NAV_1]/`NAV_2`/`NAV_3`.
 */
object SavedPlaces {
    const val COUNT = 3
    private const val PREF = "saved_places"

    fun name(context: Context, slot: Int): String =
        prefs(context).getString("name$slot", "") ?: ""

    fun query(context: Context, slot: Int): String =
        prefs(context).getString("query$slot", "") ?: ""

    fun set(context: Context, slot: Int, name: String, query: String) {
        prefs(context).edit()
            .putString("name$slot", name.trim())
            .putString("query$slot", query.trim())
            .apply()
    }

    fun isSet(context: Context, slot: Int): Boolean = query(context, slot).isNotBlank()

    /** How this slot reads in the action picker — its own name once there is one. */
    fun actionLabel(context: Context, slot: Int): String {
        if (!isSet(context, slot)) return "Navigate to saved place ${slot + 1} (not set yet)"
        val n = name(context, slot)
        return "Navigate: ${n.ifBlank { query(context, slot) }}"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
