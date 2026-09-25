package com.lpecom.gemglasses.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays the assistant's PCM (16-bit / 24 kHz / mono) through the glasses
 * speaker. The track is opened once and kept alive across session reconnects so
 * the transition is inaudible. On barge-in, [flush] clears queued audio
 * immediately so the assistant stops talking over the user.
 *
 * @param usage [AudioAttributes.USAGE_VOICE_COMMUNICATION] for the voice-call
 *   channel (SCO) or [AudioAttributes.USAGE_MEDIA] for the high-quality music
 *   channel (A2DP).
 * @param preferredOutput optional output device to pin the track to (used by
 *   the phone-speaker diagnostic so media streams don't follow A2DP anyway).
 */
@Singleton
class SpeakerSink @Inject constructor() {

    private var track: AudioTrack? = null

    private var bytesWritten: Long = 0L

    private val minBuffer = AudioTrack.getMinBufferSize(
        AudioSpec.OUTPUT_SAMPLE_RATE,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    )

    fun open(
        usage: Int = AudioAttributes.USAGE_VOICE_COMMUNICATION,
        preferredOutput: AudioDeviceInfo? = null,
    ) {
        if (track != null) return
        val built = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(AudioSpec.OUTPUT_SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, AudioSpec.OUTPUT_SAMPLE_RATE))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (preferredOutput != null) {
            built.setPreferredDevice(preferredOutput)
        }
        built.play()
        track = built
        Log.i(
            TAG,
            "speaker opened " +
                "(usage=$usage, " +
                "preferredOutput=${preferredOutput?.type})"
        )
    }

    /** Queues a chunk of assistant audio for playback. */
    fun write(pcm: ByteArray) {
        val t = track ?: return
        t.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
        bytesWritten += pcm.size
    }

    /** Barge-in: drop everything queued so the assistant goes silent at once. */
    fun flush() {
        val t = track ?: return
        Log.i(TAG, "flush (underruns so far: ${t.underrunCount})")
        t.pause()
        t.flush()
        t.play()
    }

    fun close() {
        track?.let {
            Log.i(
                TAG,
                "speaker closed " +
                    "(bytesWritten=$bytesWritten, " +
                    "underruns=${it.underrunCount})"
            )
            runCatching { it.pause(); it.flush(); it.stop() }
            it.release()
        }
        track = null
        bytesWritten = 0L
    }

    private companion object {
        const val TAG = "SpeakerSink"
    }
}
