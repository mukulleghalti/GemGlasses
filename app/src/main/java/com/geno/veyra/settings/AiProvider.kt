package com.geno.veyra.settings

/**
 * Which AI backend powers the assistant.
 *
 * GEMINI is the original live-voice backend (Gemini Live API).
 * OPENAI selects ChatGPT voice via the OpenAI Realtime API; the
 * on-device OpenAI key is managed by [OpenAiKeyRepository], the voice
 * models are listed in `com.geno.veyra.openai.OpenAiModels`, and the
 * live-voice client lives in `com.geno.veyra.openai.realtime`.
 */
enum class AiProvider(val id: String) {
    GEMINI("gemini"),
    OPENAI("openai"),
    ;

    companion object {
        fun fromId(id: String?): AiProvider =
            entries.firstOrNull { it.id == id } ?: GEMINI
    }
}
