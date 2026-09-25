package com.lpecom.gemglasses.agent

import android.Manifest
import android.annotation.SuppressLint
import android.util.Log
import androidx.annotation.RequiresPermission
import com.lpecom.gemglasses.audio.BluetoothAudioRouter
import com.lpecom.gemglasses.audio.MicStreamer
import com.lpecom.gemglasses.audio.SpeakerSink
import com.lpecom.gemglasses.gemini.SessionConfig
import com.lpecom.gemglasses.gemini.SessionEvent
import com.lpecom.gemglasses.gemini.SessionKeeper
import com.lpecom.gemglasses.gemini.ToolRegistry
import com.lpecom.gemglasses.glasses.GlassesCameraSource
import com.lpecom.gemglasses.glasses.GlassesManager
import com.lpecom.gemglasses.settings.SettingsRepository
import com.lpecom.gemglasses.state.ConversationStore
import com.lpecom.gemglasses.state.TranscriptEntry
import com.lpecom.gemglasses.tools.VisionBridge
import com.lpecom.gemglasses.tools.VisionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
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
) : VisionController {

    private val scope = CoroutineScope(SupervisorJob())
    private var eventJob: Job? = null
    private var micJob: Job? = null

    private val _status = MutableStateFlow(AgentStatus.IDLE)
    val status: StateFlow<AgentStatus> = _status

    val running: Boolean
        get() = eventJob?.isActive == true

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (running) return

        _status.value = AgentStatus.CONNECTING
        visionBridge.delegate = this

        /*
         * Temporarily disabled for the camera/HFP/SCO A/B test.
         *
         * Bluetooth can remain connected to the glasses without explicitly
         * forcing Android's communication device onto Bluetooth SCO/HFP.
         */
//        router.routeToGlasses()

        speaker.open()

        eventJob = scope.launch {
            collectEvents()
        }

        micJob = scope.launch {
            pumpMic()
        }

        scope.launch {
            val prefs = settings.snapshot()

            sessionKeeper.start(
                scope,
                SessionConfig(
                    systemInstruction = prefs.systemInstruction,
                    voiceName = prefs.voiceName,
                    languageCode = prefs.languageCode,
                ),
            )
        }
    }

    fun stop() {
        sessionKeeper.stop()

        scope.coroutineContext.cancelChildren()

        eventJob = null
        micJob = null

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
                }

                is SessionEvent.AudioChunk -> {
                    speaker.write(event.pcm)
                }

                is SessionEvent.Interrupted -> {
                    speaker.flush()
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
            sessionKeeper.sendAudio(chunk)
        }
    }

    private companion object {
        const val TAG = "AgentController"
    }
}
