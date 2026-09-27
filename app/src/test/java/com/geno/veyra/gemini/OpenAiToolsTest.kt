package com.geno.veyra.gemini

import com.geno.veyra.gemini.protocol.FunctionDeclaration
import com.geno.veyra.openai.realtime.OpenAiFunctionTool
import com.geno.veyra.tools.AgentTool
import com.geno.veyra.tools.ToolGate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiToolsTest {

    private class FakeTool(
        override val name: String,
        override val gate: ToolGate = ToolGate.ALWAYS,
        params: String = """{"type":"object","properties":{}}""",
    ) : AgentTool {
        override val declaration = FunctionDeclaration(
            name = name,
            description = "fake $name",
            parameters = kotlinx.serialization.json.Json
                .parseToJsonElement(params),
        )

        override suspend fun execute(args: JsonObject): JsonObject =
            buildJsonObject { }
    }

    @Test
    fun `asOpenAiTools maps declarations to function tools`() {
        val registry = ToolRegistry(
            setOf(FakeTool("remember_this"))
        )

        val tools: List<OpenAiFunctionTool> =
            registry.asOpenAiTools(ToolFlags())

        assertEquals(1, tools.size)
        val tool = tools.single()
        assertEquals("function", tool.type)
        assertEquals("remember_this", tool.name)
        assertEquals("fake remember_this", tool.description)
        assertEquals(
            "object",
            tool.parameters
                ?.jsonObject
                ?.get("type")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun `asOpenAiTools respects toggle gates`() {
        val registry = ToolRegistry(
            setOf(
                FakeTool("always_tool"),
                FakeTool("qr_tool", gate = ToolGate.QR_SCAN),
            )
        )

        val off = registry.asOpenAiTools(ToolFlags()).map { it.name }
        assertEquals(listOf("always_tool"), off)

        val on = registry.asOpenAiTools(ToolFlags(qrScan = true))
            .map { it.name }
        assertTrue(on.containsAll(listOf("always_tool", "qr_tool")))
    }
}
