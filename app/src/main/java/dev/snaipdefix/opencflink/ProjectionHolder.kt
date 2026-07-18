package dev.snaipdefix.opencflink

import android.media.projection.MediaProjection

/**
 * holds the active MediaProjection (full-screen capture) for the video pipeline to pick up.
 * null = "render our own Presentation" mode; non-null = "mirror the whole device screen".
 */
object ProjectionHolder {
    @Volatile var projection: MediaProjection? = null
}
