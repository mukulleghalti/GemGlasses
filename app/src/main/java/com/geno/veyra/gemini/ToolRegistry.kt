package com.geno.veyra.gemini

import android.util.Log
import com.geno.veyra.gemini.protocol.FunctionCall
import com.geno.veyra.gemini.protocol.FunctionResponse
import com.geno.veyra.gemini.protocol.GoogleSearch
import com.geno.veyra.gemini.protocol.Tool
import com.geno.veyra.openai.realtime.OpenAiFunctionTool
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
    val smartHome: Boolean = false,
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
                        ToolGate.SMART_HOME -> flags.smartHome
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

    /**
     * The `tools` array sent in the Realtime `session.update`. Same
     * toggle gating as [asLiveTools]; the `parameters` JSON Schema
     * passes through verbatim.
     *
     * Note: the Realtime API offers no server-side web-search tool, so
     * [ToolFlags.webSearch] is intentionally ignored here — unlike
     * [asLiveTools], which adds native Google Search.
     */
    fun asOpenAiTools(flags: ToolFlags): List<OpenAiFunctionTool> =
        byName.values
            .filter { tool ->
                when (tool.gate) {
                    ToolGate.ALWAYS -> true
                    ToolGate.QR_SCAN -> flags.qrScan
                    ToolGate.OCR -> flags.ocr
                    ToolGate.SMART_HOME -> flags.smartHome
                }
            }
            .map { tool ->
                OpenAiFunctionTool(
                    type = "function",
                    name = tool.declaration.name,
                    description = tool.declaration.description,
                    parameters = tool.declaration.parameters,
                )
            }

    /**
     * Runs one function call and packages the response for the socket.
     * Error messages follow the assistant's spoken [languageCode] so the
     * model can relay them in the user's language.
     */
    suspend fun dispatch(
        call: FunctionCall,
        languageCode: String = "en-US",
    ): FunctionResponse {
        val tool = byName[call.name]
        val response = if (tool == null) {
            Log.w(TAG, "Unknown tool requested: ${call.name}")
            buildJsonObject {
                put("status", "error")
                put("message", "${unknownToolLabel(languageCode)}: ${call.name}")
            }
        } else {
            runCatching { tool.execute(call.args) }.getOrElse { e ->
                Log.e(TAG, "Tool ${call.name} failed", e)
                buildJsonObject {
                    put("status", "error")
                    put(
                        "message",
                        e.message ?: toolFailureLabel(languageCode),
                    )
                }
            }
        }
        return FunctionResponse(id = call.id, name = call.name, response = response)
    }

    private fun unknownToolLabel(languageCode: String): String =
        when (languageCode.substringBefore('-')) {
            "es" -> "Herramienta desconocida"
            "pt" -> "Ferramenta desconhecida"
            "fr" -> "Outil inconnu"
            "it" -> "Strumento sconosciuto"
            "de" -> "Unbekanntes Tool"
            else -> "Unknown tool"
        }

    private fun toolFailureLabel(languageCode: String): String =
        when (languageCode.substringBefore('-')) {
            "es" -> "No se pudo ejecutar la herramienta"
            "pt" -> "Falha ao executar a ferramenta"
            "fr" -> "Échec de l'exécution de l'outil"
            "it" -> "Impossibile eseguire lo strumento"
            "de" -> "Tool-Ausführung fehlgeschlagen"
            else -> "Tool execution failed"
        }

    private companion object {
        const val TAG = "ToolRegistry"
    }
}
