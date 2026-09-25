package com.geno.veyra.gemini.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Minimal, hand-written data model for the Gemini Live `BidiGenerateContent`
 * protocol. We keep only the fields Veyra actually reads or writes — the
 * wire format is larger. All names map 1:1 to the JSON keys the server expects.
 *
 * Client → server messages are wrapped in [ClientMessage]; server → client
 * frames are parsed leniently in [LiveSession] because the top-level key
 * (`setupComplete`, `serverContent`, `toolCall`, ...) is a discriminator.
 */

// ---------------------------------------------------------------------------
// Client → server
// ---------------------------------------------------------------------------

@Serializable
data class ClientMessage(
    val setup: Setup? = null,
    val realtimeInput: RealtimeInput? = null,
    val clientContent: ClientContent? = null,
    val toolResponse: ToolResponse? = null,
)

@Serializable
data class Setup(
    val model: String,
    val generationConfig: GenerationConfig? = null,
    val systemInstruction: Content? = null,
    val tools: List<Tool>? = null,
    val realtimeInputConfig: RealtimeInputConfig? = null,
    val inputAudioTranscription: JsonObject? = null,
    val outputAudioTranscription: JsonObject? = null,
    val sessionResumption: SessionResumptionConfig? = null,
)

@Serializable
data class GenerationConfig(
    val responseModalities: List<String>? = null,
    val speechConfig: SpeechConfig? = null,
    val temperature: Double? = null,
)

@Serializable
data class SpeechConfig(
    val voiceConfig: VoiceConfig? = null,
    val languageCode: String? = null,
)

@Serializable
data class VoiceConfig(
    val prebuiltVoiceConfig: PrebuiltVoiceConfig? = null,
)

@Serializable
data class PrebuiltVoiceConfig(val voiceName: String)

@Serializable
data class RealtimeInputConfig(
    val automaticActivityDetection: JsonObject? = null,
    val activityHandling: String? = null,
)

@Serializable
data class SessionResumptionConfig(
    // Empty object => enable resumption; { handle } => resume a prior session.
    val handle: String? = null,
)

/** Streamed audio/video chunks sent continuously during a turn. */
@Serializable
data class RealtimeInput(
    val audio: Blob? = null,
    val video: Blob? = null,
    val mediaChunks: List<Blob>? = null,
)

@Serializable
data class Blob(
    val mimeType: String,
    // base64-encoded bytes
    val data: String,
)

@Serializable
data class ClientContent(
    val turns: List<Content>? = null,
    val turnComplete: Boolean? = null,
)

@Serializable
data class ToolResponse(
    val functionResponses: List<FunctionResponse>,
)

@Serializable
data class FunctionResponse(
    val id: String? = null,
    val name: String,
    val response: JsonObject,
)

// ---------------------------------------------------------------------------
// Shared: content, tools
// ---------------------------------------------------------------------------

@Serializable
data class Content(
    val role: String? = null,
    val parts: List<Part> = emptyList(),
)

@Serializable
data class Part(
    val text: String? = null,
    val inlineData: Blob? = null,
    val functionCall: FunctionCall? = null,
    val functionResponse: FunctionResponse? = null,
)

@Serializable
data class Tool(
    val functionDeclarations: List<FunctionDeclaration>? = null,
)

@Serializable
data class FunctionDeclaration(
    val name: String,
    val description: String,
    val parameters: JsonElement? = null,
)

@Serializable
data class FunctionCall(
    val id: String? = null,
    val name: String,
    val args: JsonObject = JsonObject(emptyMap()),
)

// ---------------------------------------------------------------------------
// Server → client
// ---------------------------------------------------------------------------

@Serializable
data class ServerMessage(
    val setupComplete: JsonObject? = null,
    val serverContent: ServerContent? = null,
    val toolCall: ToolCall? = null,
    val toolCallCancellation: ToolCallCancellation? = null,
    val goAway: GoAway? = null,
    val sessionResumptionUpdate: SessionResumptionUpdate? = null,
    val usageMetadata: JsonObject? = null,
)

@Serializable
data class ServerContent(
    val modelTurn: Content? = null,
    val turnComplete: Boolean? = null,
    val interrupted: Boolean? = null,
    val generationComplete: Boolean? = null,
    val inputTranscription: Transcription? = null,
    val outputTranscription: Transcription? = null,
)

@Serializable
data class Transcription(val text: String? = null)

@Serializable
data class ToolCall(
    val functionCalls: List<FunctionCall> = emptyList(),
)

@Serializable
data class ToolCallCancellation(
    val ids: List<String> = emptyList(),
)

@Serializable
data class GoAway(
    @SerialName("timeLeft") val timeLeft: String? = null,
)

@Serializable
data class SessionResumptionUpdate(
    val newHandle: String? = null,
    val resumable: Boolean = false,
)
