package com.lpecom.gemglasses.state

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationStoreTest {

    @Test
    fun `consecutive same-speaker fragments coalesce`() {
        val store = ConversationStore()
        store.appendTranscript("Oi, ", TranscriptEntry.Speaker.ASSISTANT)
        store.appendTranscript("tudo bem?", TranscriptEntry.Speaker.ASSISTANT)

        val entries = store.entries.value
        assertEquals(1, entries.size)
        assertEquals("Oi, tudo bem?", entries.first().text)
    }

    @Test
    fun `switching speaker starts a new line`() {
        val store = ConversationStore()
        store.appendTranscript("Cadê o café?", TranscriptEntry.Speaker.USER)
        store.appendTranscript("Perto de você:", TranscriptEntry.Speaker.ASSISTANT)

        val entries = store.entries.value
        assertEquals(2, entries.size)
        assertEquals(TranscriptEntry.Speaker.USER, entries[0].speaker)
        assertEquals(TranscriptEntry.Speaker.ASSISTANT, entries[1].speaker)
    }

    @Test
    fun `empty fragments are ignored`() {
        val store = ConversationStore()
        store.appendTranscript("", TranscriptEntry.Speaker.USER)
        assertEquals(0, store.entries.value.size)
    }

    @Test
    fun `places de-duplicate by uri`() {
        val store = ConversationStore()
        store.addPlaces(listOf(CitedPlace("Café A", "https://maps/a")))
        store.addPlaces(
            listOf(
                CitedPlace("Café A (dup)", "https://maps/a"),
                CitedPlace("Café B", "https://maps/b"),
            ),
        )
        assertEquals(2, store.places.value.size)
    }

    @Test
    fun `clear resets entries and places`() {
        val store = ConversationStore()
        store.appendTranscript("x", TranscriptEntry.Speaker.USER)
        store.addPlaces(listOf(CitedPlace("A", "u")))
        store.clear()
        assertEquals(0, store.entries.value.size)
        assertEquals(0, store.places.value.size)
    }
}
