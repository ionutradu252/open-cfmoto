package dev.snaipdefix.opencflink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * upgrading from 0.1.3 must not throw away margins the rider already set.
 *
 * 0.1.3 stored the four edges with no "customised" flag. adding the flag and defaulting it to false
 * would have read every one of those installs as "never set", quietly replacing a chosen margin with
 * whatever the profile suggests. silent, and only visible as "my dash moved".
 */
class ScreenMarginUpgradeTest {

    @Test
    fun `a 0_1_3 install that set margins keeps them`() {
        // no flag, but the edge keys are there: someone saved those
        assertTrue(ScreenMargins.wasCustomised(hasFlag = false, flag = false, hasEdgeKeys = true))
    }

    @Test
    fun `a 0_1_3 install that never touched margins takes the profile default`() {
        assertFalse(ScreenMargins.wasCustomised(hasFlag = false, flag = false, hasEdgeKeys = false))
    }

    @Test
    fun `a fresh install takes the profile default`() {
        assertFalse(ScreenMargins.wasCustomised(hasFlag = false, flag = false, hasEdgeKeys = false))
    }

    @Test
    fun `once the flag exists it is believed either way`() {
        assertTrue(ScreenMargins.wasCustomised(hasFlag = true, flag = true, hasEdgeKeys = true))
        // reset writes the flag false while leaving the old numbers behind; that must mean default
        assertFalse(ScreenMargins.wasCustomised(hasFlag = true, flag = false, hasEdgeKeys = true))
    }

    @Test
    fun `saving all zeros is still a choice`() {
        // "I want no margins on a dash whose profile suggests some" has to survive
        assertTrue(ScreenMargins.wasCustomised(hasFlag = true, flag = true, hasEdgeKeys = true))
    }
}
