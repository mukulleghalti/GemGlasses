package com.geno.veyra.claude.realtime

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal WAV handling for Android TextToSpeech output:
 * [decode] extracts the PCM16 samples and sample rate from a
 * canonical WAV file, [resampleTo24k] converts to the 24 kHz the
 * speaker pipeline expects, and [chunk] splits audio into
 * playback-sized pieces.
 */
object WavPcm {

    /** (PCM16 little-endian bytes, sample rate in Hz). */
    fun decode(wav: ByteArray): Pair<ByteArray, Int> {
        require(wav.size > 44) { "WAV too small" }
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        val sampleRate = buffer.getInt(24)

        // Walk the chunks to find "data" — synthesizeToFile emits a
        // canonical 44-byte header, but fmt chunks can legally vary.
        var offset = 12
        while (offset + 8 <= wav.size) {
            val chunkId = String(wav, offset, 4, Charsets.US_ASCII)
            val chunkSize = buffer.getInt(offset + 4)
            if (chunkId == "data") {
                val start = offset + 8
                val end = minOf(start + chunkSize, wav.size)
                return wav.copyOfRange(start, end) to sampleRate
            }
            offset += 8 + chunkSize + (chunkSize % 2)
        }
        throw IllegalArgumentException("WAV has no data chunk")
    }

    /**
     * Linear-interpolation resample of PCM16 mono to 24 kHz. Returns
     * the input untouched when it already runs at 24 kHz.
     */
    fun resampleTo24k(pcm: ByteArray, fromRate: Int): ByteArray {
        if (fromRate == TARGET_RATE || pcm.size < 2) return pcm

        val inSamples = pcm.size / 2
        val outSamples =
            (inSamples.toLong() * TARGET_RATE / fromRate).toInt()
        val input = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val output = ByteBuffer
            .allocate(outSamples * 2)
            .order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until outSamples) {
            val position = i.toDouble() * fromRate / TARGET_RATE
            val index = position.toInt().coerceAtMost(inSamples - 1)
            val fraction = position - index
            val current = input.getShort(index * 2).toDouble()
            val next = input
                .getShort(minOf(index + 1, inSamples - 1) * 2)
                .toDouble()
            val sample = current + (next - current) * fraction
            output.putShort(
                sample.toInt().coerceIn(-32768, 32767).toShort()
            )
        }

        return output.array()
    }

    /** Split PCM into chunks of [chunkBytes] (100 ms at 24 kHz). */
    fun chunk(
        pcm: ByteArray,
        chunkBytes: Int = 4_800,
    ): List<ByteArray> {
        if (pcm.isEmpty()) return emptyList()
        val result = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(offset + chunkBytes, pcm.size)
            result += pcm.copyOfRange(offset, end)
            offset = end
        }
        return result
    }

    private const val TARGET_RATE = 24_000
}
