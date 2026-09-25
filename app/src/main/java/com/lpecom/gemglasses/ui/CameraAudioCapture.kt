package com.lpecom.gemglasses.ui

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import java.nio.ByteBuffer

/**
 * Captures microphone audio and encodes it to AAC so the camera-test video
 * recording has sound, not just pictures.
 *
 * Runs on its own thread: PCM chunks go from [AudioRecord] into an AAC
 * [MediaCodec] encoder, and the encoded samples are delivered through
 * [Listener]. Presentation timestamps are derived from the captured sample
 * count, so the audio track starts at ~0 and stays monotonic — matching how
 * [CameraVideoRecorder] normalizes the video timestamps.
 *
 * All listener callbacks fire on the capture thread. Buffers handed to
 * [Listener.onAudioSample] are freshly allocated copies and are safe to hold.
 */
class CameraAudioCapture {

    interface Listener {
        fun onAudioFormat(format: MediaFormat)
        fun onAudioSample(data: ByteBuffer, info: MediaCodec.BufferInfo)
        fun onAudioError(e: Exception)
    }

    @Volatile
    private var running = false

    private var worker: Thread? = null
    private var listener: Listener? = null
    private var audioRecord: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private var totalInputSamples = 0L
    private var formatDelivered = false

    /**
     * Starts capture. Throws if the microphone or the AAC encoder is
     * unavailable — the caller decides whether to fall back to video-only.
     */
    fun start(listener: Listener) {
        check(worker == null) { "CameraAudioCapture already started" }

        this.listener = listener
        totalInputSamples = 0L
        formatDelivered = false

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer > 0) { "Microphone does not support $SAMPLE_RATE Hz mono PCM" }
        val bufferSize = minBuffer * 4

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) {
                "AudioRecord failed to initialize"
            }

            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                SAMPLE_RATE,
                CHANNEL_COUNT,
            ).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
            }

            val codec = try {
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                    .also { it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
            } catch (e: Exception) {
                try {
                    record.release()
                } catch (_: Exception) {
                }
                throw e
            }

            running = true
            record.startRecording()
            codec.start()

            audioRecord = record
            encoder = codec

            worker = Thread(
                { captureLoop(listener, record, codec, bufferSize) },
                "CameraAudioCapture",
            ).also { it.start() }

            Log.i(TAG, "Audio capture started: ${SAMPLE_RATE}Hz mono AAC")
        } catch (e: Exception) {
            try {
                record.release()
            } catch (_: Exception) {
            }
            audioRecord = null
            this.listener = null
            throw e
        }
    }

    /**
     * Stops capture, drains the encoder so the tail of the audio is not lost,
     * and releases everything. Safe to call more than once.
     */
    fun stop() {
        val codec = encoder
        val record = audioRecord
        val thread = worker
        val activeListener = listener
        if (thread == null && codec == null && record == null) {
            return
        }

        running = false

        try {
            record?.stop()
        } catch (_: Exception) {
        }
        try {
            thread?.join(2000L)
        } catch (_: Exception) {
        }

        // Push end-of-stream through the encoder and deliver the last samples.
        try {
            if (codec != null && activeListener != null) {
                val inputIndex = codec.dequeueInputBuffer(10_000L)
                if (inputIndex >= 0) {
                    codec.queueInputBuffer(
                        inputIndex, 0, 0, 0L,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                }
                drain(codec, activeListener, endOfStream = true)
            }
        } catch (_: Exception) {
        }

        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        try {
            record?.release()
        } catch (_: Exception) {
        }

        encoder = null
        audioRecord = null
        worker = null
        listener = null

        Log.i(TAG, "Audio capture stopped: totalSamples=$totalInputSamples")
    }

    private fun captureLoop(
        listener: Listener,
        record: AudioRecord,
        codec: MediaCodec,
        bufferSize: Int,
    ) {
        val pcm = ByteArray(bufferSize / 2)
        try {
            while (running) {
                val read = record.read(pcm, 0, pcm.size)
                when {
                    read < 0 -> {
                        listener.onAudioError(
                            IllegalStateException("AudioRecord.read error=$read"),
                        )
                        return
                    }
                    read == 0 -> continue
                }

                var offset = 0
                while (offset < read && running) {
                    val inputIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputIndex < 0) {
                        continue
                    }
                    val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                    inputBuffer.clear()
                    val chunk = minOf(read - offset, inputBuffer.remaining())
                    // Timestamp from the sample count keeps the track monotonic.
                    val chunkSamples = offset / BYTES_PER_SAMPLE
                    val ptsUs = (totalInputSamples + chunkSamples) * 1_000_000L / SAMPLE_RATE
                    inputBuffer.put(pcm, offset, chunk)
                    codec.queueInputBuffer(inputIndex, 0, chunk, ptsUs, 0)
                    offset += chunk
                }
                totalInputSamples += read / BYTES_PER_SAMPLE

                drain(codec, listener, endOfStream = false)
            }
        } catch (e: Exception) {
            if (running) {
                try {
                    listener.onAudioError(e)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun drain(
        codec: MediaCodec,
        listener: Listener,
        endOfStream: Boolean,
    ) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex =
                codec.dequeueOutputBuffer(info, if (endOfStream) 10_000L else 0L)
            when {
                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && info.size > 0) {
                        // Copy: the codec reuses this buffer on the next call.
                        val copy = ByteBuffer.allocate(info.size)
                        val view = outputBuffer.duplicate()
                        view.position(info.offset)
                        view.limit(info.offset + info.size)
                        copy.put(view)
                        copy.flip()
                        val infoCopy = MediaCodec.BufferInfo().apply {
                            set(0, info.size, info.presentationTimeUs, info.flags)
                        }
                        listener.onAudioSample(copy, infoCopy)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return
                    }
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!formatDelivered) {
                        formatDelivered = true
                        listener.onAudioFormat(codec.outputFormat)
                    }
                }
                else -> return
            }
        }
    }

    private companion object {
        const val TAG = "CameraAudioCapture"
        const val SAMPLE_RATE = 44_100
        const val CHANNEL_COUNT = 1
        const val BIT_RATE = 128_000
        const val BYTES_PER_SAMPLE = 2
    }
}
