package com.lpecom.gemglasses

import android.app.Application
import android.util.Log
import com.meta.wearable.dat.core.Wearables
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class GemGlassesApp : Application() {

    companion object {
        private const val TAG = "GemGlassesApp"
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
