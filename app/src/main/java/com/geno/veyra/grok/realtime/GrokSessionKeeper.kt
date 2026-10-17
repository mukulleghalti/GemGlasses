package com.geno.veyra.grok.realtime

import android.content.Context
import android.util.Log
import com.geno.veyra.R
import com.geno.veyra.gemini.SessionConfig
import com.geno.veyra.gemini.SessionEvent
import com.geno.veyra.gemini.ToolRegistry
import com.geno.veyra.gemini.VoiceSessionKeeper
import com.geno.veyra.gemini.protocol.FunctionResponse
import com.geno.veyra.settings.GrokKeyRepository
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
 * Owns one xAI Grok Speech to Speech connection at a time — the Grok
 * counterpart to [com.geno.veyra.gemini.SessionKeeper], mirroring
 * [com.geno.veyra.openai.realtime.RealtimeSessionKeeper]:
 *
 * - Auth is the stored API key ([GrokKeyRepository]); no ephemeral
 *   token fetch.
 * - No session-resumption opt-in (xAI offers one via
 *   `resumption.enabled`, deliberately not used in v1), so a dropped
 *   socket reconnects as a fresh session, with backoff.
 * - A missing API key is terminal for the attempt: the UI shows the
 *   reason instead of hanging on "connecting".
 * - Tool declarations reuse the OpenAI wire shape
 *   (`ToolRegistry.asOpenAiTools`) — xAI's function tools are
 *   byte-compatible with it.
 */
@Singleton
class GrokSessionKeeper @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val grokKeyRepository: GrokKeyRepository,
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
    private var current: GrokSession? = null

    private var loop: Job? = null

    /** Start the Grok session manager. */
    override fun start(
        scope: CoroutineScope,
        config: SessionConfig,
    ) {
        if (loop?.isActive == true) {
            Log.d(TAG, "GrokSessionKeeper already running")
            return
        }

        loop = scope.launch {
            runLoop(config)
        }
    }

    /** Stop the Grok session completely. */
    override fun stop() {
        Log.i(TAG, "Stopping GrokSessionKeeper")

        loop?.cancel()
        loop = null

        current?.close()
        current = null
    }

    /** Forward microphone PCM (16 kHz — Grok takes it natively). */
    override fun sendAudio(pcm: ByteArray) {
        current?.sendAudio(pcm)
    }

    /** Forward a camera JPEG (best-effort; see GrokSession's doc). */
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

            val apiKey = grokKeyRepository.getKey()

            if (apiKey.isNullOrBlank()) {
                Log.e(TAG, "🔴 No Grok API key stored")

                _events.emit(
                    SessionEvent.ConnectionFailed(
                        context.getString(R.string.error_grok_key_missing)
                    )
                )
                break
            }

            val session = GrokSession(
                client = client,
                json = json,
                apiKey = apiKey,
                model = config.grokModel,
                instructions = config.systemInstruction,
                tools = toolRegistry.asOpenAiTools(config.toolFlags),
            )

            current = session

            Log.i(TAG, "🟢 GrokSession created")

            var readySeen = false
            var setupFailed = false
            var failureDetail: String? = null

            try {
                coroutineScope {
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

                                    backoffMs = INITIAL_BACKOFF_MS

                                    _events.emit(event)
                                }

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

                                is SessionEvent.Closed -> {
                                    if (!readySeen) {
                                        failureDetail =
                                            event.error?.message
                                                ?: failureDetail
                                    }

                                    if (event.error != null) {
                                        Log.e(
                                            TAG,
                                            "🔴 GrokSession CLOSED: " +
                                                event.error.message,
                                            event.error,
                                        )
                                    } else {
                                        Log.i(
                                            TAG,
                                            "🔴 GrokSession closed cleanly"
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

            if (setupFailed) {
                Log.i(TAG, "Setup failed; not retrying automatically")
                break
            }

            if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                break
            }

            Log.i(
                TAG,
                "WebSocket ended unexpectedly. " +
                    "Reconnecting after ${backoffMs}ms"
            )

            delay(backoffMs)

            backoffMs =
                (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }

        Log.i(TAG, "GrokSessionKeeper loop stopped")
    }

    private companion object {
        const val TAG = "GrokSessionKeeper"
        const val INITIAL_BACKOFF_MS = 500L
        const val MAX_BACKOFF_MS = 8_000L
        const val SETUP_TIMEOUT_MS = 20_000L
    }
}
