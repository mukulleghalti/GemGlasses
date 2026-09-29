package com.geno.veyra.grok.realtime

import android.util.Base64
import android.util.Log
import com.geno.veyra.gemini.SessionEvent
import com.geno.veyra.gemini.protocol.FunctionCall
import com.geno.veyra.gemini.protocol.FunctionResponse
import com.geno.veyra.openai.realtime.OpenAiFunctionTool
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
 * One xAI Grok Speech to Speech session over WebSocket.
 *
 * xAI's Voice Agent API is deliberately OpenAI-Realtime-shaped (their
 * migration guide: "your existing event handling code works as-is"),
 * so this mirrors [com.geno.veyra.openai.realtime.RealtimeSession]
 * and translates frames into the shared [SessionEvent] set.
 *
 * Dialect differences from OpenAI (per xAI docs, checked 2026-10-18):
 * - Endpoint `wss://api.x.ai/v1/realtime?model=<id>`, plain Bearer
 *   auth with the user's xAI key.
 * - `session.update` is FLAT: no `type: "realtime"`, no
 *   `output_modalities`; `turn_detection` sits at session level.
 * - Input audio rate is configurable — we declare 16 kHz PCM, exactly
 *   what Veyra's mic captures, so no resampling is needed. Output
 *   stays 24 kHz PCM, matching the speaker.
 * - User transcription needs `audio.input.transcription.model` set
 *   to "grok-transcribe". xAI renames the incremental event to
 *   `conversation.item.input_audio_transcription.updated` (cumulative
 *   text); the `.completed` event keeps its OpenAI name and is what
 *   we surface.
 * - Function tools use the identical wire shape as OpenAI's, so the
 *   declarations come from `ToolRegistry.asOpenAiTools`.
 *
 * Known gap: xAI documents no image input for voice sessions, so
 * [sendFrame] sends the same `input_image` item OpenAI accepts and
 * relies on the server ignoring/rejecting it quietly (mid-session
 * errors are logged, not fatal). Vision questions may not work with
 * the Grok provider until xAI documents image support.
 */
class GrokSession(
    private val client: OkHttpClient,
    private val json: Json,
    private val apiKey: String,
    private val model: String,
    private val instructions: String,
    private val tools: List<OpenAiFunctionTool>,
) {

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var ready = false

    private val pendingMessages = ConcurrentLinkedQueue<JsonObject>()

    private val pendingCalls = mutableListOf<FunctionCall>()

    fun connect(): Flow<SessionEvent> = callbackFlow {

        ready = false
        pendingMessages.clear()
        pendingCalls.clear()

        val url = "wss://api.x.ai/v1/realtime?model=$model"

        Log.d(TAG, "Connecting to xAI Realtime (model=$model)")

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
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
     * Send microphone PCM (16 kHz mono, 16-bit LE) — the session
     * declares a 16 kHz input rate, so no resampling is needed.
     */
    fun sendAudio(pcm16k: ByteArray) {
        send(
            buildJsonObject {
                put("type", "input_audio_buffer.append")
                put("audio", pcm16k.b64())
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
     * Forward a camera JPEG as visual context — same item shape as
     * OpenAI. See the class doc: xAI documents no image input for
     * voice sessions, so this is best-effort.
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
     * The first message on the socket: xAI's flat session shape —
     * voice and turn_detection at session level, audio formats nested
     * under `audio.input` / `audio.output`.
     */
    private fun buildSessionUpdate(): JsonObject =
        buildJsonObject {
            put("type", "session.update")
            put(
                "session",
                buildJsonObject {
                    put("instructions", instructions)
                    put("voice", VOICE)
                    put(
                        "turn_detection",
                        buildJsonObject {
                            put("type", "server_vad")
                            put("threshold", 0.5)
                            put("prefix_padding_ms", 300)
                            put("silence_duration_ms", 700)
                        }
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
                                            put("rate", 16000)
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
                }
            )
        }

    /**
     * Parse one server frame into [SessionEvent]s — the same event
     * set as the OpenAI session, plus xAI's renamed transcription
     * event, which we ignore (the `.completed` event carries the
     * final user transcript).
     */
    private fun handleFrame(raw: String): List<SessionEvent> {
        val root = runCatching {
            json.parseToJsonElement(raw).jsonObject
        }.getOrElse { error ->
            Log.w(
                TAG,
                "Could not parse xAI frame: ${error.message}"
            )
            return emptyList()
        }

        val type = root.string("type") ?: return emptyList()

        return when (type) {
            "session.updated" -> {
                Log.i(TAG, "🟢 Grok session.updated received")
                ready = true
                flushPendingMessages()
                listOf(SessionEvent.Ready)
            }

            "response.created" -> {
                pendingCalls.clear()
                emptyList()
            }

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

            "input_audio_buffer.speech_started" ->
                listOf(SessionEvent.Interrupted)

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

            "error" -> {
                val detail = root["error"]
                    ?.jsonObject
                    ?.string("message")
                    ?.takeIf { it.isNotBlank() }
                if (!ready) {
                    Log.e(
                        TAG,
                        "🔴 xAI server error before ready: $detail"
                    )
                    listOf(SessionEvent.ConnectionFailed(detail))
                } else {
                    Log.w(
                        TAG,
                        "⚠️ xAI server error mid-session (ignored): $detail"
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
        const val TAG = "GrokSession"
        const val NORMAL_CLOSURE = 1000

        /**
         * Assistant voice for the Grok provider (xAI built-in).
         * Veyra's voice picker lists Gemini voices, so this stays a
         * constant until a provider-aware voice picker exists.
         */
        const val VOICE = "eve"

        /** xAI's transcription model for user speech. */
        const val TRANSCRIPTION_MODEL = "grok-transcribe"
    }
}
