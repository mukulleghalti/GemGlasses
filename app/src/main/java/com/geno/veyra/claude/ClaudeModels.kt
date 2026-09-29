package com.geno.veyra.claude

/**
 * Curated list of Claude models offered in the model picker when the
 * Claude provider is selected.
 *
 * Claude is a cascade provider in Veyra (on-device speech-to-text,
 * Messages API, on-device text-to-speech), so latency dominates the
 * voice experience: Haiku 5.5 is the default for its speed, Sonnet
 * 5.5 and Opus 5.5 trade speed for capability. All three accept
 * image input and tool use.
 */
data class ClaudeModel(
    val id: String,
    /** Product name — intentionally not translated. */
    val label: String,
)

const val DEFAULT_CLAUDE_MODEL = "claude-haiku-5-5"

val CLAUDE_MODELS = listOf(
    ClaudeModel(
        id = DEFAULT_CLAUDE_MODEL,
        label = "Claude Haiku 5.5",
    ),
    ClaudeModel(
        id = "claude-sonnet-5-5",
        label = "Claude Sonnet 5.5",
    ),
    ClaudeModel(
        id = "claude-opus-5-5",
        label = "Claude Opus 5.5",
    ),
)
