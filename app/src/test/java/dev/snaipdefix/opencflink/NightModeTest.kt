package dev.snaipdefix.opencflink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * the sun altitude decides whether the dash goes dark, and a sign error or a bad epoch constant
 * wouldn't show up until someone rode at night. checked against published almanac times.
 */
class NightModeTest {

    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun alt(lat: Double, lon: Double, t: Long) = NightMode.sunAltitudeDeg(lat, lon, t)

    // bucharest, where this is actually ridden
    private val BUC_LAT = 44.4268
    private val BUC_LON = 26.1025

    @Test
    fun `bucharest midsummer noon has a high sun`() {
        // 2026-06-21, local noon is ~09:00 utc (eest = utc+3)
        val a = alt(BUC_LAT, BUC_LON, utc(2026, 6, 21, 9, 30))
        // max possible = 90 - 44.43 + 23.44 = 69.0
        assertTrue("expected a high summer sun, got $a", a in 60.0..69.5)
    }

    @Test
    fun `bucharest midwinter noon has a low sun but is still up`() {
        // 2026-12-21, local noon ~10:00 utc (eet = utc+2)
        val a = alt(BUC_LAT, BUC_LON, utc(2026, 12, 21, 10, 30))
        // min possible at noon = 90 - 44.43 - 23.44 = 22.1
        assertTrue("expected a low winter sun, got $a", a in 18.0..23.5)
    }

    @Test
    fun `bucharest midnight is well below the horizon`() {
        val a = alt(BUC_LAT, BUC_LON, utc(2026, 7, 16, 21, 0))   // 00:00 local
        assertTrue("expected deep night, got $a", a < -15.0)
    }

    @Test
    fun `sun crosses the horizon near published sunset`() {
        // bucharest 2026-07-16 sunset is ~21:00 local (18:00 utc)
        val before = alt(BUC_LAT, BUC_LON, utc(2026, 7, 16, 17, 30))
        val after = alt(BUC_LAT, BUC_LON, utc(2026, 7, 16, 18, 30))
        assertTrue("sun should still be up at 20:30 local, got $before", before > 0)
        assertTrue("sun should be down at 21:30 local, got $after", after < 0)
    }

    @Test
    fun `southern hemisphere seasons are inverted`() {
        // sydney. december is summer there.
        val dec = alt(-33.87, 151.21, utc(2026, 12, 21, 1, 30))   // ~12:30 local
        val jun = alt(-33.87, 151.21, utc(2026, 6, 21, 2, 0))     // ~12:00 local
        assertTrue("Sydney December noon should be high, got $dec", dec > 70.0)
        assertTrue("Sydney June noon should be low, got $jun", jun < 35.0)
        assertTrue("both should still be daytime", dec > 0 && jun > 0)
    }

    @Test
    fun `polar night stays below the horizon all day`() {
        // tromso, the sun doesn't rise at all in late december
        for (h in 0..23) {
            val a = alt(69.6496, 18.9560, utc(2026, 12, 21, h, 0))
            assertTrue("Tromsø should be dark all day in December, got $a at ${h}h UTC", a < 0)
        }
    }

    @Test
    fun `equator equinox noon is near vertical`() {
        val a = alt(0.0, 0.0, utc(2026, 3, 20, 12, 0))
        assertEquals(90.0, a, 3.0)
    }
}
