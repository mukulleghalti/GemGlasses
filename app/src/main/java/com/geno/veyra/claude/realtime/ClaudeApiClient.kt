package com.geno.veyra.claude.realtime

import com.geno.veyra.claude.ClaudeConnectionCheck
import com.geno.veyra.claude.ClaudeTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Streaming client for the Anthropic Messages API.
 *
 * One call = one assistant turn: the request carries the full
 * conversation history (the cascade keeper owns it), the response is
 * consumed as server-sent events, text deltas are forwarded live via
 * [onTextDelta], and tool-use blocks are assembled from
 * `input_json_delta` fragments.
 */
class ClaudeApiClient(
    private val client: OkHttpClient,
    private val json: Json,
) {

    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonObject,
    )

    data class Result(
        val text: String,
        val toolUses: List<ToolUse>,
        /** Raw assistant content blocks, stored verbatim in history. */
        val contentBlocks: JsonArray,
    )

    @Volatile
    private var call: Call? = null

    /** Cancels the in-flight request, if any. */
    fun cancel() {
        call?.cancel()
        call = null
    }

    suspend fun streamMessage(
        apiKey: String,
        model: String,
        system: String,
        messages: List<JsonObject>,
        tools: List<ClaudeTool>,
        onTextDelta: suspend (String) -> Unit,
    ): Result = withContext(Dispatchers.IO) {

        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", MAX_TOKENS)
            put("system", system)
            put("stream", true)
            put(
                "messages",
                JsonArray(messages),
            )
            if (tools.isNotEmpty()) {
                put(
                    "tools",
                    json.encodeToJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(
                            ClaudeTool.serializer()
                        ),
                        tools,
                    ),
                )
            }
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header(
                "anthropic-version",
                ClaudeConnectionCheck.ANTHROPIC_VERSION,
            )
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val newCall = client.newCall(request)
        call = newCall

        try {
            newCall.execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody =
                        response.body?.string().orEmpty()
                    throw IOException(
                        apiErrorMessage(errorBody)
                            ?: "Anthropic returned HTTP ${response.code}"
                    )
                }

                val source = response.body?.source()
                    ?: throw IOException("Anthropic returned no body")

                val text = StringBuilder()
                val toolBuffers = mutableMapOf<Int, ToolBuffer>()
                val blockKinds = mutableMapOf<Int, String>()

                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") {
                        continue
                    }

                    val event = runCatching {
                        json.parseToJsonElement(payload).jsonObject
                    }.getOrNull() ?: continue

                    when (event.string("type")) {
                        "content_block_start" -> {
                            val index = event.int("index") ?: 0
                            val block = event["content_block"]
                                ?.jsonObject
                            val kind = block?.string("type")
                                ?: "text"
                            blockKinds[index] = kind
                            if (kind == "tool_use") {
                                toolBuffers[index] = ToolBuffer(
                                    id = block?.string("id")
                                        .orEmpty(),
                                    name = block?.string("name")
                                        .orEmpty(),
                                )
                            }
                        }

                        "content_block_delta" -> {
                            val index = event.int("index") ?: 0
                            val delta = event["delta"]?.jsonObject
                            when (delta?.string("type")) {
                                "text_delta" -> {
                                    val piece =
                                        delta.string("text")
                                            .orEmpty()
                                    if (piece.isNotEmpty()) {
                                        text.append(piece)
                                        onTextDelta(piece)
                                    }
                                }

                                "input_json_delta" -> {
                                    toolBuffers[index]
                                        ?.json
                                        ?.append(
                                            delta.string(
                                                "partial_json"
                                            ).orEmpty()
                                        )
                                }
                            }
                        }

                        "error" -> {
                            val message = event["error"]
                                ?.jsonObject
                                ?.string("message")
                            throw IOException(
                                message
                                    ?: "Anthropic stream error"
                            )
                        }

                        else -> Unit
                    }
                }

                val toolUses = toolBuffers.values.map { buffer ->
                    ToolUse(
                        id = buffer.id,
                        name = buffer.name,
                        input = runCatching {
                            json.parseToJsonElement(
                                buffer.json.toString()
                                    .ifBlank { "{}" }
                            ).jsonObject
                        }.getOrElse { JsonObject(emptyMap()) },
                    )
                }

                val contentBlocks = buildJsonArray {
                    if (text.isNotEmpty()) {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", text.toString())
                            }
                        )
                    }
                    toolUses.forEach { tool ->
                        add(
                            buildJsonObject {
                                put("type", "tool_use")
                                put("id", tool.id)
                                put("name", tool.name)
                                put("input", tool.input)
                            }
                        )
                    }
                }

                Result(
                    text = text.toString(),
                    toolUses = toolUses,
                    contentBlocks = contentBlocks,
                )
            }
        } finally {
            if (call === newCall) {
                call = null
            }
        }
    }

    private class ToolBuffer(
        val id: String,
        val name: String,
    ) {
        val json = StringBuilder()
    }

    private fun apiErrorMessage(body: String): String? =
        runCatching {
            json.parseToJsonElement(body)
                .jsonObject["error"]
                ?.jsonObject
                ?.string("message")
        }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    private companion object {
        const val MAX_TOKENS = 2048
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
