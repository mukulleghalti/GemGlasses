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
import java.util.concurrent.ConcurrentLinkedQueue

class LiveSession(
    private val client: OkHttpClient,
    private val json: Json,
    private val ephemeralToken: String,
    private val systemInstruction: String,
    private val voiceName: String,
    private val languageCode: String,
    private val bargeInEnabled: Boolean = true,
    private val liveTools: List<Tool>,
    private val resumeHandle: String?,
) {

    @Volatile
    private var socket: WebSocket? = null

    /**
     * Gemini does not allow realtimeInput/clientContent/toolResponse
     * messages until setupComplete has been received.
     *
     * Camera/microphone callbacks can start almost immediately after the
     * socket opens, so we queue those messages until Gemini confirms setup.
     */
    @Volatile
    private var setupComplete = false

    private val pendingMessages = ConcurrentLinkedQueue<ClientMessage>()

    /**
     * Called whenever Gemini gives us a new session resumption handle.
     */
    @Volatile
    var resumeCallback: ((String) -> Unit)? = null

    fun connect(): Flow<SessionEvent> = callbackFlow {

        setupComplete = false
        pendingMessages.clear()

        /*
         * IMPORTANT:
         *
         * Ephemeral tokens MUST use the constrained endpoint and the
         * access_token query parameter.
         *
         * Do NOT use:
         *   ?key=BuildConfig.GEMINI_API_KEY
         *
         * Google documents this endpoint specifically for ephemeral tokens.
         */
        val url =
            "wss://generativelanguage.googleapis.com/" +
                "ws/google.ai.generativelanguage.v1beta." +
                "GenerativeService.BidiGenerateContentConstrained" +
                "?access_token=${ephemeralToken}"

        Log.d(
            TAG,
            "Connecting to Gemini Live using ephemeral token"
        )

        val request = Request.Builder()
            .url(url)
            .build()

        val listener = object : WebSocketListener() {

            override fun onOpen(
                webSocket: WebSocket,
                response: Response,
            ) {
                socket = webSocket

                Log.i(
                    TAG,
                    "WebSocket opened. Sending SETUP as first message."
                )

                /*
                 * This MUST be the first message sent on the socket.
                 */
                val setupMessage = buildSetup()

                val jsonString = runCatching {
                    json.encodeToString(
                        ClientMessage.serializer(),
                        setupMessage,
                    )
                }.getOrElse { error ->
                    Log.e(
                        TAG,
                        "Failed to encode setup message",
                        error,
                    )

                    webSocket.close(
                        NORMAL_CLOSURE,
                        "setup encoding failed",
                    )

                    return
                }

                Log.d(
                    TAG,
                    ">>> RAW SETUP JSON:\n$jsonString"
                )

                val sent = webSocket.send(jsonString)

                if (sent) {
                    Log.i(
                        TAG,
                        ">>> Setup message sent successfully " +
                            "(activityHandling=" +
                            if (bargeInEnabled) {
                                "default"
                            } else {
                                "NO_INTERRUPTION"
                            } +
                            ")"
                    )
                } else {
                    Log.e(
                        TAG,
                        ">>> Failed to send setup message"
                    )
                }
            }

            override fun onMessage(
                webSocket: WebSocket,
                bytes: ByteString,
            ) {
                handleFrame(bytes.utf8())?.let { event ->
                    trySend(event)
                }
            }

            override fun onMessage(
                webSocket: WebSocket,
                text: String,
            ) {
                handleFrame(text)?.let { event ->
                    trySend(event)
                }
            }

            override fun onClosing(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) {
                Log.d(
                    TAG,
                    "onClosing: $code - $reason"
                )

                /*
                 * Don't call close() here. OkHttp is already closing.
                 */
                webSocket.close(
                    NORMAL_CLOSURE,
                    null,
                )
            }

            override fun onClosed(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) {
                Log.d(
                    TAG,
                    "onClosed: $code - $reason"
                )

                socket = null
                setupComplete = false

                trySend(
                    SessionEvent.Closed(null)
                )

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
                setupComplete = false

                trySend(
                    SessionEvent.Closed(t)
                )

                close()
            }
        }

        val ws = client.newWebSocket(
            request,
            listener,
        )

        socket = ws

        awaitClose {
            Log.d(
                TAG,
                "callbackFlow closed; closing WebSocket"
            )

            setupComplete = false
            pendingMessages.clear()

            ws.close(
                NORMAL_CLOSURE,
                "client closing",
            )

            socket = null
        }
    }

    /**
     * Send PCM 16 kHz mono audio.
     */
    fun sendAudio(pcm: ByteArray) {
        val msg = ClientMessage(
            realtimeInput = RealtimeInput(
                audio = Blob(
                    mimeType = "audio/pcm;rate=16000",
                    data = pcm.b64(),
                ),
            ),
        )

        send(msg)
    }

    /**
     * Send a JPEG camera frame.
     */
    fun sendFrame(jpeg: ByteArray) {
        val msg = ClientMessage(
            realtimeInput = RealtimeInput(
                video = Blob(
                    mimeType = "image/jpeg",
                    data = jpeg.b64(),
                ),
            ),
        )

        send(msg)
    }

    /**
     * Send function/tool results back to Gemini.
     */
    fun sendToolResponses(
        responses: List<FunctionResponse>,
    ) {
        val msg = ClientMessage(
            toolResponse = ToolResponse(
                functionResponses = responses,
            ),
        )

        send(msg)
    }

    /**
     * Send a complete user text turn.
     */
    fun sendText(text: String) {
        val msg = ClientMessage(
            clientContent = ClientContent(
                turns = listOf(
                    Content(
                        role = "user",
                        parts = listOf(
                            Part(text = text)
                        ),
                    )
                ),
                turnComplete = true,
            ),
        )

        send(msg)
    }

    /**
     * Close the current WebSocket.
     */
    fun close() {
        setupComplete = false
        pendingMessages.clear()

        socket?.close(
            NORMAL_CLOSURE,
            "client closing",
        )

        socket = null
    }

    /**
     * Sends a message immediately if Gemini has completed setup.
     *
     * Otherwise it is queued until setupComplete arrives.
     *
     * This prevents:
     *
     * 1007 - First message in the stream must be a setup message
     *
     * and the related race where microphone/camera data arrives before
     * Gemini has acknowledged the setup.
     */
    private fun send(message: ClientMessage) {

        /*
         * If setup isn't complete yet, queue it.
         */
        if (!setupComplete) {
            pendingMessages.add(message)

            Log.d(
                TAG,
                "Message queued because setupComplete=false. " +
                    "Queue size=${pendingMessages.size}"
            )

            /*
             * There is a small race where setupComplete could become true
             * immediately after the check above. Try flushing here.
             */
            flushPendingMessages()

            return
        }

        sendImmediately(message)
    }

    /**
     * Actually sends a message over the WebSocket.
     */
    private fun sendImmediately(
        message: ClientMessage,
    ) {
        val ws = socket

        if (ws == null) {
            Log.w(
                TAG,
                "Cannot send message: WebSocket is null"
            )
            return
        }

        val jsonString = runCatching {
            json.encodeToString(
                ClientMessage.serializer(),
                message,
            )
        }.getOrElse { error ->
            Log.e(
                TAG,
                "Failed to encode outgoing message",
                error,
            )
            return
        }

        val sent = ws.send(jsonString)

        if (!sent) {
            Log.w(
                TAG,
                "WebSocket.send() returned false"
            )
        }
    }

    /**
     * Flush messages that accumulated while Gemini was processing setup.
     */
    private fun flushPendingMessages() {

        if (!setupComplete) {
            return
        }

        while (true) {
            val message = pendingMessages.poll()
                ?: break

            sendImmediately(message)
        }

        Log.d(
            TAG,
            "Pending message queue flushed"
        )
    }

    /**
     * Build the initial Gemini Live setup message.
     *
     * This is always the FIRST WebSocket message.
     */
    private fun buildSetup(): ClientMessage {

        val resumptionConfig = SessionResumptionConfig(
            handle = resumeHandle,
        )

        return ClientMessage(
            setup = Setup(
                /*
                 * Current Gemini 3.8 Live model.
                 */
                model = "models/gemini-3.8-live",

                generationConfig = GenerationConfig(
                    responseModalities = listOf("AUDIO"),

                    speechConfig = SpeechConfig(
                        voiceConfig = VoiceConfig(
                            prebuiltVoiceConfig =
                                PrebuiltVoiceConfig(
                                    voiceName = voiceName,
                                ),
                        ),
                        languageCode = languageCode,
                    ),
                ),

                systemInstruction = Content(
                    parts = listOf(
                        Part(
                            text = systemInstruction,
                        )
                    ),
                ),

                tools = liveTools,

                /*
                 * When barge-in is off the server must not cut the
                 * model's turn when its voice-activity detector fires:
                 * phone-speaker echo was transcribed as user speech,
                 * interrupting the model mid-sentence (choppy audio)
                 * and making it answer its own echo. NO_INTERRUPTION
                 * lets the model finish; null keeps the default
                 * interrupt behavior for barge-in mode.
                 */
                realtimeInputConfig =
                    if (bargeInEnabled) {
                        null
                    } else {
                        RealtimeInputConfig(
                            activityHandling = "NO_INTERRUPTION",
                        )
                    },

                /*
                 * Enable session resumption.
                 *
                 * If resumeHandle == null:
                 *     { "sessionResumption": {} }
                 *
                 * If resumeHandle != null:
                 *     { "sessionResumption": { "handle": "..." } }
                 */
                sessionResumption = resumptionConfig,
            ),
        )
    }

    /**
     * Parse a Gemini server frame.
     */
    private fun handleFrame(
        raw: String,
    ): SessionEvent? {

        val msg = runCatching {
            json.decodeFromString(
                ServerMessage.serializer(),
                raw,
            )
        }.getOrElse { error ->

            Log.w(
                TAG,
                "Could not parse server message: ${error.message}\n$raw"
            )

            return null
        }

        /*
         * ---------------------------------------------------------------
         * SESSION RESUMPTION
         * ---------------------------------------------------------------
         *
         * Gemini can periodically send a new resumption handle.
         *
         * Always keep the newest handle.
         */
        msg.sessionResumptionUpdate?.let { update ->

            Log.d(
                TAG,
                "Session resumption update: " +
                    "resumable=${update.resumable}, " +
                    "handlePresent=${!update.newHandle.isNullOrBlank()}"
            )

            update.newHandle
                ?.takeIf { it.isNotBlank() }
                ?.let { newHandle ->
                    resumeCallback?.invoke(newHandle)

                    Log.d(
                        TAG,
                        "Saved new session resumption handle"
                    )
                }
        }

        /*
         * ---------------------------------------------------------------
         * SETUP COMPLETE
         * ---------------------------------------------------------------
         */
        msg.setupComplete?.let {

            Log.i(
                TAG,
                "🟢 Gemini setupComplete received"
            )

            setupComplete = true

            /*
             * Camera/microphone may already have queued data.
             * Gemini is now ready for it.
             */
            flushPendingMessages()

            return SessionEvent.Ready
        }

        /*
         * ---------------------------------------------------------------
         * GO AWAY
         * ---------------------------------------------------------------
         */
        msg.goAway?.let { goAway ->

            Log.w(
                TAG,
                "⚠️ Gemini GoAway received. " +
                    "timeLeft=${goAway.timeLeft}"
            )

            return SessionEvent.GoingAway(
                goAway.timeLeft
            )
        }

        /*
         * ---------------------------------------------------------------
         * TOOL CALL
         * ---------------------------------------------------------------
         */
        msg.toolCall?.let { toolCall ->

            if (toolCall.functionCalls.isNotEmpty()) {
                return SessionEvent.ToolInvocation(
                    toolCall.functionCalls
                )
            }
        }

        /*
         * ---------------------------------------------------------------
         * SERVER CONTENT
         * ---------------------------------------------------------------
         */
        msg.serverContent?.let { sc ->

            /*
             * User interrupted Gemini.
             */
            sc.interrupted
                ?.takeIf { it }
                ?.let {
                    return SessionEvent.Interrupted
                }

            /*
             * User speech transcription.
             */
            sc.inputTranscription
                ?.text
                ?.takeIf { it.isNotBlank() }
                ?.let { text ->
                    return SessionEvent.Transcript(
                        text = text,
                        fromUser = true,
                    )
                }

            /*
             * Gemini output transcription.
             */
            sc.outputTranscription
                ?.text
                ?.takeIf { it.isNotBlank() }
                ?.let { text ->
                    return SessionEvent.Transcript(
                        text = text,
                        fromUser = false,
                    )
                }

            /*
             * Gemini audio output.
             */
            sc.modelTurn
                ?.parts
                ?.firstNotNullOfOrNull { part ->
                    part.inlineData
                }
                ?.let { blob ->

                    return SessionEvent.AudioChunk(
                        Base64.decode(
                            blob.data,
                            Base64.NO_WRAP,
                        )
                    )
                }

            /*
             * End of model turn.
             */
            sc.turnComplete
                ?.takeIf { it }
                ?.let {
                    return SessionEvent.TurnComplete
                }
        }

        return null
    }

    private fun ByteArray.b64(): String =
        Base64.encodeToString(
            this,
            Base64.NO_WRAP,
        )

    private companion object {

        const val TAG = "LiveSession"

        const val NORMAL_CLOSURE = 1000
    }
}
