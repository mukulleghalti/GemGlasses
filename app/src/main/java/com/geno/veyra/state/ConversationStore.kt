package com.geno.veyra.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** A single line or image in the conversation transcript. */
data class TranscriptEntry(
    val speaker: Speaker,
    val text: String,
    val seq: Long,
    val imageBytes: ByteArray? = null,
    val timestamp: Long = System.currentTimeMillis(),
) {
    enum class Speaker { USER, ASSISTANT, SYSTEM }
}

/** A place surfaced by Maps grounding. Displaying these is a Maps ToS requirement. */
data class CitedPlace(
    val title: String,
    val uri: String,
)

/**
 * In-memory, process-scoped store of the current conversation.
 *
 * Transcripts and captured images remain on-device and are not synced off-device.
 */
@Singleton
class ConversationStore @Inject constructor() {

    private val _entries =
        MutableStateFlow<List<TranscriptEntry>>(emptyList())

    val entries: StateFlow<List<TranscriptEntry>> =
        _entries

    private val _places =
        MutableStateFlow<List<CitedPlace>>(emptyList())

    val places: StateFlow<List<CitedPlace>> =
        _places

    private var seq = 0L

    /** Appends or coalesces a streaming transcript fragment. */
    fun appendTranscript(
        text: String,
        speaker: TranscriptEntry.Speaker,
    ) {
        if (text.isEmpty()) return

        _entries.update { current ->
            val last = current.lastOrNull()

            /*
             * Only coalesce text entries.
             *
             * An image is always its own transcript entry.
             */
            if (
                last != null &&
                last.speaker == speaker &&
                last.imageBytes == null
            ) {
                current.dropLast(1) +
                    last.copy(
                        text = last.text + text,
                    )
            } else {
                current + TranscriptEntry(
                    speaker = speaker,
                    text = text,
                    seq = seq++,
                )
            }
        }
    }

    /** Adds a captured photo to the transcript. */
    fun addPhoto(
        jpegBytes: ByteArray,
        speaker: TranscriptEntry.Speaker = TranscriptEntry.Speaker.USER,
    ) {
        if (jpegBytes.isEmpty()) return

        _entries.update { current ->
            current + TranscriptEntry(
                speaker = speaker,
                text = "",
                seq = seq++,
                imageBytes = jpegBytes,
            )
        }
    }

    /** Records a system note (e.g. reconnecting, vision on). */
    fun note(text: String) {
        appendTranscript(
            text,
            TranscriptEntry.Speaker.SYSTEM,
        )
    }

    /** Adds grounded places, de-duplicated by URI. */
    fun addPlaces(places: List<CitedPlace>) {
        if (places.isEmpty()) return

        _places.update { current ->
            val known = current.mapTo(HashSet()) { it.uri }

            current + places.filter {
                it.uri !in known
            }
        }
    }

    fun clear() {
        _entries.value = emptyList()
        _places.value = emptyList()
        seq = 0L
    }
}
