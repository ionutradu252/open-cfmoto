package dev.snaipdefix.opencflink

import android.content.Context

/**
 * how many bits we spend on the picture, which is really a question about your music.
 *
 * the bike link is wifi, the helmet is bluetooth. if the dash's ap is on 2.4ghz (the `[wifi] link:`
 * log line says which) they share the band and the phone time-slices between them: whatever the
 * video sends, the a2dp feeding your helmet doesn't. measured on the 2026-07-16 ride, a parked map
 * costs ~120 kbps and a moving one ~600 kbps, and the faster you go the more the map moves. that's
 * why music was fine parked, rough at 60 and unusable at 100.
 *
 * video never suffers (29.5 fps average that ride) because wifi wins the arbitration and bluetooth
 * starves. so it's a straight trade: fewer bits on the dash, more room for audio.
 */
enum class VideoQuality(val id: String, val label: String, val bitrate: Int, val hint: String) {
    LOW("low", "Smooth audio", 800_000,
        "Blockier map when moving. Best for music."),
    MEDIUM("medium", "Balanced", 1_500_000,
        "Start here."),
    HIGH("high", "Sharp picture", 2_500_000,
        "Sharpest dash, hardest on your music."),
    ;

    companion object {
        private const val PREF = "video_quality"
        private const val KEY = "quality"

        fun byId(id: String?): VideoQuality? = entries.firstOrNull { it.id == id }

        /** read when the encoder is configured, so a change applies on the next connect */
        fun get(context: Context): VideoQuality =
            byId(context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null))
                ?: MEDIUM

        fun set(context: Context, quality: VideoQuality) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY, quality.id).apply()
        }
    }
}
