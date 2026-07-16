package dev.snaipdefix.opencflink

import android.content.Context

/**
 * How many bits we're allowed to spend on the picture — which is really a question about your music.
 *
 * The bike link is Wi-Fi and your helmet audio is Bluetooth. If the dash's AP is on 2.4 GHz (the
 * `[wifi] link:` line in the log says which), those share a band and the phone time-slices between
 * them: whatever the video sends, the A2DP feeding your helmet doesn't. Measured on the 2026-07-16
 * ride, a parked map costs ~120 kbps and a scrolling one ~600 kbps — and the faster you go, the
 * faster it scrolls. That's why the music was fine stationary, rough at 60 and unusable at 100.
 *
 * Video itself never suffers (29.5 fps average across that whole ride) because Wi-Fi wins the
 * arbitration — Bluetooth is the one that starves. So this is a straight trade: fewer bits on the
 * dash, more room for the audio.
 */
enum class VideoQuality(val id: String, val label: String, val bitrate: Int, val hint: String) {
    LOW("low", "Smooth audio", 800_000,
        "Blockier map when you're moving fast. Pick this if music matters more than the picture."),
    MEDIUM("medium", "Balanced", 1_500_000,
        "Roughly half the radio traffic of High. Start here."),
    HIGH("high", "Sharp picture", 2_500_000,
        "What older versions always used. Best-looking dash, hardest on Bluetooth audio."),
    ;

    companion object {
        private const val PREF = "video_quality"
        private const val KEY = "quality"

        fun byId(id: String?): VideoQuality? = entries.firstOrNull { it.id == id }

        /** Read at encoder-configure time, so a change applies on the next connect. */
        fun get(context: Context): VideoQuality =
            byId(context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null))
                ?: MEDIUM

        fun set(context: Context, quality: VideoQuality) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY, quality.id).apply()
        }
    }
}
