package com.lpecom.gemglasses.glasses

import kotlinx.coroutines.flow.Flow

/** A pair of glasses visible to the DAT SDK. */
data class GlassesDevice(
    val id: String,
    val name: String,
    val connected: Boolean,
)

/** Registration status with the Meta AI app. */
enum class RegistrationState { UNKNOWN, NOT_REGISTERED, REGISTERING, REGISTERED, REVOKED }

/** Result of a camera-permission check for the glasses camera. */
enum class CameraPermission { GRANTED, DENIED, NOT_DETERMINED }

/**
 * Everything GemGlasses needs from the Meta Wearables Device Access Toolkit,
 * behind one interface. Two implementations exist:
 *  - [MockGlassesBackend] (always available) for CI and hardware-free dev.
 *  - RealGlassesBackend (src/realGlasses, linked only when the SDK is present).
 *
 * The rest of the app depends only on this interface, honouring the spec's
 * "glasses = dumb peripheral" rule.
 */
interface GlassesBackend {
    val registrationState: Flow<RegistrationState>
    val devices: Flow<List<GlassesDevice>>

    /** Initialise the SDK once per process. Safe to call repeatedly. */
    fun initialize()

    /** Kick off registration with the Meta AI app (opens its flow). */
    fun startRegistration()

    suspend fun cameraPermission(): CameraPermission

    /** Request the glasses camera permission; returns the resulting state. */
    suspend fun requestCameraPermission(): CameraPermission

    /**
     * Opens the glasses camera and emits JPEG frames until the returned flow is
     * cancelled. Frames are already compressed and downscaled per the spec
     * (~1 fps, quality 70, longest side <= 768px).
     */
    fun cameraFrames(): Flow<ByteArray>
}
