package com.lpecom.gemglasses.gemini

import android.util.Log
import com.lpecom.gemglasses.gemini.protocol.FunctionResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
)

/**
 * Keeps a Gemini Live session alive across the API's time limits and transient
 * drops. It owns exactly one [LiveSession] at a time, stores the latest
 * resumption handle, and transparently reconnects on `goAway` or socket
 * failure so the user never hears a gap.
 *
 * Consumers observe [events] and call [sendAudio]/[sendFrame]/[sendToolResponses];
 * they never see the reconnect churn.
 */
@Singleton
class SessionKeeper @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val tokenProvider: TokenProvider,
    private val toolRegistry: ToolRegistry,
) {
    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    @Volatile private var current: LiveSession? = null
    @Volatile private var resumeHandle: String? = null
    private var loop: Job? = null

    fun start(scope: CoroutineScope, config: SessionConfig) {
        if (loop?.isActive == true) return
        resumeHandle = null
        loop = scope.launch { runLoop(config) }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        current?.close()
        current = null
    }

    fun sendAudio(pcm: ByteArray) = current?.sendAudio(pcm) ?: Unit
    fun sendFrame(jpeg: ByteArray) = current?.sendFrame(jpeg) ?: Unit
    fun sendToolResponses(responses: List<FunctionResponse>) =
        current?.sendToolResponses(responses) ?: Unit

    private suspend fun runLoop(config: SessionConfig) {
        var backoffMs = INITIAL_BACKOFF_MS
        val scope = requireActiveScope()

        while (scope.isActive) {
            val session = try {
                val token = tokenProvider.fetchEphemeralToken()
                Log.d(TAG, "Token acquired successfully. Creating LiveSession...")
                
                LiveSession(
                    client = client,
                    json = json,
                    ephemeralToken = token,
                    systemInstruction = config.systemInstruction,
                    voiceName = config.voiceName,
                    languageCode = config.languageCode,
                    liveTools = toolRegistry.asLiveTools(),
                    resumeHandle = resumeHandle,
                ).also { it.resumeCallback = { handle -> resumeHandle = handle } }
            } catch (e: Exception) {
                Log.e(TAG, "🔴 LiveSession SETUP FAILED: ${e.message}", e)
                _events.emit(SessionEvent.Closed(e))
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                continue
            }

            current = session
            var reconnectNow = false

            session.connect().collect { event ->
                Log.d(TAG, "Event received: $event")
                when (event) {
                    is SessionEvent.Ready -> {
                        Log.i(TAG, "🟢 Session Event: Ready")
                        backoffMs = INITIAL_BACKOFF_MS
                    }
                    is SessionEvent.GoingAway -> {
                        Log.w(TAG, "⚠️ Session Event: GoingAway")
                        // Reconnect proactively before the server drops us.
                        reconnectNow = true
                        session.close()
                    }
                    is SessionEvent.Closed -> {
                        Log.e(TAG, "🔴 LiveSession CLOSED with cause: ${event.cause?.message}", event.cause)
                    }
                    else -> Unit
                }
                // Never forward the raw Closed of a resumable reconnect as a
                // hard close to the UI; only surface Closed when we truly stop.
                if (event !is SessionEvent.Closed) _events.emit(event)
            }

            current = null
            if (!scope.isActive) break

            if (!reconnectNow && resumeHandle == null) {
                // No handle to resume with and not a planned reconnect: back off.
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    // The loop always runs inside the scope passed to start(); this reifies it.
    private suspend fun requireActiveScope(): CoroutineScope =
        kotlinx.coroutines.currentCoroutineContext().let { ctx ->
            object : CoroutineScope {
                override val coroutineContext = ctx
            }
        }

    private companion object {
        const val TAG = "SessionKeeper"
        const val INITIAL_BACKOFF_MS = 500L
        const val MAX_BACKOFF_MS = 8_000L
    }
}
