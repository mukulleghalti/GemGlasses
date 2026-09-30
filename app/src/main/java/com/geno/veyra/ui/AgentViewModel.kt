package com.geno.veyra.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
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
import com.geno.veyra.openai.OpenAiConnectionCheck
import com.geno.veyra.service.AgentForegroundService
import com.geno.veyra.service.AssistantStarter
import com.geno.veyra.settings.AgentPreferences
import com.geno.veyra.settings.AiProvider
import com.geno.veyra.settings.AppLocaleStore
import com.geno.veyra.settings.AudioOutput
import com.geno.veyra.settings.GeminiKeyRepository
import com.geno.veyra.settings.OpenAiKeyRepository
import com.geno.veyra.settings.Memory
import com.geno.veyra.settings.MemoryRepository
import com.geno.veyra.settings.SettingsRepository
import com.geno.veyra.smarthome.SmartHomeController
import com.geno.veyra.state.CitedPlace
import com.geno.veyra.state.ConversationStore
import com.geno.veyra.state.TranscriptEntry
import com.geno.veyra.wakeword.WakeWordEngine
import com.geno.veyra.wakeword.WakeWordModelState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val openAiKeyRepository: OpenAiKeyRepository,
    private val openAiConnectionCheck: OpenAiConnectionCheck,
    private val micMute: MicMuteController,
    private val memoryRepository: MemoryRepository,
    private val smartHome: SmartHomeController,
) : AndroidViewModel(application) {

    val status: StateFlow<AgentStatus> =
        controller.status

    /** Why the last assistant connection attempt failed, if it did. */
    val connectionError: StateFlow<String?> =
        controller.connectionError

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
     * Caller must have RECORD_AUDIO permission. The AI provider chosen
     * in Settings (Gemini or ChatGPT) is resolved inside
     * [AgentController] — this is the same path the wake-word service
     * uses, so both triggers stay identical.
     */
    fun startSession() {
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
     * Toggles auto-starting the assistant when the app launches.
     */
    fun setStartAssistantOnLaunch(enabled: Boolean) {
        viewModelScope.launch {
            settings.setStartAssistantOnLaunch(enabled)
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
     * Drops the custom wake phrase so the per-language default applies.
     */
    fun clearWakePhrase() {
        viewModelScope.launch {
            settings.clearWakePhrase()
        }
    }

    /** Re-reads wake-model readiness for the current app language. */
    fun refreshWakeWordModelState() {
        wakeWordEngine.refreshModelState()
    }

    /**
     * Retries the wake-word model download for the current app language.
     * Call when [wakeWordModelState] is [WakeWordModelState.Error].
     */
    fun retryWakeWordDownload() {
        viewModelScope.launch {
            try {
                wakeWordEngine.downloadModel()
            } catch (e: Exception) {
                // The engine already sets Error state; nothing more to do.
            }
        }
    }

    /**
     * Runs the wake-phrase test: listens with the current language's
     * on-device model and reports whether [phrase] would trigger. Caller
     * must hold RECORD_AUDIO.
     */
    fun testWakePhrase(phrase: String) {
        val trimmed = phrase.trim().lowercase()
        if (trimmed.isEmpty()) return
        if (_wakeTestState.value is WakeTestState.Listening) return
        _wakeTestState.value = WakeTestState.Listening("")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var lastHeard = ""
                val matched =
                    wakeWordEngine.testDecode(trimmed) { partial ->
                        lastHeard = partial
                        _wakeTestState.value =
                            WakeTestState.Listening(partial)
                    }
                _wakeTestState.value =
                    if (matched) {
                        WakeTestState.Passed(
                            lastHeard.ifBlank { trimmed },
                        )
                    } else {
                        WakeTestState.Failed(
                            lastHeard.ifBlank { "(nothing heard)" },
                        )
                    }
            } catch (e: Exception) {
                _wakeTestState.value =
                    WakeTestState.Error(e.message ?: "test failed")
            }
        }
    }

    /** Clears the wake-phrase test result. */
    fun resetWakeTest() {
        _wakeTestState.value = WakeTestState.Idle
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

    fun setSessionBeepEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.setSessionBeepEnabled(enabled)
        }
    }

    fun setVoiceChatGptEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.setVoiceChatGptEnabled(enabled)
        }
    }

    /**
     * Opens the system assistant picker. Android does not let apps change
     * the default assistant programmatically (system-guarded setting), so
     * this deep-links the user straight to the picker instead: one tap,
     * choose ChatGPT, done. Falls back to the default-apps screen on OEM
     * skins that hide the voice-input settings page.
     */
    fun openDefaultAssistantSettings(context: Context) {
        val voiceInput =
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val defaultApps =
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (voiceInput.resolveActivity(context.packageManager) != null) {
            context.startActivity(voiceInput)
        } else {
            context.startActivity(defaultApps)
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

    fun setSmartHomeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.setSmartHomeEnabled(enabled)
        }
    }

    fun setLookPayEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.setLookPayEnabled(enabled)
        }
    }

    /** True once the user has granted Google Home permissions. */
    val smartHomeConnected: StateFlow<Boolean> =
        smartHome.isConnected().stateInDefault(false)

    /** Starts the Google Home account + permission flow. */
    fun connectGoogleHome() {
        smartHome.connect()
    }

    fun setLiveModel(modelId: String) {
        viewModelScope.launch {
            settings.setLiveModel(modelId)
        }
    }

    fun setAiProvider(provider: AiProvider) {
        viewModelScope.launch {
            settings.setAiProvider(provider)
        }
    }

    fun setChatGptModel(modelId: String) {
        viewModelScope.launch {
            settings.setChatGptModel(modelId)
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

    // --- ChatGPT (OpenAI) API key ------------------------------------------

    /**
     * Status of the saved OpenAI key, verified against OpenAI's API.
     * Mirrors [ApiKeyState]; a separate flow so the Gemini and ChatGPT
     * key states never get mixed up in the UI.
     */
    private val _openAiKeyState = MutableStateFlow<ApiKeyState>(
        if (openAiKeyRepository.hasKey()) ApiKeyState.Unchecked else ApiKeyState.Missing,
    )
    val openAiKeyState: StateFlow<ApiKeyState> = _openAiKeyState

    /** The saved OpenAI key, for prefilling the Settings field. */
    val savedOpenAiKey: String?
        get() = openAiKeyRepository.getKey()

    /**
     * Saves the key verbatim (no format checks) and immediately verifies
     * it against OpenAI's /v1/models endpoint, so a typo surfaces right
     * away instead of at the next session start.
     */
    fun saveOpenAiKey(key: String) {
        viewModelScope.launch {
            openAiKeyRepository.saveKey(key)
            _openAiKeyState.value = ApiKeyState.Checking
            _openAiKeyState.value = try {
                openAiConnectionCheck.verifyKey(key)
                ApiKeyState.Valid
            } catch (e: Exception) {
                ApiKeyState.Invalid(e.message)
            }
        }
    }

    fun clearOpenAiKey() {
        openAiKeyRepository.clearKey()
        _openAiKeyState.value = ApiKeyState.Missing
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

    private val _wakeTestState: MutableStateFlow<WakeTestState> =
        MutableStateFlow(WakeTestState.Idle)

    /** Live state of the wake-phrase test in Settings. */
    val wakeTestState: StateFlow<WakeTestState> =
        _wakeTestState.asStateFlow()
}

/** UI state for the wake-phrase test in Settings. */
sealed interface WakeTestState {
    /** No test has run (or the result was cleared). */
    data object Idle : WakeTestState

    /** Listening; [heard] is the latest decoded fragment. */
    data class Listening(val heard: String) : WakeTestState

    /** The phrase matched — it would trigger the assistant. */
    data class Passed(val heard: String) : WakeTestState

    /** The phrase never matched; [heard] is what Vosk decoded. */
    data class Failed(val heard: String) : WakeTestState

    /** The test itself broke (mic, model download, …). */
    data class Error(val message: String) : WakeTestState
}
