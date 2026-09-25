package com.lpecom.gemglasses.agent

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.util.Log
import androidx.annotation.RequiresPermission
import com.lpecom.gemglasses.audio.BluetoothAudioRouter
import com.lpecom.gemglasses.audio.MicStreamer
import com.lpecom.gemglasses.audio.SpeakerSink
import com.lpecom.gemglasses.gemini.SessionConfig
import com.lpecom.gemglasses.gemini.SessionEvent
import com.lpecom.gemglasses.gemini.SessionKeeper
import com.lpecom.gemglasses.gemini.ToolRegistry
import com.lpecom.gemglasses.glasses.ConnectionState
import com.lpecom.gemglasses.glasses.GlassesCameraSource
import com.lpecom.gemglasses.glasses.GlassesManager
import com.lpecom.gemglasses.settings.AgentPreferences
import com.lpecom.gemglasses.settings.AudioOutput
import com.lpecom.gemglasses.settings.SettingsRepository
import com.lpecom.gemglasses.state.ConversationStore
import com.lpecom.gemglasses.state.TranscriptEntry
import com.lpecom.gemglasses.tools.VisionBridge
import com.lpecom.gemglasses.tools.VisionController
import com.lpecom.gemglasses.translate.TranslateController
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
 * opens playback, starts the [SessionKeeper], pumps the mic into the socket,
 * and reacts to every [SessionEvent] — playing audio, updating the transcript,
 * flushing on barge-in, and dispatching tool calls. It also serves vision bursts
 * requested by the `capture_vision` tool.
 */
@Singleton
class AgentController @Inject constructor(
    private val sessionKeeper: SessionKeeper,
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
) : VisionController {

    private val scope = CoroutineScope(SupervisorJob())
    private var eventJob: Job? = null
    private var micJob: Job? = null

    private val _status = MutableStateFlow(AgentStatus.IDLE)
    val status: StateFlow<AgentStatus> = _status

    /**
     * Phrase that ends the session when heard in the user's transcript.
     * Refreshed from settings every time the assistant starts.
     */
    @Volatile
    private var stopPhrase: String =
        AgentPreferences.DEFAULT_STOP_PHRASE

    /**
     * Whether talking over the assistant cuts it off. Refreshed from
     * settings every time the assistant starts. Disable to test whether
     * false interruption events are what make the audio sound choppy.
     */
    @Volatile
    private var bargeInEnabled: Boolean = true

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
        visionBridge.delegate = this

        eventJob = scope.launch {
            val prefs = settings.snapshot()

            stopPhrase = prefs.stopPhrase
            bargeInEnabled = prefs.bargeInEnabled

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

            launch { collectEvents() }

            sessionKeeper.start(
                scope,
                SessionConfig(
                    systemInstruction = prefs.systemInstruction,
                    voiceName = prefs.voiceName,
                    languageCode = prefs.languageCode,
                    bargeInEnabled = bargeInEnabled,
                ),
            )
        }

        micJob = scope.launch {
            pumpMic()
        }
    }

    fun stop() {
        sessionKeeper.stop()

        scope.coroutineContext.cancelChildren()

        eventJob = null
        micJob = null
        pendingInitialText = null

        speaker.close()
        router.restore()

        visionBridge.delegate = null
        _status.value = AgentStatus.IDLE
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

                sessionKeeper.sendFrame(jpeg)
            }

            conversation.note("Vision off")
        }
    }

    private suspend fun collectEvents() {
        sessionKeeper.events.collect { event ->

            when (event) {

                is SessionEvent.Ready -> {
                    _status.value = AgentStatus.LISTENING

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
                        sessionKeeper.sendText(text)
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
                    dispatchTools(event)
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

    private fun dispatchTools(
        event: SessionEvent.ToolInvocation,
    ) {
        scope.launch {
            val responses = event.calls.map {
                toolRegistry.dispatch(it)
            }

            sessionKeeper.sendToolResponses(responses)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun pumpMic() {
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
            sessionKeeper.sendAudio(chunk)
        }
    }

    private companion object {
        const val TAG = "AgentController"
    }
}
