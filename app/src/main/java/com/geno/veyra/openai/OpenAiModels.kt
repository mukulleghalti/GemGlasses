package com.geno.veyra.openai

/**
 * Curated list of OpenAI voice models offered in the model picker when
 * the ChatGPT provider is selected.
 *
 * These are the Realtime API models — OpenAI's counterpart to Gemini
 * Live — consumed by `com.geno.veyra.openai.realtime.RealtimeSession`.
 *
 * The 1.x generation is intentionally absent: `gpt-4o-realtime-preview`
 * was shut down in May 2026 and `gpt-realtime` is deprecated with
 * shutdown on 2027-01-20. The 2.1 generation below is the current one.
 */
data class OpenAiVoiceModel(
    val id: String,
    /** Product name — intentionally not translated. */
    val label: String,
)

const val DEFAULT_OPENAI_VOICE_MODEL = "gpt-realtime-2.1"

val OPENAI_VOICE_MODELS = listOf(
    OpenAiVoiceModel(
        id = DEFAULT_OPENAI_VOICE_MODEL,
        label = "GPT Realtime 2.1",
    ),
    OpenAiVoiceModel(
        id = "gpt-realtime-2.1-mini",
        label = "GPT Realtime 2.1 Mini",
    ),
)
