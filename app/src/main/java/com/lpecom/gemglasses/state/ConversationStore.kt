package com.lpecom.gemglasses.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** A single line in the conversation transcript. */
data class TranscriptEntry(
    val speaker: Speaker,
    val text: String,
    val seq: Long,
) {
    enum class Speaker { USER, ASSISTANT, SYSTEM }
}

/** A place surfaced by Maps grounding. Displaying these is a Maps ToS requirement. */
data class CitedPlace(
    val title: String,
    val uri: String,
)

/**
 * In-memory, process-scoped store of the current conversation. Transcripts are
 * never synced off-device (privacy requirement); this holds only the live
 * session's lines plus any Maps-grounded places that must be shown with links.
 *
 * Streaming transcript fragments for the same speaker are coalesced into the
 * trailing entry so partial words don't each become a new line.
 */
@Singleton
class ConversationStore @Inject constructor() {

    private val _entries = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    val entries: StateFlow<List<TranscriptEntry>> = _entries

    private val _places = MutableStateFlow<List<CitedPlace>>(emptyList())
    val places: StateFlow<List<CitedPlace>> = _places

    private var seq = 0L

    /** Appends or coalesces a streaming transcript fragment. */
    fun appendTranscript(text: String, speaker: TranscriptEntry.Speaker) {
        if (text.isEmpty()) return
        _entries.update { current ->
            val last = current.lastOrNull()
            if (last != null && last.speaker == speaker) {
                current.dropLast(1) + last.copy(text = last.text + text)
            } else {
                current + TranscriptEntry(speaker, text, seq++)
            }
        }
    }

    /** Records a system note (e.g. reconnecting, vision on). */
    fun note(text: String) = appendTranscript(text, TranscriptEntry.Speaker.SYSTEM)

    /** Adds grounded places, de-duplicated by URI. */
    fun addPlaces(places: List<CitedPlace>) {
        if (places.isEmpty()) return
        _places.update { current ->
            val known = current.mapTo(HashSet()) { it.uri }
            current + places.filter { it.uri !in known }
        }
    }

    fun clear() {
        _entries.value = emptyList()
        _places.value = emptyList()
        seq = 0L
    }
}
