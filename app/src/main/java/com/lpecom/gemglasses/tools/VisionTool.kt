package com.lpecom.gemglasses.tools

import com.lpecom.gemglasses.gemini.protocol.FunctionDeclaration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * Bridges the `capture_vision` tool to whatever component actually streams
 * frames (the session orchestrator wires the glasses camera to the Live
 * socket). Kept as an interface so the tool has no dependency on the glasses or
 * gemini packages.
 */
interface VisionController {
    /** Turns on the glasses camera and streams frames for up to [durationMs]. */
    fun startVisionBurst(durationMs: Long)
}

/**
 * `capture_vision` — turns on the glasses camera on demand. Frames then arrive
 * over the existing Live socket as realtime video input for a bounded window,
 * which keeps the session nominally audio-only and sidesteps the 2-minute
 * video cap.
 */
class VisionTool @Inject constructor(
    private val vision: VisionController,
) : AgentTool {

    override val name = "capture_vision"

    override val declaration = FunctionDeclaration(
        name = name,
        description = "Turns on the glasses camera to see what the user is " +
            "looking at. Use when the question needs visual context (e.g.: 'what is this?').",
        parameters = schema(
            """
            { "type": "object", "properties": {} }
            """
        ),
    )

    override suspend fun execute(args: JsonObject): JsonObject {
        vision.startVisionBurst(VISION_BURST_MS)
        return buildJsonObject { put("status", "streaming") }
    }

    private companion object {
        const val VISION_BURST_MS = 20_000L
    }
}
