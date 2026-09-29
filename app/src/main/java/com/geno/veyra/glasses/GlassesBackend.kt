package com.geno.veyra.glasses

import android.app.Activity
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class GlassesDevice(
    val id: String,
    val name: String,
    val connected: Boolean,
)

enum class RegistrationState {
    UNKNOWN,
    NOT_REGISTERED,
    REGISTERING,
    REGISTERED,
    REVOKED,
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

enum class CameraPermission {
    GRANTED,
    DENIED,
    NOT_DETERMINED,
}

interface GlassesBackend {

    val registrationState: Flow<RegistrationState>

    val connectionState: Flow<ConnectionState>

    val devices: Flow<List<GlassesDevice>>

    fun initialize()

    fun startRegistration()

    suspend fun cameraPermission(): CameraPermission

    suspend fun requestCameraPermission(): CameraPermission

    fun setCameraPermissionRequester(
        requester: suspend () -> CameraPermission,
    ) {}

    fun cameraFrames(): Flow<ByteArray>

    fun setActivity(activity: Activity)

    fun clearActivity(activity: Activity)

    suspend fun connect(): Boolean = false

    // === Camera Test ===
    //
    // Declared here (with safe defaults) so callers share the singleton
    // backend instance. The Meta camera permission requester registered by
    // MainActivity lives on that instance — a second backend object would
    // silently never prompt.

    fun setCameraTestConfiguration(
        videoQuality: VideoQuality,
        frameRate: Int,
    ) {}

    fun cameraTestFrames(): Flow<VideoFrame> = emptyFlow()

    suspend fun captureCameraTestPhoto(): Result<ByteArray> =
        Result.failure(
            UnsupportedOperationException("Camera Test not available"),
        )

    fun stopCameraTest() {}
}
