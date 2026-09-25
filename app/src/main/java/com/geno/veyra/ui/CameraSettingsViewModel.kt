package com.geno.veyra.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geno.veyra.settings.CameraResolution
import com.geno.veyra.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class CameraSettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    val preferences =
        settings.preferences.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue =
                com.geno.veyra.settings.AgentPreferences.DEFAULT,
        )

    fun setResolution(
        resolution: CameraResolution,
    ) {
        viewModelScope.launch {
            settings.setCameraResolution(resolution)
        }
    }

    fun setFrameRate(
        frameRate: Int,
    ) {
        viewModelScope.launch {
            settings.setCameraFrameRate(frameRate)
        }
    }
}
