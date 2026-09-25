package com.geno.veyra.tools

import com.geno.veyra.audio.MicMuteController
import com.geno.veyra.gemini.protocol.FunctionDeclaration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * `set_mic_muted` — mutes the user's microphone for the assistant session
 * WITHOUT ending the session. The user keeps hearing the assistant; the
 * assistant simply stops receiving mic audio. Unmuting is done by the user
 * via the app's mute button (a muted mic can't hear "unmute"), so in
 * practice this is only ever called with `muted: true`.
 */
class MicMuteTool @Inject constructor(
    private val micMute: MicMuteController,
) : AgentTool {

    override val name = "set_mic_muted"

    override val declaration = FunctionDeclaration(
        name = name,
        description = "Mutes the user's microphone for the ongoing assistant " +
            "session without ending it. The user can still hear you; you " +
            "just stop receiving their mic audio. Call with muted=true when " +
            "the user asks you to mute yourself, stop listening, or go " +
            "quiet for a bit. Only the app's mute button can unmute, so " +
            "only call with muted=true. After muting, briefly acknowledge " +
            "that you're muted and the session is still open.",
        parameters = schema(
            """
            {
              "type": "object",
              "properties": {
                "muted": {
                  "type": "boolean",
                  "description": "True to mute the mic, false to unmute."
                }
              },
              "required": ["muted"]
            }
            """,
        ),
    )

    override suspend fun execute(args: JsonObject): JsonObject {
        val muted = args.getValue("muted").jsonPrimitive.boolean
        micMute.setMuted(muted)
        return buildJsonObject {
            put("status", "ok")
            put("muted", muted)
        }
    }
}
