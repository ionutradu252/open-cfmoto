package dev.snaipdefix.opencflink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the 800NK digitizer splits one finger into two contacts during a press or drag. the false-touch
 * log (2026-07-17) had a ghost pointer at (444,300) land on top of a real one at (444,268), and
 * pointer counts climbing to four on a single-finger swipe. EasyConnProber.near decides which
 * contacts are the same finger doubled up.
 */
class GhostTouchTest {

    @Test
    fun `the ghost pair from the log is treated as one finger`() {
        // #0=(444,268) and #3=(444,300): 32px apart, same finger doubled
        assertTrue(EasyConnProber.near(444, 268, 444, 300))
        // (129,294) and (129,291) from the same log: 3px apart
        assertTrue(EasyConnProber.near(129, 294, 129, 291))
    }

    @Test
    fun `two real fingers a pinch apart are kept`() {
        // a deliberate two-finger touch from the log: (444,268) and (181,400)
        assertFalse(EasyConnProber.near(444, 268, 181, 400))
        // fingers just past the threshold still count as two
        assertFalse(EasyConnProber.near(300, 300, 300, 349))
    }

    @Test
    fun `the threshold is symmetric and covers both axes`() {
        assertTrue(EasyConnProber.near(100, 100, 148, 100))   // 48 on x, on the line
        assertTrue(EasyConnProber.near(100, 100, 100, 148))   // 48 on y
        assertFalse(EasyConnProber.near(100, 100, 149, 100))  // one past
        assertFalse(EasyConnProber.near(100, 100, 100, 149))
    }
}
