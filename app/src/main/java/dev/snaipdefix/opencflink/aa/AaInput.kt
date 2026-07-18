// uses AGPLv3 code/protocol ported from headunit-revived.
// input into the AA session: touches from the dash (pxc cmdType 32) and keys from the app, over the
// aap INPUT channel.
package dev.snaipdefix.opencflink.aa

import android.os.SystemClock
import dev.snaipdefix.opencflink.aa.proto.Input

/**
 * sends touch/key events to AA over the INPUT channel. coordinates must already be in AA video space
 * (0..width, 0..height), the caller maps them from the bike canvas first.
 */
class AaInput(
    private val transport: AapTransport,
    private val log: (String) -> Unit,
) {
    /** normalised actions from the bike decoder */
    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2

        // keycodes AA understands for d-pad / rotary focus. advertised in
        // ServiceDiscoveryResponse.keycodesSupported and sent by sendKey.
        const val KEY_UP = 19     // KEYCODE_DPAD_UP
        const val KEY_DOWN = 20   // KEYCODE_DPAD_DOWN
        const val KEY_LEFT = 21   // KEYCODE_DPAD_LEFT
        const val KEY_RIGHT = 22  // KEYCODE_DPAD_RIGHT
        const val KEY_ENTER = 23  // KEYCODE_DPAD_CENTER (select)
        const val KEY_BACK = 4    // KEYCODE_BACK
        const val KEY_HOME = 3    // KEYCODE_HOME

        /**
         * the aap rotary scroll wheel code. we have no physical knob and never send it, but
         * advertising it is what tells AA we're a rotary head unit, which is what makes it draw a
         * focus highlight for the d-pad keys to move. without it AA takes the keys (home works) but
         * has no focus, so the arrows do nothing. seen in the 2026-07-16 log.
         */
        const val KEY_SCROLL_WHEEL = 65536

        /** KEYCODE_SEARCH, what a head unit sends for its voice button */
        const val KEY_ASSISTANT = 84

        /** everything we advertise, so AA turns on rotary/focus navigation */
        val SUPPORTED_KEYCODES = intArrayOf(
            KEY_SCROLL_WHEEL, KEY_UP, KEY_DOWN, KEY_LEFT, KEY_RIGHT, KEY_ENTER, KEY_BACK, KEY_HOME,
            KEY_ASSISTANT,
        )
    }

    /** one key press (down then up). AA only acts on these if we advertised the keycode. */
    fun sendKey(keycode: Int) {
        try {
            sendKeyReport(keycode, down = true)
            sendKeyReport(keycode, down = false)
            log("[AA] sendKey keycode=$keycode")
        } catch (e: Exception) {
            log("[AA] sendKey failed: $e")
        }
    }

    /**
     * one click of the rotary knob. -1 = back, +1 = forward.
     *
     * on a rotary head unit (which is what AA thinks we are, since we advertise KEY_SCROLL_WHEEL)
     * the knob is the main navigation, it steps focus through list items. the d-pad only jumps
     * between panes, which is why the arrows move between the app list and taskbar but can't step
     * inside them (2026-07-16 test).
     */
    fun sendScroll(delta: Int) {
        try {
            val rel = Input.RelativeEvent.newBuilder()
                .addData(
                    Input.RelativeEvent_Rel.newBuilder()
                        .setKeycode(KEY_SCROLL_WHEEL).setDelta(delta).build()
                )
                .build()
            val report = Input.InputReport.newBuilder()
                .setTimestamp(SystemClock.elapsedRealtimeNanos() / 1000)
                .setRelativeEvent(rel)
                .build()
            transport.send(AapMessage(Channel.ID_INP, Input.MsgType.EVENT_VALUE, report))
            log("[AA] sendScroll delta=$delta")
        } catch (e: Exception) {
            log("[AA] sendScroll failed: $e")
        }
    }

    private fun sendKeyReport(keycode: Int, down: Boolean) {
        val keyEvent = Input.KeyEvent.newBuilder()
            .addKeys(
                Input.Key.newBuilder()
                    .setKeycode(keycode).setDown(down).setMetastate(0).setLongpress(false).build()
            )
            .build()
        val report = Input.InputReport.newBuilder()
            .setTimestamp(SystemClock.elapsedRealtimeNanos() / 1000)
            .setKeyEvent(keyEvent)
            .build()
        transport.send(AapMessage(Channel.ID_INP, Input.MsgType.EVENT_VALUE, report))
    }

    /**
     * one touch report: every pointer currently down, and which one changed.
     *
     * pointers is (id, x, y) in AA video coords; actionIndex indexes into that list, not the id.
     * reporting all of them is what aap wants and what makes pinch work. the old single-pointer
     * version hardcoded pointerId=0 and actionIndex=0, so a second finger arrived as another
     * first-finger down and AA saw a corrupt gesture stream.
     */
    fun sendTouch(action: Int, actionIndex: Int, pointers: List<Triple<Int, Int, Int>>) {
        if (pointers.isEmpty()) return
        val pointerAction = when (action) {
            ACTION_DOWN -> Input.TouchEvent.PointerAction.TOUCH_ACTION_DOWN
            ACTION_UP -> Input.TouchEvent.PointerAction.TOUCH_ACTION_UP
            ACTION_MOVE -> Input.TouchEvent.PointerAction.TOUCH_ACTION_MOVE
            else -> return
        }
        try {
            val touch = Input.TouchEvent.newBuilder()
            for ((id, x, y) in pointers) {
                touch.addPointerData(
                    Input.TouchEvent.Pointer.newBuilder().setX(x).setY(y).setPointerId(id).build()
                )
            }
            touch.actionIndex = actionIndex.coerceIn(0, pointers.size - 1)
            touch.action = pointerAction
            val report = Input.InputReport.newBuilder()
                // aap input timestamps are a monotonic microsecond clock
                .setTimestamp(SystemClock.elapsedRealtimeNanos() / 1000)
                .setTouchEvent(touch.build())
                .build()
            transport.send(AapMessage(Channel.ID_INP, Input.MsgType.EVENT_VALUE, report))
        } catch (e: Exception) {
            log("[AA] sendTouch failed: $e")
        }
    }
}
