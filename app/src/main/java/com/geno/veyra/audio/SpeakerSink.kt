package com.geno.veyra.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays the assistant's PCM (16-bit / 24 kHz / mono).
 *
 * Network chunks arrive in bursts, so they are queued and drained by a
 * dedicated writer thread: AudioTrack always has data to chew on even when
 * the next websocket chunk is late. The track buffer holds ~2 s of audio and
 * playback starts after a short pre-roll, so momentary jitter never reaches
 * the speaker as a glitch. On barge-in, [flush] drops everything queued so
 * the assistant goes silent at once.
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
    private var writerThread: Thread? = null

    /** Chunks waiting to be written. Never blocks the producer. */
    private val queue = LinkedBlockingQueue<Any>()

    /** Set while the freshly opened track is still gathering pre-roll. */
    @Volatile
    private var gatheringPreRoll: Boolean = false

    @Volatile
    private var bytesEnqueued: Long = 0L

    @Volatile
    private var bytesWritten: Long = 0L

    /**
     * Diagnostic: time between consecutive audio chunks arriving from the
     * network. Gaps far larger than the jitter buffer explain underruns;
     * steady arrivals with choppy sound point at the source or Bluetooth.
     */
    @Volatile
    private var lastEnqueueNanos: Long = 0L

    @Volatile
    private var maxGapMs: Long = 0L

    /**
     * When the writer thread last handed audio to AudioTrack. Powers
     * [isPlaying] for half-duplex mic gating.
     */
    @Volatile
    private var lastWriteNanos: Long = 0L

    private val minBuffer = AudioTrack.getMinBufferSize(
        AudioSpec.OUTPUT_SAMPLE_RATE,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    )

    /** ~2 s of audio: absorbs network jitter without audible gaps. */
    private val trackBufferBytes =
        maxOf(minBuffer * 4, AudioSpec.OUTPUT_SAMPLE_RATE * 2 * 2)

    /** Start audible playback once this much is buffered (~0.25 s). */
    private val preRollBytes = AudioSpec.OUTPUT_SAMPLE_RATE * 2 / 4

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
            .setBufferSizeInBytes(trackBufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (preferredOutput != null) {
            built.setPreferredDevice(preferredOutput)
        }
        track = built
        // Drop anything left queued by a previous session so it can't
        // leak into this one (including the writer thread's STOP sentinel).
        queue.clear()
        gatheringPreRoll = true
        lastEnqueueNanos = 0L
        lastWriteNanos = 0L
        maxGapMs = 0L
        startWriter()
        Log.i(
            TAG,
            "speaker opened " +
                "(usage=$usage, " +
                "bufferBytes=$trackBufferBytes, " +
                "preferredOutput=${preferredOutput?.type})"
        )
    }

    /**
     * Enqueues a chunk of assistant audio. Returns immediately; the writer
     * thread feeds it to AudioTrack.
     */
    fun write(pcm: ByteArray) {
        if (track == null) return
        val now = System.nanoTime()
        val last = lastEnqueueNanos
        lastEnqueueNanos = now
        if (last != 0L) {
            val gapMs = (now - last) / 1_000_000
            if (gapMs > maxGapMs) maxGapMs = gapMs
            if (gapMs > GAP_LOG_THRESHOLD_MS) {
                Log.i(
                    TAG,
                    "audio chunk gap ${gapMs}ms " +
                        "(chunkBytes=${pcm.size}, " +
                        "queueDepth=${queue.size})"
                )
            }
        }
        bytesEnqueued += pcm.size
        queue.offer(pcm)
    }

    /** Barge-in: drop everything queued so the assistant goes silent at once. */
    fun flush() {
        val t = track ?: return
        queue.clear()
        Log.i(TAG, "flush (underruns so far: ${t.underrunCount})")
        t.pause()
        t.flush()
        t.play()
    }

    fun close() {
        writerThread?.let {
            queue.offer(STOP)
            it.join(2000)
        }
        writerThread = null
        track?.let {
            Log.i(
                TAG,
                "speaker closed " +
                    "(bytesEnqueued=$bytesEnqueued, " +
                    "bytesWritten=$bytesWritten, " +
                    "underruns=${it.underrunCount}, " +
                    "maxChunkGapMs=$maxGapMs)"
            )
            runCatching { it.pause(); it.flush(); it.stop() }
            it.release()
        }
        track = null
        gatheringPreRoll = false
        bytesEnqueued = 0L
        bytesWritten = 0L
        lastWriteNanos = 0L
    }

    /**
     * Whether assistant audio is currently playing (or still draining
     * out of the track buffer). Used for half-duplex mic gating when
     * barge-in is off: while this is true the mic must not reach the
     * server, or phone-speaker echo gets transcribed as user speech
     * and the assistant ends up talking to itself.
     */
    fun isPlaying(): Boolean {
        if (queue.isNotEmpty()) return true
        val last = lastWriteNanos
        return last != 0L &&
            System.nanoTime() - last < PLAYING_TAIL_NANOS
    }

    private fun startWriter() {
        if (writerThread?.isAlive == true) return
        val thread = Thread(
            {
                try {
                    while (true) {
                        val item = queue.take()
                        if (item === STOP) break
                        val t = track
                        if (t == null || item !is ByteArray) continue
                        t.write(
                            item,
                            0,
                            item.size,
                            AudioTrack.WRITE_BLOCKING,
                        )
                        bytesWritten += item.size
                        lastWriteNanos = System.nanoTime()
                        if (gatheringPreRoll &&
                            bytesWritten >= preRollBytes
                        ) {
                            gatheringPreRoll = false
                            t.play()
                        }
                    }
                } catch (_: InterruptedException) {
                    // shutting down
                }
            },
            "SpeakerSink-writer",
        )
        writerThread = thread
        thread.start()
    }

    private companion object {
        const val TAG = "SpeakerSink"

        /** Only gaps worth investigating get their own log line. */
        const val GAP_LOG_THRESHOLD_MS = 500L

        /**
         * How long after the last write the speaker still counts as
         * playing: covers audio draining out of AudioTrack's buffer
         * after the queue empties.
         */
        const val PLAYING_TAIL_NANOS = 1_000_000_000L

        /** Sentinel telling the writer thread to exit. */
        val STOP = Any()
    }
}
