package com.lpecom.gemglasses.tools

import com.lpecom.gemglasses.BuildConfig
import com.lpecom.gemglasses.location.LocationProvider
import com.lpecom.gemglasses.gemini.protocol.FunctionDeclaration
import com.lpecom.gemglasses.state.CitedPlace
import com.lpecom.gemglasses.state.ConversationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * `search_places` — grounded place search. The heavy lifting (Gemini
 * `generateContent` + Maps grounding, with the API key) happens on the backend
 * Worker; the client just forwards the query and current location, then splits
 * the result into a short spoken summary (returned to the model) and the list
 * of cited places (pushed to the transcript for the ToS-required source UI).
 */
class PlacesTool @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val location: LocationProvider,
    private val conversation: ConversationStore,
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
        val query = args.requireString("query")
        val loc = location.current()

        val payload = buildJsonObject {
            put("query", query)
            if (loc != null) {
                put("latitude", loc.latitude)
                put("longitude", loc.longitude)
            }
        }

        val request = Request.Builder()
            .url("${BuildConfig.BACKEND_URL.trimEnd('/')}/places")
            .header("X-App-Secret", BuildConfig.APP_SECRET)
            .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(JSON_MEDIA))
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                check(response.isSuccessful) { "places endpoint ${response.code}" }
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
        val summary = root["summary"]?.jsonPrimitive?.content ?: "I found a few places."
        val places = root["places"]?.jsonArray.orEmpty().mapNotNull { el ->
            val obj = el.jsonObject
            val title = obj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val uri = obj["uri"]?.jsonPrimitive?.content ?: return@mapNotNull null
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
    }
}
