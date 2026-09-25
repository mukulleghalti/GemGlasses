package com.geno.veyra.wakeword

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.geno.veyra.agent.AgentController
import com.geno.veyra.agent.AgentStatus
import com.geno.veyra.glasses.ConnectionState
import com.geno.veyra.glasses.GlassesManager
import com.geno.veyra.settings.AgentPreferences
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

    /** Idempotent; call once from [com.geno.veyra.VeyraApp.onCreate]. */
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

                // startForegroundService() can throw when the app is in the
                // background (Android 12+ restrictions); never let that kill
                // the coordinator — the next state change retries.
                runCatching {
                    if (shouldListen) {
                        WakeWordService.start(context, inputs.prefs.wakePhrase)
                    } else {
                        WakeWordService.stop(context)
                    }
                }.onFailure { e ->
                    Log.w(TAG, "wake-word service toggle failed", e)
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
