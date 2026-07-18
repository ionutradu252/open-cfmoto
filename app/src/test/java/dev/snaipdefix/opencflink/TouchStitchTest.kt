package dev.snaipdefix.opencflink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the 800NK digitizer drops a finger mid-drag and re-acquires it milliseconds later, which ends the
 * gesture and turns one map pan into a string of taps. google maps opens a place card on every tap,
 * which is what "it opens things I never touched" was.
 *
 * gaps below are measured from the 2026-07-18 log. the rule has to stitch every one of those while
 * leaving real re-taps (150ms+) alone, or a deliberate second tap gets swallowed into a drag.
 */
class TouchStitchTest {

    private val STITCH_MS = 80L
    private val STITCH_PX = 150

    private fun stitches(gapMs: Long, ax: Int, ay: Int, bx: Int, by: Int): Boolean =
        gapMs <= STITCH_MS && EasyConnProber.near(ax, ay, bx, by, STITCH_PX)

    @Test
    fun `every contact loss in the log is stitched`() {
        // gap ms, up x/y, down x/y — straight from the log
        val lost = listOf(
            listOf(3, 452, 400, 413, 411),
            listOf(6, 337, 406, 293, 314),
            listOf(0, 458, 360, 491, 284),
            listOf(2, 524, 524, 530, 554),
            listOf(8, 383, 149, 373, 203),
            listOf(5, 427, 175, 435, 109),
            listOf(10, 506, 415, 488, 437),
            listOf(8, 404, 165, 424, 131),
            listOf(14, 350, 533, 347, 569),
            listOf(4, 102, 314, 87, 405),
        )
        for (e in lost) {
            assertTrue(
                "gap ${e[0]}ms (${e[1]},${e[2]})->(${e[3]},${e[4]}) should be treated as one finger",
                stitches(e[0].toLong(), e[1], e[2], e[3], e[4]),
            )
        }
    }

    @Test
    fun `a real re-tap is left alone`() {
        // deliberate repeat taps from the same log sit 150-350ms apart
        assertFalse(stitches(158, 235, 77, 237, 76))
        assertFalse(stitches(191, 215, 59, 200, 54))
        assertFalse(stitches(350, 300, 300, 305, 305))
    }

    @Test
    fun `a fast tap somewhere else is not stitched onto the last one`() {
        // quick, but way across the panel: two separate touches, not one finger
        assertFalse(stitches(10, 100, 100, 600, 600))
        assertFalse(stitches(10, 100, 100, 100, 400))
    }

    @Test
    fun `the window sits between the two populations`() {
        // the whole rule rests on this gap: losses top out at 14ms, real re-taps start at ~150ms
        assertTrue("threshold must clear the worst measured loss", STITCH_MS > 14)
        assertTrue("threshold must stay under a real re-tap", STITCH_MS < 150)
    }
}
