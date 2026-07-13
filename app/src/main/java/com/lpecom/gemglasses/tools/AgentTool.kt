package com.lpecom.gemglasses.tools

import com.lpecom.gemglasses.gemini.protocol.FunctionDeclaration
import kotlinx.serialization.json.JsonObject

/**
 * A single capability the model can invoke by name. Each tool owns its Gemini
 * [declaration] and knows how to [execute] a call, returning the JSON object
 * that goes back to the model as the function response.
 *
 * MVP has exactly four tools; do not add one without also updating the project
 * spec (see CLAUDE.md / docs).
 */
interface AgentTool {
    /** Function name as the model sees it (e.g. "buscar_lugares"). */
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
