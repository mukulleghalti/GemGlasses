package com.geno.veyra.gemini

import com.geno.veyra.gemini.protocol.FunctionCall
import com.geno.veyra.gemini.protocol.FunctionDeclaration
import com.geno.veyra.tools.AgentTool
import com.geno.veyra.tools.ToolGate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolRegistryTest {

    private class FakeTool(
        override val name: String,
        private val onExecute: suspend (JsonObject) -> JsonObject,
        override val gate: ToolGate = ToolGate.ALWAYS,
    ) : AgentTool {
        override val declaration = FunctionDeclaration(name, "fake tool")
        override suspend fun execute(args: JsonObject) = onExecute(args)
    }

    @Test
    fun `asLiveTools exposes every declaration once`() {
        val registry = ToolRegistry(
            setOf(
                FakeTool("a") { buildJsonObject { } },
                FakeTool("b") { buildJsonObject { } },
            ),
        )
        val tools = registry.asLiveTools(ToolFlags())
        val decls = tools.single().functionDeclarations!!
        assertEquals(setOf("a", "b"), decls.map { it.name }.toSet())
    }

    @Test
    fun `asLiveTools adds the Google Search tool only when enabled`() {
        val registry = ToolRegistry(emptySet())

        val without = registry.asLiveTools(ToolFlags())
        assertEquals(1, without.size)
        assertEquals(null, without.single().googleSearch)

        val with = registry.asLiveTools(ToolFlags(webSearch = true))
        assertEquals(2, with.size)
        assertEquals(true, with.last().googleSearch != null)
    }

    @Test
    fun `asLiveTools gates scan tools behind their flags`() {
        val registry = ToolRegistry(
            setOf(
                FakeTool("always_on") { buildJsonObject { } },
                FakeTool(
                    "scan_barcode",
                    { buildJsonObject { } },
                    gate = ToolGate.QR_SCAN,
                ),
                FakeTool(
                    "read_text",
                    { buildJsonObject { } },
                    gate = ToolGate.OCR,
                ),
            ),
        )

        val off = registry.asLiveTools(ToolFlags())
            .single().functionDeclarations!!
            .map { it.name }.toSet()
        assertEquals(setOf("always_on"), off)

        val on = registry.asLiveTools(ToolFlags(qrScan = true, ocr = true))
            .single().functionDeclarations!!
            .map { it.name }.toSet()
        assertEquals(setOf("always_on", "scan_barcode", "read_text"), on)
    }

    @Test
    fun `dispatch routes to the matching tool and preserves call id`() = runTest {
        val registry = ToolRegistry(
            setOf(FakeTool("start_navigation") { buildJsonObject { put("status", "started") } }),
        )
        val response = registry.dispatch(FunctionCall(id = "call-1", name = "start_navigation"))

        assertEquals("call-1", response.id)
        assertEquals("start_navigation", response.name)
        assertEquals("started", response.response["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `unknown tool yields a structured error, not a crash`() = runTest {
        val registry = ToolRegistry(emptySet())
        val response = registry.dispatch(FunctionCall(name = "inexistente"))
        assertEquals("error", response.response["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a throwing tool is caught and reported as error`() = runTest {
        val registry = ToolRegistry(
            setOf(FakeTool("boom") { error("kaboom") }),
        )
        val response = registry.dispatch(FunctionCall(name = "boom"))
        assertEquals("error", response.response["status"]?.jsonPrimitive?.content)
    }
}
