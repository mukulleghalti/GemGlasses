package com.lpecom.gemglasses.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lpecom.gemglasses.gemini.protocol.FunctionDeclaration
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * `iniciar_navegacao` — launches turn-by-turn navigation in Google Maps.
 * Falls back to a universal Maps URL if the Maps app is not installed.
 */
class NavigationTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : AgentTool {

    override val name = "iniciar_navegacao"

    override val declaration = FunctionDeclaration(
        name = name,
        description = "Inicia navegação por GPS até um destino no Google Maps. " +
            "Use quando o usuário pedir para ir a algum lugar.",
        parameters = schema(
            """
            {
              "type": "object",
              "properties": {
                "destino": {
                  "type": "string",
                  "description": "Endereço ou nome do lugar de destino."
                },
                "modo": {
                  "type": "string",
                  "enum": ["driving", "walking", "transit"],
                  "description": "Modo de transporte. Padrão: driving."
                }
              },
              "required": ["destino"]
            }
            """
        ),
    )

    override suspend fun execute(args: JsonObject): JsonObject {
        val destino = args.requireString("destino")
        val modo = args.optString("modo", "driving")

        val encoded = Uri.encode(destino)
        val navIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("google.navigation:q=$encoded&mode=${modeFlag(modo)}"),
        ).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val started = runCatching { context.startActivity(navIntent) }.isSuccess
        if (!started) {
            val web = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(
                    "https://www.google.com/maps/dir/?api=1" +
                        "&destination=$encoded&travelmode=$modo",
                ),
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            runCatching { context.startActivity(web) }
        }

        return buildJsonObject {
            put("status", "started")
            put("destino", destino)
            put("modo", modo)
        }
    }

    // Maps' google.navigation scheme uses single-letter mode flags.
    private fun modeFlag(modo: String) = when (modo) {
        "walking" -> "w"
        "transit" -> "r"
        else -> "d"
    }
}
