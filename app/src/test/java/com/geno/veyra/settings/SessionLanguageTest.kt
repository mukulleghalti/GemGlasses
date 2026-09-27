package com.geno.veyra.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLanguageTest {

    @Test
    fun `picker tags map to full speech tags`() {
        assertEquals("en-US", sessionSpeechTag("en"))
        assertEquals("es-ES", sessionSpeechTag("es"))
        assertEquals("pt-BR", sessionSpeechTag("pt"))
        assertEquals("fr-FR", sessionSpeechTag("fr"))
        assertEquals("it-IT", sessionSpeechTag("it"))
    }

    @Test
    fun `unmapped languages fall back to en-US`() {
        assertEquals("en-US", sessionSpeechTag("hi"))
        assertEquals("en-US", sessionSpeechTag("xx"))
    }

    @Test
    fun `directives are written in the target language`() {
        assertTrue(speechDirective("es").contains("español"))
        assertTrue(speechDirective("pt").contains("português"))
        assertTrue(speechDirective("fr").contains("français"))
        assertTrue(speechDirective("it").contains("italiano"))
        assertTrue(speechDirective("en").contains("English"))
        // Unknown languages fall back to the English directive.
        assertTrue(speechDirective("hi").contains("English"))
    }
}
