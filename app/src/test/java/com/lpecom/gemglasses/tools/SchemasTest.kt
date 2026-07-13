package com.lpecom.gemglasses.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SchemasTest {

    @Test
    fun `requireString returns the value when present`() {
        val args = buildJsonObject { put("destino", "Aeroporto") }
        assertEquals("Aeroporto", args.requireString("destino"))
    }

    @Test
    fun `requireString throws when missing`() {
        val args = buildJsonObject { }
        assertThrows(IllegalArgumentException::class.java) { args.requireString("destino") }
    }

    @Test
    fun `requireString throws when blank`() {
        val args = buildJsonObject { put("destino", "  ") }
        assertThrows(IllegalArgumentException::class.java) { args.requireString("destino") }
    }

    @Test
    fun `optString falls back to the default`() {
        val args = buildJsonObject { }
        assertEquals("driving", args.optString("modo", "driving"))
    }

    @Test
    fun `optString prefers the provided value`() {
        val args = buildJsonObject { put("modo", "walking") }
        assertEquals("walking", args.optString("modo", "driving"))
    }

    @Test
    fun `schema parses a JSON literal into an element`() {
        val el = schema("""{ "type": "object" }""")
        assertEquals("object", el.toString().let { if (it.contains("object")) "object" else "?" })
    }
}
