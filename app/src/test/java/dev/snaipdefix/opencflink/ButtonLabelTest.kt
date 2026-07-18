package dev.snaipdefix.opencflink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the mapping screen names each row after a button on YOUR bike, so the names have to be consistent
 * and the gestures your dash can't send have to be gone. the old list mixed "▲ double tap" with
 * "previous track key ×2" in the same screen.
 */
class ButtonLabelTest {

    private fun shown(p: BikeProfile): List<String> =
        ButtonGesture.entries.filter { !p.knowsButtons || p.buttonLabel(it) != null }
            .map { p.buttonLabel(it) ?: it.label }

    @Test
    fun `450SR shows every gesture its bars can send`() {
        // the two holds were hidden for a while on a bad read of one test; the logs show them
        // working, and on this dash they're the only spare gestures there are.
        assertEquals(
            listOf("▲ press", "▲ double tap", "▼ press", "▼ double tap", "Enter hold",
                "▲ hold", "▼ hold").sorted(),
            shown(Cfdl16MotoPlayLandscapeProfile).sorted(),
        )
    }

    @Test
    fun `800NK shows only its five buttons`() {
        val shown = shown(Cfdl26NkTouchProfile)
        assertEquals(
            listOf("★ press", "◀ press", "◀ double tap", "▶ press", "▶ double tap").sorted(),
            shown.sorted(),
        )
    }

    @Test
    fun `each bike hides only what it truly cannot send`() {
        // a hold can't be double tapped fast enough for the timer, on either dash
        for (g in listOf(ButtonGesture.NEXT_DOUBLE, ButtonGesture.PREV_DOUBLE)) {
            assertNull("450SR should not offer $g", Cfdl16MotoPlayLandscapeProfile.buttonLabel(g))
        }
        // but the holds themselves do arrive, so they must be offered
        assertNotNull(Cfdl16MotoPlayLandscapeProfile.buttonLabel(ButtonGesture.NEXT_KEY))
        assertNotNull(Cfdl16MotoPlayLandscapeProfile.buttonLabel(ButtonGesture.PREV_KEY))
        // the 800NK sends no volume at all
        for (g in listOf(ButtonGesture.VOL_UP_PRESS, ButtonGesture.VOL_UP_DOUBLE,
                         ButtonGesture.VOL_DOWN_PRESS, ButtonGesture.VOL_DOWN_DOUBLE)) {
            assertNull("800NK should not offer $g", Cfdl26NkTouchProfile.buttonLabel(g))
        }
    }

    @Test
    fun `an unknown dash still offers everything`() {
        val p = LegacyCfdl16Profile
        assertEquals(ButtonGesture.entries.size, shown(p).size)
    }

    @Test
    fun `every visible label follows the same shape`() {
        for (p in listOf(Cfdl16MotoPlayLandscapeProfile, Cfdl26NkTouchProfile)) {
            for (label in shown(p)) {
                assertTrue(
                    "'$label' should end in press / double tap / hold",
                    label.endsWith(" press") || label.endsWith(" double tap") || label.endsWith(" hold"),
                )
            }
        }
    }

    @Test
    fun `generic fallback names are consistent too`() {
        for (g in ButtonGesture.entries) {
            assertTrue(
                "'${g.label}' should end in press or double tap",
                g.label.endsWith(" press") || g.label.endsWith(" double tap"),
            )
        }
    }

    @Test
    fun `every gesture still has a hint saying what the dash sends`() {
        for (g in ButtonGesture.entries) {
            assertTrue("$g has no hint", g.hint.isNotBlank())
        }
    }
}
