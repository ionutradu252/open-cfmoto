package dev.snaipdefix.opencflink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

/**
 * the margin trick only works at 1:1, so bestFit must never return a resolution the compositor would
 * scale. getting it wrong doesn't crash, it just quietly puts AA's ui in the wrong place on
 * someone's dash, the kind of bug that costs a bike test to find.
 */
class LearnedPanelsTest {

    /** the compositor's fill scale, copied from AaCompositor.computeViewport */
    private fun fillScale(canvasW: Int, canvasH: Int, srcW: Int, srcH: Int): Double =
        max(canvasW.toDouble() / srcW, canvasH.toDouble() / srcH)

    private fun assertPixelPerfect(panelW: Int, panelH: Int, r: AaResolution) {
        val cw = panelW and 0xFFF0
        val ch = panelH and 0xFFF0
        val s = fillScale(cw, ch, r.w, r.h)
        assertEquals("panel ${panelW}x$panelH in ${r.w}x${r.h} must map 1:1, got scale $s", 1.0, s, 0.0001)
        assertTrue("AA canvas must contain the panel", r.w >= cw && r.h >= ch)
    }

    @Test
    fun `450SR 800x400 picks the landscape 800x480 it already uses`() {
        val r = LearnedPanels.bestFit(800, 400)
        assertEquals(AaResolution.LANDSCAPE_800x480, r)
        assertPixelPerfect(800, 400, r!!)
        // matches the hand tuned profile that works on the bike
        assertEquals(800 to 400, Cfdl16MotoPlayLandscapeProfile.panelSize)
        assertEquals(AaResolution.LANDSCAPE_800x480, Cfdl16MotoPlayLandscapeProfile.aaVideo.resolution)
    }

    @Test
    fun `800NK 720x712 picks the portrait 720x1280 it already uses`() {
        val r = LearnedPanels.bestFit(720, 712)
        assertEquals(AaResolution.PORTRAIT_720x1280, r)
        assertPixelPerfect(720, 712, r!!)
        assertEquals(720 to 712, Cfdl26NkTouchProfile.panelSize)
        assertEquals(AaResolution.PORTRAIT_720x1280, Cfdl26NkTouchProfile.aaVideo.resolution)
    }

    @Test
    fun `a panel AA cannot carry at 1-to-1 is refused rather than fudged`() {
        // 1000 MT-X-ish: 800x951 fits inside 1080x1920 but the compositor would scale it 0.74 and
        // the margins would be nonsense. better to keep the hand tuned profile.
        assertNull(LearnedPanels.bestFit(800, 951))
    }

    @Test
    fun `oversized panels are refused`() {
        assertNull(LearnedPanels.bestFit(3840, 2160))
    }

    @Test
    fun `every returned resolution is pixel-perfect across a wide sweep`() {
        var fits = 0
        for (w in 160..1920 step 16) {
            for (h in 160..1920 step 8) {
                val r = LearnedPanels.bestFit(w, h) ?: continue
                fits++
                assertPixelPerfect(w, h, r)
            }
        }
        assertTrue("the sweep should actually find some fits, got $fits", fits > 50)
    }

    @Test
    fun `exact height match also works - crops width instead`() {
        // 1280x720 is an AA resolution, and a 1200x720 panel shares its height exactly
        val r = LearnedPanels.bestFit(1200, 720)
        assertEquals(AaResolution.LANDSCAPE_1280x720, r)
        assertPixelPerfect(1200, 720, r!!)
    }

    @Test
    fun `margins come out as plain pixel differences`() {
        val r = LearnedPanels.bestFit(720, 712)!!
        // what ServiceDiscoveryResponse computes. 1:1 is what makes this arithmetic valid.
        assertEquals(0, r.w - 720)
        assertEquals(568, r.h - 712)
    }
}
