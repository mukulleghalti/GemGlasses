package com.geno.veyra.openai

/**
 * Curated list of OpenAI voice models offered in the model picker when
 * the ChatGPT provider is selected.
 *
 * These are the Realtime API models — OpenAI's counterpart to Gemini
 * Live — consumed by `com.geno.veyra.openai.realtime.RealtimeSession`.
 */
data class OpenAiVoiceModel(
    val id: String,
    /** Product name — intentionally not translated. */
    val label: String,
)

const val DEFAULT_OPENAI_VOICE_MODEL = "gpt-realtime"

val OPENAI_VOICE_MODELS = listOf(
    OpenAiVoiceModel(
        id = DEFAULT_OPENAI_VOICE_MODEL,
        label = "GPT Realtime",
    ),
    OpenAiVoiceModel(
        id = "gpt-4o-realtime-preview",
        label = "GPT-4o Realtime",
    ),
)
