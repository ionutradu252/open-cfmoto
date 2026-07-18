package dev.snaipdefix.opencflink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import dev.snaipdefix.opencflink.aa.AaReceiver

/**
 * Foreground service that hosts the Android Auto receiver end-to-end (M4):
 *   VideoPipeline (H.264 encoder, external-source mode) + AaReceiver (loopback AAP receiver).
 *
 * Running as a foreground service with a partial wake lock keeps the whole decode→encode→PXC
 * chain alive while the phone is backgrounded or the screen is locked. The encoder's input
 * Surface is published to [AaVideoBridge] so [EasyConnProber] streams the re-encoded Android
 * Auto video to the bike dash.
 */
class AndroidAutoService : Service() {

    private var pipeline: VideoPipeline? = null
    private var receiver: AaReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaButtons: MediaButtonBridge? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }
        startAsForeground()
        startReceiver()
        isRunning = true
        return START_STICKY
    }

    private fun startAsForeground() {
        val channelId = "opencflink_androidauto"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Android Auto receiver", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("OpenCFLink — Android Auto")
            .setContentText("Receiving Android Auto for the bike dash — tap to open")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openApp)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenCFLink:AndroidAuto").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L /* 4h safety cap */)
        }
        LogBus.log("[AA] foreground service up (wake lock held)")
    }

    private fun startReceiver() {
        if (receiver != null) { LogBus.log("[AA] receiver already started"); return }
        try {
            // Compositor mode: the AA decoder renders into the compositor's input surface; the
            // compositor letterboxes it into the bike's canvas (encoder created later, sized to the
            // bike's REQ_CONFIG_CAPTURE dims, see EasyConnProber / VideoPipeline.configureBikeCanvas).
            val vp = VideoPipeline(applicationContext, 0, 0, LogBus::log, compositor = true)
            vp.start()
            val surface = vp.decoderInputSurface()
            if (surface == null) {
                LogBus.log("[AA] compositor input surface null — cannot start receiver")
                vp.stop()
                stopSelf()
                return
            }
            pipeline = vp
            AaVideoBridge.pipeline = vp
            receiver = AaReceiver(applicationContext, surface, LogBus::log).also { it.start() }
            // Capture Bluetooth/media hardware buttons (bike handlebar buttons) → AA navigation.
            mediaButtons = MediaButtonBridge(applicationContext, LogBus::log).also { it.start() }
        } catch (e: Exception) {
            LogBus.log("[AA] receiver start failed: $e")
            stopSelf()
        }
    }

    override fun onDestroy() {
        isRunning = false
        try { mediaButtons?.stop() } catch (_: Exception) {}
        mediaButtons = null
        try { receiver?.stop() } catch (_: Exception) {}
        receiver = null
        AaVideoBridge.pipeline = null
        try { pipeline?.stop() } catch (_: Exception) {}
        pipeline = null
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        LogBus.log("[AA] foreground service stopped")
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 2

        @Volatile var isRunning = false
            private set

        const val ACTION_STOP = "dev.snaipdefix.opencflink.ACTION_STOP_AA"

        fun start(ctx: Context) {
            val i = Intent(ctx, AndroidAutoService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AndroidAutoService::class.java))
        }
    }
}
