package com.lpecom.gemglasses.gemini

import com.lpecom.gemglasses.gemini.protocol.FunctionCall
import com.lpecom.gemglasses.gemini.protocol.FunctionDeclaration
import com.lpecom.gemglasses.tools.AgentTool
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
        val decls = registry.asLiveTools().single().functionDeclarations!!
        assertEquals(setOf("a", "b"), decls.map { it.name }.toSet())
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
