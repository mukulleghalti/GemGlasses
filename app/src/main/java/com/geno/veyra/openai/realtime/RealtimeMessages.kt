package com.geno.veyra.openai.realtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A function tool as the OpenAI Realtime API expects it inside
 * `session.update`.
 *
 * Veyra's [com.geno.veyra.tools.AgentTool] declarations are authored
 * once (for Gemini) and mapped to this shape by
 * [com.geno.veyra.gemini.ToolRegistry.asOpenAiTools]; the `parameters`
 * JSON Schema object passes through verbatim.
 */
@Serializable
data class OpenAiFunctionTool(
    // No default value on purpose: the shared Json instance uses
    // encodeDefaults = false, which would silently drop `type` from the
    // session.update payload and OpenAI rejects tools without it.
    val type: String,
    val name: String,
    val description: String,
    val parameters: JsonElement? = null,
)
