package com.geno.veyra.openai.realtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioResamplerTest {

    private fun shortsToBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            out[i * 2] = (s.toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val out = ShortArray(bytes.size / 2)
        for (i in out.indices) {
            val v = ((bytes[i * 2 + 1].toInt() and 0xFF) shl 8) or
                (bytes[i * 2].toInt() and 0xFF)
            out[i] = (if (v >= 0x8000) v - 0x10000 else v).toShort()
        }
        return out
    }

    @Test
    fun `320 samples in produce 480 samples out`() {
        val input = shortsToBytes(ShortArray(320))
        val output = AudioResampler.upsample16kTo24k(input)
        assertEquals(960, output.size)
    }

    @Test
    fun `silence stays silent`() {
        val input = ByteArray(640)
        val output = AudioResampler.upsample16kTo24k(input)
        assertArrayEquals(ByteArray(960), output)
    }

    @Test
    fun `constant signal stays constant`() {
        val input = shortsToBytes(ShortArray(320) { 1000 })
        val output = bytesToShorts(AudioResampler.upsample16kTo24k(input))
        assertEquals(480, output.size)
        output.forEach { assertEquals(1000, it.toInt()) }
    }

    @Test
    fun `linear ramp interpolates without overshoot`() {
        val input = shortsToBytes(ShortArray(320) { i -> (i * 100).toShort() })
        val output = bytesToShorts(AudioResampler.upsample16kTo24k(input))
        assertEquals(480, output.size)
        // Monotonic and bounded by the input range.
        for (i in 1 until output.size) {
            assertTrue(output[i] >= output[i - 1])
        }
        assertTrue(output.first() >= 0)
        assertTrue(output.last() <= 31900)
    }

    @Test
    fun `degenerate input passes through`() {
        assertArrayEquals(ByteArray(0), AudioResampler.upsample16kTo24k(ByteArray(0)))
        val one = byteArrayOf(1, 2)
        assertArrayEquals(one, AudioResampler.upsample16kTo24k(one))
    }
}
