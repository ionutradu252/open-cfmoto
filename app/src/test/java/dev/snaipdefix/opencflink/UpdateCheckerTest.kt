package dev.snaipdefix.opencflink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * version comparison for the github update prompt. getting this wrong either nags people who are
 * already current or silently leaves them on an old build, and this project's numbering (0.1.2.1)
 * is exactly the shape that trips naive string compares.
 */
class UpdateCheckerTest {

    @Test
    fun `a newer release is offered`() {
        assertTrue(UpdateChecker.isNewer("0.1.3", "0.1.2.1"))
        assertTrue(UpdateChecker.isNewer("0.2.0", "0.1.9"))
        assertTrue(UpdateChecker.isNewer("1.0", "0.9.9"))
    }

    @Test
    fun `the same version is not offered`() {
        assertFalse(UpdateChecker.isNewer("0.1.2.1", "0.1.2.1"))
        assertFalse(UpdateChecker.isNewer("v0.1.2.1", "0.1.2.1"))
    }

    @Test
    fun `an older release is never offered`() {
        assertFalse(UpdateChecker.isNewer("0.1.2", "0.1.2.1"))
        assertFalse(UpdateChecker.isNewer("0.1.1", "0.1.2.1"))
    }

    @Test
    fun `a hotfix on the end counts as newer`() {
        // 0.1.2.1 > 0.1.2: the missing 4th part reads as 0, which is how this project numbers them
        assertTrue(UpdateChecker.isNewer("0.1.2.1", "0.1.2"))
        assertFalse(UpdateChecker.isNewer("0.1.2", "0.1.2.1"))
    }

    @Test
    fun `tag decoration is ignored`() {
        assertTrue(UpdateChecker.isNewer("v0.1.3", "0.1.2.1"))
        assertTrue(UpdateChecker.isNewer("release-0.1.3", "0.1.2.1"))
        assertFalse(UpdateChecker.isNewer("v0.1.2.1-beta", "0.1.2.1"))
    }

    @Test
    fun `a tag with no numbers never prompts`() {
        // a "latest" release tagged something odd shouldn't trigger an update loop
        assertFalse(UpdateChecker.isNewer("nightly", "0.1.2.1"))
        assertFalse(UpdateChecker.isNewer("", "0.1.2.1"))
    }
}
