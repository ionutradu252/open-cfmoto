package dev.snaipdefix.opencflink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * every input here is SYNTHETIC — same shape as a real log line, invented values.
 *
 * an earlier version of this file used real captures, which put a live wifi password and a real
 * vehicle serial into a public repo: exactly what the class under test exists to prevent. if you add
 * a case, copy the shape of a log line, never its contents.
 */
class LogRedactorTest {

    private fun r(s: String) = LogRedactor.redact(s)

    @Test
    fun `dash easy_conn password is gone`() {
        val line = """   "password" : "EXAMPLE!@#pass0000","""
        val out = r(line)
        assertFalse(out, out.contains("EXAMPLE"))
        assertTrue(out, out.contains("«redacted»"))
    }

    @Test
    fun `OTA ftp password is gone`() {
        val out = r("""   "pwd" : "${'$'}Example2000@",""")
        assertFalse(out, out.contains("Example"))
    }

    @Test
    fun `long base64 pwd blob is gone`() {
        // same length and alphabet as the real thing, none of the real bytes
        val blob = "AAAABBBBCCCCDDDDEEEEFFFFGGGGHHHHIIIIJJJJKKKKLLLLMMMMNNNNOOOOPPPP" +
            "QQQQRRRRSSSSTTTTUUUUVVVVWWWWXXXXYYYYZZZZ0000111122223333444455/6" +
            "6667777888899990000aaaabbbbccccddddeeeeffffgggghhhhiiiijjjjkk8Q="
        val out = r("""   "pwd" : "$blob",""")
        assertFalse(out, out.contains("AAAA"))
        assertFalse(out, out.contains("kk8Q="))
    }

    @Test
    fun `raw QR wifi password is gone but the rest survives`() {
        val line = "QR raw: https://example.invalid/c?ssid=DIRECT-go-CFMOTO-000000&pwd=0a1b2c3d4e" +
            "&mac=aa:bb&modelId=66660742"
        val out = r(line)
        assertFalse("the Wi-Fi password leaked: $out", out.contains("0a1b2c3d4e"))
        // still useful
        assertTrue(out, out.contains("DIRECT-go-CFMOTO-000000"))
        assertTrue(out, out.contains("66660742"))
    }

    @Test
    fun `bike serial keeps its family prefix but loses the identity`() {
        val out = r("""   "HUID" : "ABCD0000000000000000EXAM",""")
        // the profile scores on the prefix, so masking all of it breaks profile debugging
        assertTrue("prefix must survive for profile scoring: $out", out.contains("ABCD"))
        assertFalse("full serial leaked: $out", out.contains("ABCD0000000000000000EXAM"))
    }

    @Test
    fun `bare carHuid in our own prose is masked`() {
        val out = r("[:10922] carHuid=TEST000000000 HUName=CFMOTO-000000 channel=66660742")
        assertFalse("full serial leaked: $out", out.contains("TEST000000000"))
        assertTrue(out, out.contains("TEST"))
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
        val line = "Wi-Fi joined: DIRECT-go-CFMOTO-000000 (network=149, bound)"
        assertEquals(line, r(line))
    }

    @Test
    fun `a whole CLIENT_INFO block survives redaction intact except the secrets`() {
        val block = """
            {
               "HUID" : "TEST000000000",
               "channel" : "66660742",
               "password" : "EXAMPLEpass1234",
               "sdkVersion" : "0.9.23.4",
               "supportScreenTouch" : false
            }
        """.trimIndent()
        val out = r(block)
        assertFalse(out, out.contains("EXAMPLEpass"))
        assertFalse(out, out.contains("TEST000000000"))
        assertTrue(out, out.contains("\"channel\" : \"66660742\""))
        assertTrue(out, out.contains("\"sdkVersion\" : \"0.9.23.4\""))
        assertTrue(out, out.contains("\"supportScreenTouch\" : false"))
    }
}
