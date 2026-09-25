package com.geno.veyra.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A single thing the user asked the assistant to remember. */
@Serializable
data class Memory(
    val id: String,
    val text: String,
    val createdAt: Long,
)

private val Context.memoryDataStore by preferencesDataStore("veyra_memories")

/**
 * Persistent "remember this" storage, backed by DataStore as a JSON list.
 *
 * Written by the `save_memory` tool when the user asks the assistant to
 * remember something, read back by the `list_memories` tool when the user
 * asks what it remembers. Survives app restarts; deletion happens from
 * Settings.
 */
@Singleton
class MemoryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val memoriesKey = stringPreferencesKey("memories_json")

    private val json = Json { ignoreUnknownKeys = true }

    /** Newest first. */
    val memories: Flow<List<Memory>> =
        context.memoryDataStore.data.map { prefs ->
            decode(prefs[memoriesKey])
        }

    /** Saves a memory; drops the oldest entries past [MAX_MEMORIES]. */
    suspend fun save(text: String): Memory {
        val memory = Memory(
            id = UUID.randomUUID().toString(),
            text = text.trim(),
            createdAt = System.currentTimeMillis(),
        )
        context.memoryDataStore.edit { prefs ->
            val updated = (listOf(memory) + decode(prefs[memoriesKey]))
                .take(MAX_MEMORIES)
            prefs[memoriesKey] = json.encodeToString(updated)
        }
        return memory
    }

    /** Deletes one memory; true when something was actually removed. */
    suspend fun delete(id: String): Boolean {
        var removed = false
        context.memoryDataStore.edit { prefs ->
            val current = decode(prefs[memoriesKey])
            val updated = current.filterNot { it.id == id }
            removed = updated.size != current.size
            prefs[memoriesKey] = json.encodeToString(updated)
        }
        return removed
    }

    private fun decode(raw: String?): List<Memory> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<Memory>>(raw)
        }.getOrDefault(emptyList())
    }

    companion object {
        const val MAX_MEMORIES = 100
    }
}
