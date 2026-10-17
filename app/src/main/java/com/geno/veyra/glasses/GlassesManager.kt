package com.geno.veyra.glasses

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

    val inputEvents: Flow<GlassesInputEvent> =
        backend.inputEvents

    suspend fun setCaptureButtonEnabled(enabled: Boolean) {
        backend.setCaptureButtonEnabled(enabled)
    }

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

    fun setMicrophonePermissionRequester(
        requester: suspend () -> CameraPermission,
    ) {
        backend.setMicrophonePermissionRequester(requester)
    }

    suspend fun cameraPermission(): CameraPermission =
        backend.cameraPermission()

    suspend fun microphonePermission(): CameraPermission =
        backend.microphonePermission()

    /**
     * Ensures every DAT permission the camera features need. Since
     * DAT 1.0, camera streams always carry glasses-mic audio, so
     * MICROPHONE is required alongside CAMERA — without it, streams
     * stall in STARTING (MICROPHONE_PERMISSION_DENIED) and the Photo
     * API never starts.
     */
    suspend fun ensureCameraPermission(): Boolean {
        val cameraOk = when (backend.cameraPermission()) {
            CameraPermission.GRANTED -> true

            CameraPermission.DENIED,
            CameraPermission.NOT_DETERMINED -> {
                backend.requestCameraPermission() ==
                    CameraPermission.GRANTED
            }
        }

        if (!cameraOk) {
            return false
        }

        return when (backend.microphonePermission()) {
            CameraPermission.GRANTED -> true

            CameraPermission.DENIED,
            CameraPermission.NOT_DETERMINED -> {
                backend.requestMicrophonePermission() ==
                    CameraPermission.GRANTED
            }
        }
    }

    fun cameraFrames(): Flow<ByteArray> =
        backend.cameraFrames()

    fun liveFrames(): Flow<LiveFrame> =
        backend.liveFrames()

    suspend fun connect(): Boolean =
        backend.connect()

    suspend fun disconnect() =
        backend.disconnect()
}
