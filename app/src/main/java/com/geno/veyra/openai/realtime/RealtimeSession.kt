package com.geno.veyra.openai.realtime

import android.util.Base64
import android.util.Log
import com.geno.veyra.gemini.SessionEvent
import com.geno.veyra.gemini.protocol.FunctionCall
import com.geno.veyra.gemini.protocol.FunctionResponse
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * One OpenAI Realtime API voice session over WebSocket.
 *
 * Mirrors [com.geno.veyra.gemini.LiveSession]: it translates the raw
 * Realtime protocol into the shared [SessionEvent] set, so the rest of
 * the app (transcript UI, speaker, tool dispatch) needs no
 * provider-specific code.
 *
 * Protocol notes (GA shape, verified 2026-09-27):
 * - Auth is the plain API key in the `Authorization` header; there is
 *   no ephemeral-token dance like Gemini's. Do NOT send
 *   `OpenAI-Beta: realtime=v1` — OpenAI retired the beta interface on
 *   2026-05-12 and the header now hard-closes the socket
 *   (`beta_api_shape_disabled`).
 * - The first client message is `session.update` with the nested GA
 *   session object (`type: "realtime"`, `output_modalities`,
 *   `audio.input`/`audio.output`); the session is ready once the
 *   server answers `session.updated`.
 * - Mic audio is 24 kHz PCM16 (`input_audio_buffer.append`); Veyra
 *   captures at 16 kHz, so [AudioResampler] upsamples every chunk.
 *   Assistant audio arrives as 24 kHz PCM16
 *   (`response.output_audio.delta`) — exactly what
 *   [com.geno.veyra.audio.AudioSpec.OUTPUT_SAMPLE_RATE] expects.
 * - Server-side VAD (`turn_detection`) handles barge-in: speech start
 *   surfaces as [SessionEvent.Interrupted] and the server auto-cancels
 *   its in-flight response.
 * - Function calls arrive per response as
 *   `response.function_call_arguments.done` events and are flushed as
 *   one [SessionEvent.ToolInvocation] on `response.done`. The OpenAI
 *   `call_id` rides in [FunctionCall.id], so the generic
 *   [com.geno.veyra.gemini.ToolRegistry.dispatch] round-trips it into
 *   [FunctionResponse.id] for the `function_call_output` reply.
 */
class RealtimeSession(
    private val client: OkHttpClient,
    private val json: Json,
    private val apiKey: String,
    private val model: String,
    private val instructions: String,
    private val tools: List<OpenAiFunctionTool>,
) {

    @Volatile
    private var socket: WebSocket? = null

    /**
     * The server only accepts input once it has applied our
     * `session.update` (`session.updated`). Mic/text/tool messages that
     * arrive earlier are queued, mirroring LiveSession's setup gate.
     */
    @Volatile
    private var ready = false

    private val pendingMessages = ConcurrentLinkedQueue<JsonObject>()

    /**
     * Function calls collected from the current response, flushed as a
     * single ToolInvocation when `response.done` arrives.
     */
    private val pendingCalls = mutableListOf<FunctionCall>()

    fun connect(): Flow<SessionEvent> = callbackFlow {

        ready = false
        pendingMessages.clear()
        pendingCalls.clear()

        val url = "wss://api.openai.com/v1/realtime?model=$model"

        Log.d(TAG, "Connecting to OpenAI Realtime (model=$model)")

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            // NOTE: no OpenAI-Beta header. OpenAI retired the beta
            // Realtime interface on 2026-05-12; sending
            // `OpenAI-Beta: realtime=v1` now hard-closes the socket
            // with `invalid_request_error.beta_api_shape_disabled`.
            .build()

        val listener = object : WebSocketListener() {

            override fun onOpen(
                webSocket: WebSocket,
                response: Response,
            ) {
                socket = webSocket

                Log.i(
                    TAG,
                    "WebSocket opened. Sending session.update as first message."
                )

                if (!webSocket.send(buildSessionUpdate().toString())) {
                    Log.e(TAG, "Failed to send session.update")
                }
            }

            override fun onMessage(
                webSocket: WebSocket,
                bytes: ByteString,
            ) {
                handleFrame(bytes.utf8()).forEach { event ->
                    trySend(event)
                }
            }

            override fun onMessage(
                webSocket: WebSocket,
                text: String,
            ) {
                handleFrame(text).forEach { event ->
                    trySend(event)
                }
            }

            override fun onClosing(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) {
                Log.d(TAG, "onClosing: $code - $reason")
                webSocket.close(NORMAL_CLOSURE, null)
            }

            override fun onClosed(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) {
                Log.d(TAG, "onClosed: $code - $reason")
                socket = null
                ready = false
                trySend(SessionEvent.Closed(null))
                close()
            }

            override fun onFailure(
                webSocket: WebSocket,
                t: Throwable,
                response: Response?,
            ) {
                Log.e(
                    TAG,
                    "WebSocket failure. HTTP response=${response?.code}",
                    t,
                )
                socket = null
                ready = false
                trySend(SessionEvent.Closed(t))
                close()
            }
        }

        val ws = client.newWebSocket(request, listener)
        socket = ws

        awaitClose {
            Log.d(TAG, "callbackFlow closed; closing WebSocket")
            ready = false
            pendingMessages.clear()
            pendingCalls.clear()
            ws.close(NORMAL_CLOSURE, "client closing")
            socket = null
        }
    }

    /**
     * Send microphone PCM (16 kHz mono, 16-bit LE); upsampled to the
     * 24 kHz the Realtime API requires.
     */
    fun sendAudio(pcm16k: ByteArray) {
        val pcm24k = AudioResampler.upsample16kTo24k(pcm16k)
        send(
            buildJsonObject {
                put("type", "input_audio_buffer.append")
                put("audio", pcm24k.b64())
            }
        )
    }

    /** Send a complete user text turn. */
    fun sendText(text: String) {
        send(
            buildJsonObject {
                put("type", "conversation.item.create")
                put(
                    "item",
                    buildJsonObject {
                        put("type", "message")
                        put("role", "user")
                        put(
                            "content",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "input_text")
                                        put("text", text)
                                    }
                                )
                            }
                        )
                    }
                )
            }
        )
        send(buildJsonObject { put("type", "response.create") })
    }

    /** Send function results back, then let the model continue. */
    fun sendToolResponses(responses: List<FunctionResponse>) {
        responses.forEach { resp ->
            val callId = resp.id ?: return@forEach
            send(
                buildJsonObject {
                    put("type", "conversation.item.create")
                    put(
                        "item",
                        buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", callId)
                            put("output", resp.response.toString())
                        }
                    )
                }
            )
        }
        send(buildJsonObject { put("type", "response.create") })
    }

    /**
     * Forward a camera JPEG as visual context.
     *
     * The Realtime API has no realtime video input, so each frame is
     * injected as a user image message. No `response.create` is sent —
     * frames accumulate as context and the model's next turn (for
     * example after the `capture_vision` tool output lands) sees
     * whatever has arrived so far.
     */
    fun sendFrame(jpeg: ByteArray) {
        send(
            buildJsonObject {
                put("type", "conversation.item.create")
                put(
                    "item",
                    buildJsonObject {
                        put("type", "message")
                        put("role", "user")
                        put(
                            "content",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "input_image")
                                        put(
                                            "image_url",
                                            "data:image/jpeg;base64," +
                                                jpeg.b64()
                                        )
                                    }
                                )
                            }
                        )
                    }
                )
            }
        )
    }

    /** Close the current WebSocket. */
    fun close() {
        ready = false
        pendingMessages.clear()
        pendingCalls.clear()
        socket?.close(NORMAL_CLOSURE, "client closing")
        socket = null
    }

    private fun send(message: JsonObject) {
        if (!ready) {
            pendingMessages.add(message)
            flushPendingMessages()
            return
        }
        sendImmediately(message)
    }

    private fun sendImmediately(message: JsonObject) {
        val ws = socket
        if (ws == null) {
            Log.w(TAG, "Cannot send message: WebSocket is null")
            return
        }
        if (!ws.send(message.toString())) {
            Log.w(TAG, "WebSocket.send() returned false")
        }
    }

    private fun flushPendingMessages() {
        if (!ready) return
        while (true) {
            val message = pendingMessages.poll() ?: break
            sendImmediately(message)
        }
        Log.d(TAG, "Pending message queue flushed")
    }

    /**
     * The first message on the socket: GA-shaped session config with
     * the nested `audio.input` / `audio.output` objects.
     *
     * `output_modalities` is audio-only: the assistant transcript the
     * UI shows comes from the `response.output_audio_transcript.delta`
     * captions, so a separate text modality would only burn tokens.
     */
    private fun buildSessionUpdate(): JsonObject =
        buildJsonObject {
            put("type", "session.update")
            put(
                "session",
                buildJsonObject {
                    put("type", "realtime")
                    put("model", model)
                    put("instructions", instructions)
                    put(
                        "output_modalities",
                        buildJsonArray { add(JsonPrimitive("audio")) }
                    )
                    put(
                        "audio",
                        buildJsonObject {
                            put(
                                "input",
                                buildJsonObject {
                                    put(
                                        "format",
                                        buildJsonObject {
                                            put("type", "audio/pcm")
                                            put("rate", 24000)
                                        }
                                    )
                                    put(
                                        "transcription",
                                        buildJsonObject {
                                            put(
                                                "model",
                                                TRANSCRIPTION_MODEL
                                            )
                                        }
                                    )
                                    put(
                                        "turn_detection",
                                        buildJsonObject {
                                            put("type", "server_vad")
                                            put("threshold", 0.5)
                                            put(
                                                "prefix_padding_ms",
                                                300
                                            )
                                            put(
                                                "silence_duration_ms",
                                                700
                                            )
                                        }
                                    )
                                }
                            )
                            put(
                                "output",
                                buildJsonObject {
                                    put(
                                        "format",
                                        buildJsonObject {
                                            put("type", "audio/pcm")
                                            put("rate", 24000)
                                        }
                                    )
                                    put("voice", VOICE)
                                }
                            )
                        }
                    )
                    put(
                        "tools",
                        json.encodeToJsonElement(
                            kotlinx.serialization.builtins.ListSerializer(
                                OpenAiFunctionTool.serializer()
                            ),
                            tools
                        )
                    )
                    put("tool_choice", "auto")
                    put("temperature", 0.8)
                }
            )
        }

    /**
     * Parse one server frame into [SessionEvent]s. Unknown event types
     * are ignored — the Realtime API emits several dozen and we only
     * need the handful that drive the UI.
     */
    private fun handleFrame(raw: String): List<SessionEvent> {
        val root = runCatching {
            json.parseToJsonElement(raw).jsonObject
        }.getOrElse { error ->
            Log.w(
                TAG,
                "Could not parse Realtime frame: ${error.message}"
            )
            return emptyList()
        }

        val type = root.string("type") ?: return emptyList()

        return when (type) {
            /*
             * Our session.update was applied — the socket now accepts
             * input. This is the Ready signal.
             */
            "session.updated" -> {
                Log.i(TAG, "🟢 Realtime session.updated received")
                ready = true
                flushPendingMessages()
                listOf(SessionEvent.Ready)
            }

            /*
             * Fresh response starting — reset the per-response
             * function-call accumulator.
             */
            "response.created" -> {
                pendingCalls.clear()
                emptyList()
            }

            /*
             * User speech transcription finished.
             */
            "conversation.item.input_audio_transcription.completed" -> {
                root.string("transcript")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { text ->
                        listOf(
                            SessionEvent.Transcript(
                                text = text,
                                fromUser = true
                            )
                        )
                    }
                    ?: emptyList()
            }

            /*
             * Incremental assistant transcript. GA name is
             * `response.output_audio_transcript.delta`; the beta
             * `response.audio_transcript.delta` is kept as an alias.
             */
            "response.output_audio_transcript.delta",
            "response.audio_transcript.delta" -> {
                root.string("delta")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { text ->
                        listOf(
                            SessionEvent.Transcript(
                                text = text,
                                fromUser = false
                            )
                        )
                    }
                    ?: emptyList()
            }

            /*
             * Assistant audio (24 kHz PCM16 — matches the speaker). GA
             * name is `response.output_audio.delta`; the beta
             * `response.audio.delta` is kept as an alias.
             */
            "response.output_audio.delta",
            "response.audio.delta" -> {
                root.string("delta")
                    ?.let { b64 ->
                        listOf(
                            SessionEvent.AudioChunk(
                                Base64.decode(b64, Base64.NO_WRAP)
                            )
                        )
                    }
                    ?: emptyList()
            }

            /*
             * The user started talking over the assistant — with server
             * VAD the server auto-cancels its response; we flush local
             * playback like LiveSession's Interrupted.
             */
            "input_audio_buffer.speech_started" ->
                listOf(SessionEvent.Interrupted)

            /*
             * One function call of the current response is complete.
             * Accumulate; response.done flushes them as one invocation.
             */
            "response.function_call_arguments.done" -> {
                val callId = root.string("call_id")
                val name = root.string("name")
                if (callId != null && name != null) {
                    val argsString = root.string("arguments") ?: "{}"
                    val args = runCatching {
                        json.parseToJsonElement(argsString).jsonObject
                    }.getOrElse { JsonObject(emptyMap()) }
                    pendingCalls += FunctionCall(
                        id = callId,
                        name = name,
                        args = args
                    )
                    Log.d(
                        TAG,
                        "Function call queued: $name (call_id=$callId)"
                    )
                }
                emptyList()
            }

            /*
             * End of response: flush any accumulated tool calls, then
             * the turn is complete.
             */
            "response.done" -> buildList {
                if (pendingCalls.isNotEmpty()) {
                    add(
                        SessionEvent.ToolInvocation(
                            pendingCalls.toList()
                        )
                    )
                    pendingCalls.clear()
                }
                add(SessionEvent.TurnComplete)
            }

            /*
             * Explicit server error. Before ready this is terminal
             * (bad key, unknown model, ...); mid-session we log and
             * carry on.
             */
            "error" -> {
                val detail = root["error"]
                    ?.jsonObject
                    ?.string("message")
                    ?.takeIf { it.isNotBlank() }
                if (!ready) {
                    Log.e(
                        TAG,
                        "🔴 Realtime server error before ready: $detail"
                    )
                    listOf(SessionEvent.ConnectionFailed(detail))
                } else {
                    Log.w(
                        TAG,
                        "⚠️ Realtime server error mid-session (ignored): $detail"
                    )
                    emptyList()
                }
            }

            else -> emptyList()
        }
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun ByteArray.b64(): String =
        Base64.encodeToString(this, Base64.NO_WRAP)

    private companion object {
        const val TAG = "RealtimeSession"
        const val NORMAL_CLOSURE = 1000

        /**
         * Assistant voice for the ChatGPT provider. Veyra's voice
         * picker lists Gemini voices, so this stays a constant until a
         * provider-aware voice picker exists.
         */
        const val VOICE = "verse"

        /** Small, cheap transcription model for user speech. */
        const val TRANSCRIPTION_MODEL = "gpt-4o-mini-transcribe"
    }
}
