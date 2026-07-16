package dev.coletz.opencfmoto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import dev.coletz.opencfmoto.aa.AaInput

/**
 * Turns the bike's handlebar buttons into Android Auto navigation.
 *
 * The buttons never reach us over the PXC/Wi-Fi link (input capture on :10920-:10922 saw nothing).
 * What DOES work, verified on the bike:
 *   • short press ▲/▼ → AVRCP **absolute volume** → we read the DIRECTION as a knob click.
 *   • hold enter → AVRCP PLAY/PAUSE passthrough → mapped to ENTER (select).
 * The dash only emits the transport keys once we look like a real player, which is why this class
 * takes audio focus, plays a silent track, publishes metadata and posts a MediaStyle notification.
 *
 * Volume is watched with a [ContentObserver] rather than a remote-volume `VolumeProvider`: the bike
 * sends absolute volume, so there is no volume KEY event to intercept — the provider never fired,
 * and it added a confusing second volume slider on the phone, so it's gone.
 *
 * All of it is gated on [ButtonMode] — toggle off and volume/media behave completely normally.
 */
class MediaButtonBridge(private val context: Context, private val log: (String) -> Unit) {

    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private val audio by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val mediaAttrs by lazy {
        android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }

    private var volumeObserver: ContentObserver? = null
    /** Volume we hold the stream at while capturing, so there's always headroom up AND down. */
    private var pinnedVolume = -1
    /** One-shot guard: the dash only needs re-reading once per session. See [reassert]. */
    private var reasserted = false
    /** The user's own volume, restored when capture is turned off. */
    private var userVolume = -1
    private var focusRequest: AudioFocusRequest? = null
    private var silence: AudioTrack? = null

    fun start() {
        handler.post {
            try {
                val s = MediaSession(context, "OpenCfMoto")
                s.setCallback(callback)
                val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_FAST_FORWARD or
                    PlaybackState.ACTION_REWIND
                // An active PLAYING session is what makes the system route media buttons to us.
                s.setPlaybackState(
                    PlaybackState.Builder().setActions(actions)
                        .setState(PlaybackState.STATE_PLAYING, 0, 1f).build()
                )
                session = s
                instance = this
                val on = ButtonMode.isControlAa(context)
                if (on) takeMediaFocus()
                s.isActive = on
                s.setPlaybackToLocal(mediaAttrs)
                if (on) pinVolume()
                startVolumeObserver()
                log("[BTN] bridge ready — mode=${if (on) "control AA (media focus + volume hijacked)" else "control media"}")
                scheduleReassertWhenBikeUp()
            } catch (e: Exception) {
                log("[BTN] media session failed: $e")
            }
        }
    }

    /**
     * Re-announce ourselves to the dash once the bike is actually connected.
     *
     * We take media focus the moment the service starts — which is ~8 s BEFORE the bike's PXC link
     * comes up (2026-07-16 log: focus at :36.269, bike at :44.674). The dash reads the player's
     * capabilities when its AVRCP link forms, so it can read them before we've published anything,
     * and then never re-reads: the symptom is next/prev/pause doing nothing all session. Toggling the
     * setting off and on by hand fixed it — because that abandons and re-takes focus, forcing a
     * re-read. This does that same thing automatically, at the right moment.
     *
     * Only while capture is on, i.e. when the music player is paused anyway, so the brief focus drop
     * costs nothing.
     */
    private fun scheduleReassertWhenBikeUp() {
        if (reasserted) return
        val poll = object : Runnable {
            var waited = 0L
            override fun run() {
                if (reasserted || !ButtonMode.isControlAa(context)) return
                if (BikeLink.prober?.bikeConnected == true) {
                    reasserted = true
                    handler.postDelayed({ reassert() }, REASSERT_SETTLE_MS)
                    return
                }
                waited += REASSERT_POLL_MS
                if (waited < REASSERT_GIVEUP_MS) handler.postDelayed(this, REASSERT_POLL_MS)
                else log("[BTN] no bike link within ${REASSERT_GIVEUP_MS / 1000}s — skipping media re-assert")
            }
        }
        handler.postDelayed(poll, REASSERT_POLL_MS)
    }

    private fun reassert() {
        if (!ButtonMode.isControlAa(context)) return
        try {
            log("[BTN] bike link up — re-asserting media focus so the dash re-reads our player")
            releaseMediaFocus()
            session?.isActive = false
            handler.postDelayed({
                try {
                    takeMediaFocus()
                    session?.isActive = true
                    pinVolume()
                    log("[BTN] media focus re-asserted")
                } catch (e: Exception) {
                    log("[BTN] re-assert (re-take) failed: $e")
                }
            }, REASSERT_GAP_MS)
        } catch (e: Exception) {
            log("[BTN] re-assert failed: $e")
        }
    }

    /**
     * Live toggle: grab (true) or release (false) the bike's buttons.
     *
     * Grabbing means genuinely becoming the phone's active media app — see [takeMediaFocus]. Android
     * hands the media buttons to exactly ONE app, so this necessarily takes them off the music player
     * (and pauses it) for as long as it's on.
     */
    fun setCaptureActive(on: Boolean) {
        handler.post {
            try {
                if (on) takeMediaFocus() else releaseMediaFocus()
                session?.isActive = on
                if (on) pinVolume() else unpinVolume()
                log("[BTN] capture ${if (on) "ON — bike buttons drive Android Auto (music pauses)" else "OFF — buttons control media/volume"}")
            } catch (e: Exception) {
                log("[BTN] setCaptureActive failed: $e")
            }
        }
    }

    /**
     * Become the phone's active media app, so the bike's buttons come to US.
     *
     * THE BUG THIS FIXES: media buttons go to exactly one app — the one Android considers the active
     * media session, ranked by who actually holds audio focus and is playing. Merely declaring
     * STATE_PLAYING (what we did before) loses to any real music player, so holding a handlebar
     * button skipped the user's track and we never saw the event; with music paused, the player still
     * owned the session, so nothing happened at all. Winning requires two things a real player has:
     *   1. audio focus (AUDIOFOCUS_GAIN — this is what pauses the music player), and
     *   2. actual playback — hence a looping silent track, so we rank as genuinely playing.
     */
    private fun takeMediaFocus() {
        try {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(mediaAttrs)
                .setOnAudioFocusChangeListener { /* we play silence; nothing to duck */ }
                .build()
            focusRequest = req
            val granted = audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            log("[BTN] audio focus ${if (granted) "granted" else "DENIED"}")
        } catch (e: Exception) {
            log("[BTN] audio focus failed: $e")
        }
        startSilence()
        publishMetadata()
        // Re-stamp the playback state so we look freshly-playing (most recent session wins ties).
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_FAST_FORWARD or
                        PlaybackState.ACTION_REWIND
                )
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build()
        )
        postMediaNotification()
    }

    /**
     * Publish a fake "now playing" track over AVRCP.
     *
     * The dash gates its transport commands on there being a real track: it shows a song title on the
     * HUD, and won't send next/prev/play-pause for something that isn't playing. We took over the
     * media session but published no metadata, so the bike saw an empty track — which is why VOLUME
     * (a stateless command) got through while HOLD did nothing (2026-07-16 bike test). Giving it a
     * title/artist/duration makes the dash treat us as a playing track and emit those commands, which
     * we then map to Android Auto navigation. The title doubles as on-HUD feedback that the mode is on.
     */
    private fun publishMetadata() {
        try {
            session?.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Android Auto control")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Hold ▲/▼ to navigate · OK to select")
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, "OpenCfMoto")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, TRACK_MS)
                    .build()
            )
        } catch (e: Exception) {
            log("[BTN] metadata failed: $e")
        }
    }

    /**
     * Post a MediaStyle notification bound to our session.
     *
     * Without one, Android never lists us in the notification shade / lock-screen media controls —
     * i.e. the system doesn't treat us as a fully-registered media app, even though we hold the
     * session and audio focus. The dash appears to key off the same registration: after publishing
     * metadata it started sending PLAY, but never next/prev (2026-07-16 test). This is the remaining
     * piece that makes us look like a normal music player to both the system UI and AVRCP.
     */
    private fun postMediaNotification() {
        val s = session ?: return
        try {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(MEDIA_CHANNEL, "Bike button control", NotificationManager.IMPORTANCE_LOW)
            )
            val n = Notification.Builder(context, MEDIA_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Android Auto control")
                .setContentText("Bike buttons drive Android Auto")
                .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .build()
            nm.notify(MEDIA_NOTIF_ID, n)
            log("[BTN] media notification posted (registers us as a media player)")
        } catch (e: Exception) {
            log("[BTN] media notification failed: $e")
        }
    }

    private fun cancelMediaNotification() {
        try { context.getSystemService(NotificationManager::class.java).cancel(MEDIA_NOTIF_ID) } catch (_: Exception) {}
    }

    private fun releaseMediaFocus() {
        cancelMediaNotification()
        stopSilence()
        try { focusRequest?.let { audio.abandonAudioFocusRequest(it) } } catch (_: Exception) {}
        focusRequest = null
    }

    /** A looping silent track: makes us a genuinely "playing" media app so we win button routing. */
    private fun startSilence() {
        if (silence != null) return
        try {
            val rate = 8000
            val frames = rate            // 1 s of silence, looped forever
            val zeros = ShortArray(frames)
            val t = AudioTrack.Builder()
                .setAudioAttributes(mediaAttrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(frames * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(zeros, 0, zeros.size)
            t.setLoopPoints(0, frames, -1)
            t.setVolume(0f)
            t.play()
            silence = t
        } catch (e: Exception) {
            log("[BTN] silent track failed: $e")
        }
    }

    private fun stopSilence() {
        try { silence?.pause(); silence?.flush(); silence?.release() } catch (_: Exception) {}
        silence = null
    }

    fun stop() {
        if (instance === this) instance = null
        stopVolumeObserver()
        unpinVolume()
        releaseMediaFocus()
        try { session?.isActive = false } catch (_: Exception) {}
        try { session?.release() } catch (_: Exception) {}
        session = null
    }

    // ── volume as a navigation source ────────────────────────────────────────────────────────────

    /**
     * Pin the stream volume to the middle while capturing.
     *
     * The bike sends AVRCP *absolute* volume, so we read the DIRECTION of each change. Restoring to
     * the previous value (what we did first) breaks at the ends: at max, pressing up produces no
     * change, so no event — same at 0 for down (2026-07-16 bike report). Parking at mid guarantees
     * headroom both ways, so every press registers.
     */
    private fun pinVolume() {
        try {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (userVolume < 0) userVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            pinnedVolume = (max / 2).coerceIn(1, max - 1)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, pinnedVolume, 0)
            log("[BTN] volume pinned at $pinnedVolume/$max (headroom both ways)")
        } catch (e: Exception) {
            log("[BTN] pinVolume failed: $e")
        }
    }

    /** Stop pinning and give the user their volume back. */
    private fun unpinVolume() {
        pinnedVolume = -1
        if (userVolume >= 0) {
            try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, userVolume, 0) } catch (_: Exception) {}
            userVolume = -1
        }
    }

    /**
     * Watch the stream volume: the bike's up/down arrive as AVRCP absolute volume (no key event at
     * all — verified: a remote-volume provider never fired), so the direction of the change IS the
     * button press. Re-pin immediately afterwards.
     *
     * No timing guard: our own re-pin write lands back ON [pinnedVolume], which the equality check
     * below ignores. That's self-correcting and — unlike the old 300 ms guard — doesn't swallow
     * rapid presses.
     */
    private fun startVolumeObserver() {
        if (volumeObserver != null) return
        val obs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                val now = try { audio.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (e: Exception) { return }
                if (!ButtonMode.isControlAa(context) || pinnedVolume < 0) return
                if (now == pinnedVolume) return   // our own re-pin, or nothing to do

                val jump = now - pinnedVolume            // signed, in Android volume steps
                val up = jump > 0
                val dir = if (up) "UP" else "DOWN"
                // Re-pin FIRST: the gesture handling below can take a while (BACK/HOME redraw the
                // dash), and until we re-pin, a follow-up press is measured from the wrong base.
                try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, pinnedVolume, 0) } catch (_: Exception) {}

                val double = kotlin.math.abs(jump) >= DOUBLE_TAP_STEPS
                // Double tap — see [DOUBLE_TAP_STEPS]. Nothing is sent first: it arrives as one
                // event, so unlike the old timing-based guess we know it's a double before acting.
                val gesture = when {
                    up && double -> ButtonGesture.VOL_UP_DOUBLE
                    up -> ButtonGesture.VOL_UP_PRESS
                    double -> ButtonGesture.VOL_DOWN_DOUBLE
                    else -> ButtonGesture.VOL_DOWN_PRESS
                }
                log("[BTN] volume $dir ($pinnedVolume→$now, jump=$jump)${if (double) " ×2" else ""}")
                run(gesture)
            }
        }
        try {
            context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, obs)
            volumeObserver = obs
        } catch (e: Exception) {
            log("[BTN] volume observer failed: $e")
        }
    }

    private fun stopVolumeObserver() {
        volumeObserver?.let { try { context.contentResolver.unregisterContentObserver(it) } catch (_: Exception) {} }
        volumeObserver = null
    }

    // ── gesture → action dispatch ────────────────────────────────────────────────────────────────

    /**
     * Run whatever [ButtonMap] says this gesture should do.
     *
     * The mapping is read per press rather than cached, so changing it in Button mapping takes
     * effect on the very next press — no reconnect, no restart.
     */
    private fun run(gesture: ButtonGesture) {
        if (!ButtonMode.isControlAa(context)) return   // media mode: leave the buttons to music
        val action = ButtonMap.get(context, gesture)
        log("[BTN] ${gesture.label} → ${action.label}")
        perform(action)
    }

    private fun perform(action: ButtonAction) {
        when (action) {
            ButtonAction.NONE -> {}
            ButtonAction.KNOB_FORWARD -> AaVideoBridge.scrollSink?.invoke(+1)
            ButtonAction.KNOB_BACK -> AaVideoBridge.scrollSink?.invoke(-1)
            ButtonAction.SELECT -> key(AaInput.KEY_ENTER)
            ButtonAction.BACK -> key(AaInput.KEY_BACK)
            ButtonAction.HOME -> key(AaInput.KEY_HOME)
            ButtonAction.ASSISTANT -> key(AaInput.KEY_ASSISTANT)
            ButtonAction.DPAD_UP -> key(AaInput.KEY_UP)
            ButtonAction.DPAD_DOWN -> key(AaInput.KEY_DOWN)
            ButtonAction.DPAD_LEFT -> key(AaInput.KEY_LEFT)
            ButtonAction.DPAD_RIGHT -> key(AaInput.KEY_RIGHT)
            ButtonAction.NAV_1 -> navigate(0)
            ButtonAction.NAV_2 -> navigate(1)
            ButtonAction.NAV_3 -> navigate(2)
        }
    }

    private fun key(code: Int) {
        val sink = AaVideoBridge.keySink
        if (sink == null) log("[BTN] no Android Auto session — key $code dropped") else sink(code)
    }

    private fun navigate(slot: Int) {
        NavLauncher.navigate(context, SavedPlaces.query(context, slot), log)
    }

    // ── where gestures come from ─────────────────────────────────────────────────────────────────

    private val callback = object : MediaSession.Callback() {
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            @Suppress("DEPRECATION")
            val ke = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (ke != null && ke.action == KeyEvent.ACTION_DOWN) {
                log("[BTN] media key ${KeyEvent.keyCodeToString(ke.keyCode)} (code=${ke.keyCode})")
                forward(ke.keyCode)
            }
            return true
        }
        // Fallbacks: the bike takes the raw-key path above (onMediaButtonEvent returning true
        // suppresses these), but other dashes / Bluetooth remotes may dispatch here instead.
        override fun onPlay() = run(ButtonGesture.ENTER_PRESS)
        override fun onPause() = run(ButtonGesture.ENTER_PRESS)
    }

    /**
     * Map a raw media keycode to a gesture.
     *
     * This — not the [callback] transport methods — is the path the bike actually takes: it sends
     * AVRCP passthrough, which arrives as a KeyEvent, and [MediaSession.Callback.onMediaButtonEvent]
     * returning true suppresses the onPlay/onPause dispatch.
     *
     * Only enter is acted on. Everything else the dash can send here (next/previous from holding
     * ▲/▼, and in theory stop/ff/rewind) is logged and dropped — holding was tried on the bike and
     * Android Auto ignores whatever we forward from it, and aliasing an unused key onto some other
     * gesture's action would be a nasty surprise when that gesture is mapped to "navigate home".
     * The raw keycode is still logged above, so a dash that behaves differently shows up in a ride
     * log rather than silently doing nothing.
     */
    private fun forward(keyCode: Int) {
        when (keyCode) {
            // The dash's enter is a play/pause TOGGLE: it sends PLAY when it believes we're stopped
            // and PAUSE when it believes we're playing (which is why it flipped to PAUSE once we
            // published metadata). Both are the same physical button.
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> run(ButtonGesture.ENTER_PRESS)
        }
    }

    companion object {
        /** Duration of the fake track we advertise, so the dash sees a normal "now playing". */
        private const val TRACK_MS = 3_600_000L

        /**
         * How big a volume jump means the rider double-tapped, rather than tapped once.
         *
         * A double-tap does NOT arrive as two events. The bike coalesces the presses and sends a
         * single AVRCP absolute volume, so the *size* of the jump is the press count — which is why
         * the original timing-based detector never once fired (2026-07-16 bike log, ~700 presses).
         * Measured from the 12/25 pin, every event in that log was one of exactly five values:
         *
         *   ▲ single → 14 (+2)      ▲ double → 16 (+4)
         *   ▼ single → 11 (−1)      ▼ double →  9 (−3), occasionally 8 (−4)
         *
         * so 3 separates them with a step of margin either side. (The up/down asymmetry is the
         * bike's ~10/127 step landing on different sides of Android's 25-step rounding.) The jump is
         * logged on every press: if a dash calibrates differently, that log line is what to re-read.
         */
        private const val DOUBLE_TAP_STEPS = 3

        // Media re-assert once the bike link is up — see [scheduleReassertWhenBikeUp].
        private const val REASSERT_POLL_MS = 1_000L
        private const val REASSERT_GIVEUP_MS = 90_000L
        /** Let the dash's AVRCP link settle after the bike connects before poking it. */
        private const val REASSERT_SETTLE_MS = 3_000L
        /** Long enough that the drop and re-take read as two events, not a no-op. */
        private const val REASSERT_GAP_MS = 500L
        private const val MEDIA_CHANNEL = "opencfmoto_media"
        private const val MEDIA_NOTIF_ID = 3   // must not collide with AndroidAutoService's NOTIF_ID (2)

        /** The live bridge (when Android Auto is running), so the settings toggle can reach it. */
        @Volatile var instance: MediaButtonBridge? = null
            private set
    }
}
