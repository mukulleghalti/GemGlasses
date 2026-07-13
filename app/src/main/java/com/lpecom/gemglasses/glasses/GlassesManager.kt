package com.lpecom.gemglasses.glasses

import android.util.Log
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-facing entry point to the glasses. Delegates to whichever
 * [GlassesBackend] was selected at DI time (real SDK or mock) and adds no logic
 * of its own beyond logging — keeping the "glasses = dumb peripheral" boundary
 * clean.
 */
@Singleton
class GlassesManager @Inject constructor(
    private val backend: GlassesBackend,
) {
    val registrationState: Flow<RegistrationState> = backend.registrationState
    val devices: Flow<List<GlassesDevice>> = backend.devices

    fun initialize() {
        Log.i(TAG, "Initialising glasses backend: ${backend::class.simpleName}")
        backend.initialize()
    }

    fun startRegistration() = backend.startRegistration()

    suspend fun cameraPermission(): CameraPermission = backend.cameraPermission()

    suspend fun ensureCameraPermission(): Boolean =
        when (backend.cameraPermission()) {
            CameraPermission.GRANTED -> true
            else -> backend.requestCameraPermission() == CameraPermission.GRANTED
        }

    private companion object {
        const val TAG = "GlassesManager"
    }
}
