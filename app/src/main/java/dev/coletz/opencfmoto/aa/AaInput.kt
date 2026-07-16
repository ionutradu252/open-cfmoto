// OpenCfMoto (uses AGPLv3 code/protocol ported from headunit-revived). Injects touch input into the
// Android Auto session: the CFMoto dash is a touchscreen and reports touches over PXC (see
// EasyConnProber cmdType 32); this forwards them to Gearhead over the AAP INPUT channel so Maps/Waze
// can be driven from the bike.
package dev.coletz.opencfmoto.aa

import android.os.SystemClock
import dev.coletz.opencfmoto.aa.proto.Input

/**
 * Sends touch events to Android Auto over the INPUT channel (declared as a touchscreen in
 * [ServiceDiscoveryResponse], sized to the AA video). Coordinates must already be in AA video space
 * (0..width, 0..height) — the caller letterbox-maps from the bike canvas first.
 */
class AaInput(
    private val transport: AapTransport,
    private val log: (String) -> Unit,
) {
    /** Normalised actions from the bike decoder. */
    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2

        // Android KeyEvent keycodes Android Auto understands for D-pad / rotary focus navigation.
        // Advertised in ServiceDiscoveryResponse.keycodesSupported and sent by [sendKey].
        const val KEY_UP = 19     // KEYCODE_DPAD_UP
        const val KEY_DOWN = 20   // KEYCODE_DPAD_DOWN
        const val KEY_LEFT = 21   // KEYCODE_DPAD_LEFT
        const val KEY_RIGHT = 22  // KEYCODE_DPAD_RIGHT
        const val KEY_ENTER = 23  // KEYCODE_DPAD_CENTER (select)
        const val KEY_BACK = 4    // KEYCODE_BACK
        const val KEY_HOME = 3    // KEYCODE_HOME

        /**
         * The AAP "rotary controller" scroll-wheel code. We have no physical knob and never SEND it,
         * but advertising it is what tells Android Auto this is a rotary/non-touch head unit — which
         * is what makes AA render a focus highlight for the D-pad keys to move. Without it AA accepts
         * the keys (HOME works) but has no focus to navigate, so the arrows do nothing. Verified
         * symptom in the 2026-07-16 bike log.
         */
        const val KEY_SCROLL_WHEEL = 65536

        /** KEYCODE_SEARCH — what a head unit sends for its "voice / Assistant" button. */
        const val KEY_ASSISTANT = 84

        /** All keycodes we advertise — declared to AA so it enables rotary/focus navigation. */
        val SUPPORTED_KEYCODES = intArrayOf(
            KEY_SCROLL_WHEEL, KEY_UP, KEY_DOWN, KEY_LEFT, KEY_RIGHT, KEY_ENTER, KEY_BACK, KEY_HOME,
            KEY_ASSISTANT,
        )
    }

    /**
     * Send one key press (down then up) to Android Auto over the INPUT channel. Used by the phone's
     * on-screen D-pad so a non-touch dash can be driven (set a destination, then ride). AA acts on
     * these when the head unit advertised the keycodes ([SUPPORTED_KEYCODES]) in service discovery.
     */
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
     * Emulate one click of the rotary knob: [delta] -1 = rotate back (focus to the previous item),
     * +1 = rotate forward (next item).
     *
     * On a rotary head unit — which is what AA treats us as, now that we advertise
     * [KEY_SCROLL_WHEEL] — the KNOB is the primary navigation: it steps focus through list items.
     * The D-pad only jumps coarsely between panes/regions, which is exactly why the arrows move
     * between the app-list and taskbar but can't step *within* them (2026-07-16 bike test). This
     * sends the rotation AA is waiting for, as a RelativeEvent on the INPUT channel.
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
     * @param action one of [ACTION_DOWN]/[ACTION_UP]/[ACTION_MOVE]
     * @param x,y    pointer position in AA video coordinates
     */
    fun sendTouch(action: Int, x: Int, y: Int) {
        val pointerAction = when (action) {
            ACTION_DOWN -> Input.TouchEvent.PointerAction.TOUCH_ACTION_DOWN
            ACTION_UP -> Input.TouchEvent.PointerAction.TOUCH_ACTION_UP
            ACTION_MOVE -> Input.TouchEvent.PointerAction.TOUCH_ACTION_MOVE
            else -> return
        }
        try {
            val touch = Input.TouchEvent.newBuilder()
                .addPointerData(
                    Input.TouchEvent.Pointer.newBuilder()
                        .setX(x).setY(y).setPointerId(0).build()
                )
                .setActionIndex(0)
                .setAction(pointerAction)
                .build()
            val report = Input.InputReport.newBuilder()
                // AAP input timestamps are a monotonic microsecond clock.
                .setTimestamp(SystemClock.elapsedRealtimeNanos() / 1000)
                .setTouchEvent(touch)
                .build()
            transport.send(AapMessage(Channel.ID_INP, Input.MsgType.EVENT_VALUE, report))
        } catch (e: Exception) {
            log("[AA] sendTouch failed: $e")
        }
    }
}
