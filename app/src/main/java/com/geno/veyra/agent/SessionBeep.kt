package com.geno.veyra.agent

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Short start/stop blips played through the active audio route (the
 * glasses' speaker when connected) so the wearer can hear the assistant
 * session begin and end without looking at the phone.
 *
 * Start is a single rising blip; stop is two short falling blips.
 * Everything is best-effort: any audio failure is swallowed, never
 * surfaced to the session.
 */
object SessionBeep {

    private const val TAG = "SessionBeep"

    /** 0-100 volume for the tone generator; actual loudness follows media volume. */
    private const val TONE_VOLUME = 80

    private val handler = Handler(Looper.getMainLooper())

    fun started() {
        play(ToneGenerator.TONE_PROP_BEEP, 150)
    }

    fun stopped() {
        play(ToneGenerator.TONE_PROP_BEEP, 120)
        handler.postDelayed({ play(ToneGenerator.TONE_PROP_BEEP, 120) }, 220)
    }

    private fun play(tone: Int, durationMs: Int) {
        runCatching {
            val generator = ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME)
            generator.startTone(tone, durationMs)
            // startTone plays asynchronously; release once it has finished.
            handler.postDelayed(
                { runCatching { generator.release() } },
                (durationMs + 400).toLong(),
            )
        }.onFailure { e ->
            Log.w(TAG, "Could not play session beep", e)
        }
    }
}
