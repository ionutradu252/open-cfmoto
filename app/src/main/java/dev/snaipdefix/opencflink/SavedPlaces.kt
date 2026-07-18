package dev.snaipdefix.opencflink

import android.content.Context

/**
 * places you can navigate to from a handlebar button without touching the phone. three slots, each
 * a name ("home") and whatever maps accepts as a query, address, place name, or lat,lng.
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

    /** how the slot reads in the action picker */
    fun actionLabel(context: Context, slot: Int): String {
        if (!isSet(context, slot)) return "Navigate to place ${slot + 1} (empty)"
        val n = name(context, slot)
        return "Navigate to ${n.ifBlank { query(context, slot) }}"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
