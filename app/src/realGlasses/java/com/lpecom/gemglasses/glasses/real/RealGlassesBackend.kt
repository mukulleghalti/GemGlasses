package com.lpecom.gemglasses.glasses

import android.app.Activity
import android.content.Context
import android.util.Log
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Device
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.StreamConfiguration
import com.meta.wearable.dat.camera.VideoQuality
import com.meta.wearable.dat.session.AutoDeviceSelector
import com.meta.wearable.dat.session.DeviceSession
import com.meta.wearable.dat.session.removeCamera
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

        private const val VIDEO_QUALITY = "MEDIUM"
        private const val FRAME_RATE = 24
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
    }

    /**
     * MainActivity supplies its Activity instance so that Meta's
     * registration / permission APIs can use the current Activity.
     */
    override fun setActivity(activity: Activity) {
        this.activity = activity
    }

    override fun clearActivity(activity: Activity) {
        if (this.activity === activity) {
            this.activity = null
        }
    }

    override fun initialize() {
        Log.i(TAG, "Initializing Meta Wearables Device Access Toolkit")

        try {
            Wearables.initialize(context)

            Log.i(TAG, "Wearables.initialize() called")

            observeRegistrationState()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Wearables", e)
            _registrationState.value = RegistrationState.UNKNOWN
        }
    }

    /**
     * Starts Meta's registration flow.
     *
     * The Meta app / system UI handles the actual pairing/registration.
     */
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
            Log.i(TAG, "Starting Meta glasses registration")

            Wearables.startRegistration(currentActivity)

            _registrationState.value = RegistrationState.REGISTERING
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start registration", e)
            _registrationState.value = RegistrationState.UNKNOWN
        }
    }

    /**
     * Checks whether camera permission has been granted.
     *
     * IMPORTANT:
     * The actual permission request is handled through
     * Wearables.RequestPermissionContract() from the Activity.
     */
    override suspend fun cameraPermission(): CameraPermission {
        return try {
            val result = Wearables.checkPermissionStatus(
                Permission.CAMERA
            )

            /*
             * MWDAT 0.9.0 returns a DatResult.
             *
             * We intentionally avoid calling onSuccess()/getOrElse()
             * here because different 0.9.0 package combinations have
             * exposed slightly different helper APIs.
             *
             * The string representation is only used as a compatibility
             * fallback for determining the permission state.
             */
            val text = result.toString()

            Log.d(TAG, "Camera permission result: $text")

            when {
                text.contains("Granted", ignoreCase = true) ||
                    text.contains("GRANTED", ignoreCase = true) -> {
                    CameraPermission.GRANTED
                }

                text.contains("Denied", ignoreCase = true) ||
                    text.contains("DENIED", ignoreCase = true) -> {
                    CameraPermission.DENIED
                }

                else -> {
                    CameraPermission.NOT_DETERMINED
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check camera permission", e)
            CameraPermission.NOT_DETERMINED
        }
    }

    /**
     * This method is intentionally NOT attempting to launch
     * RequestPermissionContract.
     *
     * Permission requests require an ActivityResultLauncher, which belongs
     * in MainActivity.
     *
     * If permission isn't granted, return NOT_DETERMINED so the UI layer
     * can launch the Meta permission request.
     */
    override suspend fun requestCameraPermission(): CameraPermission {
        Log.i(
            TAG,
            "Camera permission request must be launched by MainActivity"
        )

        return CameraPermission.NOT_DETERMINED
    }

    /**
     * Creates a camera stream and exposes JPEG frames.
     */
    override fun cameraFrames(): Flow<ByteArray> {
        return kotlinx.coroutines.flow.flow {
            sessionMutex.withLock {
                try {
                    ensureCameraSession()

                    val activeCamera = camera

                    if (activeCamera == null) {
                        Log.e(TAG, "Camera session exists but camera is null")
                        return@flow
                    }

                    Log.i(TAG, "Starting camera video stream")

                    val startResult = activeCamera.stream.start()

                    Log.i(
                        TAG,
                        "Camera stream start result: $startResult"
                    )

                    /*
                     * MWDAT 0.9.0 exposes videoStream on Camera.stream.
                     *
                     * We deliberately don't name the frame type here.
                     * This avoids the VideoFrame import problem from the
                     * previous implementation.
                     */
                    activeCamera.stream.videoStream.collect { frame ->

                        try {
                            val jpeg = convertFrameToJpeg(
                                buffer = frame.buffer,
                                width = frame.width,
                                height = frame.height
                            )

                            if (jpeg != null) {
                                emit(jpeg)

                                Log.d(
                                    TAG,
                                    "Camera frame emitted: ${jpeg.size} bytes"
                                )
                            } else {
                                Log.w(
                                    TAG,
                                    "Camera frame could not be converted to JPEG"
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(
                                TAG,
                                "Failed to process camera frame",
                                e
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "Camera frame stream failed",
                        e
                    )
                } finally {
                    stopCamera()
                }
            }
        }
    }

    /**
     * Creates the MWDAT device session and camera.
     */
    private suspend fun ensureCameraSession() {

        if (session != null && camera != null) {
            Log.d(TAG, "Camera session already exists")
            return
        }

        val existingSession = session

        if (existingSession == null) {

            Log.i(TAG, "Creating MWDAT device session")

            val createdSession =
                Wearables.createSession(
                    AutoDeviceSelector()
                ).getOrElse { error ->

                    Log.e(
                        TAG,
                        "Failed to create device session: ${error.description}"
                    )

                    throw IllegalStateException(
                        error.description
                    )
                }

            session = createdSession

            Log.i(TAG, "Starting MWDAT device session")

            createdSession.start()
        }

        val activeSession = session
            ?: throw IllegalStateException(
                "Device session was not created"
            )

        if (camera == null) {

            Log.i(TAG, "Adding camera to device session")

            val addedCamera =
                activeSession.addCamera(
                    StreamConfiguration(
                        videoQuality = VideoQuality.MEDIUM,
                        frameRate = FRAME_RATE
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

            Log.i(TAG, "Camera added successfully")
        }
    }

    /**
     * Converts a raw MWDAT video frame into JPEG.
     *
     * MWDAT exposes the frame buffer as a ByteBuffer.
     *
     * Some versions/configurations can provide an already encoded JPEG.
     * Those frames are detected first.
     */
    private fun convertFrameToJpeg(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
    ): ByteArray? {

        val bytes = ByteArray(buffer.remaining())

        val duplicate = buffer.duplicate()

        duplicate.get(bytes)

        /*
         * If the stream already contains JPEG bytes, return them directly.
         *
         * JPEG begins with FF D8 and ends with FF D9.
         */
        if (
            bytes.size >= 4 &&
            bytes[0].toInt() and 0xFF == 0xFF &&
            bytes[1].toInt() and 0xFF == 0xD8
        ) {
            Log.d(
                TAG,
                "Received encoded JPEG frame ${width}x${height}"
            )

            return bytes
        }

        /*
         * If the buffer isn't JPEG encoded, we don't guess the pixel format.
         *
         * The camera configuration used by MWDAT should normally provide
         * encoded frames suitable for forwarding.
         */
        Log.w(
            TAG,
            "Received non-JPEG camera buffer: " +
                "${bytes.size} bytes, ${width}x${height}"
        )

        return null
    }

    /**
     * Stops camera streaming while keeping the backend usable for a
     * future camera burst.
     */
    private fun stopCamera() {

        try {
            camera?.stop()
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Error stopping camera",
                e
            )
        }

        try {
            session?.removeCamera()
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Error removing camera",
                e
            )
        }

        camera = null
    }

    /**
     * Fully closes the current device session.
     */
    private fun closeSession() {

        try {
            camera?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping camera", e)
        }

        try {
            session?.removeCamera()
        } catch (e: Exception) {
            Log.w(TAG, "Error removing camera", e)
        }

        camera = null

        try {
            session?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping device session", e)
        }

        session = null
    }

    /**
     * Watches Wearables.devices and its metadata.
     */
    private fun observeDevices() {

        scope.launch {

            try {

                Wearables.devices.collect { deviceIds ->

                    Log.d(
                        TAG,
                        "Wearables.devices: $deviceIds"
                    )

                    val result = mutableListOf<GlassesDevice>()

                    for (deviceId in deviceIds) {

                        try {

                            Wearables.devicesMetadata[deviceId]
                                ?.collect { device ->

                                    result.removeAll {
                                        it.id == deviceId
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
        id: String,
        device: Device,
    ): GlassesDevice {

        val rawName = device.name.toString()

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
            id = id,
            name = displayName,
            connected = connected
        )
    }

    /**
     * Watches the device/registration state.
     *
     * MWDAT's exact registration state representation can vary from the
     * simplified app abstraction, so we derive it from available devices.
     */
    private fun observeRegistrationState() {

        scope.launch {

            try {

                Wearables.devices.collect { deviceIds ->

                    _registrationState.value =
                        if (deviceIds.isEmpty()) {
                            RegistrationState.NOT_REGISTERED
                        } else {
                            RegistrationState.REGISTERED
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

    /**
     * Allows the app to release resources if the backend itself is ever
     * destroyed.
     */
    fun shutdown() {

        try {
            closeSession()
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Error closing MWDAT session",
                e
            )
        }

        scope.cancel()
    }
}
