package com.geno.veyra.tools

import com.geno.veyra.gemini.protocol.FunctionDeclaration
import com.geno.veyra.live.LiveStreamManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * `start_live_stream` — starts publishing the glasses camera + mic to
 * the user's saved RTMP destination (Go Live screen). Gated behind the
 * "Go Live" AI Settings toggle.
 */
class StartLiveStreamTool @Inject constructor(
    private val live: LiveStreamManager,
) : AgentTool {

    override val name = "start_live_stream"

    override val gate = ToolGate.LIVE_STREAM

    override val declaration = FunctionDeclaration(
        name = name,
        description = "Starts live streaming the glasses camera and " +
            "microphone to the user's configured streaming destination " +
            "(YouTube, Twitch, or a custom RTMP server). Use when the " +
            "user asks to go live or start a stream. The destination " +
            "must already be set in the Go Live screen.",
        parameters = schema(
            """
            {
              "type": "object",
              "properties": {}
            }
            """,
        ),
    )

    override suspend fun execute(args: JsonObject): JsonObject {
        val error = live.start()
        return if (error == null) {
            buildJsonObject {
                put("status", "ok")
                put(
                    "message",
                    "Live stream is starting. Tell the user they are " +
                        "going live.",
                )
            }
        } else {
            buildJsonObject {
                put("status", "error")
                put("message", error)
            }
        }
    }
}

/**
 * `stop_live_stream` — stops the running Go Live stream. Gated behind
 * the "Go Live" AI Settings toggle.
 */
class StopLiveStreamTool @Inject constructor(
    private val live: LiveStreamManager,
) : AgentTool {

    override val name = "stop_live_stream"

    override val gate = ToolGate.LIVE_STREAM

    override val declaration = FunctionDeclaration(
        name = name,
        description = "Stops the live stream started with " +
            "start_live_stream. Use when the user asks to stop " +
            "streaming or end the stream.",
        parameters = schema(
            """
            {
              "type": "object",
              "properties": {}
            }
            """,
        ),
    )

    override suspend fun execute(args: JsonObject): JsonObject {
        val wasLive = live.isLive
        live.stop()
        return buildJsonObject {
            put("status", "ok")
            put(
                "message",
                if (wasLive) {
                    "Live stream stopped."
                } else {
                    "No live stream was running."
                },
            )
        }
    }
}
