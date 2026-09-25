package com.geno.veyra.state

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
)

/** Lightweight summary for listing past sessions. */
data class SessionSummary(
    val id: String,
    val startedAt: Long,
    val preview: String,
    val turnCount: Int,
)

/** One search hit: the matching excerpt with surrounding turns for context. */
data class ConversationHit(
    val sessionId: String,
    val startedAt: Long,
    val excerpt: List<ArchivedTurn>,
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
                File(dir(), "${session.id}.json")
                    .writeText(json.encodeToString(session))
                prune()
            }.onFailure {
                Log.w(TAG, "archiving session failed", it)
            }
        }
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
                            )
                        }
                    }
                }
        }
        return hits
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
    }
}
