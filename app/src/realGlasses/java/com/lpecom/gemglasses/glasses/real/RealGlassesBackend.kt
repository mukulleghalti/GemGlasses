package com.lpecom.gemglasses.glasses.real

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.lpecom.gemglasses.glasses.CameraPermission
import com.lpecom.gemglasses.glasses.GlassesBackend
import com.lpecom.gemglasses.glasses.GlassesDevice
import com.lpecom.gemglasses.glasses.RegistrationState
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.PhotoData
import com.meta.wearable.dat.camera.StreamConfiguration
import com.meta.wearable.dat.camera.StreamState
import com.meta.wearable.dat.camera.VideoQuality
import com.meta.wearable.dat.camera.removeCamera
import com.meta.wearable.dat.core.DeviceSession
import com.meta.wearable.dat.core.DeviceSessionState
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.AutoDeviceSelector
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class RealGlassesBackend @Inject constructor(
    @ApplicationContext private val context: Context,
) : GlassesBackend {

    companion object {
        private const val TAG = "RealGlassesBackend"

        private const val CAMERA_FRAME_RATE = 7

        /*
         * We intentionally do not capture immediately after STREAMING.
         *
         * The glasses need a short amount of time to settle the camera
         * pipeline before the first still-photo request.
         */
        private const val CAMERA_SETTLE_MS = 1500L

        /*
         * One photo roughly every second is enough for Gemini vision.
         */
        private const val PHOTO_INTERVAL_MS = 1000L

        /*
         * Don't destroy the camera after one transient capture failure.
         */
        private const val MAX_CONSECUTIVE_CAPTURE_FAILURES = 3

        /*
         * Small delay between capture retries.
         */
        private const val CAPTURE_RETRY_DELAY_MS = 750L
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )

    private val connectMutex = Mutex()

    private val _registrationState =
        MutableStateFlow(RegistrationState.UNKNOWN)

    override val registrationState: Flow<RegistrationState> =
        _registrationState.asStateFlow()

    private val _devices =
        MutableStateFlow<List<GlassesDevice>>(emptyList())

    override val devices: Flow<List<GlassesDevice>> =
        _devices.asStateFlow()

    private var activity: Activity? = null

    private var session: DeviceSession? = null

    private var camera: Camera? = null

    private var cameraPermissionRequester:
        (suspend () -> CameraPermission)? = null

    private var registrationJob: Job? = null
    private var devicesJob: Job? = null

    // -------------------------------------------------------------------------
    // INITIALIZATION
    // -------------------------------------------------------------------------

    override fun initialize() {
        Log.i(TAG, "Initializing RealGlassesBackend")

        registrationJob?.cancel()
        devicesJob?.cancel()

        registrationJob = scope.launch {
            try {
                Wearables.registrationState.collect { state ->
                    val mapped = mapRegistrationState(state)

                    Log.i(
                        TAG,
                        "MWDAT registration state = $state -> $mapped"
                    )

                    _registrationState.value = mapped
                }
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Registration state observer failed",
                    e
                )
            }
        }

        devicesJob = scope.launch {
            try {
                Wearables.devices.collect { identifiers ->

                    Log.i(
                        TAG,
                        "MWDAT devices changed: count=${identifiers.size}"
                    )

                    val mappedDevices = identifiers.map { identifier ->

                        val name = try {
                            val metadata =
                                Wearables.getDeviceMetadata(identifier)

                            metadata?.name ?: identifier.toString()
                        } catch (e: Exception) {
                            Log.w(
                                TAG,
                                "Could not read device metadata",
                                e
                            )

                            identifier.toString()
                        }

                        GlassesDevice(
                            id = identifier.toString(),
                            name = name,
                            connected = true,
                        )
                    }

                    _devices.value = mappedDevices

                    mappedDevices.forEach {
                        Log.i(
                            TAG,
                            "MWDAT device: id=${it.id}, name=${it.name}, connected=${it.connected}"
                        )
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

    override fun startRegistration() {
        val currentActivity = activity

        if (currentActivity == null) {
            Log.e(
                TAG,
                "Cannot start registration: Activity is null"
            )
            return
        }

        try {
            Log.i(TAG, "Starting MWDAT registration")

            Wearables.startRegistration(currentActivity)
        } catch (e: Exception) {
            Log.e(
                TAG,
                "MWDAT registration failed",
                e
            )
        }
    }

    // -------------------------------------------------------------------------
    // ACTIVITY
    // -------------------------------------------------------------------------

    override fun setActivity(activity: Activity) {
        this.activity = activity

        Log.i(
            TAG,
            "Activity attached: ${activity.javaClass.simpleName}"
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

    // -------------------------------------------------------------------------
    // CAMERA PERMISSION
    // -------------------------------------------------------------------------

    override fun setCameraPermissionRequester(
        requester: suspend () -> CameraPermission,
    ) {
        cameraPermissionRequester = requester

        Log.i(
            TAG,
            "Camera permission requester registered"
        )
    }

    override suspend fun cameraPermission(): CameraPermission {
        return try {

            val result =
                Wearables.checkPermissionStatus(
                    Permission.CAMERA
                )

            Log.i(
                TAG,
                "Camera permission result = $result"
            )

            when (result) {
                is PermissionStatus.Granted -> {
                    CameraPermission.GRANTED
                }

                is PermissionStatus.Denied -> {
                    CameraPermission.DENIED
                }

                else -> {
                    CameraPermission.NOT_DETERMINED
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

    override suspend fun requestCameraPermission(): CameraPermission {

        /*
         * IMPORTANT:
         *
         * Do not call the requester if MWDAT already says GRANTED.
         *
         * This prevents the permission flow from being repeatedly triggered
         * every time vision starts.
         */
        val current = cameraPermission()

        if (current == CameraPermission.GRANTED) {
            Log.i(
                TAG,
                "Camera permission already GRANTED; no request required"
            )

            return CameraPermission.GRANTED
        }

        val requester = cameraPermissionRequester

        if (requester == null) {
            Log.e(
                TAG,
                "Camera permission requester is NULL"
            )

            return current
        }

        return try {

            Log.i(
                TAG,
                "Requesting Meta CAMERA permission"
            )

            val result = requester()

            Log.i(
                TAG,
                "Camera permission requester returned = $result"
            )

            /*
             * Re-check MWDAT after the request.
             *
             * This is the authoritative state.
             */
            val verified = cameraPermission()

            Log.i(
                TAG,
                "Camera permission verified after request = $verified"
            )

            verified

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Camera permission request failed",
                e
            )

            CameraPermission.DENIED
        }
    }

    // -------------------------------------------------------------------------
    // DEVICE SESSION
    // -------------------------------------------------------------------------

    override suspend fun connect(): Boolean {
        return connectMutex.withLock {

            /*
             * Reuse an already-working session.
             */
            val existing = session

            if (
                existing != null &&
                existing.state.value == DeviceSessionState.STARTED
            ) {
                Log.i(
                    TAG,
                    "Existing DeviceSession is already STARTED"
                )

                return@withLock true
            }

            Log.i(
                TAG,
                "=============================="
            )
            Log.i(
                TAG,
                "STARTING MWDAT CONNECTION"
            )
            Log.i(
                TAG,
                "=============================="
            )

            /*
             * Do not depend exclusively on our cached StateFlows here.
             *
             * They are asynchronous and cameraFrames() may call connect()
             * immediately after application initialization.
             */
            val registration = _registrationState.value

            Log.i(
                TAG,
                "Cached registration state = $registration"
            )

            Log.i(
                TAG,
                "Cached device count = ${_devices.value.size}"
            )

            if (registration != RegistrationState.REGISTERED) {
                Log.e(
                    TAG,
                    "CONNECT ABORTED: MWDAT registration is not REGISTERED"
                )

                return@withLock false
            }

            if (_devices.value.isEmpty()) {
                Log.e(
                    TAG,
                    "CONNECT ABORTED: no MWDAT devices available"
                )

                return@withLock false
            }

            /*
             * Clean up a previous dead session.
             */
            try {
                session?.stop()
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "Previous DeviceSession.stop() failed",
                    e
                )
            }

            session = null

            try {

                Log.i(
                    TAG,
                    "Creating DeviceSession"
                )

                val createdSession =
                    Wearables.createSession(
                        AutoDeviceSelector()
                    ).getOrElse { error ->

                        Log.e(
                            TAG,
                            "Wearables.createSession() failed: $error"
                        )

                        return@withLock false
                    }

                Log.i(
                    TAG,
                    "DeviceSession created"
                )

                session = createdSession

                scope.launch {
                    try {
                        createdSession.state.collect { state ->
                            Log.i(
                                TAG,
                                "DeviceSession state = $state"
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(
                            TAG,
                            "DeviceSession state observer ended",
                            e
                        )
                    }
                }

                Log.i(
                    TAG,
                    "Starting DeviceSession"
                )

                createdSession.start()

                val started = withTimeoutOrNull(20_000L) {

                    createdSession.state.first {
                        it == DeviceSessionState.STARTED
                    }

                    true

                } ?: false

                if (!started) {

                    Log.e(
                        TAG,
                        "DeviceSession did not reach STARTED within 20 seconds"
                    )

                    Log.e(
                        TAG,
                        "Final DeviceSession state = ${createdSession.state.value}"
                    )

                    try {
                        createdSession.stop()
                    } catch (_: Exception) {
                    }

                    session = null

                    return@withLock false
                }

                Log.i(
                    TAG,
                    "DeviceSession STARTED successfully"
                )

                return@withLock true

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "DeviceSession connection failed",
                    e
                )

                session = null

                false
            }
        }
    }

    // -------------------------------------------------------------------------
    // CAMERA
    // -------------------------------------------------------------------------

    override fun cameraFrames(): Flow<ByteArray> = flow {

        Log.i(
            TAG,
            "================================"
        )
        Log.i(
            TAG,
            "CAMERA FLOW STARTING"
        )
        Log.i(
            TAG,
            "================================"
        )

        /*
         * Permission must already be granted before touching the camera.
         *
         * This backend does NOT request permission from cameraFrames().
         * AgentController/GlassesManager owns that responsibility.
         */
        val permission = cameraPermission()

        Log.i(
            TAG,
            "CAMERA: permission state = $permission"
        )

        if (permission != CameraPermission.GRANTED) {

            Log.e(
                TAG,
                "CAMERA ABORTED: camera permission is not GRANTED"
            )

            return@flow
        }

        /*
         * Make sure a DeviceSession exists.
         */
        var activeSession = session

        if (
            activeSession == null ||
            activeSession.state.value != DeviceSessionState.STARTED
        ) {

            Log.i(
                TAG,
                "CAMERA: no usable DeviceSession; connecting glasses first"
            )

            val connected = try {
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
                    "CAMERA ABORTED: could not establish DeviceSession"
                )

                return@flow
            }

            activeSession = session
        }

        if (activeSession == null) {

            Log.e(
                TAG,
                "CAMERA ABORTED: DeviceSession is still NULL"
            )

            return@flow
        }

        if (
            activeSession.state.value !=
            DeviceSessionState.STARTED
        ) {

            Log.e(
                TAG,
                "CAMERA ABORTED: DeviceSession is not STARTED"
            )

            Log.e(
                TAG,
                "Current state = ${activeSession.state.value}"
            )

            return@flow
        }

        Log.i(
            TAG,
            "CAMERA: DeviceSession READY"
        )

        Log.i(
            TAG,
            "CAMERA: DeviceSession state = ${activeSession.state.value}"
        )

        try {

            /*
             * If an old camera object exists, clean it first.
             */
            camera?.let { oldCamera ->

                Log.i(
                    TAG,
                    "CAMERA: removing stale camera"
                )

                try {
                    oldCamera.stop()
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "CAMERA: stale camera stop failed",
                        e
                    )
                }

                try {
                    activeSession.removeCamera()
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "CAMERA: stale removeCamera failed",
                        e
                    )
                }

                camera = null
            }

            Log.i(
                TAG,
                "CAMERA: adding MWDAT camera"
            )

            val addedCamera =
                activeSession.addCamera(
                    StreamConfiguration(
                        videoQuality = VideoQuality.MEDIUM,
                        frameRate = CAMERA_FRAME_RATE,
                    )
                ).getOrElse { error ->

                    Log.e(
                        TAG,
                        "CAMERA: addCamera() failed: $error"
                    )

                    return@flow
                }

            camera = addedCamera

            Log.i(
                TAG,
                "CAMERA: camera added successfully"
            )

            /*
             * Observe stream state independently.
             */
            val streamStateJob = scope.launch {

                try {

                    addedCamera.stream.state.collect { state ->

                        Log.i(
                            TAG,
                            "CAMERA STREAM STATE = $state"
                        )
                    }

                } catch (e: Exception) {

                    Log.w(
                        TAG,
                        "CAMERA: stream state observer ended",
                        e
                    )
                }
            }

            try {

                Log.i(
                    TAG,
                    "CAMERA: starting camera stream"
                )

                val streamStart =
                    addedCamera.stream.start()

                streamStart.onFailure { error, exception ->

                    Log.e(
                        TAG,
                        "CAMERA: stream.start() failed: $error"
                    )

                    exception?.let {
                        Log.e(
                            TAG,
                            "CAMERA: stream.start() exception",
                            it
                        )
                    }
                }

                if (!streamStart.isSuccess) {

                    Log.e(
                        TAG,
                        "CAMERA ABORTED: stream.start() returned failure"
                    )

                    return@flow
                }

                Log.i(
                    TAG,
                    "CAMERA: stream.start() succeeded"
                )

                /*
                 * Wait until MWDAT itself reports STREAMING.
                 *
                 * Starting successfully is not enough.
                 */
                val streaming = withTimeoutOrNull(15_000L) {

                    addedCamera.stream.state.first {
                        it == StreamState.STREAMING
                    }

                    true

                } ?: false

                if (!streaming) {

                    Log.e(
                        TAG,
                        "CAMERA ABORTED: stream never reached STREAMING"
                    )

                    Log.e(
                        TAG,
                        "Final stream state = ${addedCamera.stream.state.value}"
                    )

                    return@flow
                }

                Log.i(
                    TAG,
                    "CAMERA: STREAMING confirmed"
                )

                /*
                 * Give the glasses camera pipeline time to settle.
                 *
                 * This is especially important before the first still
                 * capture after starting the video stream.
                 */
                Log.i(
                    TAG,
                    "CAMERA: waiting ${CAMERA_SETTLE_MS}ms for camera to settle"
                )

                delay(CAMERA_SETTLE_MS)

                var consecutiveFailures = 0

                while (true) {

                    if (
                        addedCamera.stream.state.value !=
                        StreamState.STREAMING
                    ) {

                        Log.e(
                            TAG,
                            "CAMERA: stream is no longer STREAMING; stopping capture loop"
                        )

                        break
                    }

                    var photoData: PhotoData? = null

                    /*
                     * Give capturePhoto() up to two attempts.
                     *
                     * We deliberately do not immediately destroy the camera
                     * after one transient CaptureFailed result.
                     */
                    for (attempt in 1..2) {

                        Log.d(
                            TAG,
                            "Calling camera.stream.capturePhoto() attempt=$attempt"
                        )

                        try {

                            val result =
                                addedCamera.stream.capturePhoto()

                            val captured =
                                result.getOrNull()

                            if (captured != null) {

                                Log.i(
                                    TAG,
                                    "CAMERA: capturePhoto() SUCCESS"
                                )

                                photoData = captured

                                break
                            }

                            Log.e(
                                TAG,
                                "CAMERA: capturePhoto() returned no PhotoData"
                            )

                            result.errorOrNull()?.let { error ->

                                Log.e(
                                    TAG,
                                    "CAMERA: Capture error = $error"
                                )
                            }

                            result.exceptionOrNull()?.let { exception ->

                                Log.e(
                                    TAG,
                                    "CAMERA: Capture exception",
                                    exception
                                )
                            }

                        } catch (e: Exception) {

                            Log.e(
                                TAG,
                                "CAMERA: capturePhoto() threw exception",
                                e
                            )
                        }

                        if (attempt < 2) {
                            delay(CAPTURE_RETRY_DELAY_MS)
                        }
                    }

                    if (photoData == null) {

                        consecutiveFailures++

                        Log.e(
                            TAG,
                            "CAMERA: photo capture failed " +
                                "($consecutiveFailures/$MAX_CONSECUTIVE_CAPTURE_FAILURES)"
                        )

                        if (
                            consecutiveFailures >=
                            MAX_CONSECUTIVE_CAPTURE_FAILURES
                        ) {

                            Log.e(
                                TAG,
                                "CAMERA: too many consecutive capture failures"
                            )

                            Log.e(
                                TAG,
                                "CAMERA: ending capture loop"
                            )

                            break
                        }

                        delay(PHOTO_INTERVAL_MS)

                        continue
                    }

                    consecutiveFailures = 0

                    val jpeg = photoDataToJpeg(photoData)

                    if (jpeg != null) {

                        Log.i(
                            TAG,
                            "CAMERA: JPEG generated (${jpeg.size} bytes)"
                        )

                        emit(jpeg)

                    } else {

                        Log.e(
                            TAG,
                            "CAMERA: PhotoData could not be converted to JPEG"
                        )
                    }

                    delay(PHOTO_INTERVAL_MS)
                }

            } finally {

                streamStateJob.cancel()
            }

        } finally {

            Log.i(
                TAG,
                "Camera Flow ending; cleaning up camera"
            )

            stopCameraIfNeeded(activeSession)
        }
    }

    // -------------------------------------------------------------------------
    // PHOTO CONVERSION
    // -------------------------------------------------------------------------

    private fun photoDataToJpeg(
        photoData: PhotoData,
    ): ByteArray? {

        return try {

            when (photoData) {

                is PhotoData.Bitmap -> {

                    bitmapToJpeg(photoData.bitmap)
                }

                is PhotoData.HEIC -> {

                    /*
                     * HEIC is not directly usable as a JPEG frame by the
                     * existing Gemini sendFrame() path.
                     *
                     * We therefore attempt to decode it into a Bitmap.
                     */
                    val bytes = byteBufferToByteArray(photoData.data)

                    val bitmap =
                        android.graphics.BitmapFactory.decodeByteArray(
                            bytes,
                            0,
                            bytes.size
                        )

                    if (bitmap == null) {

                        Log.e(
                            TAG,
                            "CAMERA: could not decode HEIC PhotoData"
                        )

                        null

                    } else {

                        bitmapToJpeg(bitmap).also {
                            bitmap.recycle()
                        }
                    }
                }

                else -> {

                    Log.e(
                        TAG,
                        "CAMERA: unsupported PhotoData type = " +
                            photoData::class.java.name
                    )

                    null
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "CAMERA: PhotoData conversion failed",
                e
            )

            null
        }
    }

    private fun bitmapToJpeg(
        bitmap: Bitmap,
    ): ByteArray {

        val output =
            ByteArrayOutputStream()

        bitmap.compress(
            Bitmap.CompressFormat.JPEG,
            85,
            output
        )

        return output.toByteArray()
    }

    private fun byteBufferToByteArray(
        buffer: ByteBuffer,
    ): ByteArray {

        val duplicate = buffer.duplicate()

        val bytes =
            ByteArray(duplicate.remaining())

        duplicate.get(bytes)

        return bytes
    }

    // -------------------------------------------------------------------------
    // CAMERA CLEANUP
    // -------------------------------------------------------------------------

    private fun stopCameraIfNeeded(
        activeSession: DeviceSession,
    ) {

        val activeCamera = camera

        if (activeCamera == null) {

            Log.i(
                TAG,
                "CAMERA: no active camera to clean up"
            )

            return
        }

        try {

            Log.i(
                TAG,
                "Stopping active MWDAT camera"
            )

            activeCamera.stop()

            Log.i(
                TAG,
                "Camera.stop() called"

            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Camera.stop() failed",
                e
            )
        }

        try {

            activeSession.removeCamera()

            Log.i(
                TAG,
                "DeviceSession.removeCamera() called"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "removeCamera() failed",
                e
            )
        }

        camera = null
    }

    // -------------------------------------------------------------------------
    // SHUTDOWN
    // -------------------------------------------------------------------------

    fun shutdown() {

        Log.i(
            TAG,
            "Shutting down RealGlassesBackend"
        )

        try {
            camera?.stop()
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Camera stop during shutdown failed",
                e
            )
        }

        camera = null

        try {
            session?.stop()
        } catch (e: Exception) {
            Log.w(
                TAG,
                "DeviceSession stop during shutdown failed",
                e
            )
        }

        session = null

        registrationJob?.cancel()
        devicesJob?.cancel()

        scope.cancel()
    }

    // -------------------------------------------------------------------------
    // REGISTRATION STATE MAPPING
    // -------------------------------------------------------------------------

    private fun mapRegistrationState(
        state: Any,
    ): RegistrationState {

        return when (state.toString().uppercase()) {

            "REGISTERED" ->
                RegistrationState.REGISTERED

            "REGISTERING" ->
                RegistrationState.REGISTERING

            "NOT_REGISTERED",
            "UNREGISTERED",
            "UNAVAILABLE" ->
                RegistrationState.NOT_REGISTERED

            "REVOKED" ->
                RegistrationState.REVOKED

            else ->
                RegistrationState.UNKNOWN
        }
    }
}
