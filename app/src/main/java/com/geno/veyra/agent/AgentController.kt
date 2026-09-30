package com.geno.veyra.agent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.util.Log
import androidx.annotation.RequiresPermission
import com.geno.veyra.audio.BluetoothAudioRouter
import com.geno.veyra.audio.MicStreamer
import com.geno.veyra.audio.MicMuteController
import com.geno.veyra.audio.SpeakerSink
import com.geno.veyra.gemini.SessionConfig
import com.geno.veyra.gemini.SessionEvent
import com.geno.veyra.gemini.SessionKeeper
import com.geno.veyra.gemini.ToolFlags
import com.geno.veyra.gemini.VoiceSessionKeeper
import com.geno.veyra.openai.realtime.RealtimeSessionKeeper
import com.geno.veyra.settings.AiProvider
import com.geno.veyra.gemini.ToolRegistry
import com.geno.veyra.glasses.ConnectionState
import com.geno.veyra.glasses.GlassesCameraSource
import com.geno.veyra.glasses.GlassesManager
import com.geno.veyra.settings.AgentPreferences
import com.geno.veyra.settings.AudioOutput
import com.geno.veyra.settings.SettingsRepository
import com.geno.veyra.state.ConversationArchive
import com.geno.veyra.state.ConversationStore
import com.geno.veyra.state.TranscriptEntry
import com.geno.veyra.tools.VisionBridge
import com.geno.veyra.tools.VisionController
import com.geno.veyra.translate.TranslateController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** Coarse lifecycle of the assistant, surfaced to the UI. */
enum class AgentStatus { IDLE, CONNECTING, LISTENING, RECONNECTING, ERROR }

/**
 * The conductor. Owns the running assistant session: it routes Bluetooth audio,
 * opens playback, starts the provider's [VoiceSessionKeeper] (Gemini or
 * ChatGPT), pumps the mic into the socket,
 * and reacts to every [SessionEvent] — playing audio, updating the transcript,
 * flushing on barge-in, and dispatching tool calls. It also serves vision bursts
 * requested by the `capture_vision` tool.
 */
@Singleton
class AgentController @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val sessionKeeper: SessionKeeper,
    private val realtimeKeeper: RealtimeSessionKeeper,
    private val toolRegistry: ToolRegistry,
    private val micStreamer: MicStreamer,
    private val speaker: SpeakerSink,
    private val router: BluetoothAudioRouter,
    private val cameraSource: GlassesCameraSource,
    private val glassesManager: GlassesManager,
    private val conversation: ConversationStore,
    private val settings: SettingsRepository,
    private val visionBridge: VisionBridge,
    private val translator: Provider<TranslateController>,
    private val micMute: MicMuteController,
    private val archive: ConversationArchive,
) : VisionController {

    private val scope = CoroutineScope(SupervisorJob())
    private var eventJob: Job? = null
    private var micJob: Job? = null

    /**
     * The keeper for the running session — [sessionKeeper] (Gemini) or
     * [realtimeKeeper] (ChatGPT), chosen from the AI provider in
     * Settings each time the assistant starts. Null when idle.
     */
    @Volatile
    private var activeKeeper: VoiceSessionKeeper? = null

    private val _status = MutableStateFlow(AgentStatus.IDLE)
    val status: StateFlow<AgentStatus> = _status

    /**
     * Why the last connection attempt failed (server's reason when one
     * was captured). Set alongside [AgentStatus.ERROR]; null otherwise.
     * Surfaced in the Assistant UI next to the retry button.
     */
    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError

    /**
     * Phrase that ends the session when heard in the user's transcript.
     * Refreshed from settings every time the assistant starts.
     */
    @Volatile
    private var stopPhrase: String =
        AgentPreferences.DEFAULT_STOP_PHRASE

    /**
     * Whether talking over the assistant cuts it off. Refreshed from
     * settings every time the assistant starts.
     */
    @Volatile
    private var stopPhraseEnabled: Boolean = true
    private var bargeInEnabled: Boolean = true

    /**
     * Spoken-language tag for the running session, refreshed from settings
     * every time the assistant starts. Used for tool error messages so the
     * model can relay them in the user's language.
     */
    @Volatile
    private var sessionLanguage: String = "en-US"

    /**
     * Whether the session start/stop blips are enabled, refreshed from
     * settings every time the assistant starts.
     */
    @Volatile
    private var sessionBeepEnabled: Boolean = true

    /**
     * True once the session has gone live (Ready). The stop blip only
     * plays if the session actually started — tapping stop while still
     * connecting stays silent.
     */
    @Volatile
    private var beepArmed: Boolean = false

    /**
     * First user message to send once the session is ready (the wake
     * phrase on the wake-word path). Cleared after it's sent.
     */
    @Volatile
    private var pendingInitialText: String? = null

    val running: Boolean
        get() = eventJob?.isActive == true

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(initialText: String? = null) {
        if (running) return

        // Only one voice session may own the mic and speaker at a time.
        val translation = translator.get()
        if (translation.running) translation.stop()

        pendingInitialText = initialText

        _status.value = AgentStatus.CONNECTING
        _connectionError.value = null
        visionBridge.delegate = this
        micMute.reset()

        eventJob = scope.launch {
            val prefs = settings.snapshot()

            stopPhrase = prefs.stopPhrase
            bargeInEnabled = prefs.bargeInEnabled
            sessionLanguage = prefs.languageCode
            sessionBeepEnabled = prefs.sessionBeepEnabled
            beepArmed = false

            /*
             * The voice backend comes from the AI provider picker in
             * Settings. Both keepers speak the same SessionEvent
             * language, so everything below is provider-agnostic — the
             * wake-word path lands here too.
             */
            val keeper: VoiceSessionKeeper =
                if (prefs.aiProvider == AiProvider.OPENAI) {
                    Log.i(TAG, "Starting assistant with ChatGPT (OpenAI Realtime)")
                    realtimeKeeper
                } else {
                    sessionKeeper
                }
            activeKeeper = keeper

            /*
             * Playback is always the high-quality music channel (A2DP):
             * the assistant's voice goes to the glasses while they're
             * connected, and falls back to the phone speaker when they
             * aren't. "Phone speaker" output instead pins playback to
             * the phone and takes the mic from the glasses over SCO.
             */
            val glassesConnected =
                glassesManager.connectionState.first() ==
                    ConnectionState.CONNECTED

            val phoneSpeakerMode =
                prefs.audioOutput == AudioOutput.PHONE_SPEAKER

            if (phoneSpeakerMode && glassesConnected) {
                router.routeMicToGlassesSco()
            }

            speaker.open(
                usage = AudioAttributes.USAGE_MEDIA,
                preferredOutput =
                    if (phoneSpeakerMode) {
                        router.phoneSpeakerOutputDevice()
                    } else {
                        router.preferredMediaOutput(glassesConnected)
                    },
            )

            launch { collectEvents(keeper) }

            keeper.start(
                scope,
                SessionConfig(
                    systemInstruction = prefs.systemInstruction,
                    voiceName = prefs.voiceName,
                    languageCode = prefs.languageCode,
                    bargeInEnabled = bargeInEnabled,
                    toolFlags = ToolFlags(
                        webSearch = prefs.webSearchEnabled,
                        qrScan = prefs.qrScanEnabled,
                        ocr = prefs.ocrEnabled,
                        smartHome = prefs.smartHomeEnabled,
                    ),
                    model = prefs.liveModel,
                    openAiModel = prefs.chatGptModel,
                ),
            )

            micJob = scope.launch {
                pumpMic(keeper)
            }
        }
    }

    fun stop() {
        // Stop blip first, before the speaker and audio route are torn down.
        if (beepArmed) {
            beepArmed = false
            if (sessionBeepEnabled) {
                SessionBeep.stopped()
            }
        }

        // Persist this session's transcript before anything is torn down.
        archive.saveSession(conversation.entries.value)

        activeKeeper?.stop()
        activeKeeper = null

        scope.coroutineContext.cancelChildren()

        eventJob = null
        micJob = null
        pendingInitialText = null

        speaker.close()
        router.restore()

        visionBridge.delegate = null
        _status.value = AgentStatus.IDLE
        _connectionError.value = null
        micMute.reset()
    }

    // VisionController: called by the capture_vision tool.
    override fun startVisionBurst(durationMs: Long) {
        scope.launch {
            if (!glassesManager.ensureCameraPermission()) {
                conversation.note("Camera permission denied.")
                return@launch
            }

            conversation.note("👁️ Vision on")

            cameraSource.runBurst(durationMs) { jpeg ->

                /*
                 * Add the captured image to the local transcript.
                 *
                 * The same JPEG is then sent to Gemini below. This means
                 * the transcript displays exactly the image Gemini receives.
                 */
                conversation.addPhoto(
                    jpegBytes = jpeg,
                    speaker = TranscriptEntry.Speaker.USER,
                )

                activeKeeper?.sendFrame(jpeg)
            }

            conversation.note("Vision off")
        }
    }

    private suspend fun collectEvents(keeper: VoiceSessionKeeper) {
        keeper.events.collect { event ->

            when (event) {

                is SessionEvent.Ready -> {
                    _status.value = AgentStatus.LISTENING
                    beepArmed = true
                    if (sessionBeepEnabled) {
                        SessionBeep.started()
                    }

                    /*
                     * Wake-word path: feed the wake phrase to the assistant
                     * as the first user message so it responds instead of
                     * sitting silent. Only the first Ready of a session
                     * sends it; reconnects don't repeat it.
                     */
                    pendingInitialText?.let { text ->
                        pendingInitialText = null
                        conversation.appendTranscript(
                            text,
                            TranscriptEntry.Speaker.USER,
                        )
                        keeper.sendText(text)
                        Log.i(
                            TAG,
                            "Sent initial user text: \"$text\"",
                        )
                    }
                }

                is SessionEvent.AudioChunk -> {
                    speaker.write(event.pcm)
                }

                is SessionEvent.Interrupted -> {
                    if (bargeInEnabled) {
                        speaker.flush()
                    } else {
                        Log.i(
                            TAG,
                            "Interruption ignored (barge-in disabled)",
                        )
                    }
                }

                is SessionEvent.Transcript -> {
                    conversation.appendTranscript(
                        event.text,
                        if (event.fromUser) {
                            TranscriptEntry.Speaker.USER
                        } else {
                            TranscriptEntry.Speaker.ASSISTANT
                        },
                    )

                    if (
                        event.fromUser &&
                        containsStopPhrase(event.text, stopPhrase)
                    ) {
                        Log.i(
                            TAG,
                            "Stop phrase heard — ending session",
                        )
                        stop()
                    }
                }

                is SessionEvent.ToolInvocation -> {
                    dispatchTools(event, keeper)
                }

                is SessionEvent.ToolCancelled -> {
                    Log.i(
                        TAG,
                        "tool calls cancelled: ${event.ids}",
                    )
                }

                is SessionEvent.GoingAway -> {
                    _status.value = AgentStatus.RECONNECTING
                }

                is SessionEvent.TurnComplete -> {
                    Unit
                }

                is SessionEvent.Closed -> {
                    if (event.error != null) {
                        _status.value = AgentStatus.RECONNECTING
                    }
                }

                is SessionEvent.ConnectionFailed -> {
                    /*
                     * Setup never completed and the keeper stopped
                     * retrying. Surface the reason in the UI with an
                     * explicit retry instead of hanging on connecting.
                     */
                    _connectionError.value = event.detail
                    _status.value = AgentStatus.ERROR
                }
            }
        }
    }

    /**
     * True when the user's transcript contains the stop phrase as whole
     * words (case-insensitive): "goodbye glasses" matches "okay, goodbye
     * glasses, thanks" but not "goodbye glassen".
     */
    private fun containsStopPhrase(
        text: String,
        phrase: String,
    ): Boolean {
        val trimmed =
            phrase.trim().lowercase()

        if (trimmed.isEmpty()) {
            return false
        }

        return Regex(
            "\\b${Regex.escape(trimmed)}\\b",
        ).containsMatchIn(
            text.lowercase(),
        )
    }

    /**
     * True when the user's transcript contains a ChatGPT launch phrase as
     * whole words (case-insensitive): "open chat gpt", "open chatgpt",
    private fun dispatchTools(
        event: SessionEvent.ToolInvocation,
        keeper: VoiceSessionKeeper,
    ) {
        scope.launch {
            val responses = event.calls.map {
                toolRegistry.dispatch(it, sessionLanguage)
            }

            keeper.sendToolResponses(responses)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun pumpMic(keeper: VoiceSessionKeeper) {
        micStreamer.stream().collect { chunk ->
            /*
             * Half-duplex when barge-in is off: while the assistant is
             * playing, its voice loops back through the mic
             * (phone-speaker echo) and the server transcribes it as user
             * speech — the assistant ends up talking to itself. Dropping
             * mic input during playback breaks the loop; with barge-in
             * disabled the user can't interrupt anyway.
             */
            if (!bargeInEnabled && speaker.isPlaying()) return@collect
            // Mic mute: drop outgoing audio without tearing the session down.
            if (micMute.muted.value) return@collect
            keeper.sendAudio(chunk)
        }
    }

    private companion object {
        const val TAG = "AgentController"
    }
}
