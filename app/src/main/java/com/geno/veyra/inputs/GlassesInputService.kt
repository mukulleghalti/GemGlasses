package com.geno.veyra.inputs

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.geno.veyra.MainActivity
import com.geno.veyra.R
import com.geno.veyra.agent.AgentController
import com.geno.veyra.agent.AgentStatus
import com.geno.veyra.glasses.CapturePress
import com.geno.veyra.glasses.GlassesInputEvent
import com.geno.veyra.glasses.GlassesManager
import com.geno.veyra.service.AgentForegroundService
import com.geno.veyra.service.AssistantStarter
import com.geno.veyra.settings.AppLocaleStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Capture-button listener (spike). Holds a DAT session with the Inputs
 * capability attached and turns capture-button presses into assistant
 * actions:
 *
 * - SHORT_PRESS toggles the assistant session start/stop.
 * - HOLD starts the assistant with a "what am I looking at" question.
 * - DOUBLE_PRESS is logged only — unmapped in this spike.
 *
 * Every event is logged under the `GlassesInput` tag and in the backend
 * under `RealGlassesBackend`, so device tests can be read from logcat.
 * The session is created through [GlassesManager.connect]; no camera is
 * attached until an assistant session starts.
 */
@AndroidEntryPoint
class GlassesInputService : Service() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleStore.wrapWithAppLocale(newBase))
    }

    @Inject
    lateinit var glassesManager: GlassesManager

    @Inject
    lateinit var controller: AgentController

    @Inject
    lateinit var starter: AssistantStarter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectJob: Job? = null

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

        if (collectJob?.isActive != true) {
            collectJob = scope.launch {
                glassesManager.initialize()
                val connected = glassesManager.connect()
                Log.i(TAG, "input listener connect()=$connected")
                glassesManager.inputEvents.collect { event ->
                    onInputEvent(event)
                }
            }
        }

        return START_STICKY
    }

    private fun onInputEvent(event: GlassesInputEvent) {
        Log.i(TAG, "input event: $event")
        when (event) {
            is GlassesInputEvent.Capture ->
                when (event.press) {
                    CapturePress.SHORT_PRESS -> toggleAssistant()
                    CapturePress.HOLD -> startWithPhotoQuestion()
                    CapturePress.DOUBLE_PRESS ->
                        Log.i(TAG, "double press: unmapped in this spike")
                }

            is GlassesInputEvent.Other -> Unit
        }
    }

    private fun toggleAssistant() {
        if (controller.status.value != AgentStatus.IDLE) {
            Log.i(TAG, "short press: stopping assistant")
            controller.stop()
            AgentForegroundService.stop(this)
        } else {
            startAssistant(initialText = null)
        }
    }

    private fun startWithPhotoQuestion() {
        if (controller.status.value != AgentStatus.IDLE) {
            Log.i(TAG, "hold: assistant already running; photo question skipped (spike)")
            return
        }
        startAssistant(initialText = PHOTO_QUESTION)
    }

    // RECORD_AUDIO is checked right here before calling the starter.
    @SuppressLint("MissingPermission")
    private fun startAssistant(initialText: String?) {
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "press ignored: RECORD_AUDIO not granted")
            return
        }
        Log.i(TAG, "starting assistant (photoQuestion=${initialText != null})")
        starter.start(initialText)
    }

    override fun onDestroy() {
        collectJob?.cancel()
        scope.cancel()
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
                getString(R.string.input_channel),
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
            .setContentTitle(getString(R.string.input_notification_title))
            .setContentText(getString(R.string.input_notification_text))
            .setSmallIcon(R.drawable.ic_glasses)
            .setOngoing(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "GlassesInput"
        private const val CHANNEL_ID = "glasses_input"
        private const val NOTIFICATION_ID = 44
        const val ACTION_STOP = "com.geno.veyra.inputs.STOP"

        private const val PHOTO_QUESTION =
            "What am I looking at? Describe what you see in front of me."

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, GlassesInputService::class.java),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, GlassesInputService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
