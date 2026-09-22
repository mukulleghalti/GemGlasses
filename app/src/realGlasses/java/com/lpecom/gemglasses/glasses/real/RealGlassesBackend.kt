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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
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

        private const val SESSION_START_TIMEOUT_MS = 20_000L
    }

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var activity: Activity? = null

    private var session: DeviceSession? = null

    private val _registrationState =
        MutableStateFlow(RegistrationState.UNKNOWN)

    override val registrationState: Flow<RegistrationState> =
        _registrationState.asStateFlow()

    private val _devices =
        MutableStateFlow<List<GlassesDevice>>(emptyList())

    override val devices: Flow<List<GlassesDevice>> =
        _devices.asStateFlow()

    init {
        Log.i(TAG, "RealGlassesBackend created")

        observeRegistrationState()
        observeDevices()
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
    // INITIALIZE
    // =========================================================================

    override fun initialize() {

        Log.i(
            TAG,
            "================================================"
        )

        Log.i(
            TAG,
            "INITIALIZING MWDAT"
        )

        Log.i(
            TAG,
            "================================================"
        )

        logBluetoothPermissions()

        try {

            Wearables.initialize(context)

            Log.i(
                TAG,
                "Wearables.initialize() SUCCESS"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Wearables.initialize() FAILED",
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
    // CONNECTION TEST
    // =========================================================================

    override suspend fun connect(): Boolean {

        Log.i(
            TAG,
            "================================================"
        )

        Log.i(
            TAG,
            "STARTING MINIMAL MWDAT CONNECTION TEST"
        )

        Log.i(
            TAG,
            "================================================"
        )

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

            Log.i(
                TAG,
                "Device:"
            )

            Log.i(
                TAG,
                "  id = ${device.id}"
            )

            Log.i(
                TAG,
                "  name = ${device.name}"
            )

            Log.i(
                TAG,
                "  connected = ${device.connected}"
            )
        }

        if (devices.isEmpty()) {

            Log.e(
                TAG,
                "ABORTING: MWDAT reports zero devices"
            )

            return false
        }

        // ---------------------------------------------------------------------
        // IMPORTANT:
        //
        // We deliberately do NOT:
        //
        //   - wait for LinkState.CONNECTED
        //   - use SpecificDeviceSelector
        //   - manually select a device
        //   - perform any camera operation
        //
        // This is intentionally equivalent to Meta's basic session flow.
        // ---------------------------------------------------------------------

        Log.i(
            TAG,
            "Using AutoDeviceSelector()"
        )

        // ---------------------------------------------------------------------
        // Stop previous session if one exists.
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

                Log.e(
                    TAG,
                    "================================================"
                )

                Log.e(
                    TAG,
                    "CREATE SESSION FAILED"
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
                    "================================================"
                )

                logCreateSessionError(error)

                return false
            }

        session =
            createdSession

        Log.i(
            TAG,
            "================================================"
        )

        Log.i(
            TAG,
            "CREATE SESSION SUCCEEDED"
        )

        Log.i(
            TAG,
            "DeviceSession object created"
        )

        Log.i(
            TAG,
            "Initial state = ${createdSession.state.value}"
        )

        Log.i(
            TAG,
            "================================================"
        )

        // ---------------------------------------------------------------------
        // Observe session errors
        // ---------------------------------------------------------------------

        observeSessionErrors(
            createdSession
        )

        // ---------------------------------------------------------------------
        // Observe session state
        // ---------------------------------------------------------------------

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

            Log.i(
                TAG,
                "================================================"
            )

            Log.i(
                TAG,
                "MWDAT CONNECTION TEST SUCCESS"
            )

            Log.i(
                TAG,
                "SESSION STATE = STARTED"
            )

            Log.i(
                TAG,
                "================================================"
            )

            return true
        }

        Log.e(
            TAG,
            "================================================"
        )

        Log.e(
            TAG,
            "MWDAT CONNECTION TEST FAILED"
        )

        Log.e(
            TAG,
            "Session never reached STARTED"
        )

        Log.e(
            TAG,
            "Final state = ${createdSession.state.value}"
        )

        Log.e(
            TAG,
            "================================================"
        )

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

                Log.e(
                    TAG,
                    "The SDK could not find an eligible device " +
                        "for AutoDeviceSelector()."
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

                    Log.e(
                        TAG,
                        "================================================"
                    )

                    Log.e(
                        TAG,
                        "SESSION ERROR EVENT"
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
                        "================================================"
                    )
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

        Log.i(
            TAG,
            "================================================"
        )

        Log.i(
            TAG,
            "MWDAT DEVICE DIAGNOSTICS"
        )

        Log.i(
            TAG,
            "================================================"
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
            "Device type description = " +
                "${device.deviceType.description}"
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
            "================================================"
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
    // CAMERA METHODS
    // =========================================================================
    //
    // These are intentionally disabled for this diagnostic build.
    //
    // We want to isolate the MWDAT connection/session problem first.
    //
    // =========================================================================

    override suspend fun cameraPermission(): CameraPermission {

        Log.d(
            TAG,
            "cameraPermission() called during minimal connection test"
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

        Log.w(
            TAG,
            "requestCameraPermission() disabled during " +
                "minimal MWDAT connection test"
        )

        return CameraPermission.NOT_DETERMINED
    }

    override fun cameraFrames():
        Flow<ByteArray> {

        Log.w(
            TAG,
            "cameraFrames() disabled during minimal MWDAT connection test"
        )

        return flow {
            // Intentionally empty.
        }
    }

    // =========================================================================
    // SHUTDOWN
    // =========================================================================

    fun shutdown() {

        scope.launch {

            try {

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
