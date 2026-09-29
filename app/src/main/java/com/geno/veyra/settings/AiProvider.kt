package com.geno.veyra.settings

/**
 * Which AI backend powers the assistant.
 *
 * GEMINI is the original live-voice backend (Gemini Live API).
 * OPENAI selects ChatGPT voice via the OpenAI Realtime API; the
 * on-device OpenAI key is managed by [OpenAiKeyRepository], the voice
 * models are listed in `com.geno.veyra.openai.OpenAiModels`, and the
 * live-voice client lives in `com.geno.veyra.openai.realtime`.
 *
 * GROK selects Grok voice via xAI's Speech to Speech API — the same
 * realtime protocol shape as OpenAI's; the key is managed by
 * [GrokKeyRepository], the models in `com.geno.veyra.grok.GrokModels`,
 * and the client in `com.geno.veyra.grok.realtime`.
 *
 * CLAUDE selects Claude via a cascade (Anthropic has no realtime
 * voice API): on-device Vosk speech-to-text, the Messages API, and
 * on-device text-to-speech. The key is managed by
 * [ClaudeKeyRepository], the models in
 * `com.geno.veyra.claude.ClaudeModels`, and the keeper in
 * `com.geno.veyra.claude.realtime`.
 */
enum class AiProvider(val id: String) {
    GEMINI("gemini"),
    OPENAI("openai"),
    GROK("grok"),
    CLAUDE("claude"),
    ;

    companion object {
        fun fromId(id: String?): AiProvider =
            entries.firstOrNull { it.id == id } ?: GEMINI
    }
}
