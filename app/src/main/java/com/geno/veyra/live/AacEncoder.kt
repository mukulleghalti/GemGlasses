package com.geno.veyra.live

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.nio.ByteBuffer

/**
 * PCM → AAC-LC encoder for the live pipeline. The glasses stream gives
 * 44.1 kHz mono PCM ([com.geno.veyra.glasses.LiveFrame.Audio]); RTMP
 * wants AAC. Output frames (including the codec-config frame) are handed
 * to [onOutput] as fresh buffers with copied [MediaCodec.BufferInfo].
 */
class AacEncoder(
    private val sampleRate: Int = 44_100,
    private val bitRate: Int = 96_000,
    private val onOutput: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
) {

    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()

    fun start() {
        if (codec != null) return
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            1,
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    /** Feeds one PCM chunk; encoded output is delivered via [onOutput]. */
    fun encode(pcm: ByteArray, ptsUs: Long) {
        val codec = codec ?: return
        var offset = 0
        while (offset < pcm.size) {
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex < 0) break
            val input = codec.getInputBuffer(inIndex) ?: break
            val chunk = minOf(input.remaining(), pcm.size - offset)
            input.clear()
            input.put(pcm, offset, chunk)
            // Spread the chunk's pts across its samples so multi-chunk
            // frames keep a sane clock.
            val chunkPts = ptsUs +
                (offset / 2) * 1_000_000L / sampleRate
            codec.queueInputBuffer(inIndex, 0, chunk, chunkPts, 0)
            offset += chunk
        }
        drain()
    }

    private fun drain() {
        val codec = codec ?: return
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER ||
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
            ) {
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) return
                continue
            }
            if (outIndex < 0) return
            val output = codec.getOutputBuffer(outIndex)
            if (output != null && bufferInfo.size > 0) {
                val copy = ByteBuffer.allocate(bufferInfo.size)
                val view = output.duplicate().apply {
                    position(bufferInfo.offset)
                    limit(bufferInfo.offset + bufferInfo.size)
                }
                copy.put(view)
                copy.flip()
                val infoCopy = MediaCodec.BufferInfo().apply {
                    set(
                        0,
                        bufferInfo.size,
                        bufferInfo.presentationTimeUs,
                        bufferInfo.flags,
                    )
                }
                onOutput(copy, infoCopy)
            }
            codec.releaseOutputBuffer(outIndex, false)
        }
    }

    fun release() {
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
    }
}
