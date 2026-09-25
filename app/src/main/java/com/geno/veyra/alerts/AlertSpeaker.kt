package com.geno.veyra.alerts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speaks timer/reminder alerts through Android TTS, which follows the
 * current audio route — the glasses when they are the connected BT audio
 * device, the phone speaker otherwise.
 *
 * A fresh TTS engine is created per alert; alerts are rare enough that no
 * persistent engine is worth keeping.
 */
@Singleton
class AlertSpeaker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Speaks [text], then calls [onDone] exactly once — on completion,
     * on error, or after [TIMEOUT_MS] as a safety net.
     */
    fun speak(text: String, onDone: () -> Unit) {
        val done = AtomicBoolean(false)
        val finishOnce = {
            if (done.compareAndSet(false, true)) {
                onDone()
            }
        }

        // Safety net: never leave the caller hanging on TTS.
        Handler(Looper.getMainLooper()).postDelayed(
            { finishOnce() },
            TIMEOUT_MS,
        )

        var tts: TextToSpeech? = null
        tts = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TTS init failed: $status")
                finishOnce()
                return@TextToSpeech
            }

            val engine = tts ?: run {
                finishOnce()
                return@TextToSpeech
            }

            val langResult = engine.setLanguage(Locale.getDefault())
            if (
                langResult == TextToSpeech.LANG_MISSING_DATA ||
                langResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                engine.setLanguage(Locale.ENGLISH)
            }

            engine.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        engine.shutdown()
                        finishOnce()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        engine.shutdown()
                        finishOnce()
                    }

                    override fun onError(
                        utteranceId: String?,
                        errorCode: Int,
                    ) {
                        engine.shutdown()
                        finishOnce()
                    }
                },
            )

            val result = engine.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                UTTERANCE_ID,
            )
            if (result != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TTS speak failed: $result")
                engine.shutdown()
                finishOnce()
            }
        }
    }

    private companion object {
        const val TAG = "AlertSpeaker"
        const val UTTERANCE_ID = "veyra_alert"
        const val TIMEOUT_MS = 20_000L
    }
}
