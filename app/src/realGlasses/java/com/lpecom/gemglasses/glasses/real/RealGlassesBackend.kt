package com.lpecom.gemglasses.glasses.real

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.PhotoData
import com.meta.wearable.dat.camera.StreamConfiguration
import com.meta.wearable.dat.camera.StreamState
import com.meta.wearable.dat.camera.VideoQuality
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.removeCamera
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.device.AutoDeviceSelector
import com.meta.wearable.dat.device.DeviceSession
import com.meta.wearable.dat.device.DeviceSessionState
import com.lpecom.gemglasses.glasses.CameraPermission
import com.lpecom.gemglasses.glasses.GlassesBackend
import com.lpecom.gemglasses.glasses.GlassesDevice
import com.lpecom.gemglasses.glasses.RegistrationState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class RealGlassesBackend @Inject constructor(
    @ApplicationContext private val context: Context,
) : GlassesBackend {

    companion object {
        private const val TAG = "RealGlassesBackend"

        /*
         * Camera test configuration.
         *
         * We intentionally capture ONLY ONE photo.
         * Nothing is sent to Gemini.
         */
        private const val CAMERA_STREAM_TIMEOUT_MS = 20_000L
        private const val CAMERA_SETTLE_DELAY_MS = 2_000L

        /*
         * Medium quality is used because this is the configuration
         * already known to work for reaching STREAMING.
         */
        private const val CAMERA_FRAME_RATE = 7
    }

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _registrationState =
        MutableStateFlow(RegistrationState.UNKNOWN)

    private val _devices =
        MutableStateFlow<List<GlassesDevice>>(emptyList())

    override val registrationState: Flow<RegistrationState> =
        _registrationState

    override val devices: Flow<List<GlassesDevice>> =
        _devices

    private var activity: Activity? = null

    private var session: DeviceSession? = null

    private var camera: Camera? = null

    private var cameraPermissionRequester:
        (suspend () -> CameraPermission)? = null

    /*
     * Once Meta has returned GRANTED during this app session,
     * don't repeatedly trigger the Android/Meta permission flow.
     */
    @Volatile
    private var metaCameraPermissionGranted = false

    // -------------------------------------------------------------------------
    // INITIALIZATION
    // -------------------------------------------------------------------------

    override fun initialize() {
        Log.i(TAG, "================================================")
        Log.i(TAG, "Initializing RealGlassesBackend")
        Log.i(TAG, "================================================")

        observeRegistration()
        observeDevices()
    }

    private fun observeRegistration() {
        scope.launch {
            try {
                Wearables.registrationState.collect { state ->
                    Log.i(TAG, "MWDAT registration state = $state")

                    val mapped = when (state.toString()) {
                        "REGISTERED" -> RegistrationState.REGISTERED
                        "REGISTERING" -> RegistrationState.REGISTERING
                        "REVOKED" -> RegistrationState.REVOKED
                        "NOT_REGISTERED" -> RegistrationState.NOT_REGISTERED
                        else -> RegistrationState.UNKNOWN
                    }

                    _registrationState.value = mapped
                }
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Registration observer failed",
                    e,
                )
            }
        }
    }

    private fun observeDevices() {
        scope.launch {
            try {
                Wearables.devices.collect { devices ->

                    Log.i(
                        TAG,
                        "MWDAT devices changed: count=${devices.size}",
                    )

                    val mapped = devices.map { device ->

                        val id = device.id.toString()

                        val name =
                            try {
                                device.name
                            } catch (_: Exception) {
                                "Unknown Meta Glasses"
                            }

                        Log.i(
                            TAG,
                            "Device: id=$id name=$name",
                        )

                        GlassesDevice(
                            id = id,
                            name = name,
                            connected = true,
                        )
                    }

                    _devices.value = mapped
                }
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Device observer failed",
                    e,
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // REGISTRATION
    // -------------------------------------------------------------------------

    override fun startRegistration() {
        Log.i(TAG, "startRegistration()")

        scope.launch {
            try {
                val result = Wearables.startRegistration()

                Log.i(
                    TAG,
                    "startRegistration result = $result",
                )
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "startRegistration failed",
                    e,
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // ACTIVITY
    // -------------------------------------------------------------------------

    override fun setActivity(activity: Activity) {
        this.activity = activity

        Log.i(
            TAG,
            "Activity attached: ${activity.javaClass.simpleName}",
        )
    }

    override fun clearActivity(activity: Activity) {
        if (this.activity === activity) {
            this.activity = null

            Log.i(
                TAG,
                "Activity detached",
            )
        }
    }

    // -------------------------------------------------------------------------
    // CAMERA PERMISSION
    // -------------------------------------------------------------------------

    override suspend fun cameraPermission(): CameraPermission {

        /*
         * If MWDAT already gave us GRANTED during this app session,
         * don't repeatedly ask the Meta permission flow.
         */
        if (metaCameraPermissionGranted) {
            Log.d(
                TAG,
                "cameraPermission() -> cached GRANTED",
            )

            return CameraPermission.GRANTED
        }

        return try {

            Log.i(
                TAG,
                "Checking Meta CAMERA permission",
            )

            val result =
                Wearables.checkPermissionStatus(
                    Permission.CAMERA,
                )

            Log.i(
                TAG,
                "Meta CAMERA permission result = $result",
            )

            val status = result.toString()

            when {
                status.contains(
                    "Granted",
                    ignoreCase = true,
                ) -> {
                    metaCameraPermissionGranted = true

                    Log.i(
                        TAG,
                        "Meta CAMERA permission = GRANTED",
                    )

                    CameraPermission.GRANTED
                }

                status.contains(
                    "Denied",
                    ignoreCase = true,
                ) -> {
                    Log.w(
                        TAG,
                        "Meta CAMERA permission = DENIED",
                    )

                    CameraPermission.DENIED
                }

                else -> {
                    Log.i(
                        TAG,
                        "Meta CAMERA permission = NOT_DETERMINED",
                    )

                    CameraPermission.NOT_DETERMINED
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed checking Meta CAMERA permission",
                e,
            )

            CameraPermission.DENIED
        }
    }

    override suspend fun requestCameraPermission(): CameraPermission {

        Log.i(
            TAG,
            "================================================",
        )

        Log.i(
            TAG,
            "REQUESTING META CAMERA PERMISSION",
        )

        Log.i(
            TAG,
            "================================================",
        )

        val requester = cameraPermissionRequester

        if (requester == null) {

            Log.e(
                TAG,
                "No camera permission requester configured",
            )

            return CameraPermission.DENIED
        }

        return try {

            val result = requester.invoke()

            Log.i(
                TAG,
                "Camera permission requester result = $result",
            )

            if (result == CameraPermission.GRANTED) {
                metaCameraPermissionGranted = true

                Log.i(
                    TAG,
                    "Camera permission cached as GRANTED",
                )
            }

            result

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Camera permission request failed",
                e,
            )

            CameraPermission.DENIED
        }
    }

    override fun setCameraPermissionRequester(
        requester: suspend () -> CameraPermission,
    ) {
        cameraPermissionRequester = requester

        Log.i(
            TAG,
            "Camera permission requester configured",
        )
    }

    // -------------------------------------------------------------------------
    // CONNECTION
    // -------------------------------------------------------------------------

    override suspend fun connect(): Boolean {

        Log.i(TAG, "================================================")
        Log.i(TAG, "STARTING MINIMAL MWDAT CONNECTION TEST")
        Log.i(TAG, "================================================")

        if (
            _registrationState.value !=
            RegistrationState.REGISTERED
        ) {
            Log.e(
                TAG,
                "Cannot connect: registration state = " +
                    "${_registrationState.value}",
            )

            return false
        }

        val devices = _devices.value

        if (devices.isEmpty()) {

            Log.e(
                TAG,
                "Cannot connect: no MWDAT devices available",
            )

            return false
        }

        Log.i(
            TAG,
            "Available glasses count = ${devices.size}",
        )

        devices.forEach { device ->
            Log.i(
                TAG,
                "Available glasses: " +
                    "id=${device.id}, " +
                    "name=${device.name}, " +
                    "connected=${device.connected}",
            )
        }

        /*
         * Clean up an old DeviceSession if one exists.
         */
        session?.let { oldSession ->

            try {
                Log.i(
                    TAG,
                    "Stopping previous DeviceSession",
                )

                oldSession.stop()

            } catch (e: Exception) {

                Log.w(
                    TAG,
                    "Previous DeviceSession stop failed",
                    e,
                )
            }
        }

        session = null

        try {

            Log.i(
                TAG,
                "Calling Wearables.createSession(AutoDeviceSelector())",
            )

            val createdResult =
                Wearables.createSession(
                    AutoDeviceSelector(),
                )

            val createdSession =
                createdResult.getOrElse { error ->

                    Log.e(
                        TAG,
                        "createSession failed: $error",
                    )

                    return false
                }

            session = createdSession

            Log.i(
                TAG,
                "DeviceSession created successfully",
            )

            scope.launch {
                try {

                    createdSession.state.collect { state ->

                        Log.i(
                            TAG,
                            "DeviceSession state = $state",
                        )
                    }

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "DeviceSession state observer failed",
                        e,
                    )
                }
            }

            Log.i(
                TAG,
                "Calling DeviceSession.start()",
            )

            createdSession.start()

            Log.i(
                TAG,
                "Waiting for DeviceSessionState.STARTED",
            )

            val started =
                withTimeoutOrNull(
                    20_000L,
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
                    "DeviceSession did not reach STARTED",
                )

                return false
            }

            Log.i(
                TAG,
                "DeviceSession reached STARTED",
            )

            return true

        } catch (e: Exception) {

            Log.e(
                TAG,
                "DeviceSession connection failed",
                e,
            )

            return false
        }
    }

    // -------------------------------------------------------------------------
    // CAMERA TEST
    // -------------------------------------------------------------------------

    override fun cameraFrames(): Flow<ByteArray> = flow {

        Log.i(TAG, "")
        Log.i(TAG, "================================================")
        Log.i(TAG, "CAMERA-ONLY DIAGNOSTIC TEST")
        Log.i(TAG, "================================================")
        Log.i(TAG, "This test will:")
        Log.i(TAG, "1. Connect to glasses if needed")
        Log.i(TAG, "2. Add MWDAT camera")
        Log.i(TAG, "3. Start camera stream")
        Log.i(TAG, "4. Wait for STREAMING")
        Log.i(TAG, "5. Wait ${CAMERA_SETTLE_DELAY_MS}ms")
        Log.i(TAG, "6. Call capturePhoto() ONCE")
        Log.i(TAG, "7. DO NOT send anything to Gemini")
        Log.i(TAG, "================================================")

        // ---------------------------------------------------------------------
        // Permission
        // ---------------------------------------------------------------------

        val permission = cameraPermission()

        Log.i(
            TAG,
            "Camera permission for diagnostic test = $permission",
        )

        if (permission != CameraPermission.GRANTED) {

            Log.e(
                TAG,
                "Camera diagnostic aborted: permission = $permission",
            )

            return@flow
        }

        // ---------------------------------------------------------------------
        // Session
        // ---------------------------------------------------------------------

        var activeSession: DeviceSession? = null
        var activeCamera: Camera? = null

        try {

            /*
             * Make sure we have a working DeviceSession.
             */
            val currentSession = session

            val sessionReady =
                currentSession != null &&
                    currentSession.state.value ==
                    DeviceSessionState.STARTED

            if (!sessionReady) {

                Log.i(
                    TAG,
                    "No STARTED DeviceSession. Connecting now...",
                )

                val connected = connect()

                if (!connected) {

                    Log.e(
                        TAG,
                        "Camera diagnostic aborted: connect() failed",
                    )

                    return@flow
                }
            }

            val readySession = session

            if (readySession == null) {

                Log.e(
                    TAG,
                    "Camera diagnostic aborted: session is null",
                )

                return@flow
            }

            if (
                readySession.state.value !=
                DeviceSessionState.STARTED
            ) {

                Log.e(
                    TAG,
                    "Camera diagnostic aborted: " +
                        "session state = ${readySession.state.value}",
                )

                return@flow
            }

            activeSession = readySession

            Log.i(
                TAG,
                "DeviceSession is STARTED",
            )

            // -----------------------------------------------------------------
            // Add camera
            // -----------------------------------------------------------------

            Log.i(
                TAG,
                "Adding MWDAT camera",
            )

            val cameraResult =
                readySession.addCamera(
                    StreamConfiguration(
                        videoQuality = VideoQuality.MEDIUM,
                        frameRate = CAMERA_FRAME_RATE,
                    ),
                )

            val createdCamera =
                cameraResult.getOrElse { error ->

                    Log.e(
                        TAG,
                        "addCamera() FAILED: $error",
                    )

                    return@flow
                }

            activeCamera = createdCamera
            camera = createdCamera

            Log.i(
                TAG,
                "MWDAT Camera created successfully",
            )

            Log.i(
                TAG,
                "Initial camera stream state = " +
                    createdCamera.stream.state.value,
            )

            // -----------------------------------------------------------------
            // Start stream
            // -----------------------------------------------------------------

            Log.i(
                TAG,
                "Calling camera.stream.start()",
            )

            val startResult =
                createdCamera.stream.start()

            startResult.getOrElse { error ->

                Log.e(
                    TAG,
                    "camera.stream.start() FAILED: $error",
                )

                return@flow
            }

            Log.i(
                TAG,
                "camera.stream.start() returned successfully",
            )

            Log.i(
                TAG,
                "Current stream state = " +
                    createdCamera.stream.state.value,
            )

            // -----------------------------------------------------------------
            // Wait for STREAMING
            // -----------------------------------------------------------------

            Log.i(
                TAG,
                "Waiting for StreamState.STREAMING...",
            )

            val streaming =
                withTimeoutOrNull(
                    CAMERA_STREAM_TIMEOUT_MS,
                ) {

                    createdCamera.stream.state.first { state ->

                        Log.i(
                            TAG,
                            "CAMERA STREAM STATE -> $state",
                        )

                        state == StreamState.STREAMING
                    }

                    true
                } ?: false

            if (!streaming) {

                Log.e(
                    TAG,
                    "================================================",
                )

                Log.e(
                    TAG,
                    "CAMERA NEVER REACHED STREAMING",
                )

                Log.e(
                    TAG,
                    "Final stream state = " +
                        createdCamera.stream.state.value,
                )

                Log.e(
                    TAG,
                    "================================================",
                )

                return@flow
            }

            Log.i(
                TAG,
                "================================================",
            )

            Log.i(
                TAG,
                "CAMERA STREAMING",
            )

            Log.i(
                TAG,
                "Ready for capturePhoto()",
            )

            Log.i(
                TAG,
                "================================================",
            )

            // -----------------------------------------------------------------
            // Camera settle
            // -----------------------------------------------------------------

            Log.i(
                TAG,
                "Waiting ${CAMERA_SETTLE_DELAY_MS}ms " +
                    "for camera pipeline to settle",
            )

            delay(CAMERA_SETTLE_DELAY_MS)

            Log.i(
                TAG,
                "Camera settle delay complete",
            )

            Log.i(
                TAG,
                "Stream state immediately before capture = " +
                    createdCamera.stream.state.value,
            )

            if (
                createdCamera.stream.state.value !=
                StreamState.STREAMING
            ) {

                Log.e(
                    TAG,
                    "Camera stopped streaming before capturePhoto()",
                )

                return@flow
            }

            // -----------------------------------------------------------------
            // ONE PHOTO ONLY
            // -----------------------------------------------------------------

            Log.i(
                TAG,
                "================================================",
            )

            Log.i(
                TAG,
                "CALLING capturePhoto() — ATTEMPT 1/1",
            )

            Log.i(
                TAG,
                "No Gemini connection involved",
            )

            Log.i(
                TAG,
                "No frame forwarding involved",
            )

            Log.i(
                TAG,
                "================================================",
            )

            val captureResult =
                createdCamera.stream.capturePhoto()

            // -----------------------------------------------------------------
            // Inspect result
            // -----------------------------------------------------------------

            Log.i(
                TAG,
                "capturePhoto() returned",
            )

            Log.i(
                TAG,
                "capturePhoto() success = " +
                    captureResult.isSuccess,
            )

            Log.i(
                TAG,
                "capturePhoto() error = " +
                    captureResult.errorOrNull(),
            )

            val photoData =
                captureResult.getOrNull()

            if (photoData == null) {

                Log.e(
                    TAG,
                    "================================================",
                )

                Log.e(
                    TAG,
                    "CAPTURE FAILED",
                )

                Log.e(
                    TAG,
                    "PhotoData = null",
                )

                Log.e(
                    TAG,
                    "Capture error = " +
                        captureResult.errorOrNull(),
                )

                Log.e(
                    TAG,
                    "Capture exception = " +
                        captureResult.exceptionOrNull(),
                )

                Log.e(
                    TAG,
                    "================================================",
                )

                return@flow
            }

            Log.i(
                TAG,
                "================================================",
            )

            Log.i(
                TAG,
                "CAPTURE SUCCESS!",
            )

            Log.i(
                TAG,
                "PhotoData type = " +
                    photoData::class.java.name,
            )

            Log.i(
                TAG,
                "================================================",
            )

            // -----------------------------------------------------------------
            // Bitmap
            // -----------------------------------------------------------------

            when (photoData) {

                is PhotoData.Bitmap -> {

                    val bitmap =
                        photoData.bitmap

                    Log.i(
                        TAG,
                        "PhotoData = Bitmap",
                    )

                    Log.i(
                        TAG,
                        "Bitmap width = ${bitmap.width}",
                    )

                    Log.i(
                        TAG,
                        "Bitmap height = ${bitmap.height}",
                    )

                    Log.i(
                        TAG,
                        "Bitmap config = ${bitmap.config}",
                    )

                    val jpegBytes =
                        bitmapToJpeg(bitmap)

                    Log.i(
                        TAG,
                        "JPEG conversion successful",
                    )

                    Log.i(
                        TAG,
                        "JPEG byte size = ${jpegBytes.size}",
                    )

                    /*
                     * This is deliberately emitted only after we know
                     * capturePhoto() actually returned valid image data.
                     *
                     * Nothing here sends the image to Gemini.
                     */
                    emit(jpegBytes)
                }

                // -----------------------------------------------------------------
                // HEIC
                // -----------------------------------------------------------------

                is PhotoData.HEIC -> {

                    val buffer =
                        photoData.data

                    val bytes =
                        byteBufferToByteArray(buffer)

                    Log.i(
                        TAG,
                        "PhotoData = HEIC",
                    )

                    Log.i(
                        TAG,
                        "HEIC byte size = ${bytes.size}",
                    )

                    /*
                     * We emit the raw bytes here only so the caller can
                     * observe that capture succeeded.
                     *
                     * NOTE:
                     * These are HEIC bytes, not JPEG bytes.
                     */
                    emit(bytes)
                }

                else -> {

                    Log.w(
                        TAG,
                        "Unknown PhotoData type = " +
                            photoData::class.java.name,
                    )
                }
            }

            Log.i(
                TAG,
                "================================================",
            )

            Log.i(
                TAG,
                "CAMERA-ONLY DIAGNOSTIC TEST COMPLETE",
            )

            Log.i(
                TAG,
                "================================================",
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "================================================",
            )

            Log.e(
                TAG,
                "CAMERA DIAGNOSTIC EXCEPTION",
            )

            Log.e(
                TAG,
                "================================================",
                e,
            )

        } finally {

            Log.i(
                TAG,
                "Camera diagnostic flow ending",
            )

            // -----------------------------------------------------------------
            // Stop camera
            // -----------------------------------------------------------------

            activeCamera?.let { activeCameraInstance ->

                try {

                    Log.i(
                        TAG,
                        "Stopping diagnostic camera",
                    )

                    activeCameraInstance.stop()

                    Log.i(
                        TAG,
                        "Camera.stop() called",
                    )

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Camera.stop() failed",
                        e,
                    )
                }
            }

            // -----------------------------------------------------------------
            // Remove camera
            // -----------------------------------------------------------------

            activeSession?.let { sessionInstance ->

                try {

                    Log.i(
                        TAG,
                        "Removing diagnostic camera from session",
                    )

                    sessionInstance.removeCamera()

                    Log.i(
                        TAG,
                        "removeCamera() completed",
                    )

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "removeCamera() failed",
                        e,
                    )
                }
            }

            camera = null

            Log.i(
                TAG,
                "Camera diagnostic cleanup complete",
            )
        }
    }

    // -------------------------------------------------------------------------
    // BITMAP -> JPEG
    // -------------------------------------------------------------------------

    private fun bitmapToJpeg(
        bitmap: Bitmap,
    ): ByteArray {

        val output =
            ByteArrayOutputStream()

        bitmap.compress(
            Bitmap.CompressFormat.JPEG,
            90,
            output,
        )

        return output.toByteArray()
    }

    // -------------------------------------------------------------------------
    // BYTEBUFFER -> BYTEARRAY
    // -------------------------------------------------------------------------

    private fun byteBufferToByteArray(
        buffer: ByteBuffer,
    ): ByteArray {

        val duplicate =
            buffer.duplicate()

        val bytes =
            ByteArray(duplicate.remaining())

        duplicate.get(bytes)

        return bytes
    }

    // -------------------------------------------------------------------------
    // SHUTDOWN
    // -------------------------------------------------------------------------

    override fun shutdown() {

        Log.i(
            TAG,
            "Shutting down RealGlassesBackend",
        )

        try {

            camera?.let {
                try {
                    it.stop()
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "Camera stop during shutdown failed",
                        e,
                    )
                }
            }

            camera = null

            session?.let {

                try {
                    it.stop()
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "DeviceSession stop during shutdown failed",
                        e,
                    )
                }
            }

            session = null

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Shutdown failed",
                e,
            )
        }

        scope.cancel()
    }
}
