package com.geno.veyra.state

import android.content.Context
import android.util.Log
import com.geno.veyra.settings.GeminiKeyRepository
import com.geno.veyra.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One persisted transcript turn. */
@Serializable
data class ArchivedTurn(
    val speaker: String,
    val text: String,
    val timestamp: Long,
)

/** One persisted assistant session. */
@Serializable
data class ArchivedSession(
    val id: String,
    val startedAt: Long,
    val turns: List<ArchivedTurn>,
    val title: String? = null,
)

/** Lightweight summary for listing past sessions. */
data class SessionSummary(
    val id: String,
    val startedAt: Long,
    val preview: String,
    val turnCount: Int,
    val title: String? = null,
)

/** One search hit: the matching excerpt with surrounding turns for context. */
data class ConversationHit(
    val sessionId: String,
    val startedAt: Long,
    val excerpt: List<ArchivedTurn>,
    val title: String? = null,
)

/**
 * On-device archive of past assistant conversations.
 *
 * [AgentController] hands over the transcript when a session ends; only
 * turns not yet archived are persisted (transcripts accumulate in
 * [ConversationStore] across sessions in one process). Sessions are stored
 * as one JSON file each under `filesDir/conversations`, newest kept, old
 * ones pruned. Nothing ever leaves the device — the `search_conversations`
 * and `list_conversations` tools read this archive so the assistant can
 * recall earlier chats with their context.
 */
@Singleton
class ConversationArchive @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val keyRepository: GeminiKeyRepository,
    private val client: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val json = Json { ignoreUnknownKeys = true }

    /** Highest transcript seq already archived; transcripts are append-only. */
    private var lastArchivedSeq = -1L

    private fun dir(): File =
        File(context.filesDir, "conversations").apply { mkdirs() }

    /**
     * Persists the not-yet-archived turns of [entries]. Fire-and-forget;
     * safe to call from [AgentController.stop].
     */
    fun saveSession(entries: List<TranscriptEntry>) {
        val fresh = entries.filter {
            it.seq > lastArchivedSeq &&
                it.speaker != TranscriptEntry.Speaker.SYSTEM &&
                it.text.isNotBlank()
        }
        if (fresh.isEmpty()) return
        lastArchivedSeq = fresh.maxOf { it.seq }

        val startedAt = fresh.minOf { it.timestamp }
        val session = ArchivedSession(
            id = "$startedAt-${UUID.randomUUID().toString().take(8)}",
            startedAt = startedAt,
            turns = fresh.map {
                ArchivedTurn(
                    speaker = it.speaker.name,
                    text = it.text,
                    timestamp = it.timestamp,
                )
            },
        )
        scope.launch {
            runCatching {
                val file = File(dir(), "${session.id}.json")
                file.writeText(json.encodeToString(session))
                prune()

                /*
                 * Auto history titles: one cheap summarization call per
                 * session, then rewrite the file with the title. Best
                 * effort — a missing key or failed call just leaves the
                 * session untitled.
                 */
                if (settings.snapshot().autoHistoryTitles) {
                    generateTitle(session)?.let { title ->
                        file.writeText(
                            json.encodeToString(
                                session.copy(title = title),
                            ),
                        )
                    }
                }
            }.onFailure {
                Log.w(TAG, "archiving session failed", it)
            }
        }
    }

    /**
     * Asks Gemini for a short title for [session]. Returns null when
     * there is no API key or the call fails.
     */
    private suspend fun generateTitle(
        session: ArchivedSession,
    ): String? {
        val apiKey = keyRepository.getKey() ?: return null
        val transcript = session.turns
            .joinToString("\n") { "${it.speaker}: ${it.text}" }
            .take(2000)
        if (transcript.isBlank()) return null

        val payload = buildJsonObject {
            put(
                "contents",
                buildJsonArray {
                    addJsonObject {
                        put("role", "user")
                        put(
                            "parts",
                            buildJsonArray {
                                addJsonObject {
                                    put(
                                        "text",
                                        "Generate a short title, max 6 " +
                                            "words, no quotes, for this " +
                                            "conversation:\n\n$transcript",
                                    )
                                }
                            },
                        )
                    }
                },
            )
            put(
                "generationConfig",
                buildJsonObject { put("maxOutputTokens", 32) },
            )
        }

        val request = Request.Builder()
            .url("$GEMINI_API/models/$TITLE_MODEL:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) {
                    "title generation failed (${response.code})"
                }
                val root = json.parseToJsonElement(
                    response.body?.string().orEmpty(),
                ).jsonObject
                root["candidates"]
                    ?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("content")?.jsonObject
                    ?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("text")?.jsonPrimitive?.content
                    ?.trim()
                    ?.trim('"')
                    ?.take(80)
                    ?.ifBlank { null }
            }
        }.getOrNull()
    }

    /** Recent sessions, newest first. */
    fun listSessions(limit: Int = 10): List<SessionSummary> =
        runCatching {
            dir().listFiles { f -> f.extension == "json" }
                .orEmpty()
                .sortedDescending()
                .take(limit)
                .mapNotNull { file ->
                    runCatching {
                        json.decodeFromString<ArchivedSession>(
                            file.readText(),
                        )
                    }.getOrNull()
                }
                .map { session ->
                    val firstUser = session.turns
                        .firstOrNull { it.speaker == "USER" }
                        ?.text.orEmpty()
                    SessionSummary(
                        id = session.id,
                        startedAt = session.startedAt,
                        preview = firstUser.take(PREVIEW_CHARS),
                        turnCount = session.turns.size,
                        title = session.title,
                    )
                }
        }.getOrDefault(emptyList())

    /**
     * Case-insensitive substring search over archived turns. Each hit
     * carries the matching turn plus one turn of context on each side.
     */
    fun search(query: String, maxHits: Int = 8): List<ConversationHit> {
        if (query.isBlank()) return emptyList()
        val hits = mutableListOf<ConversationHit>()
        runCatching {
            dir().listFiles { f -> f.extension == "json" }
                .orEmpty()
                .sortedDescending()
                .forEach { file ->
                    if (hits.size >= maxHits) return@forEach
                    val session = runCatching {
                        json.decodeFromString<ArchivedSession>(
                            file.readText(),
                        )
                    }.getOrNull() ?: return@forEach
                    session.turns.forEachIndexed { index, turn ->
                        if (hits.size >= maxHits) return@forEach
                        if (turn.text.contains(query, ignoreCase = true)) {
                            val from = maxOf(0, index - 1)
                            val to = minOf(
                                session.turns.lastIndex,
                                index + 1,
                            )
                            hits += ConversationHit(
                                sessionId = session.id,
                                startedAt = session.startedAt,
                                excerpt = session.turns.subList(from, to + 1),
                                title = session.title,
                            )
                        }
                    }
                }
        }
        return hits
    }

    /** Full session by id, or null if missing/corrupt. */
    fun getSession(id: String): ArchivedSession? =
        runCatching {
            val file = File(dir(), "$id.json")
            if (!file.exists()) return@runCatching null
            json.decodeFromString<ArchivedSession>(file.readText())
        }.getOrNull()

    /** Deletes one archived session. */
    fun deleteSession(id: String) {
        runCatching {
            File(dir(), "$id.json").delete()
        }.onFailure {
            Log.w(TAG, "deleting session $id failed", it)
        }
    }

    /** Deletes every archived session. */
    fun clearAll() {
        runCatching {
            dir().listFiles { f -> f.extension == "json" }
                .orEmpty()
                .forEach { it.delete() }
        }.onFailure {
            Log.w(TAG, "clearing conversation archive failed", it)
        }
    }

    /** Keeps only the newest [MAX_SESSIONS] session files. */
    private fun prune() {
        val files = dir().listFiles { f -> f.extension == "json" }
            .orEmpty()
            .sortedDescending()
        files.drop(MAX_SESSIONS).forEach { it.delete() }
    }

    private companion object {
        const val TAG = "ConversationArchive"
        const val MAX_SESSIONS = 50
        const val PREVIEW_CHARS = 80
        const val GEMINI_API = "https://generativelanguage.googleapis.com/v1beta"
        const val TITLE_MODEL = "gemini-2.5-flash"
        val JSON_MEDIA = "application/json".toMediaType()
    }
}
