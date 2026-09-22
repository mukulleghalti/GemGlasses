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
import com.lpecom.gemglasses.glasses.GlassesBackend
import com.lpecom.gemglasses.glasses.GlassesDevice
import com.lpecom.gemglasses.glasses.RegistrationState
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.removeCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
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

        private const val CAMERA_STREAM_TIMEOUT_MS = 15_000L

        // Capture approximately one still image per second.
        private const val PHOTO_INTERVAL_MS = 1_000L

        // JPEG quality used when converting MWDAT Bitmap/HEIC photos.
        private const val JPEG_QUALITY = 90
    }

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var activity: Activity? = null

    private var session: DeviceSession? = null

    /**
     * Currently attached MWDAT camera.
     */
    private var camera:
        com.meta.wearable.dat.camera.Camera? = null

    /**
     * MainActivity supplies the actual Activity Result permission request.
     */
    private var cameraPermissionRequester:
        (suspend () -> CameraPermission)? = null

    private val _registrationState =
        MutableStateFlow(RegistrationState.UNKNOWN)

    override val registrationState: Flow<RegistrationState> =
        _registrationState.asStateFlow()

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

    // =========================================================================
    // CONNECTION
    // =========================================================================

    override suspend fun connect(): Boolean {

        Log.i(TAG, "================================================")
        Log.i(TAG, "STARTING MINIMAL MWDAT CONNECTION TEST")
        Log.i(TAG, "================================================")

        logBluetoothPermissions()

        // ---------------------------------------------------------------------
        // Registration
        // ---------------------------------------------------------------------

        val registration =
            _registrationState.value

        Log.i(
            TAG,
            "Current registration state = $registration"
        )

        if (registration != RegistrationState.REGISTERED) {

            Log.e(
                TAG,
                "ABORTING: MWDAT registration is not REGISTERED"
            )

            return false
        }

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
            Log.i(TAG, "  connected = ${device.connected}")
        }

        if (devices.isEmpty()) {

            Log.e(
                TAG,
                "ABORTING: MWDAT reports zero devices"
            )

            return false
        }

        // ---------------------------------------------------------------------
        // AutoDeviceSelector
        // ---------------------------------------------------------------------

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

            Log.i(TAG, "================================================")
            Log.i(TAG, "MWDAT CONNECTION TEST SUCCESS")
            Log.i(TAG, "SESSION STATE = STARTED")
            Log.i(TAG, "================================================")

            return true
        }

        Log.e(TAG, "================================================")
        Log.e(TAG, "MWDAT CONNECTION TEST FAILED")
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
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Session state observer failed",
                    e
                )
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

            result

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
    // CAMERA
    // =========================================================================

    /**
     * Starts the MWDAT camera and emits JPEG image bytes.
     *
     * We intentionally use capturePhoto() rather than forwarding
     * camera.stream.videoStream directly.
     *
     * The MWDAT video stream is not the image format we want to send
     * into the Gemini vision path.
     */
    override fun cameraFrames():
        Flow<ByteArray> = flow {

        Log.i(TAG, "================================================")
        Log.i(TAG, "CAMERA FLOW STARTING")
        Log.i(TAG, "================================================")

        // ---------------------------------------------------------------------
// Ensure MWDAT DeviceSession exists before adding the camera.
//
// The camera cannot work without a STARTED DeviceSession.
// We do this here as a safety net because vision may be requested
// independently of the normal glasses connection lifecycle.
// ---------------------------------------------------------------------

var activeSession = session

if (
    activeSession == null ||
    activeSession.state.value != DeviceSessionState.STARTED
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

    activeSession = session
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
        "Current session state = ${activeSession.state.value}"
    )

    return@flow
}

Log.i(
    TAG,
    "CAMERA: DeviceSession is READY"
)

Log.i(
    TAG,
    "CAMERA: DeviceSession state = ${activeSession.state.value}"
)

        Log.i(
            TAG,
            "Current DeviceSession state = " +
                activeSession.state.value
        )

        if (
            activeSession.state.value !=
            DeviceSessionState.STARTED
        ) {

            Log.e(
                TAG,
                "CAMERA ABORTED: DeviceSession is not STARTED"
            )

            return@flow
        }

        // ---------------------------------------------------------------------
        // Permission
        // ---------------------------------------------------------------------

        val permission =
            cameraPermission()

        Log.i(
            TAG,
            "Camera permission before camera start = $permission"
        )

        if (permission != CameraPermission.GRANTED) {

            Log.e(
                TAG,
                "CAMERA ABORTED: Meta camera permission is not GRANTED"
            )

            return@flow
        }

        // ---------------------------------------------------------------------
        // Add camera
        // ---------------------------------------------------------------------

        val activeCamera =
            try {

                Log.i(
                    TAG,
                    "Adding MWDAT camera"
                )

                activeSession.addCamera(
                    StreamConfiguration(
                        videoQuality = VideoQuality.MEDIUM,
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

            // -----------------------------------------------------------------
            // Start stream
            // -----------------------------------------------------------------

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

            // -----------------------------------------------------------------
            // Wait for STREAMING
            // -----------------------------------------------------------------

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

            // -----------------------------------------------------------------
            // Capture still images continuously.
            //
            // GlassesCameraSource controls how long this Flow is collected.
            // When collection is cancelled, finally{} runs and removes camera.
            // -----------------------------------------------------------------

            while (true) {

                try {

                    Log.d(
                        TAG,
                        "Calling camera.stream.capturePhoto()"
                    )

                    val captureResult =
                        activeCamera.stream.capturePhoto()

                    val photoData =
                        captureResult.getOrNull()

                    if (photoData == null) {

                        val error =
                            captureResult.errorOrNull()

                        val exception =
                            captureResult.exceptionOrNull()

                        Log.e(
                            TAG,
                            "capturePhoto() returned no PhotoData"
                        )

                        Log.e(
                            TAG,
                            "Capture error = $error"
                        )

                        if (exception != null) {

                            Log.e(
                                TAG,
                                "Capture exception",
                                exception
                            )
                        }

                    } else {

                        val jpegBytes =
                            photoDataToJpeg(
                                photoData
                            )

                        if (
                            jpegBytes != null &&
                            jpegBytes.isNotEmpty()
                        ) {

                            Log.i(
                                TAG,
                                "PHOTO CAPTURED: " +
                                    "${jpegBytes.size} JPEG bytes"
                            )

                            emit(
                                jpegBytes
                            )

                            Log.d(
                                TAG,
                                "JPEG emitted to GlassesCameraSource"
                            )

                        } else {

                            Log.w(
                                TAG,
                                "PhotoData could not be converted to JPEG"
                            )
                        }
                    }

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "capturePhoto() THREW EXCEPTION",
                        e
                    )
                }

                delay(
                    PHOTO_INTERVAL_MS
                )
            }

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
    // PHOTO CONVERSION
    // =========================================================================

    /**
     * Converts MWDAT PhotoData into JPEG bytes suitable for the Gemini
     * image input path.
     *
     * MWDAT 0.9.0 exposes:
     *
     * PhotoData.Bitmap
     *     -> bitmap
     *
     * PhotoData.HEIC
     *     -> data: ByteBuffer
     */
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

                    bitmapToJpeg(
                        photoData.bitmap
                    )
                }

                is PhotoData.HEIC -> {

                    Log.d(
                        TAG,
                        "PhotoData type = HEIC"
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

    /**
     * Compress an Android Bitmap to JPEG.
     */
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

    /**
     * Convert MWDAT's HEIC ByteBuffer to JPEG.
     *
     * Android 11+ exposes ImageDecoder.createSource(ByteBuffer),
     * which lets us decode the HEIC directly into a Bitmap.
     *
     * For older Android versions we attempt BitmapFactory as a fallback.
     */
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

    /**
     * Copies the remaining contents of a ByteBuffer without modifying
     * the original buffer's position.
     */
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

    /**
     * Stops and removes the current MWDAT camera.
     *
     * IMPORTANT:
     * We do NOT stop the DeviceSession here.
     *
     * The DeviceSession must remain STARTED so the next vision burst can
     * simply attach another camera.
     */
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

                scope.cancel()
            }
        }
    }
}
