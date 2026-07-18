package dev.snaipdefix.opencflink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * every input here is a real line from a ride log (450SR and 800NK, 2026-07-16). if one of these
 * leaks, someone posts their bike's wifi password to a public issue tracker.
 */
class LogRedactorTest {

    private fun r(s: String) = LogRedactor.redact(s)

    @Test
    fun `dash easy_conn password is gone`() {
        val line = """   "password" : "ASDF!@#${'$'}asdf1234","""
        val out = r(line)
        assertFalse(out, out.contains("ASDF"))
        assertTrue(out, out.contains("«redacted»"))
    }

    @Test
    fun `OTA ftp password is gone`() {
        val out = r("""   "pwd" : "${'$'}Siwei2018@",""")
        assertFalse(out, out.contains("Siwei"))
    }

    @Test
    fun `long base64 pwd blob is gone`() {
        val blob = "Fyh2cUzEn2iPbVLu3n/MmTsna4v3saXv0utCJO51eu3ltfla2b3ufqZHa2cRubS+W/Gc6Pgif6aGe1ZmUdtiO+tnyDYl+QwFZ0nFo1Fge9V7VzMjtYZrhynDKZfx7beNvZPrgj79CfemK5GU2MX9aLMY1QZnkqjkQo2Vof4NTP0="
        val out = r("""   "pwd" : "$blob",""")
        assertFalse(out, out.contains("Fyh2"))
        assertFalse(out, out.contains("TP0="))
    }

    @Test
    fun `raw QR wifi password is gone but the rest survives`() {
        val line = "QR raw: https://cfmoto.com/c?ssid=DIRECT-go-CFMOTO-4A71BD&pwd=59a9cddc94&mac=aa:bb&modelId=66660742"
        val out = r(line)
        assertFalse("the Wi-Fi password leaked: $out", out.contains("59a9cddc94"))
        // still useful
        assertTrue(out, out.contains("DIRECT-go-CFMOTO-4A71BD"))
        assertTrue(out, out.contains("66660742"))
    }

    @Test
    fun `bike serial keeps its family prefix but loses the identity`() {
        val out = r("""   "HUID" : "6KWV0000AL35C28120000148",""")
        // Cfdl26NkTouchProfile scores on this prefix, masking it all breaks profile debugging
        assertTrue("prefix must survive for profile scoring: $out", out.contains("6KWV"))
        assertFalse("full serial leaked: $out", out.contains("6KWV0000AL35C28120000148"))
    }

    @Test
    fun `bare carHuid in our own prose is masked`() {
        val out = r("[:10922] carHuid=CRCP241003559 HUName=CFMOTO-4A71BD channel=66660742")
        assertFalse("full serial leaked: $out", out.contains("CRCP241003559"))
        assertTrue(out, out.contains("CRCP"))
        assertTrue("channel is needed for profile debugging: $out", out.contains("66660742"))
    }

    @Test
    fun `ordinary lines are untouched`() {
        for (line in listOf(
            "[AA] decode fps=29",
            "[VIDEO] bike behind → resync #3 (drop backlog + force IDR)",
            "[BTN] volume DOWN (12→11, jump=-1) → AA knob 1",
            "[wifi] link: 2437MHz (2.4GHz — SHARED WITH BLUETOOTH), rssi=-51dBm",
            "our IP=192.168.49.158  bike IP=192.168.49.1",
        )) {
            assertEquals(line, r(line))
        }
    }

    @Test
    fun `ssid is kept - the bike broadcasts it anyway and we need it`() {
        val line = "Wi-Fi joined: DIRECT-go-CFMOTO-4A71BD (network=149, bound)"
        assertEquals(line, r(line))
    }

    @Test
    fun `a whole CLIENT_INFO block survives redaction intact except the secrets`() {
        val block = """
            {
               "HUID" : "CRCP241003559",
               "channel" : "66660742",
               "password" : "ASDF!@#asdf1234",
               "sdkVersion" : "0.9.23.4",
               "supportScreenTouch" : false
            }
        """.trimIndent()
        val out = r(block)
        assertFalse(out, out.contains("ASDF"))
        assertFalse(out, out.contains("CRCP241003559"))
        assertTrue(out, out.contains("\"channel\" : \"66660742\""))
        assertTrue(out, out.contains("\"sdkVersion\" : \"0.9.23.4\""))
        assertTrue(out, out.contains("\"supportScreenTouch\" : false"))
    }
}
