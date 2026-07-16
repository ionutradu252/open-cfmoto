package dev.snaipdefix.opencflink

import android.content.Context

/**
 * Something a handlebar gesture can be made to do.
 *
 * Everything here is reachable with a live Android Auto session and no touchscreen. Deliberately
 * absent: media passthrough (next/prev/pause to your music player). While the bike buttons drive
 * Android Auto we hold the media session ourselves, so "pass it back to the player" isn't a thing we
 * can do per-button — that's the [ButtonMode] master switch, all or nothing.
 */
enum class ButtonAction(val id: String, val label: String) {
    NONE("none", "Do nothing"),
    KNOB_FORWARD("knobFwd", "Knob forward (next item)"),
    KNOB_BACK("knobBack", "Knob back (previous item)"),
    SELECT("select", "Select / OK"),
    BACK("back", "Back"),
    HOME("home", "Home (app list)"),
    ASSISTANT("assistant", "Assistant (voice)"),
    DPAD_UP("up", "D-pad up"),
    DPAD_DOWN("down", "D-pad down"),
    DPAD_LEFT("left", "D-pad left"),
    DPAD_RIGHT("right", "D-pad right"),
    NAV_1("nav1", "Navigate to saved place 1"),
    NAV_2("nav2", "Navigate to saved place 2"),
    NAV_3("nav3", "Navigate to saved place 3");

    /** Nav actions read better as the place's own name once one is saved. */
    fun displayLabel(context: Context): String = when (this) {
        NAV_1 -> SavedPlaces.actionLabel(context, 0)
        NAV_2 -> SavedPlaces.actionLabel(context, 1)
        NAV_3 -> SavedPlaces.actionLabel(context, 2)
        else -> label
    }

    companion object {
        fun byId(id: String?): ButtonAction? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The gestures a CFMoto dash can produce.
 *
 * The buttons never reach us over PXC; they arrive as Bluetooth AVRCP, in three shapes:
 *   • an absolute-volume write (the direction says which button)
 *   • a bigger absolute-volume jump — the dash coalesces a double tap into ONE message
 *   • a passthrough key: play/pause, or next/previous-track
 *
 * **Which physical button makes which is not the same on every dash**, which is why [label] names
 * the AVRCP event and [hint] carries the per-bike translation. On the 450SR, ▲/▼ send volume and
 * next/prev only appear when you HOLD them; on the 800NK, a plain press of ▲/▼ sends next/prev and
 * no volume at all. Naming these by the physical gesture (as an earlier version did) meant deleting
 * [NEXT_KEY]/[PREV_KEY] as "useless holds" on the 450SR — and that silently left the 800NK's ▲/▼
 * doing nothing whatsoever, since those keys are all it has.
 *
 * Dropped, and not coming back: **double-press enter**. The dash won't emit two play/pause events
 * close enough together to tell a double from two singles. Map a volume double tap to
 * [ButtonAction.ASSISTANT] if you want voice from the bars.
 */
enum class ButtonGesture(
    val id: String,
    val label: String,
    val hint: String,
    val default: ButtonAction,
) {
    VOL_UP_PRESS("volUpPress", "▲ press", "dash sends volume up", ButtonAction.KNOB_BACK),
    VOL_UP_DOUBLE("volUpDouble", "▲ double tap", "one big volume jump up", ButtonAction.HOME),
    VOL_DOWN_PRESS("volDownPress", "▼ press", "dash sends volume down", ButtonAction.KNOB_FORWARD),
    VOL_DOWN_DOUBLE("volDownDouble", "▼ double tap", "one big volume jump down", ButtonAction.BACK),
    ENTER_PRESS("enterPress", "Enter", "dash sends play/pause", ButtonAction.SELECT),
    // Pref ids kept from when these were named "hold", so existing mappings survive the rename.
    NEXT_KEY("volUpHold", "Next-track key", "800NK: ▲ press · 450SR: ▲ hold", ButtonAction.KNOB_FORWARD),
    PREV_KEY("volDownHold", "Previous-track key", "800NK: ▼ press · 450SR: ▼ hold", ButtonAction.KNOB_BACK),
}

/**
 * What each handlebar gesture does. Unset gestures fall back to [ButtonGesture.default], so
 * "reset to defaults" is just clearing the store — and the defaults are exactly the behaviour that
 * was hard-wired before this was configurable.
 */
object ButtonMap {
    private const val PREF = "button_map"

    fun get(context: Context, gesture: ButtonGesture): ButtonAction =
        ButtonAction.byId(prefs(context).getString(gesture.id, null)) ?: gesture.default

    fun set(context: Context, gesture: ButtonGesture, action: ButtonAction) {
        prefs(context).edit().putString(gesture.id, action.id).apply()
    }

    fun resetAll(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** True when every gesture is still on its default — used to enable/disable the reset button. */
    fun isAllDefault(context: Context): Boolean =
        ButtonGesture.entries.all { get(context, it) == it.default }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
