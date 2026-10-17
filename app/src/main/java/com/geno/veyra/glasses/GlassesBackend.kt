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
    /** Glasses battery percentage (0–100), null when the SDK reports none. */
    val batteryPercent: Int? = null,
    /** True while charging, false when not, null when unknown. */
    val batteryCharging: Boolean? = null,
    /** DAT DonState name (DONNED / DOFFED), null when unknown. */
    val donState: String? = null,
    /** DAT HingeState name (OPEN / CLOSED), null when unknown. */
    val hingeState: String? = null,
    /** DAT ThermalLevel name (NONE … SHUTDOWN), null when unknown. */
    val thermalLevel: String? = null,
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

/** One frame of the live-stream feed: compressed H.264 video or PCM audio. */
sealed interface LiveFrame {

    val ptsUs: Long

    class Video(
        val data: ByteArray,
        val width: Int,
        val height: Int,
        override val ptsUs: Long,
        val isCompressed: Boolean,
        val isCodecConfig: Boolean,
    ) : LiveFrame

    class Audio(
        val data: ByteArray,
        override val ptsUs: Long,
    ) : LiveFrame
}

enum class CameraPermission {
    GRANTED,
    DENIED,
    NOT_DETERMINED,
}

/** The three presses the glasses capture button reports (DAT CapturePressType). */
enum class CapturePress {
    SHORT_PRESS,
    HOLD,
    DOUBLE_PRESS,
}

/**
 * A physical input event from the glasses, mapped from the DAT Inputs
 * capability into our own types so the rest of the app never depends on
 * SDK classes.
 */
sealed interface GlassesInputEvent {

    val timestampMs: Long

    /** A capture-button press; [press] says which kind. */
    data class Capture(
        val press: CapturePress,
        override val timestampMs: Long,
    ) : GlassesInputEvent

    /** Any other input (touchpad nav, drag, select, back) — spike logging only. */
    data class Other(
        val description: String,
        override val timestampMs: Long,
    ) : GlassesInputEvent
}

interface GlassesBackend {

    val registrationState: Flow<RegistrationState>

    val connectionState: Flow<ConnectionState>

    val devices: Flow<List<GlassesDevice>>

    /** Physical glasses input events (capture button, touchpad). */
    val inputEvents: Flow<GlassesInputEvent>
        get() = emptyFlow()

    fun initialize()

    fun startRegistration()

    suspend fun cameraPermission(): CameraPermission

    suspend fun requestCameraPermission(): CameraPermission

    fun setCameraPermissionRequester(
        requester: suspend () -> CameraPermission,
    ) {}

    /**
     * Capture-button listener switch. When false (the default), sessions
     * never attach DAT Inputs, so the capture button stays with the
     * glasses (native / Meta AI photos) while Veyra is connected.
     */
    suspend fun setCaptureButtonEnabled(enabled: Boolean) {}

    fun cameraFrames(): Flow<ByteArray>

    fun setActivity(activity: Activity)

    fun clearActivity(activity: Activity)

    suspend fun connect(): Boolean = false

    /** Releases the glasses session, if one is held. Default: nothing held. */
    suspend fun disconnect() = Unit

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

    /** Glasses video + mic audio for live streaming (RTMP). */
    fun liveFrames(): Flow<LiveFrame> = emptyFlow()

    suspend fun captureCameraTestPhoto(): Result<ByteArray> =
        Result.failure(
            UnsupportedOperationException("Camera Test not available"),
        )

    fun stopCameraTest() {}
}
