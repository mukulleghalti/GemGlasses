package com.lpecom.gemglasses.glasses.real

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.lpecom.gemglasses.glasses.CameraPermission
import com.lpecom.gemglasses.glasses.ConnectionState
import com.lpecom.gemglasses.glasses.GlassesBackend
import com.lpecom.gemglasses.glasses.GlassesDevice
import com.lpecom.gemglasses.glasses.RegistrationState
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.removeCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Device
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState as MWDATRegistrationState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealGlassesBackend @Inject constructor(
    @ApplicationContext private val context: Context,
) : GlassesBackend {

    companion object {
        private const val TAG = "RealGlassesBackend"

        private const val UNKNOWN_NAME = "Unknown"
        private const val DEFAULT_NAME = "Ray-Ban Meta"

        private const val SESSION_START_TIMEOUT_MS = 20_000L
        private const val REGISTRATION_TIMEOUT_MS = 10_000L

        private const val CAMERA_STREAM_TIMEOUT_MS = 15_000L
        private const val CAMERA_SETTLE_DELAY_MS = 1_500L
        private const val CAMERA_FIRST_FRAME_TIMEOUT_MS = 10_000L

        /*
         * Gemini vision camera configuration.
         *
         * Keep this fixed. Camera Test settings must never change
         * the existing Gemini vision path.
         */
        private const val GEMINI_CAMERA_FRAME_RATE = 24

        /*
         * Default Camera Test configuration.
         *
         * These values are overwritten by CameraTestViewModel when
         * the user has selected a different setting.
         */
        private var cameraTestVideoQuality =
            VideoQuality.MEDIUM

        private var cameraTestFrameRate =
            24

        private const val JPEG_QUALITY = 90

        /*
         * MWDAT allows only one DeviceSession per device.
         */
        @Volatile
        private var sharedSession: DeviceSession? = null
    }

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var activity: Activity? = null

    private var session: DeviceSession? = null

    private var camera:
        com.meta.wearable.dat.camera.Camera? = null

    private var cameraPermissionRequester:
        (suspend () -> CameraPermission)? = null

    private val _registrationState =
        MutableStateFlow(RegistrationState.UNKNOWN)

    override val registrationState: Flow<RegistrationState> =
        _registrationState.asStateFlow()

    private val _connectionState =
        MutableStateFlow(ConnectionState.DISCONNECTED)

    override val connectionState: Flow<ConnectionState> =
        _connectionState.asStateFlow()

    private val _devices =
        MutableStateFlow<List<GlassesDevice>>(emptyList())

    override val devices: Flow<List<GlassesDevice>> =
        _devices.asStateFlow()

    @Volatile
    private var initialized = false

    init {
        session = sharedSession

        Log.i(TAG, "RealGlassesBackend created")

        if (session != null) {
            Log.i(
                TAG,
                "Reusing existing MWDAT DeviceSession: " +
                    session?.state?.value
            )
        }
    }

    // =========================================================================
    // CAMERA TEST CONFIGURATION
    // =========================================================================

    /**
     * Configure the camera used by Camera Test.
     *
     * This does NOT affect cameraFrames(), which is the Gemini vision path.
     */
    fun setCameraTestConfiguration(
        videoQuality: VideoQuality,
        frameRate: Int,
    ) {
        require(
            frameRate == 2 ||
                frameRate == 7 ||
                frameRate == 15 ||
                frameRate == 24 ||
                frameRate == 30
        ) {
            "Unsupported MWDAT camera frame rate: $frameRate"
        }

        cameraTestVideoQuality = videoQuality
        cameraTestFrameRate = frameRate

        Log.i(
            TAG,
            "Camera Test configuration: " +
                "quality=$videoQuality, fps=$frameRate"
        )
    }

    // =========================================================================
    // ACTIVITY
    // =========================================================================

    override fun setActivity(activity: Activity) {
        this.activity = activity
        Log.i(
            TAG,
            "Activity attached: ${activity::class.java.simpleName}"
        )
    }

    override fun clearActivity(activity: Activity) {
        if (this.activity === activity) {
            this.activity = null
            Log.i(TAG, "Activity detached")
        }
    }

    // =========================================================================
    // CAMERA PERMISSION REQUESTER
    // =========================================================================

    override fun setCameraPermissionRequester(
        requester: suspend () -> CameraPermission,
    ) {
        cameraPermissionRequester = requester
    }

    // =========================================================================
    // INITIALIZE
    // =========================================================================

    override fun initialize() {

        if (initialized) {
            Log.i(TAG, "Backend already initialized")
            return
        }

        initialized = true

        if (session == null) {
            session = sharedSession
        }

        Log.i(TAG, "Initializing glasses backend")

        observeRegistrationState()
        observeDevices()

        val existingSession = session

        if (
            existingSession != null &&
            existingSession.state.value ==
                DeviceSessionState.STARTED
        ) {

            _connectionState.value =
                ConnectionState.CONNECTED

            observeSessionErrors(existingSession)
            observeSessionState(existingSession)
        }
    }

    // =========================================================================
    // REGISTRATION
    // =========================================================================

    override fun startRegistration() {

        val currentActivity =
            activity

        if (currentActivity == null) {
            Log.e(
                TAG,
                "Cannot register glasses: Activity is null"
            )
            return
        }

        try {

            Wearables.startRegistration(
                currentActivity
            )

            _registrationState.value =
                RegistrationState.REGISTERING

            Log.i(TAG, "Glasses registration started")

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Glasses registration failed",
                e
            )

            _registrationState.value =
                RegistrationState.UNKNOWN
        }
    }

    private suspend fun waitForRegistration():
        RegistrationState {

        val current =
            Wearables.registrationState.value

        if (
            current ==
                MWDATRegistrationState.REGISTERED
        ) {

            _registrationState.value =
                RegistrationState.REGISTERED

            return RegistrationState.REGISTERED
        }

        val resolved =
            withTimeoutOrNull(
                REGISTRATION_TIMEOUT_MS
            ) {

                Wearables.registrationState.first { state ->

                    state ==
                        MWDATRegistrationState.REGISTERED ||
                        state ==
                        MWDATRegistrationState.AVAILABLE ||
                        state ==
                        MWDATRegistrationState.UNAVAILABLE
                }
            }

        if (resolved == null) {

            Log.e(
                TAG,
                "Timed out waiting for MWDAT registration"
            )

            mapMWDATRegistrationState(
                Wearables.registrationState.value
            )

            return _registrationState.value
        }

        mapMWDATRegistrationState(
            resolved
        )

        return _registrationState.value
    }

    // =========================================================================
    // CONNECTION
    // =========================================================================

    override suspend fun connect(): Boolean {

        Log.i(TAG, "Connecting to Meta glasses")

        _connectionState.value =
            ConnectionState.CONNECTING

        val registration =
            waitForRegistration()

        if (
            registration !=
                RegistrationState.REGISTERED
        ) {

            Log.e(
                TAG,
                "Cannot connect: MWDAT registration is $registration"
            )

            _connectionState.value =
                ConnectionState.ERROR

            return false
        }

        if (session == null) {
            session = sharedSession
        }

        var activeSession =
            session

        /*
         * Reuse an existing session whenever possible.
         */
        if (activeSession != null) {

            when (activeSession.state.value) {

                DeviceSessionState.STARTED -> {

                    observeSessionErrors(activeSession)
                    observeSessionState(activeSession)

                    _connectionState.value =
                        ConnectionState.CONNECTED

                    Log.i(
                        TAG,
                        "Reusing existing MWDAT session"
                    )

                    return true
                }

                DeviceSessionState.STARTING -> {

                    val started =
                        withTimeoutOrNull(
                            SESSION_START_TIMEOUT_MS
                        ) {

                            activeSession.state.first { state ->

                                state ==
                                    DeviceSessionState.STARTED ||
                                    state ==
                                    DeviceSessionState.STOPPED
                            }

                            true
                        } ?: false

                    if (
                        started &&
                        activeSession.state.value ==
                            DeviceSessionState.STARTED
                    ) {

                        _connectionState.value =
                            ConnectionState.CONNECTED

                        return true
                    }
                }

                DeviceSessionState.STOPPED -> {

                    session = null

                    if (
                        sharedSession ===
                            activeSession
                    ) {
                        sharedSession = null
                    }

                    activeSession = null
                }

                else -> Unit
            }
        }

        /*
         * Create a new session only when there is no usable one.
         */
        val sessionResult =
            try {

                Wearables.createSession(
                    AutoDeviceSelector()
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "createSession() threw",
                    e
                )

                _connectionState.value =
                    ConnectionState.ERROR

                return false
            }

        val createdSession =
            sessionResult.getOrElse { error ->

                Log.e(
                    TAG,
                    "createSession() failed: $error"
                )

                _connectionState.value =
                    ConnectionState.ERROR

                return false
            }

        session =
            createdSession

        sharedSession =
            createdSession

        observeSessionErrors(
            createdSession
        )

        observeSessionState(
            createdSession
        )

        try {

            createdSession.start()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "DeviceSession.start() failed",
                e
            )

            _connectionState.value =
                ConnectionState.ERROR

            return false
        }

        val started =
            withTimeoutOrNull(
                SESSION_START_TIMEOUT_MS
            ) {

                createdSession.state.first { state ->

                    state ==
                        DeviceSessionState.STARTED
                }

                true

            } ?: false

        if (!started) {

            Log.e(
                TAG,
                "DeviceSession did not reach STARTED: " +
                    createdSession.state.value
            )

            _connectionState.value =
                ConnectionState.ERROR

            return false
        }

        _connectionState.value =
            ConnectionState.CONNECTED

        Log.i(
            TAG,
            "Meta glasses connected"
        )

        return true
    }

    // =========================================================================
    // SESSION STATE
    // =========================================================================

    private fun observeSessionState(
        activeSession: DeviceSession,
    ) {

        scope.launch {

            try {

                activeSession.state.collect { state ->

                    when (state) {

                        DeviceSessionState.STARTED -> {

                            if (
                                session ===
                                    activeSession
                            ) {

                                _connectionState.value =
                                    ConnectionState.CONNECTED
                            }
                        }

                        DeviceSessionState.STOPPED -> {

                            if (
                                session ===
                                    activeSession
                            ) {

                                _connectionState.value =
                                    ConnectionState.DISCONNECTED

                                if (
                                    sharedSession ===
                                        activeSession
                                ) {
                                    sharedSession = null
                                }

                                session = null
                            }
                        }

                        else -> Unit
                    }
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Session state observer failed",
                    e
                )

                if (
                    session ===
                        activeSession
                ) {

                    _connectionState.value =
                        ConnectionState.ERROR
                }
            }
        }
    }

    // =========================================================================
    // SESSION ERRORS
    // =========================================================================

    private fun observeSessionErrors(
        activeSession: DeviceSession,
    ) {

        scope.launch {

            try {

                activeSession.errors.collect { error ->

                    Log.e(
                        TAG,
                        "MWDAT session error: $error"
                    )

                    if (
                        session ===
                            activeSession
                    ) {

                        _connectionState.value =
                            ConnectionState.ERROR
                    }
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Session error observer failed",
                    e
                )
            }
        }
    }

    // =========================================================================
    // DEVICE OBSERVATION
    // =========================================================================

    private fun observeDevices() {

        scope.launch {

            try {

                Wearables.devices.collect { deviceIds ->

                    if (deviceIds.isEmpty()) {
                        _devices.value =
                            emptyList()

                        return@collect
                    }

                    for (deviceId in deviceIds) {
                        observeDeviceMetadata(deviceId)
                    }
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Device observer failed",
                    e
                )
            }
        }
    }

    private fun observeDeviceMetadata(
        deviceId: DeviceIdentifier,
    ) {

        scope.launch {

            try {

                val metadataFlow =
                    Wearables.devicesMetadata[
                        deviceId
                    ]

                if (metadataFlow == null) {
                    return@launch
                }

                metadataFlow.collect { device ->

                    val glassesDevice =
                        toGlassesDevice(
                            deviceId,
                            device
                        )

                    val updated =
                        _devices.value
                            .filter {
                                it.id !=
                                    deviceId.toString()
                            }
                            .toMutableList()

                    updated.add(
                        glassesDevice
                    )

                    _devices.value =
                        updated
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Device metadata observer failed",
                    e
                )
            }
        }
    }

    private fun toGlassesDevice(
        id: DeviceIdentifier,
        device: Device,
    ): GlassesDevice {

        val rawName =
            device.name.toString()

        val displayName =
            if (
                rawName.isBlank() ||
                rawName.equals(
                    UNKNOWN_NAME,
                    ignoreCase = true
                )
            ) {
                DEFAULT_NAME
            } else {
                rawName
            }

        return GlassesDevice(
            id = id.toString(),
            name = displayName,
            connected =
                device.linkState ==
                    LinkState.CONNECTED,
        )
    }

    // =========================================================================
    // REGISTRATION OBSERVER
    // =========================================================================

    private fun observeRegistrationState() {

        scope.launch {

            try {

                Wearables.registrationState.collect { state ->

                    mapMWDATRegistrationState(
                        state
                    )
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Registration observer failed",
                    e
                )

                _registrationState.value =
                    RegistrationState.UNKNOWN
            }
        }
    }

    private fun mapMWDATRegistrationState(
        state: MWDATRegistrationState,
    ) {

        _registrationState.value =
            when (state) {

                MWDATRegistrationState.REGISTERED ->
                    RegistrationState.REGISTERED

                MWDATRegistrationState.REGISTERING ->
                    RegistrationState.REGISTERING

                MWDATRegistrationState.AVAILABLE ->
                    RegistrationState.NOT_REGISTERED

                MWDATRegistrationState.UNAVAILABLE ->
                    RegistrationState.UNKNOWN

                MWDATRegistrationState.UNREGISTERING ->
                    RegistrationState.REVOKED
            }
    }

    // =========================================================================
    // CAMERA PERMISSION
    // =========================================================================

    override suspend fun cameraPermission():
        CameraPermission {

        return try {

            val result =
                Wearables.checkPermissionStatus(
                    Permission.CAMERA
                )

            val status =
                result.getOrElse {
                    return CameraPermission.NOT_DETERMINED
                }

            when (status) {

                PermissionStatus.Granted ->
                    CameraPermission.GRANTED

                PermissionStatus.Denied ->
                    CameraPermission.DENIED
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Camera permission check failed",
                e
            )

            CameraPermission.NOT_DETERMINED
        }
    }

    override suspend fun requestCameraPermission():
        CameraPermission {

        if (
            cameraPermission() ==
                CameraPermission.GRANTED
        ) {
            return CameraPermission.GRANTED
        }

        val requester =
            cameraPermissionRequester

        if (requester == null) {

            Log.e(
                TAG,
                "Camera permission requester is not registered"
            )

            return CameraPermission.NOT_DETERMINED
        }

        return try {

            requester()

            cameraPermission()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Camera permission request failed",
                e
            )

            CameraPermission.NOT_DETERMINED
        }
    }

    // =========================================================================
    // GEMINI CAMERA FLOW
    // =========================================================================

    /**
     * Existing Gemini vision flow.
     *
     * Deliberately fixed to MEDIUM / 24 FPS.
     *
     * Camera Test settings do not affect this path.
     */
    override fun cameraFrames():
        Flow<ByteArray> = channelFlow {

        Log.i(
            TAG,
            "Starting Gemini camera capture"
        )

        var activeSession =
            session ?: sharedSession

        if (
            activeSession == null ||
            activeSession.state.value !=
                DeviceSessionState.STARTED
        ) {

            val connected =
                try {
                    connect()
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "Gemini camera connection failed",
                        e
                    )
                    false
                }

            if (!connected) {
                return@channelFlow
            }

            activeSession =
                session ?: sharedSession
        }

        if (
            activeSession == null ||
            activeSession.state.value !=
                DeviceSessionState.STARTED
        ) {
            Log.e(
                TAG,
                "Gemini camera: no STARTED DeviceSession"
            )
            return@channelFlow
        }

        if (
            cameraPermission() !=
                CameraPermission.GRANTED
        ) {

            Log.e(
                TAG,
                "Gemini camera: camera permission not granted"
            )

            return@channelFlow
        }

        val activeCamera =
            try {

                activeSession.addCamera(
                    StreamConfiguration(
                        videoQuality =
                            VideoQuality.MEDIUM,
                        frameRate =
                            GEMINI_CAMERA_FRAME_RATE,
                        compressVideo = true,
                    )
                ).getOrElse { error ->

                    Log.e(
                        TAG,
                        "Gemini addCamera() failed: $error"
                    )

                    return@channelFlow
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Gemini addCamera() threw",
                    e
                )

                return@channelFlow
            }

        camera =
            activeCamera

        try {

            activeCamera.stream
                .start()
                .getOrElse { error ->

                    Log.e(
                        TAG,
                        "Gemini camera stream start failed: $error"
                    )

                    return@channelFlow
                }

            val streaming =
                withTimeoutOrNull(
                    CAMERA_STREAM_TIMEOUT_MS
                ) {

                    activeCamera.stream.state.first {
                        it ==
                            StreamState.STREAMING
                    }

                    true

                } ?: false

            if (!streaming) {

                Log.e(
                    TAG,
                    "Gemini camera stream did not reach STREAMING"
                )

                return@channelFlow
            }

            val firstRealFrame =
                CompletableDeferred<VideoFrame>()

            val videoCollectorJob =
                launch {

                    try {

                        activeCamera.stream.videoStream.collect { frame ->

                            val bytes =
                                frame.buffer.remaining()

                            if (
                                !frame.isCodecConfig &&
                                bytes > 0 &&
                                !firstRealFrame.isCompleted
                            ) {

                                firstRealFrame.complete(
                                    frame
                                )
                            }
                        }

                    } catch (e: Exception) {

                        if (
                            !firstRealFrame.isCompleted
                        ) {

                            firstRealFrame.completeExceptionally(
                                e
                            )
                        }
                    }
                }

            val firstFrameReceived =
                withTimeoutOrNull(
                    CAMERA_FIRST_FRAME_TIMEOUT_MS
                ) {

                    try {
                        firstRealFrame.await()
                        true
                    } catch (_: Exception) {
                        false
                    }

                } ?: false

            if (!firstFrameReceived) {

                Log.e(
                    TAG,
                    "Gemini camera: no video frame received"
                )

                videoCollectorJob.cancel()

                return@channelFlow
            }

            delay(
                CAMERA_SETTLE_DELAY_MS
            )

            if (
                activeCamera.stream.state.value !=
                    StreamState.STREAMING
            ) {

                Log.e(
                    TAG,
                    "Gemini camera stopped before photo capture"
                )

                videoCollectorJob.cancel()

                return@channelFlow
            }

            try {

                val captureResult =
                    activeCamera.stream.capturePhoto()

                val photoData =
                    captureResult.getOrNull()

                if (photoData == null) {

                    Log.e(
                        TAG,
                        "Gemini capturePhoto() failed: " +
                            captureResult.errorOrNull()
                    )

                } else {

                    val jpeg =
                        photoDataToJpeg(
                            photoData
                        )

                    if (
                        jpeg != null &&
                        jpeg.isNotEmpty()
                    ) {

                        send(jpeg)

                    } else {

                        Log.e(
                            TAG,
                            "Gemini photo JPEG conversion failed"
                        )
                    }
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Gemini capturePhoto() threw",
                    e
                )

            } finally {

                videoCollectorJob.cancel()
            }

        } finally {

            stopCameraIfNeeded()
        }
    }

    // =========================================================================
    // CAMERA TEST - LIVE VIDEO STREAM
    // =========================================================================

    fun cameraTestFrames():
        Flow<VideoFrame> = channelFlow {

        Log.i(
            TAG,
            "Starting Camera Test: " +
                "$cameraTestVideoQuality / " +
                "${cameraTestFrameRate} FPS"
        )

        var activeSession =
            session ?: sharedSession

        if (
            activeSession == null ||
            activeSession.state.value !=
                DeviceSessionState.STARTED
        ) {

            val connected =
                try {
                    connect()
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "Camera Test connection failed",
                        e
                    )
                    false
                }

            if (!connected) {
                return@channelFlow
            }

            activeSession =
                session ?: sharedSession
        }

        if (
            activeSession == null ||
            activeSession.state.value !=
                DeviceSessionState.STARTED
        ) {

            Log.e(
                TAG,
                "Camera Test: no STARTED DeviceSession"
            )

            return@channelFlow
        }

        if (
            cameraPermission() !=
                CameraPermission.GRANTED
        ) {

            Log.e(
                TAG,
                "Camera Test: camera permission not granted"
            )

            return@channelFlow
        }

        if (camera != null) {
            stopCameraIfNeeded()
        }

        val activeCamera =
            try {

                activeSession.addCamera(
                    StreamConfiguration(
                        videoQuality =
                            cameraTestVideoQuality,
                        frameRate =
                            cameraTestFrameRate,
                        compressVideo = true,
                    )
                ).getOrElse { error ->

                    Log.e(
                        TAG,
                        "Camera Test addCamera() failed: $error"
                    )

                    return@channelFlow
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Camera Test addCamera() threw",
                    e
                )

                return@channelFlow
            }

        camera =
            activeCamera

        try {

            activeCamera.stream
                .start()
                .getOrElse { error ->

                    Log.e(
                        TAG,
                        "Camera Test stream start failed: $error"
                    )

                    return@channelFlow
                }

            val streaming =
                withTimeoutOrNull(
                    CAMERA_STREAM_TIMEOUT_MS
                ) {

                    activeCamera.stream.state.first {
                        it ==
                            StreamState.STREAMING
                    }

                    true

                } ?: false

            if (!streaming) {

                Log.e(
                    TAG,
                    "Camera Test stream did not reach STREAMING"
                )

                return@channelFlow
            }

            Log.i(
                TAG,
                "Camera Test streaming"
            )

            activeCamera.stream.videoStream.collect { frame ->

                send(frame)
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Camera Test video stream failed",
                e
            )

        } finally {

            stopCameraIfNeeded()
        }
    }

    // =========================================================================
    // CAMERA TEST - PHOTO CAPTURE
    // =========================================================================

    suspend fun captureCameraTestPhoto():
        Result<ByteArray> {

        val activeCamera =
            camera

        if (activeCamera == null) {

            return Result.failure(
                IllegalStateException(
                    "No active camera. Start the camera stream first."
                )
            )
        }

        if (
            activeCamera.stream.state.value !=
                StreamState.STREAMING
        ) {

            return Result.failure(
                IllegalStateException(
                    "Camera stream is not STREAMING: " +
                        activeCamera.stream.state.value
                )
            )
        }

        return try {

            val captureResult =
                activeCamera.stream.capturePhoto()

            val photoData =
                captureResult.getOrNull()

            if (photoData == null) {

                val exception =
                    captureResult.exceptionOrNull()

                val error =
                    captureResult.errorOrNull()

                Log.e(
                    TAG,
                    "Camera Test photo capture failed: " +
                        (exception ?: error)
                )

                Result.failure(
                    exception
                        ?: IllegalStateException(
                            "capturePhoto failed: $error"
                        )
                )

            } else {

                val jpeg =
                    photoDataToJpeg(
                        photoData
                    )

                if (
                    jpeg == null ||
                    jpeg.isEmpty()
                ) {

                    Result.failure(
                        IllegalStateException(
                            "Photo captured but JPEG conversion failed"
                        )
                    )

                } else {

                    Log.i(
                        TAG,
                        "Camera Test photo captured: " +
                            "${jpeg.size} bytes"
                    )

                    Result.success(jpeg)
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Camera Test capturePhoto() threw",
                e
            )

            Result.failure(e)
        }
    }

    fun stopCameraTest() {
        stopCameraIfNeeded()
    }

    // =========================================================================
    // PHOTO CONVERSION
    // =========================================================================

    private fun photoDataToJpeg(
        photoData: PhotoData,
    ): ByteArray? {

        return try {

            when (photoData) {

                is PhotoData.Bitmap -> {

                    bitmapToJpeg(
                        photoData.bitmap
                    )
                }

                is PhotoData.HEIC -> {

                    heicToJpeg(
                        photoData.data
                    )
                }

                else -> {

                    Log.w(
                        TAG,
                        "Unsupported PhotoData: " +
                            photoData::class.java.name
                    )

                    null
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Photo conversion failed",
                e
            )

            null
        }
    }

    private fun bitmapToJpeg(
        bitmap: Bitmap,
    ): ByteArray? {

        if (bitmap.isRecycled) {
            return null
        }

        val output =
            ByteArrayOutputStream()

        return try {

            if (
                !bitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    JPEG_QUALITY,
                    output
                )
            ) {

                null

            } else {

                output.toByteArray()
            }

        } finally {

            try {
                output.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun heicToJpeg(
        buffer: ByteBuffer,
    ): ByteArray? {

        val bytes =
            byteBufferToByteArray(
                buffer
            )

        if (bytes.isEmpty()) {
            return null
        }

        val bitmap =
            if (
                Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.R
            ) {

                try {

                    val source =
                        ImageDecoder.createSource(
                            ByteBuffer.wrap(bytes)
                        )

                    ImageDecoder.decodeBitmap(
                        source
                    )

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "HEIC ImageDecoder failed",
                        e
                    )

                    null
                }

            } else {

                try {

                    BitmapFactory.decodeByteArray(
                        bytes,
                        0,
                        bytes.size
                    )

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "HEIC BitmapFactory decode failed",
                        e
                    )

                    null
                }
            }

        if (bitmap == null) {
            return null
        }

        return try {

            bitmapToJpeg(
                bitmap
            )

        } finally {

            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun byteBufferToByteArray(
        buffer: ByteBuffer,
    ): ByteArray {

        val duplicate =
            buffer.duplicate()

        val bytes =
            ByteArray(
                duplicate.remaining()
            )

        duplicate.get(bytes)

        return bytes
    }

    // =========================================================================
    // CAMERA CLEANUP
    // =========================================================================

    private fun stopCameraIfNeeded() {

        val activeCamera =
            camera

        val activeSession =
            session ?: sharedSession

        if (activeCamera == null) {
            return
        }

        try {

            activeCamera.stop()

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Camera.stop() failed",
                e
            )
        }

        if (activeSession != null) {

            try {

                val result =
                    activeSession.removeCamera()

                if (!result.isSuccess) {

                    Log.w(
                        TAG,
                        "removeCamera() failed: " +
                            result.errorOrNull()
                    )
                }

            } catch (e: Exception) {

                Log.w(
                    TAG,
                    "removeCamera() threw",
                    e
                )
            }
        }

        camera = null
    }

    // =========================================================================
    // SHUTDOWN
    // =========================================================================

    fun shutdown() {

        scope.launch {

            val activeSession =
                session ?: sharedSession

            try {

                stopCameraIfNeeded()

                if (activeSession != null) {

                    try {

                        activeSession.stop()

                    } catch (e: Exception) {

                        Log.w(
                            TAG,
                            "DeviceSession.stop() failed",
                            e
                        )
                    }

                    withTimeoutOrNull(
                        SESSION_START_TIMEOUT_MS
                    ) {

                        activeSession.state.first {
                            it ==
                                DeviceSessionState.STOPPED
                        }
                    }
                }

            } catch (e: Exception) {

                Log.w(
                    TAG,
                    "Error shutting down MWDAT session",
                    e
                )

            } finally {

                if (
                    sharedSession ===
                        activeSession
                ) {
                    sharedSession = null
                }

                session = null

                _connectionState.value =
                    ConnectionState.DISCONNECTED

                scope.cancel()
            }
        }
    }

    private fun activeSessionOrNull():
        DeviceSession? {

        return session ?: sharedSession
    }
}
