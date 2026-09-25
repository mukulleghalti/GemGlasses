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
 * `send_message` — drafts a message to a contact. MVP opens the SMS composer
 * pre-filled with the text; the user confirms the send manually. Nothing is
 * ever sent automatically.
 */
class MessageTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : AgentTool {

    override val name = "send_message"

    override val declaration = FunctionDeclaration(
        name = name,
        description = "Prepares a text message for a contact and opens the " +
            "messaging app for the user to confirm sending. Never sends on its own.",
        parameters = schema(
            """
            {
              "type": "object",
              "properties": {
                "recipient": {
                  "type": "string",
                  "description": "Name or number of the recipient."
                },
                "text": {
                  "type": "string",
                  "description": "Message content."
                }
              },
              "required": ["recipient", "text"]
            }
            """
        ),
    )

    override suspend fun execute(args: JsonObject): JsonObject {
        val recipient = args.requireString("recipient")
        val text = args.requireString("text")

        // If the "recipient" looks like a phone number, target it directly;
        // otherwise open a generic SMS draft the user can address.
        val smsUri = if (recipient.any { it.isDigit() } && recipient.all { it.isDigit() || it in "+ ()-" }) {
            Uri.parse("smsto:${recipient.filter { it.isDigit() || it == '+' }}")
        } else {
            Uri.parse("smsto:")
        }

        val intent = Intent(Intent.ACTION_SENDTO, smsUri).apply {
            putExtra("sms_body", text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val opened = runCatching { context.startActivity(intent) }.isSuccess

        return buildJsonObject {
            put("status", if (opened) "draft_opened" else "error")
            put("recipient", recipient)
        }
    }
}
