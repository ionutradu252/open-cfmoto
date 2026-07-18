package dev.snaipdefix.opencflink

/**
 * builds a problem report. plain strings, so it's testable without a device.
 *
 * the rule: the file has to stand on its own. an earlier version put the answers in the share
 * intent's EXTRA_TEXT and only the log in the attachment, which reads fine in gmail and loses
 * everything in share targets that take the stream and drop the text. the report then arrives as a
 * bare log with no bike, no year, no description, and the sender never knows. so the answers go in
 * the file and the body is just a copy.
 */
object ProblemReport {

    private const val RULE = "════════════════════════════════════════════════════════"

    /** the attachment: everything needed to act on it, in one file */
    fun file(problem: String, model: String, year: String, diagnostics: String, log: String): String =
        buildString {
            appendLine(RULE)
            appendLine("OpenCFLink problem report")
            appendLine(RULE)
            appendLine()
            appendLine("WHAT WENT WRONG")
            appendLine(problem.trim())
            appendLine()
            appendLine("BIKE")
            appendLine(bikeLine(model, year))
            appendLine()
            appendLine("SETUP")
            appendLine(diagnostics.trim())
            appendLine()
            appendLine(RULE)
            appendLine("LOG — passwords and serials already removed by the app")
            appendLine(RULE)
            append(log)
        }

    /** the message body: same thing, formatted to paste into a github issue */
    fun body(problem: String, model: String, year: String, diagnostics: String): String =
        buildString {
            appendLine("### What went wrong")
            appendLine(problem.trim())
            appendLine()
            appendLine("### Bike")
            appendLine(bikeLine(model, year))
            appendLine()
            appendLine("### Setup (filled in by the app)")
            appendLine("```")
            appendLine(diagnostics.trim())
            appendLine("```")
            appendLine()
            append("The full log is attached, and repeats all of the above at the top. " +
                "Passwords and serials were removed automatically.")
        }

    fun subject(model: String, version: String): String =
        "OpenCFLink ${bikeLine(model, "")} report — $version"

    private fun bikeLine(model: String, year: String): String {
        val m = model.trim().ifEmpty { "unknown model" }
        val y = year.trim()
        return if (y.isEmpty()) "CFMOTO $m" else "CFMOTO $m, $y"
    }
}
