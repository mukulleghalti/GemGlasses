package com.lpecom.gemglasses.audio

/** Audio constants shared by capture and playback. Gemini Live is fixed at
 *  16 kHz mono in, 24 kHz mono out (both PCM 16-bit LE). */
object AudioSpec {
    const val INPUT_SAMPLE_RATE = 16_000
    const val OUTPUT_SAMPLE_RATE = 24_000
    const val CHANNELS = 1
    const val BYTES_PER_SAMPLE = 2

    /** ~20 ms of input audio per chunk keeps latency low without flooding. */
    const val INPUT_CHUNK_BYTES = INPUT_SAMPLE_RATE / 50 * BYTES_PER_SAMPLE
}
