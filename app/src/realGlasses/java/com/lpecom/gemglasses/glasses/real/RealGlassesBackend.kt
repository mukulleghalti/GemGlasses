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

        private const val CAMERA_TEST_FRAME_RATE = 24

        private const val JPEG_QUALITY = 90
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

    /*
     * IMPORTANT:
     *
     * This represents the MWDAT DeviceSession connection.
     *
     * It is deliberately separate from:
     *
     *     Device.linkState == LinkState.CONNECTED
     *
     * Bluetooth/device link state only tells us that the glasses are
     * linked. It does NOT mean that our app has an active MWDAT
     * DeviceSession.
     */
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
        Log.i(TAG, "RealGlassesBackend created")
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

            Log.i(
                TAG,
                "Activity detached"
            )
        }
    }

    // =========================================================================
    // CAMERA PERMISSION REQUESTER
    // =========================================================================

    override fun setCameraPermissionRequester(
        requester: suspend () -> CameraPermission,
    ) {
        cameraPermissionRequester = requester

        Log.i(
            TAG,
            "Camera permission requester registered"
        )
    }

    // =========================================================================
    // INITIALIZE
    // =========================================================================

    override fun initialize() {

        if (initialized) {
            Log.i(
                TAG,
                "Glasses backend already initialized; ignoring duplicate call"
            )
            return
        }

        initialized = true

        Log.i(TAG, "================================================")
        Log.i(TAG, "INITIALIZING GLASSES BACKEND")
        Log.i(TAG, "MWDAT SDK is initialized by GemGlassesApp")
        Log.i(TAG, "================================================")

        logBluetoothPermissions()

        observeRegistrationState()
        observeDevices()
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
                "BLUETOOTH_SCAN = $scanGranted"
            )

            Log.i(
                TAG,
                "BLUETOOTH_CONNECT = $connectGranted"
            )

        } else {

            Log.i(
                TAG,
                "Android < 12: Bluetooth runtime permissions not required"
            )
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
                "Cannot start registration: Activity is null"
            )

            return
        }

        try {

            Log.i(
                TAG,
                "Starting Meta glasses registration"
            )

            Wearables.startRegistration(
                currentActivity
            )

            _registrationState.value =
                RegistrationState.REGISTERING

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Wearables.startRegistration() FAILED",
                e
            )

            _registrationState.value =
                RegistrationState.UNKNOWN
        }
    }

    private suspend fun waitForRegistration():
        RegistrationState {

        val current =
            _registrationState.value

        Log.i(
            TAG,
            "Registration check before wait = $current"
        )

        if (
            current == RegistrationState.REGISTERED ||
            current == RegistrationState.NOT_REGISTERED ||
            current == RegistrationState.REVOKED
        ) {
            return current
        }

        Log.i(
            TAG,
            "MWDAT registration is still unresolved; waiting up to " +
                "${REGISTRATION_TIMEOUT_MS}ms"
        )

        val resolved =
            withTimeoutOrNull(
                REGISTRATION_TIMEOUT_MS
            ) {

                _registrationState.first { state ->

                    Log.i(
                        TAG,
                        "Waiting for registration -> $state"
                    )

                    state == RegistrationState.REGISTERED ||
                        state == RegistrationState.NOT_REGISTERED ||
                        state == RegistrationState.REVOKED
                }
            }

        if (resolved == null) {

            Log.e(
                TAG,
                "Timed out waiting for MWDAT registration state"
            )

            Log.e(
                TAG,
                "Registration state after timeout = " +
                    _registrationState.value
            )

            return RegistrationState.UNKNOWN
        }

        Log.i(
            TAG,
            "MWDAT registration resolved = $resolved"
        )

        return resolved
    }

    // =========================================================================
    // CONNECTION
    // =========================================================================

    override suspend fun connect(): Boolean {

        Log.i(TAG, "================================================")
        Log.i(TAG, "STARTING MWDAT CONNECTION")
        Log.i(TAG, "================================================")

        /*
         * This is the state that drives the HomeScreen.
         *
         * It is NOT derived from Bluetooth LinkState.
         */
        _connectionState.value =
            ConnectionState.CONNECTING

        logBluetoothPermissions()

        // ---------------------------------------------------------------------
        // Registration
        // ---------------------------------------------------------------------

        val registration =
            waitForRegistration()

        Log.i(
            TAG,
            "Resolved registration state = $registration"
        )

        if (registration != RegistrationState.REGISTERED) {

            Log.e(
                TAG,
                "ABORTING: MWDAT registration is not REGISTERED"
            )

            Log.e(
                TAG,
                "Resolved registration state = $registration"
            )

            _connectionState.value =
                ConnectionState.ERROR

            return false
        }

        Log.i(
            TAG,
            "MWDAT registration confirmed REGISTERED"
        )

        // ---------------------------------------------------------------------
        // Log devices
        // ---------------------------------------------------------------------

        val devices =
            _devices.value

        Log.i(
            TAG,
            "Known MWDAT devices = ${devices.size}"
        )

        devices.forEach { device ->

            Log.i(TAG, "Device:")
            Log.i(TAG, "  id = ${device.id}")
            Log.i(TAG, "  name = ${device.name}")
            Log.i(TAG, "  bluetooth connected = ${device.connected}")
        }

        if (devices.isEmpty()) {

            Log.e(
                TAG,
                "ABORTING: MWDAT reports zero devices"
            )

            _connectionState.value =
                ConnectionState.ERROR

            return false
        }

        Log.i(
            TAG,
            "Using AutoDeviceSelector()"
        )

        // ---------------------------------------------------------------------
        // Stop previous session
        // ---------------------------------------------------------------------

        val oldSession =
            session

        if (oldSession != null) {

            Log.i(
                TAG,
                "Existing session found"
            )

            Log.i(
                TAG,
                "Existing session state = ${oldSession.state.value}"
            )

            try {

                stopCameraIfNeeded()

                oldSession.stop()

                Log.i(
                    TAG,
                    "Existing session stop() called"
                )

            } catch (e: Exception) {

                Log.w(
                    TAG,
                    "Could not stop existing session",
                    e
                )
            }

            session = null
        }

        // ---------------------------------------------------------------------
        // CREATE SESSION
        // ---------------------------------------------------------------------

        Log.i(
            TAG,
            "Calling Wearables.createSession(AutoDeviceSelector())"
        )

        val sessionResult =
            try {

                Wearables.createSession(
                    AutoDeviceSelector()
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "createSession() THREW EXCEPTION",
                    e
                )

                _connectionState.value =
                    ConnectionState.ERROR

                return false
            }

        // ---------------------------------------------------------------------
        // Handle createSession result
        // ---------------------------------------------------------------------

        val createdSession =
            sessionResult.getOrElse { error ->

                Log.e(TAG, "================================================")
                Log.e(TAG, "CREATE SESSION FAILED")
                Log.e(TAG, "Error = $error")
                Log.e(
                    TAG,
                    "Error type = ${error::class.java.name}"
                )
                Log.e(TAG, "================================================")

                logCreateSessionError(error)

                _connectionState.value =
                    ConnectionState.ERROR

                return false
            }

        session =
            createdSession

        Log.i(TAG, "================================================")
        Log.i(TAG, "CREATE SESSION SUCCEEDED")
        Log.i(TAG, "DeviceSession object created")
        Log.i(
            TAG,
            "Initial state = ${createdSession.state.value}"
        )
        Log.i(TAG, "================================================")

        // ---------------------------------------------------------------------
        // Observe session errors/state
        // ---------------------------------------------------------------------

        observeSessionErrors(
            createdSession
        )

        observeSessionState(
            createdSession
        )

        // ---------------------------------------------------------------------
        // START SESSION
        // ---------------------------------------------------------------------

        Log.i(
            TAG,
            "Calling DeviceSession.start()"
        )

        try {

            createdSession.start()

            Log.i(
                TAG,
                "DeviceSession.start() RETURNED"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "DeviceSession.start() THREW EXCEPTION",
                e
            )

            _connectionState.value =
                ConnectionState.ERROR

            return false
        }

        // ---------------------------------------------------------------------
        // Wait for STARTED
        // ---------------------------------------------------------------------

        Log.i(
            TAG,
            "Waiting for DeviceSessionState.STARTED..."
        )

        val started =
            withTimeoutOrNull(
                SESSION_START_TIMEOUT_MS
            ) {

                createdSession.state.first { state ->

                    Log.i(
                        TAG,
                        "DeviceSession state = $state"
                    )

                    state ==
                        DeviceSessionState.STARTED
                }

                true

            } ?: false

        // ---------------------------------------------------------------------
        // RESULT
        // ---------------------------------------------------------------------

        if (started) {

            _connectionState.value =
                ConnectionState.CONNECTED

            Log.i(TAG, "================================================")
            Log.i(TAG, "MWDAT CONNECTION SUCCESS")
            Log.i(TAG, "SESSION STATE = STARTED")
            Log.i(TAG, "CONNECTION STATE = CONNECTED")
            Log.i(TAG, "================================================")

            return true
        }

        _connectionState.value =
            ConnectionState.ERROR

        Log.e(TAG, "================================================")
        Log.e(TAG, "MWDAT CONNECTION FAILED")
        Log.e(TAG, "Session never reached STARTED")
        Log.e(
            TAG,
            "Final state = ${createdSession.state.value}"
        )
        Log.e(TAG, "================================================")

        return false
    }

    // =========================================================================
    // CREATE SESSION ERROR LOGGING
    // =========================================================================

    private fun logCreateSessionError(
        error: Any?,
    ) {

        val text =
            error?.toString()
                ?: "null"

        Log.e(
            TAG,
            "createSession error text = $text"
        )

        Log.e(
            TAG,
            "createSession error runtime type = " +
                error?.let {
                    it::class.java.name
                }
        )

        when {

            text.contains(
                "NO_ELIGIBLE_DEVICE",
                ignoreCase = true
            ) -> {

                Log.e(
                    TAG,
                    "RESULT: NO_ELIGIBLE_DEVICE"
                )
            }

            text.contains(
                "DEVICE_UPDATE_REQUIRED",
                ignoreCase = true
            ) -> {

                Log.e(
                    TAG,
                    "RESULT: DEVICE_UPDATE_REQUIRED"
                )
            }

            text.contains(
                "DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED",
                ignoreCase = true
            ) -> {

                Log.e(
                    TAG,
                    "RESULT: DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED"
                )
            }

            else -> {

                Log.e(
                    TAG,
                    "RESULT: Unknown createSession() failure"
                )
            }
        }
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

                    Log.i(
                        TAG,
                        "SESSION STATE EVENT -> $state"
                    )

                    /*
                     * Keep the UI connection state synchronized with the
                     * actual MWDAT DeviceSession.
                     */
                    when (state) {

                        DeviceSessionState.STARTED -> {

                            if (
                                session === activeSession
                            ) {
                                _connectionState.value =
                                    ConnectionState.CONNECTED
                            }
                        }

                        DeviceSessionState.STOPPED,
                        DeviceSessionState.CLOSED -> {

                            if (
                                session === activeSession
                            ) {
                                _connectionState.value =
                                    ConnectionState.DISCONNECTED
                            }
                        }

                        else -> {
                            // STARTING and other intermediate states
                            // are handled by connect()'s CONNECTING state.
                        }
                    }
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Session state observer failed",
                    e
                )

                if (
                    session === activeSession
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

                    Log.e(TAG, "================================================")
                    Log.e(TAG, "SESSION ERROR EVENT")
                    Log.e(TAG, "Error = $error")
                    Log.e(
                        TAG,
                        "Error type = ${error::class.java.name}"
                    )
                    Log.e(TAG, "================================================")

                    if (
                        session === activeSession
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

                    Log.i(
                        TAG,
                        "Wearables.devices = $deviceIds"
                    )

                    if (deviceIds.isEmpty()) {

                        Log.w(
                            TAG,
                            "MWDAT currently reports ZERO devices"
                        )

                        _devices.value =
                            emptyList()

                        return@collect
                    }

                    for (deviceId in deviceIds) {

                        observeDeviceMetadata(
                            deviceId
                        )
                    }
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Wearables.devices observer failed",
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

                    Log.w(
                        TAG,
                        "No metadata flow for device $deviceId"
                    )

                    return@launch
                }

                metadataFlow.collect { device ->

                    logDeviceDiagnostics(
                        deviceId,
                        device
                    )

                    val glassesDevice =
                        toGlassesDevice(
                            deviceId,
                            device
                        )

                    val updated =
                        _devices.value
                            .filter {
                                it.id != deviceId.toString()
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
                    "Device metadata observer failed for $deviceId",
                    e
                )
            }
        }
    }

    // =========================================================================
    // DEVICE DIAGNOSTICS
    // =========================================================================

    private fun logDeviceDiagnostics(
        deviceId: DeviceIdentifier,
        device: Device,
    ) {

        Log.i(TAG, "================================================")
        Log.i(TAG, "MWDAT DEVICE DIAGNOSTICS")
        Log.i(TAG, "================================================")
        Log.i(TAG, "Device ID = $deviceId")
        Log.i(TAG, "Device name = ${device.name}")
        Log.i(TAG, "Device type = ${device.deviceType}")
        Log.i(
            TAG,
            "Device type description = " +
                "${device.deviceType.description}"
        )
        Log.i(TAG, "Link state = ${device.linkState}")
        Log.i(TAG, "Compatibility = ${device.compatibility}")
        Log.i(
            TAG,
            "Display capable = ${device.isDisplayCapable()}"
        )
        Log.i(TAG, "Device object = $device")
        Log.i(TAG, "================================================")
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

        /*
         * IMPORTANT:
         *
         * This remains Bluetooth/device-link state.
         *
         * It is NOT used by HomeScreen to determine whether our
         * MWDAT DeviceSession is connected.
         */
        val connected =
            device.linkState ==
                LinkState.CONNECTED

        return GlassesDevice(
            id = id.toString(),
            name = displayName,
            connected = connected,
        )
    }

    // =========================================================================
    // REGISTRATION OBSERVER
    // =========================================================================

    private fun observeRegistrationState() {

        scope.launch {

            try {

                Wearables.registrationState.collect { state ->

                    Log.i(
                        TAG,
                        "MWDAT registration state = $state"
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
                    "Registration observer failed",
                    e
                )

                _registrationState.value =
                    RegistrationState.UNKNOWN
            }
        }
    }

    // =========================================================================
    // CAMERA PERMISSION
    // =========================================================================

    override suspend fun cameraPermission(): CameraPermission {

        Log.d(
            TAG,
            "cameraPermission() called"
        )

        return try {

            val result =
                Wearables.checkPermissionStatus(
                    Permission.CAMERA
                )

            Log.d(
                TAG,
                "Camera permission result = $result"
            )

            val status =
                result.getOrElse {
                    Log.e(
                        TAG,
                        "Camera permission status lookup failed: $it"
                    )

                    return CameraPermission.NOT_DETERMINED
                }

            when (status) {

                PermissionStatus.Granted -> {

                    Log.i(
                        TAG,
                        "Meta CAMERA permission = GRANTED"
                    )

                    CameraPermission.GRANTED
                }

                PermissionStatus.Denied -> {

                    Log.i(
                        TAG,
                        "Meta CAMERA permission = DENIED"
                    )

                    CameraPermission.DENIED
                }
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

        val currentPermission =
            cameraPermission()

        if (
            currentPermission ==
            CameraPermission.GRANTED
        ) {

            Log.i(
                TAG,
                "Meta CAMERA permission already GRANTED; no request needed"
            )

            return CameraPermission.GRANTED
        }

        Log.i(
            TAG,
            "Requesting Meta Wearables CAMERA permission"
        )

        val requester =
            cameraPermissionRequester

        if (requester == null) {

            Log.e(
                TAG,
                "Camera permission requester is NULL"
            )

            Log.e(
                TAG,
                "MainActivity has not registered the " +
                    "Wearables.RequestPermissionContract() bridge"
            )

            return CameraPermission.NOT_DETERMINED
        }

        return try {

            val result =
                requester()

            Log.i(
                TAG,
                "Meta CAMERA permission request completed: $result"
            )

            val verifiedPermission =
                cameraPermission()

            Log.i(
                TAG,
                "Meta CAMERA permission after request = " +
                    verifiedPermission
            )

            verifiedPermission

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Meta CAMERA permission request failed",
                e
            )

            CameraPermission.NOT_DETERMINED
        }
    }

    // =========================================================================
    // EXISTING GEMINI CAMERA FLOW
    // =========================================================================

    override fun cameraFrames():
        Flow<ByteArray> = flow {

        Log.i(TAG, "================================================")
        Log.i(TAG, "CAMERA FLOW STARTING - SINGLE PHOTO DIAGNOSTIC")
        Log.i(TAG, "================================================")

        var activeSession =
            session

        if (
            activeSession == null ||
            activeSession.state.value !=
                DeviceSessionState.STARTED
        ) {

            Log.i(
                TAG,
                "CAMERA: No usable DeviceSession; connecting glasses first"
            )

            val connected =
                try {

                    connect()

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "CAMERA: connect() threw exception",
                        e
                    )

                    false
                }

            if (!connected) {

                Log.e(
                    TAG,
                    "CAMERA ABORTED: Could not establish DeviceSession"
                )

                return@flow
            }

            activeSession =
                session
        }

        if (activeSession == null) {

            Log.e(
                TAG,
                "CAMERA ABORTED: DeviceSession is still NULL after connect()"
            )

            return@flow
        }

        if (
            activeSession.state.value !=
                DeviceSessionState.STARTED
        ) {

            Log.e(
                TAG,
                "CAMERA ABORTED: DeviceSession is not STARTED after connect()"
            )

            Log.e(
                TAG,
                "Current session state = " +
                    activeSession.state.value
            )

            return@flow
        }

        Log.i(
            TAG,
            "CAMERA: DeviceSession is READY"
        )

        Log.i(
            TAG,
            "CAMERA: DeviceSession state = " +
                activeSession.state.value
        )

        val permission =
            cameraPermission()

        Log.i(
            TAG,
            "Camera permission before camera start = $permission"
        )

        if (
            permission !=
                CameraPermission.GRANTED
        ) {

            Log.e(
                TAG,
                "CAMERA ABORTED: Meta camera permission is not GRANTED"
            )

            return@flow
        }

        val activeCamera =
            try {

                Log.i(
                    TAG,
                    "Adding MWDAT camera"
                )

                activeSession.addCamera(
                    StreamConfiguration(
                        videoQuality =
                            VideoQuality.MEDIUM,
                        frameRate = 7,
                    )
                ).getOrElse { error ->

                    Log.e(
                        TAG,
                        "addCamera() FAILED: $error"
                    )

                    Log.e(
                        TAG,
                        "addCamera() error type = " +
                            error::class.java.name
                    )

                    return@flow
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "addCamera() THREW EXCEPTION",
                    e
                )

                return@flow
            }

        camera =
            activeCamera

        Log.i(
            TAG,
            "MWDAT camera added successfully"
        )

        Log.i(
            TAG,
            "Camera stream initial state = " +
                activeCamera.stream.state.value
        )

        try {

            Log.i(
                TAG,
                "Starting MWDAT camera stream"
            )

            try {

                activeCamera.stream
                    .start()
                    .getOrElse { error ->

                        Log.e(
                            TAG,
                            "camera.stream.start() FAILED: $error"
                        )

                        Log.e(
                            TAG,
                            "Stream start error type = " +
                                error::class.java.name
                        )

                        return@flow
                    }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "camera.stream.start() THREW EXCEPTION",
                    e
                )

                return@flow
            }

            Log.i(
                TAG,
                "camera.stream.start() returned successfully"
            )

            Log.i(
                TAG,
                "Waiting for camera StreamState.STREAMING..."
            )

            val streaming =
                withTimeoutOrNull(
                    CAMERA_STREAM_TIMEOUT_MS
                ) {

                    activeCamera.stream.state.first { state ->

                        Log.i(
                            TAG,
                            "CAMERA STREAM STATE -> $state"
                        )

                        state ==
                            StreamState.STREAMING
                    }

                    true

                } ?: false

            if (!streaming) {

                Log.e(
                    TAG,
                    "CAMERA FAILED: stream never reached STREAMING"
                )

                Log.e(
                    TAG,
                    "Final stream state = " +
                        activeCamera.stream.state.value
                )

                return@flow
            }

            Log.i(TAG, "================================================")
            Log.i(TAG, "CAMERA STREAMING")
            Log.i(TAG, "Ready for capturePhoto()")
            Log.i(TAG, "================================================")

            Log.i(
                TAG,
                "Waiting ${CAMERA_SETTLE_DELAY_MS}ms for camera pipeline to settle"
            )

            delay(
                CAMERA_SETTLE_DELAY_MS
            )

            Log.i(
                TAG,
                "Camera settle delay complete"
            )

            Log.i(TAG, "================================================")
            Log.i(TAG, "SINGLE PHOTO CAPTURE TEST")
            Log.i(TAG, "Calling capturePhoto() exactly ONCE")
            Log.i(TAG, "================================================")

            try {

                val captureResult =
                    activeCamera.stream.capturePhoto()

                Log.i(
                    TAG,
                    "capturePhoto() returned"
                )

                Log.i(
                    TAG,
                    "Capture result = $captureResult"
                )

                val photoData =
                    captureResult.getOrNull()

                if (photoData == null) {

                    Log.e(TAG, "================================================")
                    Log.e(TAG, "PHOTO CAPTURE FAILED")
                    Log.e(TAG, "capturePhoto() returned NO PhotoData")
                    Log.e(TAG, "================================================")

                    val captureError =
                        captureResult.errorOrNull()

                    Log.e(
                        TAG,
                        "Capture error = $captureError"
                    )

                    if (captureError != null) {

                        Log.e(
                            TAG,
                            "Capture error type = " +
                                captureError::class.java.name
                        )
                    }

                    val captureException =
                        captureResult.exceptionOrNull()

                    if (captureException != null) {

                        Log.e(
                            TAG,
                            "Capture exception = " +
                                captureException::class.java.name
                        )

                        Log.e(
                            TAG,
                            "Capture exception message = " +
                                captureException.message
                        )

                        Log.e(
                            TAG,
                            "Capture exception details",
                            captureException
                        )
                    }

                    Log.e(
                        TAG,
                        "Camera stream state after capture failure = " +
                            activeCamera.stream.state.value
                    )

                    Log.e(TAG, "================================================")

                } else {

                    Log.i(TAG, "================================================")
                    Log.i(TAG, "PHOTO CAPTURE SUCCEEDED")
                    Log.i(
                        TAG,
                        "PhotoData implementation = " +
                            photoData::class.java.name
                    )
                    Log.i(
                        TAG,
                        "PhotoData = $photoData"
                    )
                    Log.i(TAG, "================================================")

                    val jpegBytes =
                        photoDataToJpeg(
                            photoData
                        )

                    if (
                        jpegBytes != null &&
                        jpegBytes.isNotEmpty()
                    ) {

                        Log.i(TAG, "================================================")
                        Log.i(TAG, "PHOTO CONVERTED TO JPEG")
                        Log.i(
                            TAG,
                            "JPEG size = ${jpegBytes.size} bytes"
                        )
                        Log.i(TAG, "Emitting ONE JPEG to camera flow")
                        Log.i(TAG, "================================================")

                        emit(
                            jpegBytes
                        )

                    } else {

                        Log.e(TAG, "================================================")
                        Log.e(
                            TAG,
                            "PHOTO CAPTURED BUT JPEG CONVERSION FAILED"
                        )
                        Log.e(TAG, "================================================")
                    }
                }

            } catch (e: Exception) {

                Log.e(TAG, "================================================")
                Log.e(
                    TAG,
                    "capturePhoto() THREW EXCEPTION"
                )
                Log.e(
                    TAG,
                    "Exception type = ${e::class.java.name}"
                )
                Log.e(
                    TAG,
                    "Exception message = ${e.message}"
                )
                Log.e(
                    TAG,
                    "Exception details",
                    e
                )
                Log.e(TAG, "================================================")
            }

            Log.i(TAG, "================================================")
            Log.i(
                TAG,
                "SINGLE PHOTO CAPTURE TEST COMPLETE"
            )
            Log.i(
                TAG,
                "Ending camera flow intentionally"
            )
            Log.i(TAG, "================================================")

        } finally {

            Log.i(
                TAG,
                "Camera Flow ending; cleaning up camera"
            )

            stopCameraIfNeeded()

            Log.i(
                TAG,
                "Camera Flow cleanup complete"
            )
        }
    }

    // =========================================================================
    // NEW CAMERA TEST - LIVE VIDEO STREAM
    // =========================================================================

    fun cameraTestFrames():
        Flow<VideoFrame> = flow {

        Log.i(TAG, "================================================")
        Log.i(TAG, "CAMERA TEST - LIVE STREAM STARTING")
        Log.i(TAG, "================================================")

        var activeSession =
            session

        if (
            activeSession == null ||
            activeSession.state.value !=
                DeviceSessionState.STARTED
        ) {

            Log.i(
                TAG,
                "CAMERA TEST: DeviceSession unavailable; connecting"
            )

            val connected =
                try {
                    connect()
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "CAMERA TEST: connect() failed",
                        e
                    )
                    false
                }

            if (!connected) {

                Log.e(
                    TAG,
                    "CAMERA TEST ABORTED: Could not connect"
                )

                return@flow
            }

            activeSession =
                session
        }

        if (activeSession == null) {

            Log.e(
                TAG,
                "CAMERA TEST ABORTED: session is NULL"
            )

            return@flow
        }

        if (
            activeSession.state.value !=
                DeviceSessionState.STARTED
        ) {

            Log.e(
                TAG,
                "CAMERA TEST ABORTED: session not STARTED"
            )

            return@flow
        }

        val permission =
            cameraPermission()

        Log.i(
            TAG,
            "CAMERA TEST permission = $permission"
        )

        if (
            permission !=
                CameraPermission.GRANTED
        ) {

            Log.e(
                TAG,
                "CAMERA TEST ABORTED: camera permission not granted"
            )

            return@flow
        }

        if (camera != null) {

            Log.w(
                TAG,
                "CAMERA TEST: Existing camera found; stopping it first"
            )

            stopCameraIfNeeded()
        }

        val activeCamera =
            try {

                Log.i(
                    TAG,
                    "CAMERA TEST: Adding camera"
                )

                activeSession.addCamera(
                    StreamConfiguration(
                        videoQuality =
                            VideoQuality.MEDIUM,
                        frameRate =
                            CAMERA_TEST_FRAME_RATE,
                        compressVideo = true,
                    )
                ).getOrElse { error ->

                    Log.e(
                        TAG,
                        "CAMERA TEST addCamera() FAILED: $error"
                    )

                    return@flow
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "CAMERA TEST addCamera() threw",
                    e
                )

                return@flow
            }

        camera =
            activeCamera

        Log.i(
            TAG,
            "CAMERA TEST: camera added"
        )

        try {

            Log.i(
                TAG,
                "CAMERA TEST: starting stream"
            )

            val startResult =
                activeCamera.stream.start()

            if (!startResult.isSuccess) {

                Log.e(
                    TAG,
                    "CAMERA TEST: stream.start() failed: " +
                        startResult.errorOrNull()
                )

                return@flow
            }

            Log.i(
                TAG,
                "CAMERA TEST: stream.start() returned successfully"
            )

            val streaming =
                withTimeoutOrNull(
                    CAMERA_STREAM_TIMEOUT_MS
                ) {

                    activeCamera.stream.state.first { state ->

                        Log.i(
                            TAG,
                            "CAMERA TEST STREAM STATE -> $state"
                        )

                        state ==
                            StreamState.STREAMING
                    }

                    true

                } ?: false

            if (!streaming) {

                Log.e(
                    TAG,
                    "CAMERA TEST: stream never reached STREAMING"
                )

                return@flow
            }

            Log.i(TAG, "================================================")
            Log.i(TAG, "CAMERA TEST: STREAMING")
            Log.i(
                TAG,
                "compressVideo = true"
            )
            Log.i(TAG, "Waiting for VideoFrame objects...")
            Log.i(TAG, "================================================")

            activeCamera.stream.videoStream.collect { frame ->

                Log.d(
                    TAG,
                    "CAMERA TEST FRAME: " +
                        "${frame.width}x${frame.height}, " +
                        "bytes=${frame.buffer.remaining()}, " +
                        "compressed=${frame.isCompressed}, " +
                        "codecConfig=${frame.isCodecConfig}"
                )

                emit(frame)
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "CAMERA TEST live stream failed",
                e
            )

        } finally {

            Log.i(
                TAG,
                "CAMERA TEST live stream ending"
            )

            stopCameraIfNeeded()

            Log.i(
                TAG,
                "CAMERA TEST camera cleanup complete"
            )
        }
    }

    // =========================================================================
    // NEW CAMERA TEST - PHOTO CAPTURE
    // =========================================================================

    suspend fun captureCameraTestPhoto():
        Result<ByteArray> {

        Log.i(TAG, "================================================")
        Log.i(TAG, "CAMERA TEST PHOTO CAPTURE")
        Log.i(TAG, "================================================")

        val activeCamera =
            camera

        if (activeCamera == null) {

            val message =
                "No active camera. Start the camera stream first."

            Log.e(
                TAG,
                message
            )

            return Result.failure(
                IllegalStateException(message)
            )
        }

        val currentState =
            activeCamera.stream.state.value

        Log.i(
            TAG,
            "Camera stream state before capture = $currentState"
        )

        if (
            currentState !=
                StreamState.STREAMING
        ) {

            val message =
                "Camera stream is not STREAMING: $currentState"

            Log.e(
                TAG,
                message
            )

            return Result.failure(
                IllegalStateException(message)
            )
        }

        try {

            Log.i(
                TAG,
                "Calling capturePhoto() from Camera Test"
            )

            val captureResult =
                activeCamera.stream.capturePhoto()

            Log.i(
                TAG,
                "Camera Test capturePhoto() returned"
            )

            Log.i(
                TAG,
                "Capture result = $captureResult"
            )

            val photoData =
                captureResult.getOrNull()

            if (photoData == null) {

                val captureError =
                    captureResult.errorOrNull()

                val captureException =
                    captureResult.exceptionOrNull()

                Log.e(
                    TAG,
                    "CAMERA TEST PHOTO FAILED"
                )

                Log.e(
                    TAG,
                    "Capture error = $captureError"
                )

                Log.e(
                    TAG,
                    "Capture exception = $captureException"
                )

                if (captureException != null) {

                    Log.e(
                        TAG,
                        "Capture exception details",
                        captureException
                    )
                }

                return Result.failure(
                    captureException
                        ?: IllegalStateException(
                            "capturePhoto failed: $captureError"
                        )
                )
            }

            Log.i(
                TAG,
                "CAMERA TEST PHOTO CAPTURED"
            )

            Log.i(
                TAG,
                "PhotoData type = ${photoData::class.java.name}"
            )

            val jpeg =
                photoDataToJpeg(
                    photoData
                )

            if (
                jpeg == null ||
                jpeg.isEmpty()
            ) {

                val message =
                    "Photo captured but JPEG conversion failed"

                Log.e(
                    TAG,
                    message
                )

                return Result.failure(
                    IllegalStateException(message)
                )
            }

            Log.i(
                TAG,
                "CAMERA TEST PHOTO JPEG SIZE = ${jpeg.size} bytes"
            )

            return Result.success(
                jpeg
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "CAMERA TEST capturePhoto() threw exception",
                e
            )

            return Result.failure(e)
        }
    }

    fun stopCameraTest() {

        Log.i(
            TAG,
            "Stopping Camera Test"
        )

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

                    Log.d(
                        TAG,
                        "PhotoData type = Bitmap"
                    )

                    Log.d(
                        TAG,
                        "Bitmap width = ${photoData.bitmap.width}"
                    )

                    Log.d(
                        TAG,
                        "Bitmap height = ${photoData.bitmap.height}"
                    )

                    bitmapToJpeg(
                        photoData.bitmap
                    )
                }

                is PhotoData.HEIC -> {

                    Log.d(
                        TAG,
                        "PhotoData type = HEIC"
                    )

                    Log.d(
                        TAG,
                        "HEIC remaining bytes = " +
                            photoData.data.remaining()
                    )

                    heicToJpeg(
                        photoData.data
                    )
                }

                else -> {

                    Log.w(
                        TAG,
                        "Unknown PhotoData implementation: " +
                            photoData::class.java.name
                    )

                    null
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed converting PhotoData to JPEG",
                e
            )

            null
        }
    }

    private fun bitmapToJpeg(
        bitmap: Bitmap,
    ): ByteArray? {

        if (bitmap.isRecycled) {

            Log.w(
                TAG,
                "Bitmap is already recycled"
            )

            return null
        }

        val output =
            ByteArrayOutputStream()

        return try {

            val success =
                bitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    JPEG_QUALITY,
                    output
                )

            if (!success) {

                Log.e(
                    TAG,
                    "Bitmap.compress() returned false"
                )

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

            Log.w(
                TAG,
                "HEIC ByteBuffer contained zero bytes"
            )

            return null
        }

        val bitmap =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

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
                        "ImageDecoder could not decode HEIC",
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
                        "BitmapFactory could not decode HEIC",
                        e
                    )

                    null
                }
            }

        if (bitmap == null) {

            Log.e(
                TAG,
                "HEIC could not be decoded into Bitmap"
            )

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

        duplicate.get(
            bytes
        )

        return bytes
    }

    // =========================================================================
    // CAMERA CLEANUP
    // =========================================================================

    private fun stopCameraIfNeeded() {

        val activeCamera =
            camera

        val activeSession =
            session

        if (activeCamera == null) {
            return
        }

        Log.i(
            TAG,
            "Stopping active MWDAT camera"
        )

        try {

            activeCamera.stop()

            Log.i(
                TAG,
                "Camera.stop() called"
            )

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

                if (result.isSuccess) {

                    Log.i(
                        TAG,
                        "DeviceSession.removeCamera() succeeded"
                    )

                } else {

                    Log.w(
                        TAG,
                        "DeviceSession.removeCamera() failed: " +
                            "${result.errorOrNull()}"
                    )
                }

            } catch (e: Exception) {

                Log.w(
                    TAG,
                    "DeviceSession.removeCamera() threw exception",
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

            try {

                stopCameraIfNeeded()

                val activeSession =
                    session

                if (activeSession != null) {

                    Log.i(
                        TAG,
                        "Stopping DeviceSession"
                    )

                    activeSession.stop()

                    Log.i(
                        TAG,
                        "DeviceSession.stop() called"
                    )
                }

            } catch (e: Exception) {

                Log.w(
                    TAG,
                    "Error stopping DeviceSession",
                    e
                )

            } finally {

                session = null

                _connectionState.value =
                    ConnectionState.DISCONNECTED

                scope.cancel()
            }
        }
    }
}
