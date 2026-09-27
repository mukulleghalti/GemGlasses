package com.geno.veyra.openai.realtime

import android.content.Context
import android.util.Log
import com.geno.veyra.R
import com.geno.veyra.gemini.SessionConfig
import com.geno.veyra.gemini.SessionEvent
import com.geno.veyra.gemini.ToolRegistry
import com.geno.veyra.gemini.VoiceSessionKeeper
import com.geno.veyra.gemini.protocol.FunctionResponse
import com.geno.veyra.settings.OpenAiKeyRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns one OpenAI Realtime connection at a time — the ChatGPT counterpart
 * to [com.geno.veyra.gemini.SessionKeeper].
 *
 * Differences from the Gemini keeper:
 * - Auth is the stored API key ([OpenAiKeyRepository]); there is no
 *   ephemeral-token fetch.
 * - The Realtime API has no session-resumption handles, so a dropped
 *   socket always reconnects as a fresh session (with backoff).
 * - A missing API key is terminal for the attempt: the UI shows the
 *   reason instead of hanging on "connecting".
 *
 * Otherwise the contract is identical: setup watchdog, loud failure on
 * rejected setup, reconnect churn hidden from the app, and the same
 * [SessionEvent] stream.
 */
@Singleton
class RealtimeSessionKeeper @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val openAiKeyRepository: OpenAiKeyRepository,
    private val toolRegistry: ToolRegistry,
    @ApplicationContext private val context: Context,
) : VoiceSessionKeeper {

    private val _events =
        MutableSharedFlow<SessionEvent>(
            extraBufferCapacity = 256,
        )

    override val events: SharedFlow<SessionEvent> =
        _events.asSharedFlow()

    @Volatile
    private var current: RealtimeSession? = null

    private var loop: Job? = null

    /** Start the Realtime session manager. */
    override fun start(
        scope: CoroutineScope,
        config: SessionConfig,
    ) {
        if (loop?.isActive == true) {
            Log.d(TAG, "RealtimeSessionKeeper already running")
            return
        }

        loop = scope.launch {
            runLoop(config)
        }
    }

    /** Stop the Realtime session completely. */
    override fun stop() {
        Log.i(TAG, "Stopping RealtimeSessionKeeper")

        loop?.cancel()
        loop = null

        current?.close()
        current = null
    }

    /**
     * Forward microphone PCM (16 kHz). RealtimeSession upsamples to the
     * 24 kHz the API requires.
     */
    override fun sendAudio(pcm: ByteArray) {
        current?.sendAudio(pcm)
    }

    /** Forward a camera JPEG (injected as an image message). */
    override fun sendFrame(jpeg: ByteArray) {
        current?.sendFrame(jpeg)
    }

    /** Forward function responses. */
    override fun sendToolResponses(responses: List<FunctionResponse>) {
        current?.sendToolResponses(responses)
    }

    /** Forward user text. */
    override fun sendText(text: String) {
        current?.sendText(text)
    }

    private suspend fun runLoop(config: SessionConfig) {
        var backoffMs = INITIAL_BACKOFF_MS

        while (kotlinx.coroutines.currentCoroutineContext().isActive) {

            /*
             * -----------------------------------------------------------
             * API KEY
             * -----------------------------------------------------------
             *
             * Unlike Gemini's ephemeral tokens, the Realtime API takes
             * the long-lived key directly. A missing key is a
             * configuration problem, not a transient failure — report it
             * once and stop instead of retrying.
             */
            val apiKey = openAiKeyRepository.getKey()

            if (apiKey.isNullOrBlank()) {
                Log.e(TAG, "🔴 No OpenAI API key stored")

                _events.emit(
                    SessionEvent.ConnectionFailed(
                        context.getString(R.string.error_openai_key_missing)
                    )
                )
                break
            }

            /*
             * -----------------------------------------------------------
             * CREATE REALTIME SESSION
             * -----------------------------------------------------------
             */
            val session = RealtimeSession(
                client = client,
                json = json,
                apiKey = apiKey,
                model = config.openAiModel,
                instructions = config.systemInstruction,
                tools = toolRegistry.asOpenAiTools(config.toolFlags),
            )

            current = session

            Log.i(TAG, "🟢 RealtimeSession created")

            var readySeen = false
            var setupFailed = false
            var failureDetail: String? = null

            try {
                coroutineScope {
                    /*
                     * Setup watchdog: the server must acknowledge our
                     * session.update (session.updated) within the
                     * timeout, otherwise this attempt is dead. Failing
                     * loudly here keeps the UI from hanging on
                     * "connecting" when the key is invalid or the
                     * model id is wrong.
                     */
                    val watchdog = launch {
                        delay(SETUP_TIMEOUT_MS)
                        if (!readySeen) {
                            setupFailed = true
                            Log.e(
                                TAG,
                                "🔴 Setup timed out after " +
                                    "${SETUP_TIMEOUT_MS}ms without session.updated",
                            )
                            _events.emit(
                                SessionEvent.ConnectionFailed(failureDetail)
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
                            Log.d(TAG, "Event received: $event")

                            when (event) {
                                is SessionEvent.Ready -> {
                                    readySeen = true
                                    watchdog.cancel()

                                    Log.i(
                                        TAG,
                                        "🟢 Session Event: Ready"
                                    )

                                    /*
                                     * Successful connection resets the
                                     * exponential backoff.
                                     */
                                    backoffMs = INITIAL_BACKOFF_MS

                                    _events.emit(event)
                                }

                                /*
                                 * The server rejected setup (bad key,
                                 * unknown model, ...). Terminal for this
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
                                 * Don't forward Closed: most closures
                                 * here are part of an automatic
                                 * reconnect. Remember the reason in case
                                 * the socket died before setup.
                                 */
                                is SessionEvent.Closed -> {
                                    if (!readySeen) {
                                        failureDetail =
                                            event.error?.message
                                                ?: failureDetail
                                    }

                                    if (event.error != null) {
                                        Log.e(
                                            TAG,
                                            "🔴 RealtimeSession CLOSED: " +
                                                event.error.message,
                                            event.error,
                                        )
                                    } else {
                                        Log.i(
                                            TAG,
                                            "🔴 RealtimeSession closed cleanly"
                                        )
                                    }
                                }

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
                 * The socket died with an error before setup completed.
                 * Treat it as a setup failure rather than a mid-session
                 * drop, but never report it when the keeper itself was
                 * stopped.
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
                if (current === session) {
                    current = null
                }
                session.close()
            }

            /*
             * A failed setup is terminal for this start(): the user saw
             * the reason, and silent retry loops would just burn quota.
             * The UI offers an explicit retry.
             */
            if (setupFailed) {
                Log.i(TAG, "Setup failed; not retrying automatically")
                break
            }

            if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                break
            }

            /*
             * -----------------------------------------------------------
             * RECONNECT
             * -----------------------------------------------------------
             *
             * The Realtime API has no resumption handles, so every
             * reconnect starts a fresh session.
             */
            Log.i(
                TAG,
                "WebSocket ended unexpectedly. " +
                    "Reconnecting after ${backoffMs}ms"
            )

            delay(backoffMs)

            backoffMs =
                (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }

        Log.i(TAG, "RealtimeSessionKeeper loop stopped")
    }

    private companion object {
        const val TAG = "RealtimeSessionKeeper"
        const val INITIAL_BACKOFF_MS = 500L
        const val MAX_BACKOFF_MS = 8_000L

        /**
         * How long to wait for session.updated before declaring the
         * attempt dead.
         */
        const val SETUP_TIMEOUT_MS = 20_000L
    }
}
