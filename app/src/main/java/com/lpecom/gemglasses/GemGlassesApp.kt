package com.lpecom.gemglasses

import android.app.Application
import com.lpecom.gemglasses.glasses.GlassesManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class GemGlassesApp : Application() {

    @Inject lateinit var glassesManager: GlassesManager

    override fun onCreate() {
        super.onCreate()
        // Initialise the DAT SDK once per process (per Meta's requirement).
        glassesManager.initialize()
    }
}
