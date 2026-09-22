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

        private const val DEVICE_CONNECT_TIMEOUT_MS = 20_000L
        private const val SESSION_START_TIMEOUT_MS = 15_000L
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
    // ACTIVITY
    // -------------------------------------------------------------------------

    override fun setActivity(activity: Activity) {
        this.activity = activity
        Log.d(TAG, "Activity attached: ${activity::class.java.simpleName}")
    }

    override fun clearActivity(activity: Activity) {
        if (this.activity === activity) {
            this.activity = null
            Log.d(TAG, "Activity detached")
        }
    }

    // -------------------------------------------------------------------------
    // INITIALIZATION
    // -------------------------------------------------------------------------

    override fun initialize() {
        Log.i(TAG, "Initializing Meta Wearables Device Access Toolkit")
        logBluetoothPermissions()

        try {
            Wearables.initialize(context)
            Log.i(TAG, "Wearables.initialize() called successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Wearables", e)
            _registrationState.value = RegistrationState.UNKNOWN
        }
    }

    private fun logBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scanGranted =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN,
                ) == PackageManager.PERMISSION_GRANTED

            val connectGranted =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ) == PackageManager.PERMISSION_GRANTED

            Log.i(
                TAG,
                "Bluetooth permissions: SCAN=$scanGranted, CONNECT=$connectGranted",
            )
        } else {
            Log.i(
                TAG,
                "Bluetooth runtime permissions not required on Android < 12",
            )
        }
    }

    // -------------------------------------------------------------------------
    // REGISTRATION
    // -------------------------------------------------------------------------

    override fun startRegistration() {
        val currentActivity = activity

        if (currentActivity == null) {
            Log.e(TAG, "Cannot start registration: Activity is not attached")
            return
        }

        try {
            Log.i(TAG, "Starting Meta glasses registration")
            Wearables.startRegistration(currentActivity)
            _registrationState.value = RegistrationState.REGISTERING
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Meta glasses registration", e)
            _registrationState.value = RegistrationState.UNKNOWN
        }
    }

    // -------------------------------------------------------------------------
    // CONNECT
    // -------------------------------------------------------------------------

    override suspend fun connect(): Boolean {
        return sessionMutex.withLock {
            try {
                Log.i(TAG, "========== CONNECT GLASSES ==========")
                logBluetoothPermissions()

                val registration = _registrationState.value
                Log.i(TAG, "Registration state = $registration")

                if (registration != RegistrationState.REGISTERED) {
                    Log.e(
                        TAG,
                        "Cannot connect: app is not registered with Meta AI",
                    )
                    return@withLock false
                }

                val existingSession = session

                if (existingSession != null) {
                    val state = existingSession.state.value
                    Log.i(TAG, "Existing DeviceSession state = $state")

                    if (state == DeviceSessionState.STARTED) {
                        return@withLock true
                    }

                    if (
                        state == DeviceSessionState.STOPPED ||
                        state == DeviceSessionState.STOPPING
                    ) {
                        Log.w(TAG, "Discarding terminal DeviceSession")
                        session = null
                    }
                }

                /*
                 * Wait for the SDK metadata flow to report the device as
                 * connected. The device can be visible in Wearables.devices
                 * while still being in DISCONNECTED link state.
                 */
                val connected =
                    withTimeoutOrNull(DEVICE_CONNECT_TIMEOUT_MS) {
                        _devices.first { deviceList ->
                            val connectedDevice =
                                deviceList.firstOrNull { it.connected }

                            if (connectedDevice != null) {
                                Log.i(
                                    TAG,
                                    "MWDAT now reports a connected device: " +
                                        connectedDevice.name,
                                )
                                true
                            } else {
                                Log.d(
                                    TAG,
                                    "Waiting for MWDAT link state CONNECTED",
                                )
                                false
                            }
                        }
                    } ?: false

                if (!connected) {
                    val current = _devices.value

                    Log.e(
                        TAG,
                        "Timed out waiting for MWDAT CONNECTED state",
                    )

                    current.forEach {
                        Log.e(
                            TAG,
                            "Current device state: " +
                                "id=${it.id}, " +
                                "name=${it.name}, " +
                                "connected=${it.connected}",
                        )
                    }

                    Log.e(
                        TAG,
                        "Do not call createSession(): MWDAT reports " +
                            "no eligible connected device",
                    )

                    return@withLock false
                }

                val deviceId = selectedDeviceId

                if (deviceId == null) {
                    Log.e(TAG, "No DeviceIdentifier is selected")
                    return@withLock false
                }

                Log.i(TAG, "Selected device ID = $deviceId")

                if (session == null) {
                    Log.i(
                        TAG,
                        "Creating MWDAT session with SpecificDeviceSelector",
                    )

                    val sessionResult =
                        Wearables.createSession(
                            SpecificDeviceSelector(deviceId),
                        )

                    val createdSession =
                        sessionResult.getOrElse { error ->
                            logSessionFailure(error)
                            return@withLock false
                        }

                    session = createdSession

                    Log.i(TAG, "DeviceSession created successfully")
                    observeSessionErrors(createdSession)

                    Log.i(TAG, "Starting DeviceSession")
                    createdSession.start()
                }

                val activeSession =
                    session ?: return@withLock false

                val started =
                    withTimeoutOrNull(SESSION_START_TIMEOUT_MS) {
                        activeSession.state.first { state ->
                            Log.i(TAG, "DeviceSession state = $state")
                            state == DeviceSessionState.STARTED
                        }
                        true
                    } ?: false

                if (started) {
                    Log.i(TAG, "GLASSES DEVICE SESSION STARTED")
                    true
                } else {
                    Log.e(
                        TAG,
                        "DeviceSession did not reach STARTED within " +
                            "${SESSION_START_TIMEOUT_MS}ms",
                    )
                    Log.e(
                        TAG,
                        "Final state = ${activeSession.state.value}",
                    )
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception while connecting to glasses", e)
                false
            }
        }
    }

    private fun logSessionFailure(error: Any?) {
        val text = error.toString()

        Log.e(TAG, "MWDAT createSession() failure: $text")
        Log.e(
            TAG,
            "Failure runtime type: ${error?.let { it::class.java.name }}",
        )

        when {
            text.contains("DEVICE_UPDATE_REQUIRED", ignoreCase = true) -> {
                Log.e(TAG, "Glasses firmware/device update required")
            }

            text.contains(
                "DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED",
                ignoreCase = true,
            ) -> {
                Log.e(TAG, "DAT app on glasses update required")
            }

            text.contains("NO_ELIGIBLE_DEVICE", ignoreCase = true) -> {
                Log.e(TAG, "No eligible connected device")
            }
        }
    }

    // -------------------------------------------------------------------------
    // CAMERA PERMISSION
    // -------------------------------------------------------------------------

    override suspend fun cameraPermission(): CameraPermission {
        return try {
            val result =
                Wearables.checkPermissionStatus(Permission.CAMERA)

            result.onSuccess {
                Log.d(TAG, "Camera permission status: $it")
            }

            result.onFailure { error, _ ->
                Log.e(TAG, "Camera permission check failed: $error")
            }

            val status =
                result.getOrElse {
                    return CameraPermission.NOT_DETERMINED
                }

            when (status) {
                PermissionStatus.Granted -> CameraPermission.GRANTED
                PermissionStatus.Denied -> CameraPermission.DENIED
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check camera permission", e)
            CameraPermission.NOT_DETERMINED
        }
    }

    override suspend fun requestCameraPermission(): CameraPermission {
        Log.i(
            TAG,
            "Camera permission request requires Wearables.RequestPermissionContract()",
        )
        return CameraPermission.NOT_DETERMINED
    }

    // -------------------------------------------------------------------------
    // CAMERA
    // -------------------------------------------------------------------------

    override fun cameraFrames(): Flow<ByteArray> =
        flow {
            try {
                if (!connect()) {
                    Log.e(TAG, "Cannot start camera: session unavailable")
                    return@flow
                }

                cameraMutex.withLock {
                    ensureCamera()
                }

                val activeCamera =
                    camera ?: return@flow

                val startResult =
                    activeCamera.stream.start()

                startResult.onFailure { error, _ ->
                    Log.e(TAG, "Failed to start camera stream: $error")
                }

                if (startResult.isFailure) {
                    return@flow
                }

                repeat(MAX_PHOTOS_PER_BURST) { index ->
                    try {
                        val photoResult =
                            activeCamera.stream.capturePhoto()

                        photoResult.onSuccess { photoData ->
                            Log.d(TAG, "capturePhoto #${index + 1} succeeded")
                            Log.d(
                                TAG,
                                "PhotoData type=${photoData::class.java.name}",
                            )
                        }

                        photoResult.onFailure { error, _ ->
                            Log.e(
                                TAG,
                                "capturePhoto #${index + 1} failed: $error",
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Photo capture exception", e)
                    }

                    if (index < MAX_PHOTOS_PER_BURST - 1) {
                        delay(PHOTO_INTERVAL_MS)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera vision session failed", e)
            }
        }

    private suspend fun ensureCamera() {
        val activeSession =
            session ?: throw IllegalStateException(
                "DeviceSession is not available",
            )

        if (camera != null) {
            return
        }

        val addResult =
            activeSession.addCamera(
                StreamConfiguration(
                    videoQuality = VideoQuality.MEDIUM,
                    frameRate = FRAME_RATE,
                ),
            )

        val addedCamera =
            addResult.getOrElse { error ->
                throw IllegalStateException("addCamera failed: $error")
            }

        camera = addedCamera
        Log.i(TAG, "Camera added successfully")
    }

    // -------------------------------------------------------------------------
    // DEVICE OBSERVATION
    // -------------------------------------------------------------------------

    private fun observeDevices() {
        scope.launch {
            try {
                Wearables.devices.collect { deviceIds ->
                    val result = mutableListOf<GlassesDevice>()

                    for (deviceId in deviceIds) {
                        try {
                            val metadataFlow =
                                Wearables.devicesMetadata[deviceId]
                                    ?: continue

                            metadataFlow.collect { device ->
                                selectedDeviceId = deviceId

                                logDeviceDiagnostics(deviceId, device)

                                result.removeAll {
                                    it.id == deviceId.toString()
                                }

                                result.add(
                                    toGlassesDevice(deviceId, device),
                                )

                                _devices.value = result.toList()
                            }
                        } catch (e: Exception) {
                            Log.e(
                                TAG,
                                "Failed reading metadata for $deviceId",
                                e,
                            )
                        }
                    }

                    if (deviceIds.isEmpty()) {
                        selectedDeviceId = null
                        _devices.value = emptyList()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Device observation failed", e)
            }
        }
    }

    private fun logDeviceDiagnostics(
        deviceId: DeviceIdentifier,
        device: Device,
    ) {
        Log.i(TAG, "========== MWDAT DEVICE DIAGNOSTICS ==========")
        Log.i(TAG, "Device ID = $deviceId")
        Log.i(TAG, "Device name = ${device.name}")
        Log.i(TAG, "Device type = ${device.deviceType}")
        Log.i(
            TAG,
            "Device type description = ${device.deviceType.description}",
        )
        Log.i(TAG, "Link state = ${device.linkState}")
        Log.i(TAG, "Compatibility = ${device.compatibility}")
        Log.i(TAG, "Display capable = ${device.isDisplayCapable()}")
        Log.i(TAG, "Device object = $device")
        Log.i(TAG, "==============================================")
    }

    private fun toGlassesDevice(
        id: DeviceIdentifier,
        device: Device,
    ): GlassesDevice {
        val rawName = device.name.toString()

        val displayName =
            if (
                rawName.isBlank() ||
                rawName.equals(UNKNOWN_NAME, ignoreCase = true)
            ) {
                DEFAULT_NAME
            } else {
                rawName
            }

        return GlassesDevice(
            id = id.toString(),
            name = displayName,
            connected = device.linkState == LinkState.CONNECTED,
        )
    }

    // -------------------------------------------------------------------------
    // REGISTRATION OBSERVATION
    // -------------------------------------------------------------------------

    private fun observeRegistrationState() {
        scope.launch {
            try {
                Wearables.registrationState.collect { state ->
                    Log.i(TAG, "MWDAT registration state: $state")

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
                Log.e(TAG, "Registration observation failed", e)
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
                    Log.e(TAG, "DeviceSession error: $error")
                    Log.e(
                        TAG,
                        "DeviceSession error type: ${error::class.java.name}",
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "DeviceSession error observation failed", e)
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
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping camera", e)
                }

                camera = null

                try {
                    session?.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping DeviceSession", e)
                }

                session = null
                selectedDeviceId = null
            }

            scope.cancel()
        }
    }
}
