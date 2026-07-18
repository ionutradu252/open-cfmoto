package dev.snaipdefix.opencflink

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the attachment has to stand on its own. the first version put the answers only in the share
 * intent's EXTRA_TEXT, so any share target that dropped the text (plenty do) delivered a bare log
 * with no bike, no year and no description.
 */
class ProblemReportTest {

    private val problem = "The dash stayed black after I turned the ignition on"
    private val diag = """
        App:        0.1.2.1 (3)
        Phone:      Xiaomi 13 / Android 16 (SDK 36)
        Profile:    CFDL16 / MotoPlay Landscape (modelId 66660742)
        Wi-Fi:      2437MHz (2.4GHz — shared with Bluetooth)
    """.trimIndent()
    private val log = "10:00:00.000  Ready.\n10:00:01.000  [AA] decode fps=30\n"

    @Test
    fun `the attached file carries the answers, not just the log`() {
        val f = ProblemReport.file(problem, "450SR", "2025", diag, log)
        assertTrue("the description is missing from the file", f.contains(problem))
        assertTrue("the bike is missing from the file", f.contains("CFMOTO 450SR, 2025"))
        assertTrue("the diagnostics are missing from the file", f.contains("2437MHz"))
        assertTrue("the profile is missing from the file", f.contains("CFDL16 / MotoPlay Landscape"))
        assertTrue("the log is missing from the file", f.contains("[AA] decode fps=30"))
    }

    @Test
    fun `the answers come before the log so they are not buried`() {
        val f = ProblemReport.file(problem, "450SR", "2025", diag, log)
        assertTrue("answers must precede the log", f.indexOf(problem) < f.indexOf("decode fps=30"))
        assertTrue("bike must precede the log", f.indexOf("CFMOTO 450SR") < f.indexOf("decode fps=30"))
    }

    @Test
    fun `a missing year still reads properly`() {
        val f = ProblemReport.file(problem, "800NK", "", diag, log)
        assertTrue(f, f.contains("CFMOTO 800NK"))
        assertTrue("should not leave a dangling comma", !f.contains("CFMOTO 800NK,"))
    }

    @Test
    fun `body and file agree on the essentials`() {
        val b = ProblemReport.body(problem, "450SR", "2025", diag)
        assertTrue(b.contains(problem))
        assertTrue(b.contains("CFMOTO 450SR, 2025"))
        assertTrue(b.contains("2437MHz"))
    }

    @Test
    fun `subject names the bike and the version`() {
        val s = ProblemReport.subject("450SR", "0.1.2.1")
        assertTrue(s, s.contains("450SR"))
        assertTrue(s, s.contains("0.1.2.1"))
    }

    @Test
    fun `an empty model does not produce a nonsense report`() {
        val f = ProblemReport.file(problem, "", "", diag, log)
        assertTrue(f, f.contains("unknown model"))
    }
}
