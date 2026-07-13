package com.lpecom.gemglasses.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * Captures microphone audio as PCM 16-bit / 16 kHz / mono and emits it in
 * ~20 ms chunks. Capture uses [MediaRecorder.AudioSource.VOICE_COMMUNICATION]
 * so the platform applies echo cancellation against the assistant's playback.
 */
@Singleton
class MicStreamer @Inject constructor() {

    private val bufferSize = maxOf(
        AudioRecord.getMinBufferSize(
            AudioSpec.INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ),
        AudioSpec.INPUT_CHUNK_BYTES * 4,
    )

    /** Cold flow of mic chunks. Recording starts on collect, stops on cancel. */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun stream(): Flow<ByteArray> = callbackFlow {
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            AudioSpec.INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }

        val running = AtomicBoolean(true)
        record.startRecording()

        val worker = thread(name = "mic-streamer") {
            val buf = ByteArray(AudioSpec.INPUT_CHUNK_BYTES)
            while (running.get()) {
                val read = record.read(buf, 0, buf.size)
                if (read > 0) {
                    trySend(if (read == buf.size) buf.copyOf() else buf.copyOf(read))
                }
            }
        }

        awaitClose {
            running.set(false)
            worker.join(500)
            runCatching { record.stop() }
            record.release()
            Log.i(TAG, "mic stream stopped")
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val TAG = "MicStreamer"
    }
}
