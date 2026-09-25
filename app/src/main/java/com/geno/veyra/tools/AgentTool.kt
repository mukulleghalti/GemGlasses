package com.geno.veyra.tools

import com.geno.veyra.gemini.protocol.FunctionDeclaration
import kotlinx.serialization.json.JsonObject

/**
 * A single capability the model can invoke by name. Each tool owns its Gemini
 * [declaration] and knows how to [execute] a call, returning the JSON object
 * that goes back to the model as the function response.
 *
 * Tools are registered in [com.geno.veyra.di.ToolsModule]; adding one means
 * adding a binding there and updating the project spec (see docs/).
 */
interface AgentTool {
    /** Function name as the model sees it (e.g. "search_places"). */
    val name: String

    /** The declaration advertised to Gemini in the setup message. */
    val declaration: FunctionDeclaration

    /**
     * Runs the tool. [args] are the model-supplied arguments. The returned
     * object becomes `functionResponse.response`. Implementations must not throw
     * for expected failures — encode them as `{ "status": "error", ... }`.
     */
    suspend fun execute(args: JsonObject): JsonObject
}
