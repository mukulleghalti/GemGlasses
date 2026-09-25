package com.lpecom.gemglasses.wakeword

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.lpecom.gemglasses.agent.AgentController
import com.lpecom.gemglasses.agent.AgentStatus
import com.lpecom.gemglasses.glasses.ConnectionState
import com.lpecom.gemglasses.glasses.GlassesManager
import com.lpecom.gemglasses.settings.AgentPreferences
import com.lpecom.gemglasses.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides when the always-on wake-word listener should run.
 *
 * Listening starts only when the user enabled it in Settings, the glasses
 * are connected, the microphone permission is granted, and no assistant
 * session is active. After a detection the service starts the session and
 * stops itself; listening resumes automatically when the session ends.
 */
@Singleton
class WakeWordCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val glassesManager: GlassesManager,
    private val controller: AgentController,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    /** Idempotent; call once from [com.lpecom.gemglasses.GemGlassesApp.onCreate]. */
    fun start() {
        if (started) return
        started = true

        scope.launch {
            combine(
                settings.preferences,
                glassesManager.connectionState,
                controller.status,
                ::Inputs,
            ).collect { inputs ->
                val shouldListen = inputs.prefs.wakeWordEnabled &&
                    hasMicPermission() &&
                    inputs.connection == ConnectionState.CONNECTED &&
                    inputs.status == AgentStatus.IDLE

                Log.d(TAG, "wake-word listening=$shouldListen")

                if (shouldListen) {
                    WakeWordService.start(context, inputs.prefs.wakePhrase)
                } else {
                    WakeWordService.stop(context)
                }
            }
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    private data class Inputs(
        val prefs: AgentPreferences,
        val connection: ConnectionState,
        val status: AgentStatus,
    )

    private companion object {
        const val TAG = "WakeWordCoordinator"
    }
}
