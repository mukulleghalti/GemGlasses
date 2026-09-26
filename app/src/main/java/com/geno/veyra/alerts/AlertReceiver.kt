package com.geno.veyra.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.geno.veyra.MainActivity
import com.geno.veyra.R
import com.geno.veyra.settings.AppLocaleStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Fired by AlarmManager when a timer or reminder goes off. Removes the
 * alert from the store, speaks it through [AlertSpeaker], and posts a
 * notification so the alert is visible even if TTS is missed.
 */
@AndroidEntryPoint
class AlertReceiver : BroadcastReceiver() {

    @Inject
    lateinit var scheduler: AlertScheduler

    @Inject
    lateinit var speaker: AlertSpeaker

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()

        val id = intent.getStringExtra(EXTRA_ID)
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        val kind = intent.getStringExtra(EXTRA_KIND).orEmpty()

        if (id == null) {
            Log.w(TAG, "alert fired without id")
            pending.finish()
            return
        }

        scope.launch {
            runCatching { scheduler.removeFired(id) }
        }

        // Resolve user-visible strings in the chosen app language, not
        // the system language.
        val localized = AppLocaleStore.wrapWithAppLocale(context)
        val title = localized.getString(
            if (kind == "timer") R.string.alert_timer_done else R.string.alert_reminder,
        )

        ensureChannel(localized)
        postNotification(context, title, label)

        val speech = "$title. $label"

        speaker.speak(speech.trim()) {
            pending.finish()
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.alert_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    private fun postNotification(
        context: Context,
        title: String,
        label: String,
    ) {
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_glasses)
            .setContentTitle(title)
            .setContentText(label.ifBlank { title })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val TAG = "AlertReceiver"
        const val CHANNEL_ID = "veyra_alerts"
        const val NOTIFICATION_ID = 43

        const val EXTRA_ID = "com.geno.veyra.alerts.EXTRA_ID"
        const val EXTRA_LABEL = "com.geno.veyra.alerts.EXTRA_LABEL"
        const val EXTRA_KIND = "com.geno.veyra.alerts.EXTRA_KIND"
    }
}
