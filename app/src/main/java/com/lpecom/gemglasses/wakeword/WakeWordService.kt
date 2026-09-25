package com.lpecom.gemglasses.wakeword

import android.annotation.SuppressLint
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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lpecom.gemglasses.MainActivity
import com.lpecom.gemglasses.R
import com.lpecom.gemglasses.service.AssistantStarter
import com.lpecom.gemglasses.settings.AgentPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Always-on wake-word listener. Runs the [WakeWordEngine] against the glasses
 * microphone; on detection it starts the assistant session through
 * [AssistantStarter] — the exact same path as the HomeScreen button — and
 * stops itself. [WakeWordCoordinator] restarts it when the session ends.
 */
@AndroidEntryPoint
class WakeWordService : Service() {

    @Inject
    lateinit var engine: WakeWordEngine

    @Inject
    lateinit var starter: AssistantStarter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var runJob: Job? = null
    private var activePhrase: String? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val phrase = intent?.getStringExtra(EXTRA_PHRASE)
            .takeUnless { it.isNullOrBlank() }
            ?: AgentPreferences.DEFAULT.wakePhrase

        startForeground(
            NOTIFICATION_ID,
            buildNotification(phrase),
            // connectedDevice, not microphone: the coordinator restarts this
            // service fresh after a session ends, which can happen while the
            // app is backgrounded — and Android 14+ throws SecurityException
            // when a *microphone*-type FGS is started from the background.
            // connectedDevice isn't a foreground-only type, and it describes
            // the work honestly (listening on the glasses' microphone).
            // (Both types stay declared in the manifest.)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            else
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )

        // Restart the engine only when the phrase actually changed; repeat
        // start() calls while already listening are a no-op.
        if (phrase != activePhrase) {
            activePhrase = phrase
            runJob?.cancel()
            runJob = scope.launch {
                launch {
                    engine.detections.collect { onDetected(it) }
                }
                try {
                    engine.start(phrase)
                } catch (e: Exception) {
                    Log.e(TAG, "wake-word engine failed", e)
                    stopSelf()
                }
            }
        }

        return START_STICKY
    }

    // RECORD_AUDIO is held — the engine is actively recording with it.
    @SuppressLint("MissingPermission")
    private fun onDetected(phrase: String) {
        Log.i(TAG, "wake word detected (\"$phrase\") — starting assistant")
        starter.start(initialText = phrase)
        stopSelf()
    }

    override fun onDestroy() {
        runJob?.cancel()
        runJob = null
        activePhrase = null

        // Release the mic/model promptly, then tear down the scope.
        scope.launch {
            runCatching { engine.stop() }
        }.invokeOnCompletion { scope.cancel() }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.wakeword_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(phrase: String): Notification {
        val pretty = phrase
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar(Char::titlecase) }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.wakeword_title))
            .setContentText(getString(R.string.wakeword_text, pretty))
            .setSmallIcon(R.drawable.ic_glasses)
            .setOngoing(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "wakeword"
        private const val NOTIFICATION_ID = 43
        private const val EXTRA_PHRASE = "extra_wake_phrase"
        const val ACTION_STOP = "com.lpecom.gemglasses.wakeword.STOP"

        fun start(context: Context, phrase: String) {
            val intent = Intent(context, WakeWordService::class.java)
                .putExtra(EXTRA_PHRASE, phrase)
            // minSdk 29: startForegroundService is always available.
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, WakeWordService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
