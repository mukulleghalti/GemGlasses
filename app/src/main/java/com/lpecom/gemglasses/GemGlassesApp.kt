package com.lpecom.gemglasses

import android.app.Application
import android.util.Log
import com.lpecom.gemglasses.glasses.GlassesManager
import com.meta.wearable.dat.core.Wearables
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class GemGlassesApp : Application() {

    @Inject
    lateinit var glassesManager: GlassesManager

    override fun onCreate() {
        super.onCreate()

        Log.i("GemGlassesApp", "Initializing MWDAT once for process")

        try {
            Wearables.initialize(this)

            Log.i(
                "GemGlassesApp",
                "Wearables.initialize() SUCCESS"
            )
        } catch (e: Exception) {
            Log.e(
                "GemGlassesApp",
                "Wearables.initialize() FAILED",
                e
            )
        }

        Log.i(
            "GemGlassesApp",
            "Initializing glasses backend"
        )

        glassesManager.initialize()
    }
}
