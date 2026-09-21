package com.lpecom.gemglasses.gemini

import android.util.Base64
import android.util.Log
import com.lpecom.gemglasses.gemini.protocol.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

class LiveSession(
    private val client: OkHttpClient,
    private val json: Json,
    private val ephemeralToken: String,
    private val systemInstruction: String,
    private val voiceName: String,
    private val languageCode: String,
    private val liveTools: List<Tool>,
    private val resumeHandle: String?,
) {
    @Volatile private var socket: WebSocket? = null

    fun connect(): Flow<SessionEvent> = callbackFlow {
        val url = "${Models.LIVE_WS_HOST}?key=$ephemeralToken"   // ← Changed to ?key=
        val request = Request.Builder().url(url).build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                val setupMessage = buildSetup()
                val jsonString = json.encodeToString(ClientMessage.serializer(), setupMessage)
                Log.d(TAG, ">>> Sending setup: $jsonString")
                webSocket.send(jsonString)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val raw = bytes.utf8()
                Log.d(TAG, "RAW ← (bytes) $raw")
                handleFrame(raw)?.let { trySend(it) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "RAW ← $text")
                handleFrame(text)?.let { trySend(it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "onClosing: code=$code, reason=$reason")
                webSocket.close(NORMAL_CLOSURE, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "onClosed: code=$code, reason=$reason")
                trySend(SessionEvent.Closed(null))
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "onFailure: ${t.message}", t)
                trySend(SessionEvent.Closed(t))
                close()
            }
        }

        val ws = client.newWebSocket(request, listener)
        socket = ws

        awaitClose {
            ws.close(NORMAL_CLOSURE, "client closing")
            socket = null
        }
    }

    fun sendAudio(pcm: ByteArray) {
        val msg = ClientMessage(
            realtimeInput = RealtimeInput(
                audio = Blob(mimeType = "audio/pcm;rate=16000", data = pcm.b64()),
            ),
        )
        send(msg)
    }

    fun sendFrame(jpeg: ByteArray) {
        val msg = ClientMessage(
            realtimeInput = RealtimeInput(
                video = Blob(mimeType = "image/jpeg", data = jpeg.b64()),
            ),
        )
        send(msg)
    }

    fun sendToolResponses(responses: List<FunctionResponse>) {
        send(ClientMessage(toolResponse = ToolResponse(functionResponses = responses)))
    }

    fun sendText(text: String) {
        send(
            ClientMessage(
                clientContent = ClientContent(
                    turns = listOf(Content(role = "user", parts = listOf(Part(text = text)))),
                    turnComplete = true,
                ),
            ),
        )
    }

    fun close() {
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
    }

    private fun send(message: ClientMessage) {
        val ws = socket ?: return
        ws.send(json.encodeToString(ClientMessage.serializer(), message))
    }

    private fun buildSetup() = ClientMessage(
        setup = Setup(
            model = "models/${Models.GEMINI_LIVE_MODEL}",
            generationConfig = GenerationConfig(
                responseModalities = listOf("AUDIO"),
                speechConfig = SpeechConfig(
                    voiceConfig = VoiceConfig(PrebuiltVoiceConfig(voiceName)),
                    languageCode = languageCode,
                ),
            ),
            systemInstruction = Content(parts = listOf(Part(text = systemInstruction))),
            tools = liveTools,
            realtimeInputConfig = RealtimeInputConfig(activityHandling = "START_OF_ACTIVITY_INTERRUPTS"),
            inputAudioTranscription = EMPTY_OBJECT,
            outputAudioTranscription = EMPTY_OBJECT,
            sessionResumption = SessionResumptionConfig(handle = resumeHandle),
        ),
    )

    private fun handleFrame(raw: String): SessionEvent? {
        val msg = runCatching { json.decodeFromString(ServerMessage.serializer(), raw) }
            .getOrElse {
                Log.w(TAG, "unparsed frame: ${raw.take(120)}")
                return null
            }

        msg.setupComplete?.let { return SessionEvent.Ready }
        msg.goAway?.let { return SessionEvent.GoingAway(it.timeLeft) }
        msg.toolCallCancellation?.let { return SessionEvent.ToolCancelled(it.ids) }
        msg.toolCall?.let {
            if (it.functionCalls.isNotEmpty()) return SessionEvent.ToolInvocation(it.functionCalls)
        }
        msg.sessionResumptionUpdate?.let { update ->
            if (update.resumable && update.newHandle != null) {
                resumeCallback?.invoke(update.newHandle)
            }
        }

        msg.serverContent?.let { sc ->
            sc.interrupted?.takeIf { it }?.let { return SessionEvent.Interrupted }
            sc.inputTranscription?.text?.let { return SessionEvent.Transcript(it, fromUser = true) }
            sc.outputTranscription?.text?.let { return SessionEvent.Transcript(it, fromUser = false) }
            sc.modelTurn?.parts?.firstNotNullOfOrNull { it.inlineData }?.let { blob ->
                return SessionEvent.AudioChunk(Base64.decode(blob.data, Base64.NO_WRAP))
            }
            sc.turnComplete?.takeIf { it }?.let { return SessionEvent.TurnComplete }
        }
        return null
    }

    @Volatile var resumeCallback: ((String) -> Unit)? = null

    private fun ByteArray.b64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private companion object {
        const val TAG = "LiveSession"
        const val NORMAL_CLOSURE = 1000
        val EMPTY_OBJECT = kotlinx.serialization.json.JsonObject(emptyMap())
    }
}
