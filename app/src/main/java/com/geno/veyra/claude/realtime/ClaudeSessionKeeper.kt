package com.geno.veyra.claude.realtime

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import com.geno.veyra.R
import com.geno.veyra.gemini.SessionConfig
import com.geno.veyra.gemini.SessionEvent
import com.geno.veyra.gemini.ToolRegistry
import com.geno.veyra.gemini.VoiceSessionKeeper
import com.geno.veyra.gemini.protocol.FunctionCall
import com.geno.veyra.gemini.protocol.FunctionResponse
import com.geno.veyra.settings.ClaudeKeyRepository
import com.geno.veyra.wakeword.VoskWakeWordEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Claude voice provider — a CASCADE, because Anthropic has no
 * realtime voice API:
 *
 *   mic PCM → Vosk (on-device STT, the wake word's model) → Claude
 *   Messages API (streaming, with Veyra's tools) → Android
 *   TextToSpeech rendered to PCM → the normal speaker pipeline.
 *
 * Consequences of the cascade shape, by design in v1:
 * - Strictly turn-based. Mic audio is dropped while Claude answers
 *   or speaks, so there is no barge-in (and no echo self-trigger).
 *   The stop phrase still works — it is matched on user turns.
 * - Vision WORKS: a camera frame is attached as an image block to
 *   the next user message (Anthropic accepts images natively).
 * - Latency is higher than the realtime providers: STT final +
 *   full model reply + TTS render before the first sound.
 *
 * The Vosk speech model is the wake word's (~40 MB, per app
 * language). When it is not on the phone yet, start() downloads it
 * via [VoskWakeWordEngine.downloadModel] — the same one-time
 * download the wake word needs.
 */
@Singleton
class ClaudeSessionKeeper @Inject constructor(
    client: OkHttpClient,
    private val json: Json,
    private val claudeKeyRepository: ClaudeKeyRepository,
    private val toolRegistry: ToolRegistry,
    private val vosk: VoskWakeWordEngine,
    @ApplicationContext private val context: Context,
) : VoiceSessionKeeper {

    private val api = ClaudeApiClient(client, json)

    private val _events =
        MutableSharedFlow<SessionEvent>(
            extraBufferCapacity = 256,
        )

    override val events: SharedFlow<SessionEvent> =
        _events.asSharedFlow()

    private val audioIn = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile
    private var running = false

    /** True while Claude is answering or speaking: mic is dropped. */
    @Volatile
    private var busy = false

    @Volatile
    private var ready = false

    private var sessionScope: CoroutineScope? = null
    private var config: SessionConfig? = null
    private var apiKey: String? = null

    private var initJob: Job? = null
    private var feedJob: Job? = null
    private var turnJob: Job? = null

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var tts: TextToSpeech? = null

    private val history = mutableListOf<JsonObject>()

    @Volatile
    private var pendingImage: ByteArray? = null

    private val pendingTexts = ConcurrentLinkedQueue<String>()

    override fun start(
        scope: CoroutineScope,
        config: SessionConfig,
    ) {
        if (running) {
            Log.d(TAG, "ClaudeSessionKeeper already running")
            return
        }
        running = true
        ready = false
        busy = false
        // Drop mic chunks left over from a previous session.
        while (audioIn.tryReceive().isSuccess) {
            // discard
        }
        this.config = config
        sessionScope = scope
        history.clear()
        pendingImage = null

        initJob = scope.launch {
            initialise(scope, config)
        }
    }

    override fun stop() {
        Log.i(TAG, "Stopping ClaudeSessionKeeper")
        running = false
        ready = false
        busy = false

        api.cancel()
        initJob?.cancel()
        initJob = null
        feedJob?.cancel()
        feedJob = null
        turnJob?.cancel()
        turnJob = null

        recognizer?.close()
        recognizer = null
        model?.close()
        model = null

        tts?.stop()
        tts?.shutdown()
        tts = null

        history.clear()
        pendingImage = null
        pendingTexts.clear()
        sessionScope = null
        config = null
        apiKey = null
    }

    /**
     * Mic PCM feeds the Vosk recognizer — but only while idle.
     * Dropping audio while busy is what makes the cascade
     * turn-based (no barge-in, no TTS echo in the recognizer).
     */
    override fun sendAudio(pcm: ByteArray) {
        if (running && ready && !busy) {
            audioIn.trySend(pcm)
        }
    }

    /** Stash the frame; it rides the next user message as an image. */
    override fun sendFrame(jpeg: ByteArray) {
        pendingImage = jpeg
    }

    override fun sendToolResponses(responses: List<FunctionResponse>) {
        if (!running) return
        val results = responses.mapNotNull { resp ->
            val id = resp.id ?: return@mapNotNull null
            buildJsonObject {
                put("type", "tool_result")
                put("tool_use_id", id)
                put("content", resp.response.toString())
            }
        }
        if (results.isEmpty()) return

        synchronized(history) {
            history += buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray { results.forEach { add(it) } })
            }
        }
        runAssistantTurn()
    }

    override fun sendText(text: String) {
        if (!running) return
        if (!ready || busy) {
            pendingTexts += text
            return
        }
        addUserText(text)
        runAssistantTurn()
    }

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    private suspend fun initialise(
        scope: CoroutineScope,
        config: SessionConfig,
    ) {
        val key = claudeKeyRepository.getKey()
        if (key.isNullOrBlank()) {
            Log.e(TAG, "🔴 No Claude API key stored")
            _events.emit(
                SessionEvent.ConnectionFailed(
                    context.getString(R.string.error_claude_key_missing)
                )
            )
            running = false
            return
        }
        apiKey = key

        if (!initTts(config)) {
            if (!running) return
            _events.emit(
                SessionEvent.ConnectionFailed(
                    context.getString(R.string.error_claude_tts)
                )
            )
            running = false
            return
        }

        val dir = vosk.modelDirIfReady() ?: try {
            vosk.downloadModel()
            vosk.modelDirIfReady()
        } catch (e: Exception) {
            Log.e(TAG, "Speech model download failed", e)
            null
        }
        if (dir == null) {
            if (!running) return
            _events.emit(
                SessionEvent.ConnectionFailed(
                    context.getString(
                        R.string.error_claude_stt_unavailable
                    )
                )
            )
            running = false
            return
        }

        try {
            val loadedModel = Model(dir.absolutePath)
            model = loadedModel
            recognizer = Recognizer(loadedModel, SAMPLE_RATE_HZ)
        } catch (e: Exception) {
            Log.e(TAG, "Vosk model load failed", e)
            if (!running) return
            _events.emit(
                SessionEvent.ConnectionFailed(
                    context.getString(
                        R.string.error_claude_stt_unavailable
                    )
                )
            )
            running = false
            return
        }

        if (!running) return
        ready = true
        Log.i(TAG, "🟢 Claude cascade ready")
        _events.emit(SessionEvent.Ready)

        feedJob = scope.launch(Dispatchers.Default) {
            feedLoop()
        }

        // Text that arrived during setup (e.g. the start phrase).
        drainPendingTexts()
    }

    private suspend fun initTts(config: SessionConfig): Boolean {
        val engine = suspendCancellableCoroutine<TextToSpeech?> { cont ->
            var created: TextToSpeech? = null
            created = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    cont.resume(created)
                } else {
                    cont.resume(null)
                }
            }
            cont.invokeOnCancellation { created.shutdown() }
        } ?: return false

        tts = engine
        runCatching {
            engine.language =
                Locale.forLanguageTag(config.languageCode)
        }
        return true
    }

    // ------------------------------------------------------------------
    // Speech in
    // ------------------------------------------------------------------

    private suspend fun feedLoop() {
        val rec = recognizer ?: return
        for (chunk in audioIn) {
            if (!running || busy) continue
            val finished = runCatching {
                rec.acceptWaveForm(chunk, chunk.size)
            }.getOrDefault(false)
            if (finished) {
                val text = runCatching {
                    JSONObject(rec.result).optString("text")
                }.getOrDefault("").trim()
                if (text.isNotEmpty() && running && !busy) {
                    Log.d(TAG, "Heard: $text")
                    _events.emit(
                        SessionEvent.Transcript(
                            text = text,
                            fromUser = true,
                        )
                    )
                    addUserText(text)
                    runAssistantTurn()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Claude turn
    // ------------------------------------------------------------------

    private fun runAssistantTurn() {
        val scope = sessionScope ?: return
        val cfg = config ?: return
        val key = apiKey ?: return
        if (turnJob?.isActive == true) return

        turnJob = scope.launch {
            busy = true
            try {
                trimHistory()
                val snapshot = synchronized(history) {
                    history.toList()
                }
                val result = api.streamMessage(
                    apiKey = key,
                    model = cfg.claudeModel,
                    system = cfg.systemInstruction,
                    messages = snapshot,
                    tools = toolRegistry.asClaudeTools(cfg.toolFlags),
                ) { delta ->
                    _events.emit(
                        SessionEvent.Transcript(
                            text = delta,
                            fromUser = false,
                        )
                    )
                }

                if (!running) return@launch

                synchronized(history) {
                    history += buildJsonObject {
                        put("role", "assistant")
                        put("content", result.contentBlocks)
                    }
                }

                if (result.toolUses.isNotEmpty()) {
                    _events.emit(
                        SessionEvent.ToolInvocation(
                            result.toolUses.map { tool ->
                                FunctionCall(
                                    id = tool.id,
                                    name = tool.name,
                                    args = tool.input,
                                )
                            }
                        )
                    )
                    _events.emit(SessionEvent.TurnComplete)
                } else {
                    speak(result.text)
                    if (!running) return@launch
                    _events.emit(SessionEvent.TurnComplete)
                }
            } catch (e: Exception) {
                if (running) {
                    Log.e(TAG, "Claude turn failed", e)
                    _events.emit(
                        SessionEvent.ConnectionFailed(e.message)
                    )
                }
            } finally {
                busy = false
                if (running) {
                    drainPendingTexts()
                }
            }
        }
    }

    private fun drainPendingTexts() {
        if (busy) return
        val next = pendingTexts.poll() ?: return
        addUserText(next)
        runAssistantTurn()
    }

    // ------------------------------------------------------------------
    // Speech out
    // ------------------------------------------------------------------

    /**
     * Render the reply with Android TTS into a WAV file, then push
     * the PCM through the normal speaker pipeline as [SessionEvent]
     * audio chunks (24 kHz, chunked at 100 ms).
     */
    private suspend fun speak(rawText: String) {
        val engine = tts ?: return
        val text = sanitizeForSpeech(rawText)
        if (text.isBlank()) return

        val file = File(
            context.cacheDir,
            "claude_tts_${UUID.randomUUID()}.wav",
        )
        try {
            val rendered = suspendCancellableCoroutine<Boolean> { cont ->
                val utteranceId = UUID.randomUUID().toString()
                engine.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(id: String?) = Unit

                        override fun onDone(id: String?) {
                            if (id == utteranceId && cont.isActive) {
                                cont.resume(true)
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) {
                            if (id == utteranceId && cont.isActive) {
                                cont.resume(false)
                            }
                        }

                        override fun onError(
                            id: String?,
                            errorCode: Int,
                        ) {
                            if (id == utteranceId && cont.isActive) {
                                cont.resume(false)
                            }
                        }
                    }
                )
                val queued = engine.synthesizeToFile(
                    text,
                    Bundle(),
                    file,
                    utteranceId,
                )
                if (queued != TextToSpeech.SUCCESS &&
                    cont.isActive
                ) {
                    cont.resume(false)
                }
            }
            if (!rendered || !file.exists() || file.length() == 0L) {
                Log.w(TAG, "TTS render failed")
                return
            }

            val (pcm, rate) = WavPcm.decode(file.readBytes())
            val pcm24k = WavPcm.resampleTo24k(pcm, rate)
            for (piece in WavPcm.chunk(pcm24k)) {
                if (!running) break
                _events.emit(SessionEvent.AudioChunk(piece))
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS speak failed", e)
        } finally {
            file.delete()
        }
    }

    /** Strip markdown so the TTS voice does not read it aloud. */
    private fun sanitizeForSpeech(text: String): String =
        text
            .replace(LINK_REGEX, "$1")
            .replace("**", "")
            .replace("__", "")
            .replace("`", "")
            .replace(HEADING_REGEX, "")
            .replace(BULLET_REGEX, "")
            .trim()

    // ------------------------------------------------------------------
    // History helpers
    // ------------------------------------------------------------------

    private fun addUserText(text: String) {
        val image = pendingImage
        pendingImage = null
        synchronized(history) {
            history += buildJsonObject {
                put("role", "user")
                put(
                    "content",
                    buildJsonArray {
                        if (image != null) {
                            add(
                                buildJsonObject {
                                    put("type", "image")
                                    put(
                                        "source",
                                        buildJsonObject {
                                            put("type", "base64")
                                            put(
                                                "media_type",
                                                "image/jpeg",
                                            )
                                            put(
                                                "data",
                                                Base64.encodeToString(
                                                    image,
                                                    Base64.NO_WRAP,
                                                ),
                                            )
                                        },
                                    )
                                }
                            )
                        }
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", text)
                            }
                        )
                    },
                )
            }
        }
    }

    /**
     * Bound the history so a long session does not resend an ever
     * growing transcript. Trims whole turns from the front and never
     * orphans a tool_result from its tool_use.
     */
    private fun trimHistory() {
        synchronized(history) {
            while (history.size > MAX_HISTORY_MESSAGES) {
                val first = history.first()
                val isPlainUserText =
                    first["role"]?.jsonPrimitive?.contentOrNull ==
                        "user" &&
                        first["content"]?.jsonArray?.none { block ->
                            block.jsonObject["type"]
                                ?.jsonPrimitive
                                ?.contentOrNull == "tool_result"
                        } == true
                if (!isPlainUserText) break
                history.removeAt(0)
                if (history.isNotEmpty() &&
                    history.first()["role"]
                        ?.jsonPrimitive
                        ?.contentOrNull == "assistant"
                ) {
                    history.removeAt(0)
                }
            }
        }
    }

    private companion object {
        const val TAG = "ClaudeSessionKeeper"
        const val SAMPLE_RATE_HZ = 16_000f
        const val MAX_HISTORY_MESSAGES = 60
        val LINK_REGEX = Regex("\\[([^\\]]+)\\]\\([^)]*\\)")
        val HEADING_REGEX = Regex("(?m)^#{1,6}\\s*")
        val BULLET_REGEX = Regex("(?m)^\\s*[-*]\\s+")
    }
}
