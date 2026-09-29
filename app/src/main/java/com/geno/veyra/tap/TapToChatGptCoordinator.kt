package com.geno.veyra.tap

import android.content.Context
import android.util.Log
import com.geno.veyra.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts/stops [TapToChatGptService] from the "Tap to ChatGPT" Settings
 * toggle. Deliberately independent of the glasses connection: like
 * Chachan's standby mode, the tap listener just needs to be registered —
 * the tap itself only exists when the glasses are worn and connected.
 */
@Singleton
class TapToChatGptCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    /** Idempotent; call once from [com.geno.veyra.VeyraApp.onCreate]. */
    fun start() {
        if (started) return
        started = true

        scope.launch {
            settings.preferences.collect { prefs ->
                val enabled = prefs.tapToChatGptEnabled
                Log.d(TAG, "tap-to-chatgpt enabled=$enabled")
                // startForegroundService() can throw when the app is in the
                // background (Android 12+ restrictions); never let that kill
                // the coordinator — the next prefs change retries.
                runCatching {
                    if (enabled) TapToChatGptService.start(context)
                    else TapToChatGptService.stop(context)
                }.onFailure { e ->
                    Log.w(TAG, "tap-to-chatgpt service toggle failed", e)
                }
            }
        }
    }

    companion object {
        private const val TAG = "TapToChatGptCoordinator"
    }
}
