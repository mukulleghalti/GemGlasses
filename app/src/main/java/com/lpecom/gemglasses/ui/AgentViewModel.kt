package com.lpecom.gemglasses.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lpecom.gemglasses.agent.AgentController
import com.lpecom.gemglasses.agent.AgentStatus
import com.lpecom.gemglasses.glasses.GlassesDevice
import com.lpecom.gemglasses.glasses.GlassesManager
import com.lpecom.gemglasses.glasses.RegistrationState
import com.lpecom.gemglasses.service.AgentForegroundService
import com.lpecom.gemglasses.settings.AgentPreferences
import com.lpecom.gemglasses.settings.SettingsRepository
import com.lpecom.gemglasses.state.CitedPlace
import com.lpecom.gemglasses.state.ConversationStore
import com.lpecom.gemglasses.state.TranscriptEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
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
) : AndroidViewModel(application) {

    val status: StateFlow<AgentStatus> =
        controller.status

    val registration: StateFlow<RegistrationState> =
        glassesManager.registrationState
            .stateInDefault(RegistrationState.UNKNOWN)

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
     * Starts the assistant.
     *
     * Caller must have RECORD_AUDIO permission.
     */
    fun startSession() {
        val app = getApplication<Application>()

        conversation.clear()

        AgentForegroundService.start(app)
        controller.start()
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
     *
     * Example:
     *     setLanguage("en")
     *     setLanguage("hi")
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
