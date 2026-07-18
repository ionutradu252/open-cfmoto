package dev.snaipdefix.opencflink

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Location
import android.location.LocationManager
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * is it dark out? AA asks and won't stop waiting.
 *
 * AA subscribes to the NIGHT sensor on every connect. we used to answer "ok" and never send an
 * event, so it stayed in day mode forever = white map at eye level on a night ride.
 *
 * why sunrise/sunset and not something simpler:
 * - the phone's light sensor is useless, it rides in a pocket and reads black at noon
 * - the system dark theme is a preference, not a fact (plenty of people run "always dark").
 *    fine as a fallback
 * - we already have ACCESS_FINE_LOCATION for the wifi join, and only need the LAST KNOWN
 *    position, so no gps fix and no battery cost. a stale position is fine, sunset doesn't move
 *    far in a day
 */
object NightMode {

    enum class Mode(val id: String, val label: String) {
        AUTO("auto", "Automatic (sunset)"),
        DAY("day", "Always day"),
        NIGHT("night", "Always night"),
        ;

        companion object {
            fun byId(id: String?): Mode? = entries.firstOrNull { it.id == id }
        }
    }

    private const val PREF = "night_mode"
    private const val KEY = "mode"

    /** sun altitude below which it's night. -0.833 = standard sunset (refraction + disc edge).
     * erring early is on purpose: a dark map at dusk is harmless, a white one after dark isn't. */
    private const val NIGHT_ALTITUDE_DEG = -0.833

    fun mode(context: Context): Mode =
        Mode.byId(context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null))
            ?: Mode.AUTO

    fun setMode(context: Context, m: Mode) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, m.id).apply()
    }

    fun isNight(context: Context): Boolean = when (mode(context)) {
        Mode.DAY -> false
        Mode.NIGHT -> true
        Mode.AUTO -> {
            val loc = lastKnownLocation(context)
            if (loc != null) sunAltitudeDeg(loc.latitude, loc.longitude, System.currentTimeMillis()) < NIGHT_ALTITUDE_DEG
            else systemDarkTheme(context)
        }
    }

    /** so the log says why the dash went dark */
    fun reason(context: Context): String = when (mode(context)) {
        Mode.DAY -> "forced day"
        Mode.NIGHT -> "forced night"
        Mode.AUTO -> {
            val loc = lastKnownLocation(context)
            if (loc == null) "no location — following the phone's dark theme"
            else "sun %.1f° above horizon".format(
                sunAltitudeDeg(loc.latitude, loc.longitude, System.currentTimeMillis())
            )
        }
    }

    private fun systemDarkTheme(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** last known position from any provider. never requests a fix. */
    private fun lastKnownLocation(context: Context): Location? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
                .maxByOrNull { it.time }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * sun altitude in degrees. usno low precision algorithm, good to a fraction of a degree,
     * which is way more than "is the sun up" needs, and it's a few lines instead of a dependency.
     */
    internal fun sunAltitudeDeg(latDeg: Double, lonDeg: Double, epochMillis: Long): Double {
        // days since J2000.0 (2000-01-01 12:00 UTC)
        val d = epochMillis / 86_400_000.0 - 10957.5

        val g = Math.toRadians((357.529 + 0.98560028 * d) % 360.0)          // mean anomaly
        val q = (280.459 + 0.98564736 * d) % 360.0                          // mean longitude
        val lDeg = q + 1.915 * sin(g) + 0.020 * sin(2 * g)                  // ecliptic longitude
        val l = Math.toRadians(lDeg % 360.0)
        val e = Math.toRadians(23.439 - 0.00000036 * d)                     // obliquity

        val ra = atan2(cos(e) * sin(l), cos(l))
        val dec = asin(sin(e) * sin(l))

        val gmstH = (18.697374558 + 24.06570982441908 * d) % 24.0
        val lmstH = (gmstH + lonDeg / 15.0) % 24.0
        var haDeg = lmstH * 15.0 - Math.toDegrees(ra)
        haDeg = ((haDeg + 180.0).mod(360.0)) - 180.0
        val ha = Math.toRadians(haDeg)

        val lat = Math.toRadians(latDeg)
        return Math.toDegrees(asin(sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(ha)))
    }
}
