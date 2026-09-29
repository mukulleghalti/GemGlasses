package com.geno.veyra.tap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import com.geno.veyra.MainActivity
import com.geno.veyra.R
import com.geno.veyra.settings.AppLocaleStore
import dagger.hilt.android.AndroidEntryPoint

/**
 * Spike: tap-to-start for ChatGPT (Chachan-style).
 *
 * Holds an active [MediaSession] so the glasses' temple tap (which arrives
 * as a Bluetooth media-button event) is routed to Veyra instead of the
 * music player. On tap it fires [Intent.ACTION_ASSIST], which opens the
 * user's default assistant in voice mode — the user sets ChatGPT as
 * the default assistant, exactly like Chachan's documented setup.
 *
 * Known tradeoff, same as Chachan's: the media-button channel is shared,
 * so while this is enabled Veyra competes with music apps for the tap.
 * Mitigation: taps are ignored while music is actively playing
 * ([AudioManager.isMusicActive]), so play/pause keeps working mid-track.
 */
@AndroidEntryPoint
class TapToChatGptService : Service() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleStore.wrapWithAppLocale(newBase))
    }

    private var mediaSession: MediaSession? = null

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            else 0,
        )

        if (mediaSession == null) {
            val session = MediaSession(this, TAG)
            session.setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(
                    mediaButtonEvent: Intent,
                ): Boolean {
                    return handleMediaButton(mediaButtonEvent)
                }
            })
            // Framework MediaSession delivers media-button events via the
            // PendingIntent set here, NOT via Callback.onMediaButtonEvent.
            // Point it at our manifest receiver so taps actually arrive.
            val receiverIntent =
                Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(packageName)
            val pi = PendingIntent.getBroadcast(
                this,
                0,
                receiverIntent,
                PendingIntent.FLAG_IMMUTABLE,
            )
            session.setMediaButtonReceiver(pi)
            // Claim media-button handling so taps route here.
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS)
            // Publish a PLAYING playback state: the system only routes
            // media-button events to sessions it considers "real" players.
            // A session stuck at PAUSED that never played is deprioritized
            // and taps vanish silently. PLAYING makes us the dispatch
            // target; we still ignore taps while AudioManager.isMusicActive
            // so actual music keeps working. Spike only -- UX polish later.
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1.0f)
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                    .build(),
            )
            session.isActive = true
            mediaSession = session
            // Hold audio focus so the system treats us as the active media
            // app. A real music app takes focus back when it plays.
            runCatching {
                val audio = getSystemService(AudioManager::class.java)
                audio?.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN,
                )
            }
            Log.i(TAG, "MediaSession active; listening for glasses taps")
        } else {
            // Re-assert dispatch priority: the system routes taps to the
            // most recently active eligible session, so if another app's
            // session has taken the lead (e.g. a music app that was opened
            // after us), toggle active state and refresh the playback
            // state to bump us back to the front. Safe while music plays:
            // a PLAYING session still outranks our PAUSED one, and we
            // ignore taps while AudioManager.isMusicActive anyway.
            mediaSession?.let { session ->
                session.isActive = false
                session.setPlaybackState(
                    PlaybackState.Builder()
                        .setState(PlaybackState.STATE_PLAYING, 0L, 1.0f)
                        .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                        .build(),
                )
                session.isActive = true
                Log.d(TAG, "MediaSession priority re-asserted")
            }
        }

        return START_STICKY
    }

    private fun handleMediaButton(intent: Intent): Boolean {
        val event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent
            ?: return false
        val code = event.keyCode
        Log.d(
            TAG,
            "media-button event: keyCode=$code action=${event.action} " +
                "repeat=${event.repeatCount}",
        )
        if (code != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE &&
            code != KeyEvent.KEYCODE_MEDIA_PLAY &&
            code != KeyEvent.KEYCODE_MEDIA_PAUSE &&
            code != KeyEvent.KEYCODE_HEADSETHOOK
        ) {
            return false
        }
        // Single tap only: key-up, no repeats.
        if (event.action != KeyEvent.ACTION_UP || event.repeatCount != 0) {
            return true
        }
        // Don't steal the tap while music is actually playing — the
        // player owns the button then.
        val audio = getSystemService(AudioManager::class.java)
        if (audio?.isMusicActive == true) {
            Log.d(TAG, "tap ignored: music is playing")
            return false
        }
        Log.i(TAG, "glasses tap detected; launching ChatGPT voice")
        TapToChatGpt.launch(this)
        return true
    }

    override fun onDestroy() {
        mediaSession?.let {
            it.isActive = false
            it.release()
        }
        mediaSession = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.tap_service_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun buildNotification(): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_glasses)
            .setContentTitle(getString(R.string.tap_service_title))
            .setContentText(getString(R.string.tap_service_text))
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "TapToChatGpt"
        private const val CHANNEL_ID = "tap_to_chatgpt"
        private const val NOTIFICATION_ID = 44
        const val ACTION_STOP = "com.geno.veyra.tap.STOP"

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, TapToChatGptService::class.java),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TapToChatGptService::class.java)
                    .setAction(ACTION_STOP),
            )
        }
    }
}
