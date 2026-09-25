package com.lpecom.gemglasses.tools

import com.lpecom.gemglasses.location.LocationProvider
import com.lpecom.gemglasses.gemini.protocol.FunctionDeclaration
import com.lpecom.gemglasses.settings.GeminiKeyRepository
import com.lpecom.gemglasses.state.CitedPlace
import com.lpecom.gemglasses.state.ConversationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

/**
 * `search_places` — grounded place search.
 *
 * The Gemini `generateContent` + Maps grounding call (previously proxied
 * through the Cloudflare backend) now runs on the device, using the
 * user's own API key in the `x-goog-api-key` header. The result is split
 * into a short spoken summary (returned to the model) and the list of
 * cited places (pushed to the transcript for the ToS-required source UI).
 */
class PlacesTool @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val location: LocationProvider,
    private val conversation: ConversationStore,
    private val keyRepository: GeminiKeyRepository,
) : AgentTool {

    override val name = "search_places"

    override val declaration = FunctionDeclaration(
        name = name,
        description = "Searches for real nearby places (restaurants, pharmacies, etc.) " +
            "using Google Maps data. Always use this tool instead of " +
            "inventing establishment names.",
        parameters = schema(
            """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "What to look for, e.g.: 'cafe open now', 'nearest pharmacy'."
                }
              },
              "required": ["query"]
            }
            """
        ),
    )

    override suspend fun execute(args: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val apiKey = keyRepository.getKey()
        if (apiKey == null) {
            return@withContext buildJsonObject {
                put("status", "error")
                put("summary", "Place search needs a Gemini API key — add one in Settings.")
            }
        }

        val query = args.requireString("query")
        val loc = location.current()

        val payload = buildJsonObject {
            put(
                "contents",
                buildJsonArray {
                    addJsonObject {
                        put("role", "user")
                        put(
                            "parts",
                            buildJsonArray {
                                addJsonObject { put("text", query) }
                            },
                        )
                    }
                },
            )
            put(
                "tools",
                buildJsonArray {
                    addJsonObject { put("googleMaps", buildJsonObject {}) }
                },
            )
            put(
                "systemInstruction",
                buildJsonObject {
                    put(
                        "parts",
                        buildJsonArray {
                            addJsonObject { put("text", SYSTEM_INSTRUCTION) }
                        },
                    )
                },
            )
            if (loc != null) {
                put(
                    "toolConfig",
                    buildJsonObject {
                        put(
                            "retrievalConfig",
                            buildJsonObject {
                                put(
                                    "latLng",
                                    buildJsonObject {
                                        put("latitude", loc.latitude)
                                        put("longitude", loc.longitude)
                                    },
                                )
                            },
                        )
                    },
                )
            }
        }

        val request = Request.Builder()
            .url("$GEMINI_API/models/$GROUNDING_MODEL:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                check(response.isSuccessful) {
                    "places lookup failed (${response.code})"
                }
                parseAndPublish(body)
            }
        }.getOrElse {
            buildJsonObject {
                put("status", "error")
                put("summary", "Couldn't look up places right now.")
            }
        }
    }

    private fun parseAndPublish(body: String): JsonObject {
        val root = json.parseToJsonElement(body).jsonObject
        val candidate = root["candidates"]
            ?.jsonArray?.firstOrNull()?.jsonObject

        val summary = candidate?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
            .joinToString(" ")
            .trim()
            .ifEmpty { FALLBACK_SUMMARY }

        val places = candidate?.get("groundingMetadata")?.jsonObject
            ?.get("groundingChunks")?.jsonArray.orEmpty()
            .mapNotNull { el ->
                val chunk = el.jsonObject["maps"]?.jsonObject
                    ?: el.jsonObject["web"]?.jsonObject
                val uri = chunk?.get("uri")?.jsonPrimitive?.content
                    ?: return@mapNotNull null
                val title = chunk["title"]?.jsonPrimitive?.content ?: uri
                CitedPlace(title, uri)
            }
        conversation.addPlaces(places)

        return buildJsonObject {
            put("status", "ok")
            put("summary", summary)
            put("total", places.size)
        }
    }

    private fun kotlinx.serialization.json.JsonArray?.orEmpty() = this ?: kotlinx.serialization.json.JsonArray(emptyList())

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
        const val GEMINI_API = "https://generativelanguage.googleapis.com/v1beta"
        const val GROUNDING_MODEL = "gemini-2.5-flash"

        // Kept verbatim from the old backend so place-search behavior is unchanged.
        const val SYSTEM_INSTRUCTION =
            "Responda em português, curto, listando 2-4 lugares reais e próximos. " +
                "Não invente estabelecimentos."
        const val FALLBACK_SUMMARY = "Não encontrei lugares."
    }
}
