package com.lpecom.gemglasses.glasses

import android.app.Activity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class GlassesManager @Inject constructor(
    private val backend: GlassesBackend,
) {

    val registrationState: Flow<RegistrationState> =
        backend.registrationState

    val connectionState: Flow<ConnectionState> =
        backend.connectionState

    val devices: Flow<List<GlassesDevice>> =
        backend.devices

    fun initialize() {
        backend.initialize()
    }

    fun startRegistration() {
        backend.startRegistration()
    }

    fun setActivity(activity: Activity) {
        backend.setActivity(activity)
    }

    fun clearActivity(activity: Activity) {
        backend.clearActivity(activity)
    }

    fun setCameraPermissionRequester(
        requester: suspend () -> CameraPermission,
    ) {
        backend.setCameraPermissionRequester(requester)
    }

    suspend fun cameraPermission(): CameraPermission =
        backend.cameraPermission()

    suspend fun ensureCameraPermission(): Boolean {
        return when (backend.cameraPermission()) {
            CameraPermission.GRANTED -> true

            CameraPermission.DENIED,
            CameraPermission.NOT_DETERMINED -> {
                backend.requestCameraPermission() ==
                    CameraPermission.GRANTED
            }
        }
    }

    fun cameraFrames(): Flow<ByteArray> =
        backend.cameraFrames()

    suspend fun connect(): Boolean =
        backend.connect()
}
