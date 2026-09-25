package com.geno.veyra.tools

import com.geno.veyra.gemini.protocol.FunctionDeclaration
import com.geno.veyra.settings.MemoryRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * `save_memory` — persists something the user asked the assistant to
 * remember ("remember this", "don't forget that ..."). Storage is
 * on-device and survives restarts; the user manages entries in Settings.
 */
class SaveMemoryTool @Inject constructor(
    private val memories: MemoryRepository,
) : AgentTool {

    override val name = "save_memory"

    override val declaration = FunctionDeclaration(
        name = name,
        description = "Saves a note the user explicitly asked you to remember, " +
            "so you can recall it later with list_memories. Call this when the " +
            "user says \"remember this/that\" or otherwise asks you not to " +
            "forget something. Keep the saved text faithful to what the user " +
            "said, phrased so it still makes sense when read back later. " +
            "After the call succeeds, briefly confirm in your own words what " +
            "you saved — do not just read the tool response back.",
        parameters = schema(
            """
            {
              "type": "object",
              "properties": {
                "text": {
                  "type": "string",
                  "description": "The thing to remember, as a short self-contained note."
                }
              },
              "required": ["text"]
            }
            """,
        ),
    )

    override suspend fun execute(args: JsonObject): JsonObject {
        val text = args.requireString("text")
        if (text.isBlank()) {
            return buildJsonObject {
                put("status", "error")
                put("reason", "empty text — nothing to remember")
            }
        }
        val memory = memories.save(text)
        return buildJsonObject {
            put("status", "saved")
            put("id", memory.id)
            put("text", memory.text)
        }
    }
}

/**
 * `list_memories` — reads back everything saved via `save_memory`, newest
 * first. Call when the user asks what you remember / what they asked you
 * to remember. Summarize the entries conversationally; don't dump raw IDs.
 */
class ListMemoriesTool @Inject constructor(
    private val memories: MemoryRepository,
) : AgentTool {

    override val name = "list_memories"

    override val declaration = FunctionDeclaration(
        name = name,
        description = "Lists everything the user has asked you to remember, " +
            "newest first. Call when the user asks what you remember, what " +
            "they asked you to remember, or whether you remember something " +
            "specific. Present the entries naturally in your reply.",
        parameters = schema(
            """
            {
              "type": "object",
              "properties": {}
            }
            """,
        ),
    )

    override suspend fun execute(args: JsonObject): JsonObject {
        val dateFormat =
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val items = buildJsonArray {
            memories.memories.first().forEach { memory ->
                addJsonObject {
                    put("id", memory.id)
                    put("text", memory.text)
                    put(
                        "saved_at",
                        dateFormat.format(Date(memory.createdAt)),
                    )
                }
            }
        }
        return buildJsonObject {
            put("status", "ok")
            put("memories", items)
        }
    }
}
