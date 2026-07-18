package dev.snaipdefix.opencflink

/**
 * strips secrets out of log lines.
 *
 * runs when the line is written, not when it's shared. nobody reads 2000 lines before tapping
 * "report a problem", so the buffer itself has to be safe to post publicly.
 *
 * logs used to contain:
 *   QR raw: ...&pwd=<bike wifi password>
 *   "password" : "ASDF!@#$asdf1234"
 *   "pwd" : "XulxsD/vib+..."      (ota ftp)
 *   "HUID" : "CRCP241003559"      (bike serial)
 *
 * ssid, model id and the first 4 chars of serials are kept, needed to debug anything, and the
 * ssid is broadcast to the street anyway. Cfdl26NkTouchProfile scores on the HUID prefix.
 */
object LogRedactor {

    private const val MASK = "«redacted»"

    private val JSON_SECRET = Regex("""("(?:password|pwd|passphrase|psk|btPin)"\s*:\s*)"[^"]*"""",
        RegexOption.IGNORE_CASE)

    /** pwd=... in the qr url */
    private val URL_SECRET = Regex("""\b(pwd|password|psk|passphrase)=([^&\s"]*)""",
        RegexOption.IGNORE_CASE)

    private val JSON_SERIAL = Regex("""("(?:HUID|sn|uuid|carHuid)"\s*:\s*)"([^"]*)"""",
        RegexOption.IGNORE_CASE)

    /** bare carHuid=... in our own log text */
    private val BARE_SERIAL = Regex("""\b(carHuid|HUID|sn)=([A-Za-z0-9]{6,})""",
        RegexOption.IGNORE_CASE)

    fun redact(line: String): String {
        var s = line
        s = JSON_SECRET.replace(s) { m -> m.groupValues[1] + "\"$MASK\"" }
        s = URL_SECRET.replace(s) { m -> m.groupValues[1] + "=" + MASK }
        s = JSON_SERIAL.replace(s) { m -> m.groupValues[1] + "\"" + maskTail(m.groupValues[2]) + "\"" }
        s = BARE_SERIAL.replace(s) { m -> m.groupValues[1] + "=" + maskTail(m.groupValues[2]) }
        return s
    }

    /** keep enough to tell the family, lose enough to not identify the bike */
    private fun maskTail(v: String): String =
        if (v.length <= 4) v else v.take(4) + "…" + "*".repeat((v.length - 4).coerceAtMost(8))
}
