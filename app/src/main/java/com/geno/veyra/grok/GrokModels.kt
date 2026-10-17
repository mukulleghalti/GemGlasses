package com.geno.veyra.grok

/**
 * Curated list of Grok voice models offered in the model picker when
 * the Grok provider is selected.
 *
 * These are xAI's Speech to Speech (Voice Agent) models, consumed by
 * `com.geno.veyra.grok.realtime.GrokSession`. `grok-voice-latest`
 * currently resolves to Grok Voice 3.0 (October 2026); the pinned
 * 3.0 entry exists so a user can avoid silent alias upgrades.
 */
data class GrokVoiceModel(
    val id: String,
    /** Product name — intentionally not translated. */
    val label: String,
)

const val DEFAULT_GROK_VOICE_MODEL = "grok-voice-latest"

val GROK_VOICE_MODELS = listOf(
    GrokVoiceModel(
        id = DEFAULT_GROK_VOICE_MODEL,
        label = "Grok Voice (Latest)",
    ),
    GrokVoiceModel(
        id = "grok-voice-3.0",
        label = "Grok Voice 3.0",
    ),
)
