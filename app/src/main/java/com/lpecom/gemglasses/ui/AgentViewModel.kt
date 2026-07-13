package com.lpecom.gemglasses.ui

import android.app.Application
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Bridges the Compose UI to the [AgentController] and shared state. Holds no
 * business logic — it starts/stops the session (and the foreground service that
 * keeps it alive) and re-exposes flows the screens observe.
 */
@HiltViewModel
class AgentViewModel @Inject constructor(
    application: Application,
    private val controller: AgentController,
    private val glassesManager: GlassesManager,
    private val conversation: ConversationStore,
    private val settings: SettingsRepository,
) : AndroidViewModel(application) {

    val status: StateFlow<AgentStatus> = controller.status

    val registration: StateFlow<RegistrationState> =
        glassesManager.registrationState.stateInDefault(RegistrationState.UNKNOWN)

    val devices: StateFlow<List<GlassesDevice>> =
        glassesManager.devices.stateInDefault(emptyList())

    val transcript: StateFlow<List<TranscriptEntry>> =
        conversation.entries.stateInDefault(emptyList())

    val places: StateFlow<List<CitedPlace>> =
        conversation.places.stateInDefault(emptyList())

    val preferences: StateFlow<AgentPreferences> =
        settings.preferences.stateInDefault(AgentPreferences.DEFAULT)

    /** Starts the assistant. Caller must have RECORD_AUDIO granted. */
    fun startSession() {
        val app = getApplication<Application>()
        conversation.clear()
        AgentForegroundService.start(app)
        controller.start()
    }

    fun stopSession() {
        controller.stop()
        AgentForegroundService.stop(getApplication())
    }

    fun registerGlasses() = glassesManager.startRegistration()

    fun setLanguage(code: String) = viewModelScope.launch { settings.setLanguage(code) }
    fun setVoice(voice: String) = viewModelScope.launch { settings.setVoice(voice) }

    private fun <T> kotlinx.coroutines.flow.Flow<T>.stateInDefault(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)
}
