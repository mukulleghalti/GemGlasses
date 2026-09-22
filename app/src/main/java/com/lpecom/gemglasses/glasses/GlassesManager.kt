package com.lpecom.gemglasses.glasses

import android.app.Activity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GlassesManager @Inject constructor(
    private val backend: GlassesBackend,
) {

    val registrationState: Flow<RegistrationState> =
        backend.registrationState

    val devices: Flow<List<GlassesDevice>> =
        backend.devices

    fun initialize() {
        backend.initialize()
    }

    fun startRegistration() {
        backend.startRegistration()
    }

    suspend fun connect(): Boolean =
        backend.connect()

    fun setActivity(activity: Activity) {
        backend.setActivity(activity)
    }

    fun clearActivity(activity: Activity) {
        backend.clearActivity(activity)
    }

    suspend fun cameraPermission(): CameraPermission =
        backend.cameraPermission()

    suspend fun ensureCameraPermission(): Boolean =
        when (backend.cameraPermission()) {
            CameraPermission.GRANTED -> true

            else ->
                backend.requestCameraPermission() ==
                    CameraPermission.GRANTED
        }

    fun cameraFrames(): Flow<ByteArray> =
        backend.cameraFrames()
}
