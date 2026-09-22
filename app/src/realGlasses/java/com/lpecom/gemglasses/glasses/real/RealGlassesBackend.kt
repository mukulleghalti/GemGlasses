package com.lpecom.gemglasses.glasses.real

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.lpecom.gemglasses.glasses.CameraPermission
import com.lpecom.gemglasses.glasses.GlassesBackend
import com.lpecom.gemglasses.glasses.GlassesDevice
import com.lpecom.gemglasses.glasses.RegistrationState
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.SpecificDeviceSelector
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

        private const val SESSION_START_TIMEOUT_MS = 20_000L
    }

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val sessionMutex = Mutex()
    private val cameraMutex = Mutex()

    private var activity: Activity? = null

    private var session: DeviceSession? = null
    private var camera: Camera? = null

    private var selectedDeviceId: DeviceIdentifier? = null

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

        Log.d(
            TAG,
            "Activity attached: ${activity::class.java.simpleName}"
        )
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

        logBluetoothPermissions()

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

    private fun logBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            val scanGranted =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED

            val connectGranted =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED

            Log.i(
                TAG,
                "Bluetooth permissions: SCAN=$scanGranted, CONNECT=$connectGranted"
            )

        } else {

            Log.i(
                TAG,
                "Bluetooth runtime permissions not required on Android < 12"
            )
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
                "Cannot start registration: Activity is not attached"
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
                    "================================================"
                )
                Log.i(
                    TAG,
                    "CONNECT GLASSES"
                )
                Log.i(
                    TAG,
                    "================================================"
                )

                logBluetoothPermissions()

                val registration =
                    _registrationState.value

                Log.i(
                    TAG,
                    "Registration state = $registration"
                )

                if (registration != RegistrationState.REGISTERED) {

                    Log.e(
                        TAG,
                        "Cannot create session: app is not REGISTERED"
                    )

                    return@withLock false
                }

                // -------------------------------------------------------------
                // Existing session
                // -------------------------------------------------------------

                val existingSession = session

                if (existingSession != null) {

                    val existingState =
                        existingSession.state.value

                    Log.i(
                        TAG,
                        "Existing DeviceSession state = $existingState"
                    )

                    when (existingState) {

                        DeviceSessionState.STARTED -> {

                            Log.i(
                                TAG,
                                "DeviceSession is already STARTED"
                            )

                            return@withLock true
                        }

                        DeviceSessionState.STARTING -> {

                            Log.i(
                                TAG,
                                "Existing DeviceSession is STARTING"
                            )

                            val started =
                                waitForSessionStarted(existingSession)

                            if (started) {
                                return@withLock true
                            }

                            Log.w(
                                TAG,
                                "Existing session failed to reach STARTED"
                            )

                            stopCurrentSessionLocked()
                        }

                        DeviceSessionState.STOPPING,
                        DeviceSessionState.STOPPED -> {

                            Log.w(
                                TAG,
                                "Existing DeviceSession is terminal: $existingState"
                            )

                            stopCurrentSessionLocked()
                        }

                        else -> {
                            Log.d(
                                TAG,
                                "Existing DeviceSession state: $existingState"
                            )
                        }
                    }
                }

                // -------------------------------------------------------------
                // Find a device ID
                // -------------------------------------------------------------

                val deviceId = selectedDeviceId

                if (deviceId == null) {

                    Log.e(
                        TAG,
                        "Cannot create session: no DeviceIdentifier available"
                    )

                    logCurrentDevices()

                    return@withLock false
                }

                Log.i(
                    TAG,
                    "Selected device ID = $deviceId"
                )

                // -------------------------------------------------------------
                // IMPORTANT:
                //
                // Do NOT wait for LinkState.CONNECTED here.
                //
                // The SDK itself performs the session/device eligibility
                // handshake.
                // -------------------------------------------------------------

                val currentDevices = _devices.value

                currentDevices.forEach { device ->

                    Log.i(
                        TAG,
                        "Pre-session device: " +
                            "id=${device.id}, " +
                            "name=${device.name}, " +
                            "connected=${device.connected}"
                    )
                }

                // -------------------------------------------------------------
                // Create session
                // -------------------------------------------------------------

                Log.i(
                    TAG,
                    "Creating MWDAT DeviceSession"
                )

                Log.i(
                    TAG,
                    "Using SpecificDeviceSelector($deviceId)"
                )

                val sessionResult =
                    Wearables.createSession(
                        SpecificDeviceSelector(deviceId)
                    )

                val createdSession =
                    sessionResult.getOrElse { error ->

                        Log.e(
                            TAG,
                            "================================================"
                        )

                        Log.e(
                            TAG,
                            "createSession() FAILED"
                        )

                        logSessionFailure(error)

                        Log.e(
                            TAG,
                            "================================================"
                        )

                        return@withLock false
                    }

                session = createdSession

                Log.i(
                    TAG,
                    "DeviceSession created successfully"
                )

                // -------------------------------------------------------------
                // Observe errors BEFORE start()
                // -------------------------------------------------------------

                observeSessionErrors(createdSession)

                // -------------------------------------------------------------
                // Observe state BEFORE start()
                // -------------------------------------------------------------

                observeSessionState(createdSession)

                // -------------------------------------------------------------
                // Start session
                // -------------------------------------------------------------

                Log.i(
                    TAG,
                    "Calling DeviceSession.start()"
                )

                createdSession.start()

                Log.i(
                    TAG,
                    "DeviceSession.start() returned"
                )

                // -------------------------------------------------------------
                // Wait for STARTED
                // -------------------------------------------------------------

                val started =
                    waitForSessionStarted(createdSession)

                if (started) {

                    Log.i(
                        TAG,
                        "================================================"
                    )

                    Log.i(
                        TAG,
                        "GLASSES DEVICE SESSION STARTED"
                    )

                    Log.i(
                        TAG,
                        "================================================"
                    )

                    return@withLock true
                }

                Log.e(
                    TAG,
                    "================================================"
                )

                Log.e(
                    TAG,
                    "DEVICE SESSION DID NOT START"
                )

                Log.e(
                    TAG,
                    "Final DeviceSession state = ${createdSession.state.value}"
                )

                Log.e(
                    TAG,
                    "================================================"
                )

                // Clean up the failed session so the next attempt creates
                // a completely fresh DeviceSession.
                stopCurrentSessionLocked()

                false

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Exception while connecting to glasses",
                    e
                )

                stopCurrentSessionLocked()

                false
            }
        }
    }

    // -------------------------------------------------------------------------
    // Wait for session STARTED
    // -------------------------------------------------------------------------

    private suspend fun waitForSessionStarted(
        activeSession: DeviceSession,
    ): Boolean {

        Log.i(
            TAG,
            "Waiting for DeviceSessionState.STARTED..."
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

        if (!started) {

            Log.e(
                TAG,
                "Timed out after ${SESSION_START_TIMEOUT_MS}ms waiting for STARTED"
            )

            Log.e(
                TAG,
                "Final DeviceSession state = ${activeSession.state.value}"
            )
        }

        return started
    }

    // -------------------------------------------------------------------------
    // Session diagnostics
    // -------------------------------------------------------------------------

    private fun observeSessionState(
        activeSession: DeviceSession,
    ) {

        scope.launch {

            try {

                activeSession.state.collect { state ->

                    Log.i(
                        TAG,
                        "SESSION STATE -> $state"
                    )
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "DeviceSession state observation failed",
                    e
                )
            }
        }
    }

    private fun observeSessionErrors(
        activeSession: DeviceSession,
    ) {

        scope.launch {

            try {

                activeSession.errors.collect { error ->

                    Log.e(
                        TAG,
                        "================================================"
                    )

                    Log.e(
                        TAG,
                        "DEVICE SESSION ERROR"
                    )

                    Log.e(
                        TAG,
                        "Error = $error"
                    )

                    Log.e(
                        TAG,
                        "Error type = ${error::class.java.name}"
                    )

                    Log.e(
                        TAG,
                        "Error string = ${error}"
                    )

                    Log.e(
                        TAG,
                        "================================================"
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

    private fun logSessionFailure(error: Any?) {

        val text =
            error?.toString() ?: "null"

        Log.e(
            TAG,
            "MWDAT createSession() failure = $text"
        )

        Log.e(
            TAG,
            "Failure runtime type = " +
                error?.let { it::class.java.name }
        )

        when {

            text.contains(
                "DEVICE_UPDATE_REQUIRED",
                ignoreCase = true
            ) -> {

                Log.e(
                    TAG,
                    "Glasses firmware/device update required"
                )
            }

            text.contains(
                "DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED",
                ignoreCase = true
            ) -> {

                Log.e(
                    TAG,
                    "DAT app on the glasses requires an update"
                )
            }

            text.contains(
                "NO_ELIGIBLE_DEVICE",
                ignoreCase = true
            ) -> {

                Log.e(
                    TAG,
                    "MWDAT reports NO_ELIGIBLE_DEVICE"
                )

                Log.e(
                    TAG,
                    "This occurred during createSession(), before session.start()"
                )
            }

            else -> {

                Log.e(
                    TAG,
                    "Unclassified MWDAT session creation failure"
                )
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
                "Camera permission result = $result"
            )

            result.onSuccess { status ->

                Log.d(
                    TAG,
                    "Camera permission status = $status"
                )
            }

            result.onFailure { error, _ ->

                Log.e(
                    TAG,
                    "Camera permission check failed = $error"
                )
            }

            val status =
                result.getOrElse {

                    Log.w(
                        TAG,
                        "Camera permission unavailable because MWDAT " +
                            "does not currently have an eligible device/session"
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

        Log.i(
            TAG,
            "Camera permission request requires " +
                "Wearables.RequestPermissionContract() from MainActivity"
        )

        return CameraPermission.NOT_DETERMINED
    }

    // -------------------------------------------------------------------------
    // CAMERA / VISION
    // -------------------------------------------------------------------------

    override fun cameraFrames(): Flow<ByteArray> =
        flow {

            try {

                Log.i(
                    TAG,
                    "========== CAMERA VISION START =========="
                )

                // -------------------------------------------------------------
                // Make sure the DeviceSession exists and is STARTED.
                // -------------------------------------------------------------

                val connected = connect()

                if (!connected) {

                    Log.e(
                        TAG,
                        "Cannot start camera: DeviceSession could not be started"
                    )

                    return@flow
                }

                // -------------------------------------------------------------
                // Add camera only after DeviceSession STARTED.
                // -------------------------------------------------------------

                cameraMutex.withLock {
                    ensureCamera()
                }

                val activeCamera =
                    camera ?: run {

                        Log.e(
                            TAG,
                            "Camera was not created"
                        )

                        return@flow
                    }

                // -------------------------------------------------------------
                // Start camera stream.
                // -------------------------------------------------------------

                Log.i(
                    TAG,
                    "Starting camera stream"
                )

                val startResult =
                    activeCamera.stream.start()

                startResult.onSuccess {

                    Log.i(
                        TAG,
                        "Camera stream start succeeded"
                    )
                }

                startResult.onFailure { error, _ ->

                    Log.e(
                        TAG,
                        "Camera stream start failed = $error"
                    )
                }

                if (startResult.isFailure) {

                    Log.e(
                        TAG,
                        "Stopping vision because camera stream failed"
                    )

                    return@flow
                }

                Log.i(
                    TAG,
                    "Camera stream started"
                )

                // -------------------------------------------------------------
                // Capture still photos.
                //
                // We deliberately don't use the raw videoStream bytes here.
                // The raw camera stream is HEVC and is not directly suitable
                // for Gemini image input.
                // -------------------------------------------------------------

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
                                "capturePhoto #${index + 1} succeeded"
                            )

                            Log.d(
                                TAG,
                                "PhotoData type = " +
                                    photoData::class.java.name
                            )

                            Log.d(
                                TAG,
                                "PhotoData value = $photoData"
                            )

                            /*
                             * IMPORTANT:
                             *
                             * The exact PhotoData byte accessor depends on
                             * the resolved MWDAT 0.9.0 artifact.
                             *
                             * We are intentionally logging PhotoData here
                             * instead of guessing a property such as .data.
                             *
                             * Once we confirm the actual byte accessor in
                             * your resolved 0.9.0 dependency, this is where
                             * the JPEG will be emitted to Gemini.
                             */
                        }

                        photoResult.onFailure { error, _ ->

                            Log.e(
                                TAG,
                                "Failed to capture vision photo " +
                                    "#${index + 1}: $error"
                            )
                        }

                    } catch (e: Exception) {

                        Log.e(
                            TAG,
                            "Exception while capturing vision photo " +
                                "#${index + 1}",
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

                Log.i(
                    TAG,
                    "========== CAMERA VISION END =========="
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Camera vision session failed",
                    e
                )
            }
        }

    // -------------------------------------------------------------------------
    // CAMERA CREATION
    // -------------------------------------------------------------------------

    private suspend fun ensureCamera() {

        val activeSession =
            session
                ?: throw IllegalStateException(
                    "DeviceSession is not available"
                )

        // Camera already exists.
        if (camera != null) {

            Log.d(
                TAG,
                "Camera already exists"
            )

            return
        }

        // Safety check: camera must only be attached after STARTED.
        val currentState =
            activeSession.state.value

        if (currentState != DeviceSessionState.STARTED) {

            throw IllegalStateException(
                "Cannot add camera while DeviceSession state is $currentState"
            )
        }

        Log.i(
            TAG,
            "Adding camera to DeviceSession"
        )

        val addResult =
            activeSession.addCamera(
                StreamConfiguration(
                    videoQuality = VideoQuality.MEDIUM,
                    frameRate = FRAME_RATE,
                ),
            )

        addResult.onFailure { error, _ ->

            Log.e(
                TAG,
                "addCamera() failed = $error"
            )
        }

        val addedCamera =
            addResult.getOrElse { error ->

                throw IllegalStateException(
                    "addCamera failed: $error"
                )
            }

        camera = addedCamera

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
                        "Wearables.devices = $deviceIds"
                    )

                    if (deviceIds.isEmpty()) {

                        selectedDeviceId = null

                        _devices.value =
                            emptyList()

                        return@collect
                    }

                    val result =
                        mutableListOf<GlassesDevice>()

                    for (deviceId in deviceIds) {

                        try {

                            val metadataFlow =
                                Wearables.devicesMetadata[deviceId]

                            if (metadataFlow == null) {

                                Log.w(
                                    TAG,
                                    "No metadata flow for device $deviceId"
                                )

                                continue
                            }

                            /*
                             * This collection intentionally runs for the
                             * lifetime of the device metadata flow.
                             */

                            scope.launch {

                                try {

                                    metadataFlow.collect { device ->

                                        selectedDeviceId =
                                            deviceId

                                        logDeviceDiagnostics(
                                            deviceId,
                                            device
                                        )

                                        val glassesDevice =
                                            toGlassesDevice(
                                                deviceId,
                                                device
                                            )

                                        val current =
                                            _devices.value
                                                .filter {
                                                    it.id !=
                                                        deviceId.toString()
                                                }
                                                .toMutableList()

                                        current.add(
                                            glassesDevice
                                        )

                                        _devices.value =
                                            current

                                    }

                                } catch (e: Exception) {

                                    Log.e(
                                        TAG,
                                        "Metadata observation failed for $deviceId",
                                        e
                                    )
                                }
                            }

                        } catch (e: Exception) {

                            Log.e(
                                TAG,
                                "Failed reading metadata for $deviceId",
                                e
                            )
                        }
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

    private fun logCurrentDevices() {

        val currentDevices =
            _devices.value

        if (currentDevices.isEmpty()) {

            Log.e(
                TAG,
                "No devices currently available from MWDAT"
            )

            return
        }

        currentDevices.forEach { device ->

            Log.e(
                TAG,
                "Current device: " +
                    "id=${device.id}, " +
                    "name=${device.name}, " +
                    "connected=${device.connected}"
            )
        }
    }

    private fun logDeviceDiagnostics(
        deviceId: DeviceIdentifier,
        device: Device,
    ) {

        Log.i(
            TAG,
            "========== MWDAT DEVICE DIAGNOSTICS =========="
        )

        Log.i(
            TAG,
            "Device ID = $deviceId"
        )

        Log.i(
            TAG,
            "Device name = ${device.name}"
        )

        Log.i(
            TAG,
            "Device type = ${device.deviceType}"
        )

        Log.i(
            TAG,
            "Device type description = ${device.deviceType.description}"
        )

        Log.i(
            TAG,
            "Link state = ${device.linkState}"
        )

        Log.i(
            TAG,
            "Compatibility = ${device.compatibility}"
        )

        Log.i(
            TAG,
            "Display capable = ${device.isDisplayCapable()}"
        )

        Log.i(
            TAG,
            "Device object = $device"
        )

        Log.i(
            TAG,
            "=============================================="
        )
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

        Log.i(
            TAG,
            "Device: " +
                "id=$id, " +
                "name=$displayName, " +
                "linkState=${device.linkState}, " +
                "connected=$connected"
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

                    Log.i(
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
    // SESSION CLEANUP
    // -------------------------------------------------------------------------

    private suspend fun stopCurrentSessionLocked() {

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
                "DeviceSession stop requested"
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

    // -------------------------------------------------------------------------
    // SHUTDOWN
    // -------------------------------------------------------------------------

    fun shutdown() {

        scope.launch {

            sessionMutex.withLock {

                stopCurrentSessionLocked()

                selectedDeviceId = null
            }

            scope.cancel()
        }
    }
}
