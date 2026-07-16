package dev.snaipdefix.opencflink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Minimal foreground service of type `mediaProjection`. Android 14+ requires an FGS of this
 * type to be running before MediaProjectionManager.getMediaProjection() is called.
 */
class ProjectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "opencflink_projection"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Screen mirroring", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("OpenCFLink")
            .setContentText("Mirroring screen to bike")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
        isForeground = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isForeground = false
        super.onDestroy()
    }

    companion object {
        /** True once startForeground() has completed — poll this before getMediaProjection(). */
        @Volatile var isForeground = false
            private set

        fun start(ctx: Context) {
            isForeground = false
            val i = Intent(ctx, ProjectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
        fun stop(ctx: Context) {
            isForeground = false
            ctx.stopService(Intent(ctx, ProjectionService::class.java))
        }
    }
}
