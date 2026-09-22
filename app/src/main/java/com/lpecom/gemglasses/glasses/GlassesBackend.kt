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

enum class CameraPermission {
    GRANTED,
    DENIED,
    NOT_DETERMINED,
}

interface GlassesBackend {

    val registrationState: Flow<RegistrationState>

    val devices: Flow<List<GlassesDevice>>

    fun initialize()

    fun startRegistration()

    suspend fun cameraPermission(): CameraPermission

    suspend fun requestCameraPermission(): CameraPermission

    fun cameraFrames(): Flow<ByteArray>

    /**
     * Gives the backend the current Activity when an Activity is available.
     * Real MWDAT backend uses this for registration / permission flows.
     */
    fun setActivity(activity: Activity) {
        // Default no-op for mock backends.
    }

    /**
     * Removes the Activity reference when it is destroyed.
     */
    fun clearActivity(activity: Activity) {
        // Default no-op for mock backends.
    }
}
