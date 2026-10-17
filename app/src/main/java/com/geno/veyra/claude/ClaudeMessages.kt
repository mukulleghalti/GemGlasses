package com.geno.veyra.claude

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A tool as the Anthropic Messages API expects it in the `tools`
 * array. Veyra's tool declarations are authored once (for Gemini)
 * and mapped to this shape by
 * [com.geno.veyra.gemini.ToolRegistry.asClaudeTools]; the
 * `input_schema` JSON Schema object passes through verbatim.
 */
@Serializable
data class ClaudeTool(
    val name: String,
    val description: String,
    val input_schema: JsonElement? = null,
)
