package dev.snaipdefix.opencflink

import android.content.Context

/**
 * what a handlebar gesture can be made to do. everything here works with a live AA session and no
 * touchscreen.
 *
 * not here on purpose: passing next/prev/pause back to your music player. while the buttons drive
 * AA we hold the media session ourselves, so that's the ButtonMode switch, all or nothing.
 */
enum class ButtonAction(val id: String, val label: String) {
    NONE("none", "Nothing"),
    KNOB_FORWARD("knobFwd", "Next item"),
    KNOB_BACK("knobBack", "Previous item"),
    SELECT("select", "Select"),
    BACK("back", "Back"),
    HOME("home", "Home"),
    ASSISTANT("assistant", "Assistant"),
    DPAD_UP("up", "D-pad up"),
    DPAD_DOWN("down", "D-pad down"),
    DPAD_LEFT("left", "D-pad left"),
    DPAD_RIGHT("right", "D-pad right"),
    NAV_1("nav1", "Navigate to place 1"),
    NAV_2("nav2", "Navigate to place 2"),
    NAV_3("nav3", "Navigate to place 3");

    /** show the place's own name once one is saved */
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
 * the gestures a cfmoto dash can produce.
 *
 * the buttons never come over pxc, they arrive as bluetooth avrcp in three shapes:
 *   - an absolute volume write (direction says which button)
 *   - a bigger volume jump: the dash coalesces a double tap into one message
 *   - a passthrough key: play/pause or next/prev track
 *
 * these are named after the avrcp event, because that's the only thing that's the same on every
 * dash. which physical button sends it is not: on the 450SR up/down send volume and next/prev only
 * come from a hold, on the 800NK left/right send next/prev and there's no volume at all. so the ui
 * shows BikeProfile.buttonLabel instead of [label] whenever it knows the bike, and falls back to
 * these names when it doesn't.
 *
 * naming them after the physical button (an earlier version did) is how NEXT_KEY/PREV_KEY got
 * deleted as "useless holds" on the 450SR, which quietly left the 800NK with two dead buttons since
 * those keys are all it has.
 *
 * the x2 gestures are timed, unlike the volume doubles where the jump size gives it away. on the
 * 800NK two deliberate taps land ~230ms apart and one press gives one event, so timing works there.
 * it didn't for the 450SR's enter, which is really a hold and can't repeat fast enough, hence no
 * enter x2.
 *
 * both default to NONE and the detection is skipped while they're unmapped, see
 * MediaButtonBridge.pressedWithDouble.
 */
enum class ButtonGesture(
    val id: String,
    /** fallback name, used when we don't know the bike. BikeProfile.buttonLabel wins when it does. */
    val label: String,
    /** what the dash actually sends. the certain part, so it stays the same on every bike. */
    val hint: String,
    val default: ButtonAction,
) {
    VOL_UP_PRESS("volUpPress", "Volume up press", "dash sends volume up", ButtonAction.KNOB_BACK),
    VOL_UP_DOUBLE("volUpDouble", "Volume up double tap", "one big volume jump up", ButtonAction.HOME),
    VOL_DOWN_PRESS("volDownPress", "Volume down press", "dash sends volume down", ButtonAction.KNOB_FORWARD),
    VOL_DOWN_DOUBLE("volDownDouble", "Volume down double tap", "one big volume jump down", ButtonAction.BACK),
    ENTER_PRESS("enterPress", "Play/pause press", "dash sends play/pause", ButtonAction.SELECT),
    // pref ids kept from when these were called "hold", so old mappings survive
    NEXT_KEY("volUpHold", "Next track press", "dash sends next-track", ButtonAction.KNOB_FORWARD),
    PREV_KEY("volDownHold", "Previous track press", "dash sends previous-track", ButtonAction.KNOB_BACK),
    NEXT_DOUBLE("nextDouble", "Next track double tap", "two next-track within 400ms", ButtonAction.NONE),
    PREV_DOUBLE("prevDouble", "Previous track double tap", "two previous-track within 400ms", ButtonAction.NONE),
}

/**
 * what each gesture does. unset ones fall back to the gesture's default, so "reset to defaults" is
 * just clearing the store. the defaults are the behaviour that was hardcoded before this existed.
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

    /** true when nothing has been changed, so the reset button can grey out */
    fun isAllDefault(context: Context): Boolean =
        ButtonGesture.entries.all { get(context, it) == it.default }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
