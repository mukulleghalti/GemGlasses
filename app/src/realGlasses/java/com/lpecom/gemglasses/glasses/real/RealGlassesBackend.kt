package com.lpecom.gemglasses.glasses.real

import android.app.Activity
import android.content.Context
import android.util.Log
import com.lpecom.gemglasses.glasses.CameraPermission
import com.lpecom.gemglasses.glasses.GlassesBackend
import com.lpecom.gemglasses.glasses.GlassesDevice
import com.lpecom.gemglasses.glasses.RegistrationState
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

        /*
         * We capture one photo approximately every second during a vision
         * burst. Gemini receives the resulting JPEG bytes directly.
         */
        private const val PHOTO_INTERVAL_MS = 1_000L
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )

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
    // Activity lifecycle
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
        Log.i(TAG, "Initializing Meta Wearables Device Access Toolkit")

        try {
            Wearables.initialize(context)

            Log.i(TAG, "Wearables.initialize() called")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Wearables", e)
            _registrationState.value = RegistrationState.UNKNOWN
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
            Log.i(TAG, "Starting Meta glasses registration")

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
    // Camera permission
    // -------------------------------------------------------------------------

    override suspend fun cameraPermission(): CameraPermission {
        return try {
            val result =
                Wearables.checkPermissionStatus(Permission.CAMERA)

            Log.d(
                TAG,
                "Camera permission result: $result"
            )

            when (result) {
                PermissionStatus.GRANTED ->
                    CameraPermission.GRANTED

                PermissionStatus.DENIED ->
                    CameraPermission.DENIED

                else ->
                    CameraPermission.NOT_DETERMINED
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
         * MWDAT's RequestPermissionContract needs to be launched from an
         * Activity. We are intentionally not launching an ActivityResult
         * contract from this backend.
         *
         * For now, report NOT_DETERMINED so the UI can handle the permission
         * flow without pretending that permission was granted.
         */
        Log.i(
            TAG,
            "Camera permission request requires ActivityResult handling"
        )

        return CameraPermission.NOT_DETERMINED
    }

    // -------------------------------------------------------------------------
    // Camera / vision
    // -------------------------------------------------------------------------

    override fun cameraFrames(): Flow<ByteArray> {
        return flow {

            sessionMutex.withLock {

                try {
                    ensureCameraSession()

                    val activeCamera = camera

                    if (activeCamera == null) {
                        Log.e(
                            TAG,
                            "Camera session exists but camera is null"
                        )
                        return@flow
                    }

                    Log.i(
                        TAG,
                        "Starting camera stream"
                    )

                    val startResult =
                        activeCamera.stream.start()

                    Log.i(
                        TAG,
                        "Camera stream start result: $startResult"
                    )

                    /*
                     * We don't depend on the internal VideoFrame structure.
                     *
                     * Instead, capture still images from the camera stream.
                     * PhotoData.data gives us the actual image bytes.
                     */
                    repeat(MAX_PHOTOS_PER_BURST) { index ->

                        try {
                            val photoResult =
                                activeCamera.stream.capturePhoto()

                            photoResult.onSuccess { photoData ->

                                val jpeg = photoData.data

                                if (jpeg.isNotEmpty()) {
                                    Log.d(
                                        TAG,
                                        "Captured vision photo #${index + 1}: ${jpeg.size} bytes"
                                    )

                                    emit(jpeg)
                                } else {
                                    Log.w(
                                        TAG,
                                        "Captured empty vision photo #${index + 1}"
                                    )
                                }

                            }.onFailure { error, _ ->

                                Log.e(
                                    TAG,
                                    "Failed to capture vision photo #${index + 1}: ${error.description}"
                                )
                            }

                        } catch (e: Exception) {

                            Log.e(
                                TAG,
                                "Exception while capturing vision photo #${index + 1}",
                                e
                            )
                        }

                        if (index < MAX_PHOTOS_PER_BURST - 1) {
                            delay(PHOTO_INTERVAL_MS)
                        }
                    }

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Camera vision session failed",
                        e
                    )

                } finally {

                    stopCameraSession()
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Camera session creation
    // -------------------------------------------------------------------------

    private suspend fun ensureCameraSession() {

        if (session != null && camera != null) {
            Log.d(
                TAG,
                "Camera session already exists"
            )
            return
        }

        /*
         * Create DeviceSession.
         *
         * MWDAT 0.9.0:
         * Wearables.createSession(AutoDeviceSelector())
         */
        if (session == null) {

            Log.i(
                TAG,
                "Creating MWDAT DeviceSession"
            )

            val createdSession =
                Wearables.createSession(
                    AutoDeviceSelector()
                ).getOrElse { error ->

                    Log.e(
                        TAG,
                        "Failed to create DeviceSession: ${error.description}"
                    )

                    throw IllegalStateException(
                        error.description
                    )
                }

            session = createdSession

            Log.i(
                TAG,
                "Starting MWDAT DeviceSession"
            )

            /*
             * DeviceSession.start() returns Unit in MWDAT 0.9.0.
             * Asynchronous failures are reported through session.errors.
             */
            createdSession.start()
        }

        val activeSession =
            session
                ?: throw IllegalStateException(
                    "DeviceSession was not created"
                )

        /*
         * Add the camera capability.
         */
        if (camera == null) {

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

                    Log.e(
                        TAG,
                        "Failed to add camera: ${error.description}"
                    )

                    throw IllegalStateException(
                        error.description
                    )
                }

            camera = addedCamera

            Log.i(
                TAG,
                "Camera added successfully"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Camera cleanup
    // -------------------------------------------------------------------------

    private fun stopCameraSession() {

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

    // -------------------------------------------------------------------------
    // Device observation
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

                            /*
                             * IMPORTANT:
                             *
                             * Wearables.devices contains DeviceIdentifier,
                             * not String.
                             */
                            Wearables.devicesMetadata[deviceId]
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
                        _devices.value = emptyList()
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
            device.linkState == LinkState.CONNECTED

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
    // Registration observation
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
    // Shutdown
    // -------------------------------------------------------------------------

    fun shutdown() {

        try {
            stopCameraSession()
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Error shutting down camera/session",
                e
            )
        }

        scope.cancel()
    }

    private companion object {
        const val MAX_PHOTOS_PER_BURST = 20
    }
}
