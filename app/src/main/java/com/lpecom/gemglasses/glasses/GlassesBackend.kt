package com.lpecom.gemglasses.glasses

import android.app.Activity
import kotlinx.coroutines.flow.Flow

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
}
