package com.geno.veyra.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseLanguageTest {

    @Test
    fun `base language resolves from picker tags`() {
        assertEquals("en", wakeBaseLanguage("en"))
        assertEquals("es", wakeBaseLanguage("es"))
        assertEquals("pt", wakeBaseLanguage("pt"))
        assertEquals("fr", wakeBaseLanguage("fr"))
        assertEquals("it", wakeBaseLanguage("it"))
        assertEquals("de", wakeBaseLanguage("de"))
    }

    @Test
    fun `base language falls back to english`() {
        assertEquals("en", wakeBaseLanguage("hi"))
        assertEquals("en", wakeBaseLanguage("xx"))
    }

    @Test
    fun `default wake phrases are ascii and distinct`() {
        val phrases =
            listOf("en", "es", "pt", "fr", "it", "de")
                .map { defaultWakePhrase(it) }
        // ASCII-only: Vosk decodes unaccented lowercase and the matcher's
        // word boundaries are ASCII-based.
        phrases.forEach { phrase ->
            assertTrue(
                "non-ascii default wake phrase: $phrase",
                phrase.all { it.code < 128 },
            )
        }
        assertEquals(6, phrases.toSet().size)
        assertEquals("hey glasses", defaultWakePhrase("en"))
        assertEquals("hola gafas", defaultWakePhrase("es"))
    }
}
