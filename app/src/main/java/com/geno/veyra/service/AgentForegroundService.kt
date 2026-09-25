package com.geno.veyra.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.geno.veyra.MainActivity
import com.geno.veyra.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Keeps the assistant session alive while the app is backgrounded. Holds a
 * partial wakelock only for the duration of an active session — no session,
 * zero background work (battery/thermal requirement from the spec). The
 * [AgentController] itself is started/stopped by the UI; this service just
 * guarantees the process stays warm and shows the required ongoing notification.
 */
@AndroidEntryPoint
class AgentForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startForegroundSession()
        }
        return START_STICKY
    }

    private fun startForegroundSession() {
        ensureChannel()
        val notification = buildNotification()
        // The wake word can trigger a session while the app is backgrounded,
        // and Android 14+ forbids starting a *microphone*-type foreground
        // service from the background (SecurityException). connectedDevice is
        // not a foreground-only type, so it works from either state — and it
        // describes this service's real job: keeping the session alive with
        // the connected glasses. (Both types are declared in the manifest.)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireWakeLock()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Veyra::Session").apply {
            setReferenceCounted(false)
            acquire(SESSION_WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun buildNotification(): Notification {
        val open = android.app.PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_glasses)
            .setOngoing(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "veyra_session"
        private const val NOTIFICATION_ID = 42
        private const val SESSION_WAKELOCK_TIMEOUT_MS = 60L * 60L * 1000L // 1h ceiling
        const val ACTION_STOP = "com.geno.veyra.STOP"

        fun start(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AgentForegroundService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
