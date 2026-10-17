package com.geno.veyra.inputs

import android.content.Context
import android.util.Log
import com.geno.veyra.agent.AgentController
import com.geno.veyra.agent.AgentStatus
import com.geno.veyra.glasses.GlassesManager
import com.geno.veyra.glasses.RegistrationState
import com.geno.veyra.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides when the capture-button listener runs: the Settings toggle is
 * on and the glasses are registered with Veyra. Mirrors
 * [com.geno.veyra.wakeword.WakeWordCoordinator]'s shape — the service
 * itself creates the DAT session, so unlike the wake-word listener this
 * does not wait for an existing connection.
 */
@Singleton
class GlassesInputCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val glassesManager: GlassesManager,
    private val controller: AgentController,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false
    private var serviceRunning = false

    /** Idempotent; call once from [com.geno.veyra.VeyraApp.onCreate]. */
    fun start() {
        if (started) return
        started = true

        scope.launch {
            combine(
                settings.preferences,
                glassesManager.registrationState,
            ) { prefs, registration ->
                prefs.captureButtonEnabled &&
                    registration == RegistrationState.REGISTERED
            }.collect { shouldRun ->
                Log.d(TAG, "capture-button listener=$shouldRun")
                if (shouldRun && !serviceRunning) {
                    GlassesInputService.start(context)
                    serviceRunning = true
                } else if (!shouldRun && serviceRunning) {
                    GlassesInputService.stop(context)
                    serviceRunning = false
                    // Release the DAT session too, so the capture button
                    // returns to its native behaviour. Skipped while an
                    // assistant session is live — it shares the session.
                    if (controller.status.value == AgentStatus.IDLE) {
                        glassesManager.disconnect()
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "GlassesInputCoord"
    }
}
