package com.geno.veyra.live

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
import androidx.core.app.NotificationCompat
import com.geno.veyra.MainActivity
import com.geno.veyra.R
import com.geno.veyra.settings.AppLocaleStore

/**
 * Keeps the process alive while [LiveStreamManager] publishes. Started
 * and stopped by the manager only; shows a persistent "You are live"
 * notification.
 */
class LiveForegroundService : Service() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleStore.wrapWithAppLocale(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.live_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.live_notification_title))
            .setContentText(getString(R.string.live_notification_text))
            .setSmallIcon(R.drawable.ic_glasses)
            .setOngoing(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "live_stream"
        private const val NOTIFICATION_ID = 45
        const val ACTION_STOP = "com.geno.veyra.live.STOP"

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, LiveForegroundService::class.java),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LiveForegroundService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
