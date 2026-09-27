package com.geno.veyra.gemini

import android.util.Log
import com.geno.veyra.gemini.protocol.FunctionResponse
import com.geno.veyra.openai.DEFAULT_OPENAI_VOICE_MODEL
import com.geno.veyra.settings.DEFAULT_LIVE_MODEL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Immutable knobs for a Live session, resolved from Settings at start time. */
data class SessionConfig(
    val systemInstruction: String,
    val voiceName: String,
    val languageCode: String,
    /** Live model id chosen in AI Settings (Gemini Model). */
    val model: String = DEFAULT_LIVE_MODEL,
    /**
     * Realtime model id chosen in AI Settings (ChatGPT Model). Only read
     * by the OpenAI keeper; the Gemini keeper ignores it.
     */
    val openAiModel: String = DEFAULT_OPENAI_VOICE_MODEL,
    /**
     * Whether talking over the assistant cuts it off. When false the
     * session is created with activityHandling=NO_INTERRUPTION, so the
     * server never kills the model's turn on voice activity (its VAD
     * was firing on phone-speaker echo and making the model stutter).
     */
    val bargeInEnabled: Boolean = true,
    /**
     * Toggle-gated tool capabilities for the session (web search,
     * QR/barcode scan, OCR). Each mirrors an AI Settings switch and
     * controls which tools are declared in the Live setup message.
     */
    val toolFlags: ToolFlags = ToolFlags(),
)

/**
 * Owns one Gemini Live connection at a time.
 *
 * Responsibilities:
 * - Obtains a fresh ephemeral token from TokenProvider.
 * - Creates LiveSession using that token.
 * - Keeps the latest Gemini session resumption handle.
 * - Reconnects after GoAway / socket failures.
 * - Hides reconnect churn from the rest of the application.
 */
@Singleton
class SessionKeeper @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val tokenProvider: TokenProvider,
    private val toolRegistry: ToolRegistry,
) : VoiceSessionKeeper {

    private val _events =
        MutableSharedFlow<SessionEvent>(
            extraBufferCapacity = 256,
        )

    override val events: SharedFlow<SessionEvent> =
        _events.asSharedFlow()

    @Volatile
    private var current: LiveSession? = null

    /**
     * Latest Gemini session resumption handle.
     *
     * Gemini periodically sends a new one. LiveSession updates this
     * through resumeCallback.
     */
    @Volatile
    private var resumeHandle: String? = null

    private var loop: Job? = null

    /**
     * Start the Live session manager.
     */
    override fun start(
        scope: CoroutineScope,
        config: SessionConfig,
    ) {
        if (loop?.isActive == true) {
            Log.d(
                TAG,
                "SessionKeeper already running"
            )
            return
        }

        /*
         * A completely new start should begin without an old handle.
         */
        resumeHandle = null

        loop = scope.launch {
            runLoop(config)
        }
    }

    /**
     * Stop the Live session completely.
     */
    override fun stop() {

        Log.i(
            TAG,
            "Stopping SessionKeeper"
        )

        loop?.cancel()
        loop = null

        current?.close()
        current = null

        /*
         * A deliberate stop means the next start is a fresh session.
         */
        resumeHandle = null
    }

    /**
     * Forward microphone PCM.
     *
     * LiveSession itself queues the data until setupComplete.
     */
    override fun sendAudio(
        pcm: ByteArray,
    ) {
        current?.sendAudio(pcm)
    }

    /**
     * Forward camera JPEG.
     *
     * LiveSession itself queues the data until setupComplete.
     */
    override fun sendFrame(
        jpeg: ByteArray,
    ) {
        current?.sendFrame(jpeg)
    }

    /**
     * Forward function responses.
     */
    override fun sendToolResponses(
        responses: List<FunctionResponse>,
    ) {
        current?.sendToolResponses(responses)
    }

    /**
     * Forward user text.
     */
    override fun sendText(
        text: String,
    ) {
        current?.sendText(text)
    }

    private suspend fun runLoop(
        config: SessionConfig,
    ) {

        var backoffMs = INITIAL_BACKOFF_MS

        while (kotlinx.coroutines.currentCoroutineContext().isActive) {

            /*
             * -----------------------------------------------------------
             * GET EPHEMERAL TOKEN
             * -----------------------------------------------------------
             */
            val token = try {

                Log.d(
                    TAG,
                    "Requesting fresh Gemini ephemeral token..."
                )

                tokenProvider.fetchEphemeralToken()

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "🔴 Failed to obtain ephemeral token",
                    e,
                )

                _events.emit(
                    SessionEvent.Closed(e)
                )

                delay(backoffMs)

                backoffMs =
                    (backoffMs * 2)
                        .coerceAtMost(MAX_BACKOFF_MS)

                continue
            }

            if (token.isBlank()) {

                val error =
                    IllegalStateException(
                        "TokenProvider returned an empty ephemeral token"
                    )

                Log.e(
                    TAG,
                    "🔴 $error"
                )

                _events.emit(
                    SessionEvent.Closed(error)
                )

                delay(backoffMs)

                backoffMs =
                    (backoffMs * 2)
                        .coerceAtMost(MAX_BACKOFF_MS)

                continue
            }

            /*
             * -----------------------------------------------------------
             * CREATE LIVE SESSION
             * -----------------------------------------------------------
             */
            val session = LiveSession(
                client = client,
                json = json,
                ephemeralToken = token,
                systemInstruction = config.systemInstruction,
                voiceName = config.voiceName,
                languageCode = config.languageCode,
                bargeInEnabled = config.bargeInEnabled,
                liveTools = toolRegistry.asLiveTools(config.toolFlags),
                resumeHandle = resumeHandle,
                modelName = config.model,
            )

            /*
             * Every time Gemini sends a new resumption handle,
             * remember it for the next connection.
             */
            session.resumeCallback = { handle ->

                if (handle.isNotBlank()) {

                    resumeHandle = handle

                    Log.d(
                        TAG,
                        "Updated session resumption handle"
                    )
                }
            }

            current = session

            Log.i(
                TAG,
                "🟢 LiveSession created. " +
                    "resumeHandlePresent=${!resumeHandle.isNullOrBlank()}"
            )

            var reconnectNow = false
            var readySeen = false
            var setupFailed = false
            var failureDetail: String? = null

            try {

                coroutineScope {

                    /*
                     * Setup watchdog: the server must acknowledge setup
                     * (Ready) within the timeout, otherwise this attempt
                     * is dead. Failing loudly here is what keeps the UI
                     * from hanging on "connecting" forever when the
                     * server silently rejects setup (quota exhausted,
                     * invalid key, ...).
                     */
                    val watchdog = launch {
                        delay(SETUP_TIMEOUT_MS)
                        if (!readySeen) {
                            setupFailed = true
                            Log.e(
                                TAG,
                                "🔴 Setup timed out after " +
                                    "${SETUP_TIMEOUT_MS}ms without Ready",
                            )
                            _events.emit(
                                SessionEvent.ConnectionFailed(
                                    failureDetail
                                )
                            )
                            session.close()
                        }
                    }

                    try {

                        /*
                         * collect() remains active while the WebSocket
                         * is alive.
                         */
                        session.connect().collect { event ->

                            Log.d(
                                TAG,
                                "Event received: $event"
                            )

                            when (event) {

                                /*
                                 * ------------------------------------------------
                                 * READY
                                 * ------------------------------------------------
                                 */
                                is SessionEvent.Ready -> {

                                    readySeen = true
                                    watchdog.cancel()

                                    Log.i(
                                        TAG,
                                        "🟢 Session Event: Ready"
                                    )

                                    /*
                                     * Successful connection means reset the
                                     * exponential backoff.
                                     */
                                    backoffMs =
                                        INITIAL_BACKOFF_MS

                                    _events.emit(event)
                                }

                                /*
                                 * ------------------------------------------------
                                 * GOING AWAY
                                 * ------------------------------------------------
                                 *
                                 * Gemini is telling us the current connection
                                 * will be terminated.
                                 *
                                 * Because session resumption is enabled, reconnect
                                 * using the latest handle.
                                 */
                                is SessionEvent.GoingAway -> {

                                    Log.w(
                                        TAG,
                                        "⚠️ Session Event: GoingAway " +
                                            "timeLeft=${event.timeLeft}"
                                    )

                                    reconnectNow = true

                                    _events.emit(event)

                                    /*
                                     * Closing the socket causes connect() to finish,
                                     * after which runLoop creates the next session.
                                     */
                                    session.close()
                                }

                                /*
                                 * ------------------------------------------------
                                 * CLOSED
                                 * ------------------------------------------------
                                 *
                                 * We don't immediately forward Closed to the UI
                                 * because most closures here are part of an
                                 * automatic reconnect.
                                 */
                                is SessionEvent.Closed -> {

                                    if (!readySeen) {
                                        /*
                                         * The socket died before setup
                                         * completed. Keep the reason for
                                         * the failure message.
                                         */
                                        failureDetail =
                                            event.error?.message
                                                ?: failureDetail
                                    }

                                    if (event.error != null) {

                                        Log.e(
                                            TAG,
                                            "🔴 LiveSession CLOSED: " +
                                                event.error.message,
                                            event.error,
                                        )

                                    } else {

                                        Log.i(
                                            TAG,
                                            "🔴 LiveSession closed cleanly"
                                        )
                                    }

                                    /*
                                     * Do not emit this here.
                                     *
                                     * The SessionKeeper owns the reconnect logic.
                                     */
                                }

                                /*
                                 * ------------------------------------------------
                                 * CONNECTION FAILED
                                 * ------------------------------------------------
                                 *
                                 * The server explicitly rejected setup
                                 * (error payload instead of
                                 * setupComplete). Terminal for this
                                 * attempt: surface it and stop retrying
                                 * rather than looping silently.
                                 */
                                is SessionEvent.ConnectionFailed -> {

                                    setupFailed = true

                                    failureDetail =
                                        event.detail ?: failureDetail

                                    Log.e(
                                        TAG,
                                        "🔴 Server rejected setup: " +
                                            failureDetail,
                                    )

                                    _events.emit(event)

                                    session.close()
                                }

                                /*
                                 * ------------------------------------------------
                                 * ALL NORMAL EVENTS
                                 * ------------------------------------------------
                                 */
                                else -> {
                                    _events.emit(event)
                                }
                            }
                        }

                    } finally {
                        watchdog.cancel()
                    }
                }

            } catch (e: Exception) {

                /*
                 * Defensive protection around the Flow collection.
                 * Normally WebSocket errors arrive as SessionEvent.Closed.
                 */
                Log.e(
                    TAG,
                    "Exception while collecting LiveSession",
                    e,
                )

                /*
                 * The socket died with an error before setup completed
                 * (this is where quota rejections land: the server kills
                 * the socket instead of sending setupComplete). Treat it
                 * as a setup failure rather than a mid-session drop, but
                 * never report it when the keeper itself was stopped.
                 */
                if (
                    !readySeen &&
                    !setupFailed &&
                    kotlinx.coroutines.currentCoroutineContext().isActive
                ) {
                    setupFailed = true
                    failureDetail = e.message ?: failureDetail
                    _events.emit(
                        SessionEvent.ConnectionFailed(failureDetail)
                    )
                }

            } finally {

                /*
                 * Only clear current if it still points to this session.
                 */
                if (current === session) {
                    current = null
                }

                /*
                 * Make sure the old socket cannot remain alive.
                 */
                session.close()
            }

            /*
             * A failed setup is terminal for this start(): the user saw
             * the reason, and silent retry loops would just burn quota.
             * The UI offers an explicit retry.
             */
            if (setupFailed) {
                Log.i(
                    TAG,
                    "Setup failed; not retrying automatically"
                )
                break
            }

            /*
             * If SessionKeeper itself was cancelled, stop here.
             */
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                break
            }

            /*
             * -----------------------------------------------------------
             * RECONNECT
             * -----------------------------------------------------------
             *
             * IMPORTANT:
             *
             * We intentionally reconnect even when resumeHandle is null.
             *
             * Previously the code had:
             *
             *   if (!reconnectNow && resumeHandle == null) ...
             *
             * which could leave the manager sitting in an awkward state.
             *
             * A fresh connection is still useful even if Gemini hasn't
             * supplied a resumption handle yet.
             */
            if (reconnectNow) {

                Log.i(
                    TAG,
                    "Reconnecting immediately after GoAway"
                )

            } else {

                Log.i(
                    TAG,
                    "WebSocket ended unexpectedly. " +
                        "Reconnecting after ${backoffMs}ms"
                )

                delay(backoffMs)

                backoffMs =
                    (backoffMs * 2)
                        .coerceAtMost(MAX_BACKOFF_MS)
            }
        }

        Log.i(
            TAG,
            "SessionKeeper loop stopped"
        )
    }

    private companion object {

        const val TAG = "SessionKeeper"

        const val INITIAL_BACKOFF_MS = 500L

        const val MAX_BACKOFF_MS = 8_000L

        /**
         * How long to wait for setupComplete (Ready) before declaring
         * the attempt dead. Without this, a silently rejected setup
         * (quota, invalid key) leaves the UI on "connecting" forever.
         */
        const val SETUP_TIMEOUT_MS = 20_000L
    }
}
