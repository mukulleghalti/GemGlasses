package com.geno.veyra.gemini

import android.util.Log
import com.geno.veyra.gemini.protocol.FunctionCall
import com.geno.veyra.gemini.protocol.FunctionResponse
import com.geno.veyra.gemini.protocol.GoogleSearch
import com.geno.veyra.gemini.protocol.Tool
import com.geno.veyra.tools.AgentTool
import com.geno.veyra.tools.ToolGate
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Toggle-gated tool capabilities for a Live session. Each flag mirrors an
 * AI Settings switch; see [ToolGate] on [AgentTool].
 */
data class ToolFlags(
    val webSearch: Boolean = false,
    val qrScan: Boolean = false,
    val ocr: Boolean = false,
)

/**
 * Holds the app's [AgentTool]s, exposes their declarations for the Live setup
 * message, and dispatches incoming function calls to the right implementation.
 */
@Singleton
class ToolRegistry @Inject constructor(
    tools: Set<@JvmSuppressWildcards AgentTool>,
) {
    private val byName: Map<String, AgentTool> = tools.associateBy { it.name }

    /**
     * The `tools` array sent in the Live setup message. Toggle-gated tools
     * are only declared when their [ToolFlags] entry is on; the rest are
     * always advertised.
     */
    fun asLiveTools(flags: ToolFlags): List<Tool> =
        buildList {
            val declarations = byName.values
                .filter { tool ->
                    when (tool.gate) {
                        ToolGate.ALWAYS -> true
                        ToolGate.QR_SCAN -> flags.qrScan
                        ToolGate.OCR -> flags.ocr
                    }
                }
                .map { it.declaration }
            add(
                Tool(
                    functionDeclarations = declarations,
                ),
            )
            if (flags.webSearch) {
                add(Tool(googleSearch = GoogleSearch()))
            }
        }

    /** Runs one function call and packages the response for the socket. */
    suspend fun dispatch(call: FunctionCall): FunctionResponse {
        val tool = byName[call.name]
        val response = if (tool == null) {
            Log.w(TAG, "Unknown tool requested: ${call.name}")
            buildJsonObject {
                put("status", "error")
                put("message", "Ferramenta desconhecida: ${call.name}")
            }
        } else {
            runCatching { tool.execute(call.args) }.getOrElse { e ->
                Log.e(TAG, "Tool ${call.name} failed", e)
                buildJsonObject {
                    put("status", "error")
                    put("message", e.message ?: "Falha ao executar a ferramenta")
                }
            }
        }
        return FunctionResponse(id = call.id, name = call.name, response = response)
    }

    private companion object {
        const val TAG = "ToolRegistry"
    }
}
