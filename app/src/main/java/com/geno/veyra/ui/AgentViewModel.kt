package com.geno.veyra.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geno.veyra.agent.AgentController
import com.geno.veyra.agent.AgentStatus
import com.geno.veyra.audio.MicMuteController
import com.geno.veyra.gemini.TokenProvider
import com.geno.veyra.glasses.GlassesDevice
import com.geno.veyra.glasses.GlassesManager
import com.geno.veyra.glasses.RegistrationState
import com.geno.veyra.glasses.ConnectionState
import com.geno.veyra.service.AgentForegroundService
import com.geno.veyra.service.AssistantStarter
import com.geno.veyra.settings.AgentPreferences
import com.geno.veyra.settings.AppLocaleStore
import com.geno.veyra.settings.AudioOutput
import com.geno.veyra.settings.GeminiKeyRepository
import com.geno.veyra.settings.Memory
import com.geno.veyra.settings.MemoryRepository
import com.geno.veyra.settings.SettingsRepository
import com.geno.veyra.state.CitedPlace
import com.geno.veyra.state.ConversationStore
import com.geno.veyra.state.TranscriptEntry
import com.geno.veyra.wakeword.WakeWordEngine
import com.geno.veyra.wakeword.WakeWordModelState
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
    private val micMute: MicMuteController,
    private val memoryRepository: MemoryRepository,
) : AndroidViewModel(application) {

    val status: StateFlow<AgentStatus> =
        controller.status

    /** True while the assistant's mic is muted (session still alive). */
    val micMuted: StateFlow<Boolean> =
        micMute.muted

    /** Saved "remember this" memories, newest first. */
    val memories: StateFlow<List<Memory>> =
        memoryRepository.memories
            .stateInDefault(emptyList())

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

    /**
     * App UI language as a BCP-47 tag, or `null` for the system language.
     * Independent of the assistant's spoken language.
     */
    val appLanguage: StateFlow<String?> =
        settings.appLanguage
            .stateInDefault(null)

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
     * Mutes/unmutes the mic without ending the session.
     */
    fun setMicMuted(muted: Boolean) {
        micMute.setMuted(muted)
    }

    /**
     * Deletes one saved memory.
     */
    fun deleteMemory(id: String) {
        viewModelScope.launch {
            memoryRepository.delete(id)
        }
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
     * Changes where the assistant's voice plays (and, paired with it,
     * which mic listens): the glasses, or the phone speaker with the
     * glasses' mic.
     */
    fun setAudioOutput(output: AudioOutput) {
        viewModelScope.launch {
            settings.setAudioOutput(output)
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

    /**
     * Persists the app UI language. Callers should recreate the activity
     * afterwards so the new locale applies immediately.
     */
    fun setAppLanguage(tag: String?) {
        // Cache synchronously first: callers recreate the activity right
        // after this call, and attachBaseContext must see the new tag before
        // the DataStore write completes.
        AppLocaleStore.cacheAppLanguageTag(getApplication(), tag)
        viewModelScope.launch {
            settings.setAppLanguage(tag)
        }
    }

    /**
     * Toggles web search: whether the assistant may use Google Search
     * grounding during a session.
     */
    fun setWebSearchEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.setWebSearchEnabled(enabled)
        }
    }

    fun setAutoHistoryTitles(enabled: Boolean) {
        viewModelScope.launch {
            settings.setAutoHistoryTitles(enabled)
        }
    }

    fun setQrScanEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.setQrScanEnabled(enabled)
        }
    }

    fun setOcrEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.setOcrEnabled(enabled)
        }
    }

    fun setLiveModel(modelId: String) {
        viewModelScope.launch {
            settings.setLiveModel(modelId)
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
        data class Invalid(val message: String?) : ApiKeyState

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
                ApiKeyState.Invalid(e.message)
            }
        }
    }

    fun clearApiKey() {
        keyRepository.clearKey()
        _apiKeyState.value = ApiKeyState.Missing
    }

    /**
     * Re-verifies the saved key against Google on demand (the "Check
     * Connection" row under Advanced). Same check as [saveApiKey].
     */
    fun checkConnection() {
        viewModelScope.launch {
            if (!keyRepository.hasKey()) {
                _apiKeyState.value = ApiKeyState.Missing
                return@launch
            }
            _apiKeyState.value = ApiKeyState.Checking
            _apiKeyState.value = try {
                tokenProvider.fetchEphemeralToken()
                ApiKeyState.Valid
            } catch (e: Exception) {
                ApiKeyState.Invalid(e.message)
            }
        }
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
