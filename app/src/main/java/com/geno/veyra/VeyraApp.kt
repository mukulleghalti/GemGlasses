package com.geno.veyra

import android.app.Application
import android.util.Log
import com.geno.veyra.wakeword.WakeWordCoordinator
import com.meta.wearable.dat.core.Wearables
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class VeyraApp : Application() {

    @Inject
    lateinit var wakeWordCoordinator: WakeWordCoordinator

    companion object {
        private const val TAG = "VeyraApp"
    }

    override fun onCreate() {
        super.onCreate()

        // Starts observing wake-word prefs; the listener service itself only
        // runs when enabled, glasses are connected, and no session is active.
        wakeWordCoordinator.start()
    }

    @Volatile
    private var wearablesInitialized = false

    /**
     * Initializes the Meta Wearables DAT SDK exactly once per process.
     *
     * IMPORTANT:
     * This must only be called after the required Bluetooth runtime
     * permissions have been granted.
     */
    fun initializeWearables(): Boolean {

        if (wearablesInitialized) {
            Log.i(
                TAG,
                "Wearables SDK already initialized; ignoring duplicate call"
            )
            return true
        }

        return try {

            Log.i(
                TAG,
                "Initializing MWDAT SDK"
            )

            Wearables.initialize(this)

            wearablesInitialized = true

            Log.i(
                TAG,
                "Wearables.initialize() SUCCESS"
            )

            true

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Wearables.initialize() FAILED",
                e
            )

            false
        }
    }
}
