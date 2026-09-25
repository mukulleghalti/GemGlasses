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
 * `start_navigation` — launches turn-by-turn navigation in Google Maps.
 * Falls back to a universal Maps URL if the Maps app is not installed.
 */
class NavigationTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : AgentTool {

    override val name = "start_navigation"

    override val declaration = FunctionDeclaration(
        name = name,
        description = "Starts GPS navigation to a destination in Google Maps. " +
            "Use when the user asks to go somewhere.",
        parameters = schema(
            """
            {
              "type": "object",
              "properties": {
                "destination": {
                  "type": "string",
                  "description": "Destination address or place name."
                },
                "mode": {
                  "type": "string",
                  "enum": ["driving", "walking", "transit"],
                  "description": "Transportation mode. Default: driving."
                }
              },
              "required": ["destination"]
            }
            """
        ),
    )

    override suspend fun execute(args: JsonObject): JsonObject {
        val destination = args.requireString("destination")
        val mode = args.optString("mode", "driving")

        val encoded = Uri.encode(destination)
        val navIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("google.navigation:q=$encoded&mode=${modeFlag(mode)}"),
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
                        "&destination=$encoded&travelmode=$mode",
                ),
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            runCatching { context.startActivity(web) }
        }

        return buildJsonObject {
            put("status", "started")
            put("destination", destination)
            put("mode", mode)
        }
    }

    // Maps' google.navigation scheme uses single-letter mode flags.
    private fun modeFlag(mode: String) = when (mode) {
        "walking" -> "w"
        "transit" -> "r"
        else -> "d"
    }
}
