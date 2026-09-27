package com.geno.veyra.openai.realtime

/**
 * Sample-rate conversion for microphone audio.
 *
 * Veyra captures mic PCM at 16 kHz mono ([com.geno.veyra.audio.AudioSpec])
 * for Gemini Live, but the OpenAI Realtime API only accepts 24 kHz PCM16
 * input — so every mic chunk is upsampled here before
 * `input_audio_buffer.append`.
 */
object AudioResampler {

    /**
     * Linear-interpolation upsample of 16-bit little-endian mono PCM
     * from 16 kHz to 24 kHz. Output length is `inputSamples * 3 / 2`
     * samples.
     */
    fun upsample16kTo24k(input: ByteArray): ByteArray {
        val inSamples = input.size / 2
        if (inSamples < 2) return input

        val outSamples = inSamples * 3 / 2
        val out = ByteArray(outSamples * 2)

        var outPos = 0
        for (i in 0 until outSamples) {
            val pos = i * 2.0 / 3.0
            val i0 = pos.toInt().coerceAtMost(inSamples - 2)
            val frac = pos - i0
            val s0 = getSample(input, i0)
            val s1 = getSample(input, i0 + 1)
            val s = (s0 * (1 - frac) + s1 * frac)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[outPos++] = (s and 0xFF).toByte()
            out[outPos++] = ((s shr 8) and 0xFF).toByte()
        }

        return out
    }

    private fun getSample(buf: ByteArray, index: Int): Int {
        val v = ((buf[index * 2 + 1].toInt() and 0xFF) shl 8) or
            (buf[index * 2].toInt() and 0xFF)
        return if (v >= 0x8000) v - 0x10000 else v
    }
}
