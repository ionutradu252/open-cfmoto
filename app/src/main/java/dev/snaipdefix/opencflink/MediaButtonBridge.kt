package dev.snaipdefix.opencflink

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
import dev.snaipdefix.opencflink.aa.AaInput

/**
 * turns the handlebar buttons into android auto navigation.
 *
 * the buttons never come over the pxc link (input capture on :10920-:10922 saw nothing). what does
 * work, from bike tests:
 *   450SR: short press up/down = avrcp absolute volume, we read the direction as a knob click.
 *          hold enter = avrcp play/pause = select.
 *   800NK: left/right = next/prev track keys, star = play/pause. it sends no volume at all.
 *
 * the dash only sends the transport keys once we look like a real player, hence the audio focus,
 * the silent track, the metadata and the notification.
 *
 * volume is watched with a ContentObserver, not a VolumeProvider: the bike sends absolute volume so
 * there's no volume key event to intercept. the provider never fired and added a second volume
 * slider on the phone, so it's gone.
 *
 * all gated on ButtonMode, toggle off and everything behaves normally again.
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
    /** volume we hold the stream at while capturing, so there's headroom both ways */
    private var pinnedVolume = -1
    /** last press per double-tappable key, see pressedWithDouble */
    private val lastPressMs = HashMap<ButtonGesture, Long>()
    /** the dash only needs re-reading once per session, see reassert */
    private var reasserted = false
    /** set by stop(). nothing on the handler may touch audio focus after this. */
    @Volatile private var released = false
    /** the user's own volume, put back when capture goes off */
    private var userVolume = -1
    private var focusRequest: AudioFocusRequest? = null
    private var silence: AudioTrack? = null

    fun start() {
        handler.post {
            try {
                val s = MediaSession(context, "OpenCFLink")
                s.setCallback(callback)
                val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_FAST_FORWARD or
                    PlaybackState.ACTION_REWIND
                // an active PLAYING session is what makes android route media buttons to us
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
     * re-announce ourselves once the bike is actually connected.
     *
     * we take media focus when the service starts, ~8s before the pxc link comes up (2026-07-16 log:
     * focus at :36.269, bike at :44.674). the dash reads the player's capabilities when its avrcp
     * link forms and never re-reads, so it can read them before we've published anything, symptom
     * is next/prev/pause doing nothing all session. toggling the setting off and on by hand fixed it
     * because that drops and re-takes focus. this does the same thing at the right moment.
     *
     * only while capture is on, so the music player is already paused and the focus drop is free.
     */
    private fun scheduleReassertWhenBikeUp() {
        if (reasserted || released) return
        val poll = object : Runnable {
            var waited = 0L
            override fun run() {
                if (reasserted || released || !ButtonMode.isControlAa(context)) return
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
        if (released || !ButtonMode.isControlAa(context)) return
        try {
            log("[BTN] bike link up — re-asserting media focus so the dash re-reads our player")
            releaseMediaFocus()
            session?.isActive = false
            handler.postDelayed({
                try {
                    if (released) return@postDelayed
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
     * grab (true) or release (false) the bike's buttons, live.
     *
     * grabbing means becoming the phone's active media app (see takeMediaFocus). android gives the
     * media buttons to exactly one app, so this takes them off your music player and pauses it for
     * as long as it's on.
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
     * become the phone's active media app so the buttons come to us.
     *
     * media buttons go to one app: whichever android thinks is the active media session, ranked by
     * who holds audio focus and is actually playing. just declaring STATE_PLAYING (what we did at
     * first) loses to any real music player, holding a handlebar button skipped the user's track
     * and we never saw it. winning needs the two things a real player has:
     *   1. audio focus (AUDIOFOCUS_GAIN, this is what pauses the music player)
     *   2. actual playback, hence the looping silent track
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
        // re-stamp the state so we look freshly playing (most recent session wins ties)
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
     * publish a fake "now playing" track over avrcp.
     *
     * the dash won't send next/prev/play-pause unless it thinks there's a real track, it shows a
     * song title on the hud. we had the media session but published no metadata, so the bike saw an
     * empty track: volume (stateless) got through, hold did nothing (2026-07-16 test). giving it a
     * title/artist/duration makes it treat us as playing. the title also shows the mode is on.
     */
    private fun publishMetadata() {
        try {
            session?.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Android Auto control")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Hold ▲/▼ to navigate · OK to select")
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, "OpenCFLink")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, TRACK_MS)
                    .build()
            )
        } catch (e: Exception) {
            log("[BTN] metadata failed: $e")
        }
    }

    /**
     * post a mediastyle notification tied to our session.
     *
     * without one android doesn't list us in the shade / lock screen, i.e. doesn't count us as a
     * fully registered media app even though we hold the session and focus. the dash seems to key
     * off the same thing: after metadata it sent PLAY but never next/prev (2026-07-16 test).
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

    /** looping silent track, so we count as actually playing and win button routing */
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

    /**
     * tear down for good.
     *
     * the released flag and the callback purge matter: the re-assert poll sits on this handler for
     * up to 90s, so without them a bridge you'd already stopped wakes up later, takes audio focus,
     * posts a notification and re-pins the volume, your music pausing itself with the app stopped.
     * showed up in the 2026-07-16 800NK log as two "bike link up" lines 23ms apart, the dead bridge
     * and its replacement both alive.
     */
    fun stop() {
        released = true
        handler.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        stopVolumeObserver()
        unpinVolume()
        releaseMediaFocus()
        try { session?.isActive = false } catch (_: Exception) {}
        try { session?.release() } catch (_: Exception) {}
        session = null
    }

    // ---- volume as a navigation source ----────────────────

    /**
     * park the volume mid-scale while capturing.
     *
     * the bike sends absolute volume, so we read the direction of each change. restoring the
     * previous value (first attempt) breaks at the ends: at max, pressing up changes nothing so
     * there's no event, same at 0 for down. mid-scale always has headroom both ways.
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

    /** stop pinning, give the user their volume back */
    private fun unpinVolume() {
        pinnedVolume = -1
        if (userVolume >= 0) {
            try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, userVolume, 0) } catch (_: Exception) {}
            userVolume = -1
        }
    }

    /**
     * watch the stream volume. the 450SR's up/down arrive as absolute volume with no key event at
     * all (a VolumeProvider never fired once), so the direction of the change is the press. re-pin
     * straight after.
     *
     * no timing guard: our own re-pin lands back on pinnedVolume and the equality check below
     * ignores it. self correcting, and unlike the old 300ms guard it doesn't swallow fast presses.
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
                // re-pin first: the gesture below can take a while (back/home redraw the dash) and
                // until we do, the next press is measured from the wrong base
                try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, pinnedVolume, 0) } catch (_: Exception) {}

                val maxVol = try { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } catch (e: Exception) { 25 }
                if (kotlin.math.abs(jump) > maxGestureSteps(maxVol)) {
                    // not a handlebar press. something else moved the volume: the phone's slider,
                    // a call, or the bt link dropping (which slams it to 0). acting on that sent a
                    // spurious back to AA the moment the bike disconnected.
                    log("[BTN] volume $dir ($pinnedVolume→$now, jump=$jump) — too big for a button, ignoring")
                    return
                }

                val double = kotlin.math.abs(jump) >= doubleTapSteps(maxVol)
                // double tap, see DOUBLE_TAP_STEPS. it arrives as one event so we know before
                // acting, unlike the old timing based guess.
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

    // ---- gesture -> action ----──────────────────

    /** run whatever ButtonMap says this gesture does. read per press, so edits apply right away. */
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

    /**
     * a key that can also be double tapped.
     *
     * the single fires immediately instead of waiting out the window. these are the knob, and
     * putting 400ms of lag on every scroll step to serve a gesture most people won't map is a bad
     * trade. cost: a double runs the single first, and scrolling faster than ~2.5 clicks/sec reads
     * as doubles.
     *
     * that's why the detection is skipped unless the x2 gesture is mapped to something. left on
     * "do nothing" (the default) the button behaves exactly as before, no timing involved.
     */
    private fun pressedWithDouble(single: ButtonGesture, double: ButtonGesture) {
        if (!ButtonMode.isControlAa(context)) return
        if (ButtonMap.get(context, double) == ButtonAction.NONE) { run(single); return }

        val now = android.os.SystemClock.uptimeMillis()
        val last = lastPressMs[single] ?: 0L
        if (now - last < DOUBLE_TAP_MS) {
            lastPressMs[single] = 0L        // consume it, so a triple isn't two doubles
            run(double)
            return
        }
        lastPressMs[single] = now
        run(single)
    }

    private fun key(code: Int) {
        val sink = AaVideoBridge.keySink
        if (sink == null) log("[BTN] no Android Auto session — key $code dropped") else sink(code)
    }

    private fun navigate(slot: Int) {
        NavLauncher.navigate(context, SavedPlaces.query(context, slot), log)
    }

    // ---- where gestures come from ----─────────────────────

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
        // not just fallbacks: the bike's raw keys take the path above, but android auto itself
        // calls these directly (transport controls, no KeyEvent) when its on-screen media buttons
        // are used. on a touch dash that's most play/pause traffic.
        override fun onPlay() = enterPress()
        override fun onPause() = enterPress()
        override fun onSkipToNext() = run(ButtonGesture.NEXT_KEY)
        override fun onSkipToPrevious() = run(ButtonGesture.PREV_KEY)
    }

    /**
     * play/pause with a loop breaker.
     *
     * on a touch dash this can feed itself: tap AA's on-screen play button, gearhead calls play()
     * on our session (we're the active media session), we turn that into select, select presses
     * the still-focused play button, gearhead calls pause(), repeat. the 800NK log has hundreds
     * of selects at ~8ms apart from one tap, for seconds.
     *
     * a real double tap on that bike measures ~230ms between presses and no finger gets under
     * ~120ms, while the loop runs at ipc speed. so a play/pause under 150ms after the last one we
     * acted on can't be a person and is dropped. dropping it sends no select, which is what
     * starves the loop.
     */
    private fun enterPress() {
        val now = android.os.SystemClock.elapsedRealtime()
        val gap = now - lastEnterMs
        if (gap in 0 until ENTER_MIN_GAP_MS) {
            if (enterDropsLogged++ % 50 == 0) {
                log("[BTN] play/pause ${gap}ms after the last one — not a finger, dropped")
            }
            return
        }
        enterDropsLogged = 0
        lastEnterMs = now
        run(ButtonGesture.ENTER_PRESS)
    }

    private val ENTER_MIN_GAP_MS = 150L
    private var lastEnterMs = 0L
    private var enterDropsLogged = 0

    /**
     * map a raw media keycode to a gesture. this, not the callback methods above, is the path the
     * bike actually takes: avrcp passthrough arrives as a KeyEvent, and onMediaButtonEvent returning
     * true stops onPlay/onPause firing.
     *
     * keys with no gesture (stop, ff, rewind) are logged and dropped, not aliased onto some other
     * gesture, that gesture might be mapped to "navigate home".
     */
    private fun forward(keyCode: Int) {
        when (keyCode) {
            // enter is a play/pause toggle: PLAY when the dash thinks we're stopped, PAUSE when it
            // thinks we're playing (why it flipped once we published metadata). same button.
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> enterPress()
            // on the 800NK these are the left/right buttons, and it sends no volume at all. an
            // earlier version dropped them (on the 450SR they're a useless hold) which left that
            // bike with two dead buttons.
            KeyEvent.KEYCODE_MEDIA_NEXT ->
                pressedWithDouble(ButtonGesture.NEXT_KEY, ButtonGesture.NEXT_DOUBLE)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS ->
                pressedWithDouble(ButtonGesture.PREV_KEY, ButtonGesture.PREV_DOUBLE)
        }
    }

    companion object {
        /** length of the fake track we advertise, so the dash sees a normal now-playing */
        private const val TRACK_MS = 3_600_000L

        /**
         * how big a volume jump means the rider double-tapped, rather than tapped once.
         *
         * A double-tap does NOT arrive as two events. The bike coalesces the presses and sends a
         * single AVRCP absolute volume, so the *size* of the jump is the press count, which is why
         * the original timing-based detector never once fired (2026-07-16 bike log, ~700 presses).
         * Measured on a Xiaomi (max volume 25, pinned at 12), every event in that log was one of
         * exactly five values:
         *
         *   ▲ single → 14 (+2)      ▲ double → 16 (+4)
         *   ▼ single → 11 (−1)      ▼ double →  9 (−3), occasionally 8 (−4)
         *
         * so 3 separated them with a step of margin either side. But **the scale is the phone's, not
         * the bike's**: the dash moves AVRCP volume by a fixed ~8% of full scale, which lands on a
         * different number of Android steps depending on how many the phone has (25 on that Xiaomi,
         * 15 on the Samsung in the 800NK log). Hard-coding 3 would read a Samsung double-tap as a
         * single. Hence a fraction of max, with the Xiaomi numbers as the anchor: 12% of 25 = 3.
         */
        private fun doubleTapSteps(maxVol: Int): Int =
            kotlin.math.max(2, kotlin.math.ceil(maxVol * 0.12).toInt())

        /**
         * Above this, the volume didn't move because of a handlebar button.
         *
         * the phone's own slider, a call, or the Bluetooth link dropping can all rewrite the volume
         *, and a drop slams it to 0, which from a mid-scale pin looks like an enormous "double
         * tap". That fired a spurious BACK into Android Auto at the exact moment the 800NK
         * disconnected (2026-07-16 log: `volume DOWN (7→0, jump=-7) ×2 → Back`). A real press is a
         * couple of steps; anything approaching a third of the scale is somebody else's doing.
         */
        private fun maxGestureSteps(maxVol: Int): Int =
            kotlin.math.max(4, kotlin.math.ceil(maxVol * 0.28).toInt())

        /**
         * Window for a double-tap of the ◀/▶ keys, see [pressedWithDouble].
         *
         * Measured, not guessed: on the 800NK two deliberate taps arrived 224 ms and 245 ms apart
         * (2026-07-16 log), and a single press produces exactly one event (2026-07-17 log), so the
         * dash isn't echoing and timing is a real signal here. 400 ms leaves comfortable headroom
         * over ~230 ms without being so wide that ordinary scrolling reads as doubles, and since
         * the detection only runs when the ×2 gesture is mapped, the failure mode is opt-in.
         */
        private const val DOUBLE_TAP_MS = 400L

        // media re-assert once the bike link is up, see scheduleReassertWhenBikeUp
        private const val REASSERT_POLL_MS = 1_000L
        private const val REASSERT_GIVEUP_MS = 90_000L
        /** let the avrcp link settle after the bike connects before poking it */
        private const val REASSERT_SETTLE_MS = 3_000L
        /** long enough that the drop and re-take read as two events, not a no-op */
        private const val REASSERT_GAP_MS = 500L
        private const val MEDIA_CHANNEL = "opencflink_media"
        private const val MEDIA_NOTIF_ID = 3   // must not collide with AndroidAutoService's NOTIF_ID (2)

        /** the live bridge, so the settings toggle can reach it */
        @Volatile var instance: MediaButtonBridge? = null
            private set
    }
}
