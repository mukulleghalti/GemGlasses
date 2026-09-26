package com.geno.veyra.tools

import com.geno.veyra.gemini.protocol.FunctionDeclaration
import com.geno.veyra.state.ConversationArchive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

private fun dateFormat() =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

/**
 * `search_conversations` — searches past assistant sessions for a topic.
 * Each hit includes the session date and the surrounding turns, so the
 * answer comes back with its context, not just a bare quote.
 */
class SearchConversationsTool @Inject constructor(
    private val archive: ConversationArchive,
) : AgentTool {

    override val name = "search_conversations"

    override val declaration = FunctionDeclaration(
        name = name,
        description = "Searches the user's past assistant conversations for " +
            "a topic or phrase, newest sessions first. Each hit includes " +
            "the session date and the surrounding turns for context. Call " +
            "when the user asks about something discussed before — " +
            "\"what did we talk about\", \"did I ask you about X\", " +
            "\"when did we discuss Y\". Summarize the hits naturally with " +
            "their dates; do not dump raw JSON.",
        parameters = schema(
            """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "Topic, phrase, or keyword to search past conversations for."
                }
              },
              "required": ["query"]
            }
            """,
        ),
    )

    override suspend fun execute(args: JsonObject): JsonObject {
        val query = args.requireString("query")
        val hits = buildJsonArray {
            archive.search(query).forEach { hit ->
                addJsonObject {
                    put("session_id", hit.sessionId)
                    put(
                        "date",
                        dateFormat().format(Date(hit.startedAt)),
                    )
                    hit.title?.let { put("title", it) }
                    put(
                        "excerpt",
                        buildJsonArray {
                            hit.excerpt.forEach { turn ->
                                addJsonObject { put("speaker", turn.speaker.lowercase()); put("text", turn.text.take(240)); put("time", dateFormat().format(Date(turn.timestamp))) }
                            }
                        },
                    )
                }
            }
        }
        return buildJsonObject {
            put("status", "ok")
            put("hits", hits)
        }
    }
}

/**
 * `list_conversations` — lists recent past assistant sessions with their
 * dates and a preview of what each was about.
 */
class ListConversationsTool @Inject constructor(
    private val archive: ConversationArchive,
) : AgentTool {

    override val name = "list_conversations"

    override val declaration = FunctionDeclaration(
        name = name,
        description = "Lists the user's recent past assistant sessions, " +
            "newest first, each with its date, auto-generated title " +
            "(when available), and a short preview of what it was " +
            "about. Call when the user asks what you've talked " +
            "about lately or wants to pick up an earlier topic. Present " +
            "as a short dated list.",
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
        val sessions = buildJsonArray {
            archive.listSessions().forEach { summary ->
                addJsonObject {
                    put("session_id", summary.id)
                    put(
                        "date",
                        dateFormat().format(Date(summary.startedAt)),
                    )
                    summary.title?.let { put("title", it) }
                    put("preview", summary.preview)
                    put("turns", summary.turnCount)
                }
            }
        }
        return buildJsonObject {
            put("status", "ok")
            put("sessions", sessions)
        }
    }
}
