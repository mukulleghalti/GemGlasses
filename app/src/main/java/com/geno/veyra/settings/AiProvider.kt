package com.geno.veyra.settings

/**
 * Which AI backend powers the assistant.
 *
 * GEMINI is the original live-voice backend (Gemini Live API).
 * OPENAI selects ChatGPT; the on-device OpenAI key is managed by
 * [OpenAiKeyRepository] and the voice models are listed in
 * `com.geno.veyra.openai.OpenAiModels`. The OpenAI live-voice client
 * itself is the next build — until it lands, starting a session with
 * OPENAI selected surfaces a "coming soon" notice instead of a
 * half-working session.
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
