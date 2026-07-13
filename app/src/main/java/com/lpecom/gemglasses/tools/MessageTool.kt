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
 * `enviar_mensagem` — drafts a message to a contact. MVP opens the SMS composer
 * pre-filled with the text; the user confirms the send manually. Nothing is
 * ever sent automatically.
 */
class MessageTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : AgentTool {

    override val name = "enviar_mensagem"

    override val declaration = FunctionDeclaration(
        name = name,
        description = "Prepara uma mensagem de texto para um contato e abre o app " +
            "de mensagens para o usuário confirmar o envio. Não envia sozinho.",
        parameters = schema(
            """
            {
              "type": "object",
              "properties": {
                "contato": {
                  "type": "string",
                  "description": "Nome ou número do destinatário."
                },
                "texto": {
                  "type": "string",
                  "description": "Conteúdo da mensagem."
                }
              },
              "required": ["contato", "texto"]
            }
            """
        ),
    )

    override suspend fun execute(args: JsonObject): JsonObject {
        val contato = args.requireString("contato")
        val texto = args.requireString("texto")

        // If the "contato" looks like a phone number, target it directly;
        // otherwise open a generic SMS draft the user can address.
        val smsUri = if (contato.any { it.isDigit() } && contato.all { it.isDigit() || it in "+ ()-" }) {
            Uri.parse("smsto:${contato.filter { it.isDigit() || it == '+' }}")
        } else {
            Uri.parse("smsto:")
        }

        val intent = Intent(Intent.ACTION_SENDTO, smsUri).apply {
            putExtra("sms_body", texto)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val opened = runCatching { context.startActivity(intent) }.isSuccess

        return buildJsonObject {
            put("status", if (opened) "draft_opened" else "error")
            put("contato", contato)
        }
    }
}
