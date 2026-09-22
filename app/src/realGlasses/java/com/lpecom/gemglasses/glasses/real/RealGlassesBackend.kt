package com.lpecom.gemglasses.glasses.real

import android.app.Activity
import android.content.Context
import android.util.Log
import com.lpecom.gemglasses.glasses.CameraPermission
import com.lpecom.gemglasses.glasses.GlassesBackend
import com.lpecom.gemglasses.glasses.GlassesDevice
import com.lpecom.gemglasses.glasses.RegistrationState
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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

        private const val FRAME_RATE = 24
        private const val PHOTO_INTERVAL_MS = 1_000L
        private const val MAX_PHOTOS_PER_BURST = 20

        private const val SESSION_START_TIMEOUT_MS = 15_000L
    }

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val sessionMutex = Mutex()

    private var activity: Activity? = null

    private var session: DeviceSession? = null

    private var camera: Camera? = null

    private val _registrationState =
        MutableStateFlow(RegistrationState.UNKNOWN)

    override val registrationState: Flow<RegistrationState> =
        _registrationState.asStateFlow()

    private val _devices =
        MutableStateFlow<List<GlassesDevice>>(emptyList())

    override val devices: Flow<List<GlassesDevice>> =
        _devices.asStateFlow()

    init {
        observeDevices()
        observeRegistrationState()
    }

    // -------------------------------------------------------------------------
    // Activity
    // -------------------------------------------------------------------------

    override fun setActivity(activity: Activity) {
        this.activity = activity
        Log.d(TAG, "Activity attached")
    }

    override fun clearActivity(activity: Activity) {
        if (this.activity === activity) {
            this.activity = null
            Log.d(TAG, "Activity detached")
        }
    }

    // -------------------------------------------------------------------------
    // MWDAT initialization
    // -------------------------------------------------------------------------

    override fun initialize() {
        Log.i(
            TAG,
            "Initializing Meta Wearables Device Access Toolkit"
        )

        try {
            Wearables.initialize(context)

            Log.i(
                TAG,
                "Wearables.initialize() called successfully"
            )
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Failed to initialize Wearables",
                e
            )

            _registrationState.value =
                RegistrationState.UNKNOWN
        }
    }

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    override fun startRegistration() {
        val currentActivity = activity

        if (currentActivity == null) {
            Log.e(
                TAG,
                "Cannot start registration because Activity is not attached"
            )
            return
        }

        try {
            Log.i(
                TAG,
                "Starting Meta glasses registration"
            )

            Wearables.startRegistration(currentActivity)

            _registrationState.value =
                RegistrationState.REGISTERING

        } catch (e: Exception) {
            Log.e(
                TAG,
                "Failed to start Meta glasses registration",
                e
            )

            _registrationState.value =
                RegistrationState.UNKNOWN
        }
    }

    // -------------------------------------------------------------------------
    // CONNECT
    // -------------------------------------------------------------------------

    override suspend fun connect(): Boolean {
        return sessionMutex.withLock {

            try {
                Log.i(
                    TAG,
                    "========== CONNECT GLASSES =========="
                )

                val registration =
                    _registrationState.value

                Log.i(
                    TAG,
                    "Registration state = $registration"
                )

                if (registration != RegistrationState.REGISTERED) {
                    Log.e(
                        TAG,
                        "Cannot connect: app is not registered with Meta AI"
                    )
                    return@withLock false
                }

                val currentDevices =
                    _devices.value

                Log.i(
                    TAG,
                    "Known devices = $currentDevices"
                )

                val connectedDevice =
                    currentDevices.firstOrNull { it.connected }

                if (connectedDevice != null) {
                    Log.i(
                        TAG,
                        "A connected device is already available: ${connectedDevice.name}"
                    )
                } else {
                    Log.w(
                        TAG,
                        "No device currently reports connected=true"
                    )

                    Log.w(
                        TAG,
                        "Attempting DeviceSession anyway; MWDAT will determine device availability"
                    )
                }

                // Reuse an already active session.
                val existingSession = session

                if (existingSession != null) {

                    val existingState =
                        existingSession.state.value

                    Log.i(
                        TAG,
                        "Existing DeviceSession state = $existingState"
                    )

                    if (existingState == DeviceSessionState.STARTED) {
                        Log.i(
                            TAG,
                            "DeviceSession is already STARTED"
                        )

                        return@withLock true
                    }

                    if (
                        existingState == DeviceSessionState.STOPPED ||
                        existingState == DeviceSessionState.STOPPING
                    ) {
                        Log.w(
                            TAG,
                            "Existing DeviceSession is terminal; recreating it"
                        )

                        session = null
                    }
                }

                // Create a fresh session when necessary.
                if (session == null) {

                    Log.i(
                        TAG,
                        "Creating MWDAT DeviceSession"
                    )

                    val createdSession =
                        Wearables
                            .createSession(AutoDeviceSelector())
                            .getOrElse { error ->

                                Log.e(
                                    TAG,
                                    "createSession() failed: $error"
                                )

                                return@withLock false
                            }

                    session = createdSession

                    Log.i(
                        TAG,
                        "DeviceSession created"
                    )

                    observeSessionErrors(createdSession)

                    Log.i(
                        TAG,
                        "Starting DeviceSession"
                    )

                    createdSession.start()
                }

                val activeSession =
                    session
                        ?: return@withLock false

                Log.i(
                    TAG,
                    "Waiting for DeviceSessionState.STARTED"
                )

                val started =
                    withTimeoutOrNull(SESSION_START_TIMEOUT_MS) {

                        activeSession.state.first { state ->

                            Log.i(
                                TAG,
                                "DeviceSession state = $state"
                            )

                            state == DeviceSessionState.STARTED
                        }

                        true

                    } ?: false

                if (started) {

                    Log.i(
                        TAG,
                        "========================================"
                    )

                    Log.i(
                        TAG,
                        "GLASSES DEVICE SESSION STARTED"
                    )

                    Log.i(
                        TAG,
                        "========================================"
                    )

                    true

                } else {

                    val finalState =
                        activeSession.state.value

                    Log.e(
                        TAG,
                        "DeviceSession did not reach STARTED within ${SESSION_START_TIMEOUT_MS}ms"
                    )

                    Log.e(
                        TAG,
                        "Final DeviceSession state = $finalState"
                    )

                    false
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Exception while connecting to glasses",
                    e
                )

                false
            }
        }
    }

    // -------------------------------------------------------------------------
    // Camera permission
    // -------------------------------------------------------------------------

    override suspend fun cameraPermission(): CameraPermission {

        return try {

            val result =
                Wearables.checkPermissionStatus(
                    Permission.CAMERA
                )

            Log.d(
                TAG,
                "Camera permission result: $result"
            )

            result.onSuccess { status ->

                Log.d(
                    TAG,
                    "Camera permission status: $status"
                )
            }

            val status =
                result.getOrElse {

                    Log.w(
                        TAG,
                        "Camera permission check failed because device/session is unavailable"
                    )

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
                "Failed to check camera permission",
                e
            )

            CameraPermission.NOT_DETERMINED
        }
    }

    override suspend fun requestCameraPermission(): CameraPermission {

        /*
         * IMPORTANT:
         *
         * MWDAT permission requests use Wearables.RequestPermissionContract()
         * and therefore have to be launched by an Activity/Compose
         * ActivityResult launcher.
         *
         * This method cannot directly launch that contract from a background
         * coroutine.
         *
         * We will wire this into MainActivity/HomeScreen next.
         */

        Log.i(
            TAG,
            "Camera permission request must be launched through MWDAT RequestPermissionContract"
        )

        return CameraPermission.NOT_DETERMINED
    }

    // -------------------------------------------------------------------------
    // CAMERA / VISION
    // -------------------------------------------------------------------------

    override fun cameraFrames(): Flow<ByteArray> =
        flow {

            sessionMutex.withLock {

                try {

                    /*
                     * The camera path now guarantees that we have an active
                     * DeviceSession before touching the camera capability.
                     */
                    val connected =
                        connect()

                    if (!connected) {

                        Log.e(
                            TAG,
                            "Cannot start camera because DeviceSession is not STARTED"
                        )

                        return@flow
                    }

                    ensureCamera()

                    val activeCamera =
                        camera

                    if (activeCamera == null) {

                        Log.e(
                            TAG,
                            "Camera was not created"
                        )

                        return@flow
                    }

                    Log.i(
                        TAG,
                        "Starting camera stream"
                    )

                    val startResult =
                        activeCamera.stream.start()

                    startResult.onFailure { error, _ ->

                        Log.e(
                            TAG,
                            "Failed to start camera stream: $error"
                        )
                    }

                    if (startResult.isFailure) {
                        return@flow
                    }

                    Log.i(
                        TAG,
                        "Camera stream started"
                    )

                    /*
                     * capturePhoto() gives us still-image data suitable for
                     * sending to Gemini. The raw videoStream is HEVC, so we
                     * deliberately don't forward those bytes as JPEG.
                     */
                    repeat(MAX_PHOTOS_PER_BURST) { index ->

                        try {

                            val photoResult =
                                activeCamera.stream.capturePhoto()

                            Log.d(
                                TAG,
                                "capturePhoto #${index + 1}: result=$photoResult"
                            )

                            photoResult.onSuccess { photoData ->

                                Log.d(
                                    TAG,
                                    "capturePhoto success type=${photoData::class.java.name}"
                                )

                                /*
                                 * IMPORTANT:
                                 *
                                 * Your installed 0.9.0 artifact previously
                                 * rejected photoData.data at compile time.
                                 *
                                 * Therefore we are intentionally NOT guessing
                                 * the property name here.
                                 *
                                 * The next compiler-visible step should inspect
                                 * the actual PhotoData API from your resolved
                                 * 0.9.0 dependency.
                                 */
                                Log.d(
                                    TAG,
                                    "capturePhoto success value=$photoData"
                                )
                            }

                            photoResult.onFailure { error, _ ->

                                Log.e(
                                    TAG,
                                    "Failed to capture vision photo #${index + 1}: $error"
                                )
                            }

                        } catch (e: Exception) {

                            Log.e(
                                TAG,
                                "Exception while capturing vision photo #${index + 1}",
                                e
                            )
                        }

                        if (
                            index <
                            MAX_PHOTOS_PER_BURST - 1
                        ) {
                            delay(PHOTO_INTERVAL_MS)
                        }
                    }

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Camera vision session failed",
                        e

                    )
                }

                /*
                 * Do NOT destroy the DeviceSession after every photo burst.
                 *
                 * The session represents the glasses connection. We keep it
                 * alive so subsequent vision requests don't have to reconnect.
                 */
            }
        }

    private suspend fun ensureCamera() {

        val activeSession =
            session
                ?: throw IllegalStateException(
                    "DeviceSession is not available"
                )

        if (camera != null) {

            Log.d(
                TAG,
                "Camera already exists"
            )

            return
        }

        Log.i(
            TAG,
            "Adding camera to DeviceSession"
        )

        val addedCamera =
            activeSession.addCamera(
                StreamConfiguration(
                    videoQuality = VideoQuality.MEDIUM,
                    frameRate = FRAME_RATE,
                )
            ).getOrElse { error ->

                val message =
                    error.toString()

                Log.e(
                    TAG,
                    "Failed to add camera: $message"
                )

                throw IllegalStateException(message)
            }

        camera =
            addedCamera

        Log.i(
            TAG,
            "Camera added successfully"
        )
    }

    // -------------------------------------------------------------------------
    // DEVICE OBSERVATION
    // -------------------------------------------------------------------------

    private fun observeDevices() {

        scope.launch {

            try {

                Wearables.devices.collect { deviceIds ->

                    Log.d(
                        TAG,
                        "Wearables.devices: $deviceIds"
                    )

                    val result =
                        mutableListOf<GlassesDevice>()

                    for (deviceId in deviceIds) {

                        try {

                            Wearables
                                .devicesMetadata[deviceId]
                                ?.collect { device ->

                                    result.removeAll {
                                        it.id == deviceId.toString()
                                    }

                                    result.add(
                                        toGlassesDevice(
                                            deviceId,
                                            device
                                        )
                                    )

                                    _devices.value =
                                        result.toList()
                                }

                        } catch (e: Exception) {

                            Log.e(
                                TAG,
                                "Failed reading metadata for $deviceId",
                                e
                            )
                        }
                    }

                    if (deviceIds.isEmpty()) {
                        _devices.value =
                            emptyList()
                    }
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Device observation failed",
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

        val connected =
            device.linkState ==
                LinkState.CONNECTED

        Log.d(
            TAG,
            "Device: id=$id name=$displayName connected=$connected"
        )

        return GlassesDevice(
            id = id.toString(),
            name = displayName,
            connected = connected,
        )
    }

    // -------------------------------------------------------------------------
    // REGISTRATION OBSERVATION
    // -------------------------------------------------------------------------

    private fun observeRegistrationState() {

        scope.launch {

            try {

                Wearables.registrationState.collect { state ->

                    Log.d(
                        TAG,
                        "MWDAT registration state: $state"
                    )

                    _registrationState.value =
                        when (state.toString()) {

                            "REGISTERED" ->
                                RegistrationState.REGISTERED

                            "REGISTERING" ->
                                RegistrationState.REGISTERING

                            "NOT_REGISTERED" ->
                                RegistrationState.NOT_REGISTERED

                            "REVOKED" ->
                                RegistrationState.REVOKED

                            else ->
                                RegistrationState.UNKNOWN
                        }
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Registration observation failed",
                    e
                )

                _registrationState.value =
                    RegistrationState.UNKNOWN
            }
        }
    }

    // -------------------------------------------------------------------------
    // SESSION ERRORS
    // -------------------------------------------------------------------------

    private fun observeSessionErrors(
        activeSession: DeviceSession,
    ) {

        scope.launch {

            try {

                activeSession.errors.collect { error ->

                    Log.e(
                        TAG,
                        "MWDAT DeviceSession error: $error"
                    )
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "DeviceSession error observation failed",
                    e
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // SHUTDOWN
    // -------------------------------------------------------------------------

    fun shutdown() {

        scope.launch {

            sessionMutex.withLock {

                try {

                    camera?.stop()

                    Log.d(
                        TAG,
                        "Camera stopped"
                    )

                } catch (e: Exception) {

                    Log.w(
                        TAG,
                        "Error stopping camera",
                        e
                    )
                }

                camera = null

                try {

                    session?.stop()

                    Log.d(
                        TAG,
                        "DeviceSession stopped"
                    )

                } catch (e: Exception) {

                    Log.w(
                        TAG,
                        "Error stopping DeviceSession",
                        e
                    )
                }

                session = null
            }

            scope.cancel()
        }
    }
}
