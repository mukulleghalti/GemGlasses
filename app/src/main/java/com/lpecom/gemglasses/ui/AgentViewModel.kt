package com.lpecom.gemglasses.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lpecom.gemglasses.agent.AgentController
import com.lpecom.gemglasses.agent.AgentStatus
import com.lpecom.gemglasses.gemini.TokenProvider
import com.lpecom.gemglasses.glasses.GlassesDevice
import com.lpecom.gemglasses.glasses.GlassesManager
import com.lpecom.gemglasses.glasses.RegistrationState
import com.lpecom.gemglasses.glasses.ConnectionState
import com.lpecom.gemglasses.service.AgentForegroundService
import com.lpecom.gemglasses.service.AssistantStarter
import com.lpecom.gemglasses.settings.AgentPreferences
import com.lpecom.gemglasses.settings.AudioOutput
import com.lpecom.gemglasses.settings.GeminiKeyRepository
import com.lpecom.gemglasses.settings.PlaybackQuality
import com.lpecom.gemglasses.settings.SettingsRepository
import com.lpecom.gemglasses.state.CitedPlace
import com.lpecom.gemglasses.state.ConversationStore
import com.lpecom.gemglasses.state.TranscriptEntry
import com.lpecom.gemglasses.wakeword.WakeWordEngine
import com.lpecom.gemglasses.wakeword.WakeWordModelState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Bridges the Compose UI to the [AgentController] and shared state.
 *
 * Holds no business logic — it starts/stops the session, manages the
 * foreground service, and re-exposes flows that the screens observe.
 */
@HiltViewModel
class AgentViewModel @Inject constructor(
    application: Application,
    private val controller: AgentController,
    private val glassesManager: GlassesManager,
    private val conversation: ConversationStore,
    private val settings: SettingsRepository,
    private val assistantStarter: AssistantStarter,
    private val wakeWordEngine: WakeWordEngine,
    private val keyRepository: GeminiKeyRepository,
    private val tokenProvider: TokenProvider,
) : AndroidViewModel(application) {

    val status: StateFlow<AgentStatus> =
        controller.status

    val registration: StateFlow<RegistrationState> =
        glassesManager.registrationState
            .stateInDefault(RegistrationState.UNKNOWN)

    val connectionState: StateFlow<ConnectionState> =
        glassesManager.connectionState
            .stateInDefault(ConnectionState.DISCONNECTED)

    val devices: StateFlow<List<GlassesDevice>> =
        glassesManager.devices
            .stateInDefault(emptyList())

    val transcript: StateFlow<List<TranscriptEntry>> =
        conversation.entries
            .stateInDefault(emptyList())

    val places: StateFlow<List<CitedPlace>> =
        conversation.places
            .stateInDefault(emptyList())

    val preferences: StateFlow<AgentPreferences> =
        settings.preferences
            .stateInDefault(AgentPreferences.DEFAULT)

    val wakeWordModelState: StateFlow<WakeWordModelState> =
        wakeWordEngine.modelState

    /**
     * Starts the assistant.
     *
     * Caller must have RECORD_AUDIO permission.
     */
    fun startSession() {
        // Same path the wake-word service uses; keep both identical.
        assistantStarter.start()
    }

    /**
     * Stops the assistant.
     */
    fun stopSession() {
        controller.stop()
        AgentForegroundService.stop(getApplication())
    }

    /**
     * Starts the Meta glasses registration flow.
     */
    fun registerGlasses() {
        glassesManager.startRegistration()
    }

    /**
     * Connects to the registered Ray-Ban Meta glasses.
     *
     * Registration and an active MWDAT connection are separate states.
     */
    fun connectGlasses() {
        viewModelScope.launch {
            try {
                val connected = glassesManager.connect()

                if (connected) {
                    Log.i(TAG, "Glasses connected successfully")
                } else {
                    Log.w(TAG, "Glasses connection failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting glasses", e)
            }
        }
    }

    /**
     * Changes the assistant language.
     */
    fun setLanguage(code: String) {
        viewModelScope.launch {
            settings.setLanguage(code)
        }
    }

    /**
     * Changes the Gemini voice.
     */
    fun setVoice(voice: String) {
        viewModelScope.launch {
            settings.setVoice(voice)
        }
    }

    /**
     * Toggles always-on wake-word listening ("Hey Glasses").
     */
    fun setWakeWordEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.setWakeWordEnabled(enabled)
        }
    }

    /**
     * Changes the wake phrase (e.g. "hey glasses").
     */
    fun setWakePhrase(phrase: String) {
        viewModelScope.launch {
            settings.setWakePhrase(phrase)
        }
    }

    /**
     * Changes the stop phrase (e.g. "goodbye glasses"). Saying it while
     * the assistant is running ends the session.
     */
    fun setStopPhrase(phrase: String) {
        viewModelScope.launch {
            settings.setStopPhrase(phrase)
        }
    }

    /**
     * Changes where the assistant's voice plays: the glasses or the phone
     * speaker (diagnostic for choppy Bluetooth audio).
     */
    fun setAudioOutput(output: AudioOutput) {
        viewModelScope.launch {
            settings.setAudioOutput(output)
        }
    }

    /**
     * Changes playback quality: the voice-call channel (SCO) or the
     * high-quality music channel (A2DP).
     */
    fun setPlaybackQuality(quality: PlaybackQuality) {
        viewModelScope.launch {
            settings.setPlaybackQuality(quality)
        }
    }

    /**
     * Toggles barge-in: whether talking over the assistant cuts it off.
     */
    fun setBargeInEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.setBargeInEnabled(enabled)
        }
    }

    // --- Gemini API key --------------------------------------------------

    /** Status of the saved API key, as last verified against Google. */
    sealed interface ApiKeyState {
        /** A key is saved but hasn't been verified yet in this session. */
        data object Unchecked : ApiKeyState

        /** A verification call is in flight. */
        data object Checking : ApiKeyState

        /** Google accepted the key (a token was minted). */
        data object Valid : ApiKeyState

        /** Google rejected the key, or no key is saved. */
        data class Invalid(val message: String) : ApiKeyState

        /** No key saved at all. */
        data object Missing : ApiKeyState
    }

    private val _apiKeyState = MutableStateFlow<ApiKeyState>(
        if (keyRepository.hasKey()) ApiKeyState.Unchecked else ApiKeyState.Missing,
    )
    val apiKeyState: StateFlow<ApiKeyState> = _apiKeyState

    /** The saved key, for prefilling the Settings field. */
    val savedApiKey: String?
        get() = keyRepository.getKey()

    /**
     * Saves the key verbatim (no format checks) and immediately verifies
     * it by minting a real ephemeral token, so a typo surfaces right away
     * instead of at the next session start.
     */
    fun saveApiKey(key: String) {
        viewModelScope.launch {
            keyRepository.saveKey(key)
            _apiKeyState.value = ApiKeyState.Checking
            _apiKeyState.value = try {
                tokenProvider.fetchEphemeralToken()
                ApiKeyState.Valid
            } catch (e: Exception) {
                ApiKeyState.Invalid(e.message ?: "Couldn't verify the key.")
            }
        }
    }

    fun clearApiKey() {
        keyRepository.clearKey()
        _apiKeyState.value = ApiKeyState.Missing
    }

    /**
     * Reactive "do we hold a live token" signal for the home screen's
     * green/red dot. Fires on every successful mint.
     */
    val hasLiveToken: StateFlow<Boolean> = tokenProvider.hasLiveToken

    /** Clock-accurate check; see [TokenProvider.hasLiveTokenNow]. */
    fun hasLiveTokenNow(): Boolean = tokenProvider.hasLiveTokenNow()

    /**
     * Converts a Flow into a StateFlow with a default value.
     */
    private fun <T> Flow<T>.stateInDefault(
        initial: T,
    ): StateFlow<T> =
        stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = initial,
        )

    private companion object {
        const val TAG = "AgentViewModel"
    }
}
