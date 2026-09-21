package com.lpecom.gemglasses.glasses.real

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
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
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream

@Singleton
class RealGlassesBackend @Inject constructor(
    @ApplicationContext
    private val context: Context,
) : GlassesBackend {

    companion object {
        private const val TAG = "RealGlassesBackend"
    }

    @Volatile
    private var currentActivity: Activity? = null

    /**
     * MainActivity calls this when it becomes active.
     *
     * MWDAT requires an Activity for registration.
     */
    fun setActivity(activity: Activity) {
        currentActivity = activity

        Log.i(
            TAG,
            "Host Activity registered: ${activity::class.java.simpleName}",
        )
    }

    /**
     * Clear the Activity reference only if this is still
     * the Activity currently registered.
     */
    fun clearActivity(activity: Activity) {
        if (currentActivity === activity) {
            currentActivity = null

            Log.i(
                TAG,
                "Host Activity cleared: ${activity::class.java.simpleName}",
            )
        }
    }

    /**
     * MWDAT registration state.
     *
     * We intentionally only depend on REGISTERED / REGISTERING here.
     * The other states have changed between SDK versions.
     */
    override val registrationState: Flow<RegistrationState> =
        Wearables.registrationState.map { state ->

            when (state.name) {
                "REGISTERED" ->
                    RegistrationState.REGISTERED

                "REGISTERING" ->
                    RegistrationState.REGISTERING

                else ->
                    RegistrationState.UNKNOWN
            }
        }

    /**
     * Expose the devices reported by MWDAT.
     *
     * DAT 0.9.0 exposes Wearables.devices as device identifiers.
     * Device metadata is available through Wearables.devicesMetadata[id].
     */
    override val devices: Flow<List<GlassesDevice>> =
        callbackFlow {

            val job = launch(Dispatchers.Default) {

                try {
                    Wearables.devices.collect { deviceIds ->

                        val result = mutableListOf<GlassesDevice>()

                        for (deviceId in deviceIds) {
                            try {
                                val metadataFlow =
                                    Wearables.devicesMetadata[deviceId]

                                metadataFlow.collect { device ->

                                    val rawName = device.name

                                    val displayName =
                                        if (
                                            rawName.isBlank() ||
                                            rawName.equals(
                                                "Unknown",
                                                ignoreCase = true,
                                            )
                                        ) {
                                            "Ray-Ban Meta"
                                        } else {
                                            rawName
                                        }

                                    result.removeAll {
                                        it.id == deviceId.toString()
                                    }

                                    result.add(
                                        GlassesDevice(
                                            id = deviceId.toString(),
                                            name = displayName,
                                            connected = device.linkState.name == "CONNECTED",
                                        ),
                                    )

                                    trySend(result.toList())
                                }
                            } catch (e: Exception) {
                                Log.e(
                                    TAG,
                                    "Failed to read device metadata for $deviceId",
                                    e,
                                )
                            }
                        }

                        if (deviceIds.isEmpty()) {
                            trySend(emptyList())
                        }
                    }
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "Device monitoring failed",
                        e,
                    )
                }
            }

            awaitClose {
                job.cancel()
            }
        }

    /**
     * Initialize MWDAT once.
     */
    override fun initialize() {
        try {
            Log.i(TAG, "Initializing MWDAT")

            Wearables.initialize(context)
                .onSuccess {
                    Log.i(TAG, "MWDAT initialized successfully")
                }
                .onFailure { error, _ ->
                    Log.e(
                        TAG,
                        "MWDAT initialization failed: ${error.description}",
                    )
                }
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Exception while initializing MWDAT",
                e,
            )
        }
    }

    /**
     * Starts Meta's registration flow.
     */
    override fun startRegistration() {

        val activity = currentActivity

        if (activity == null) {
            Log.e(
                TAG,
                "Failed to start registration: no registered Activity",
            )
            return
        }

        Log.i(
            TAG,
            "Starting Meta registration using ${activity::class.java.simpleName}",
        )

        try {
            Wearables.startRegistration(activity)
                .onSuccess {
                    Log.i(
                        TAG,
                        "Meta registration request started successfully",
                    )
                }
                .onFailure { error, _ ->
                    Log.e(
                        TAG,
                        "Meta registration failed: ${error.description}",
                    )
                }
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Exception while starting Meta registration",
                e,
            )
        }
    }

    /**
     * Check MWDAT camera permission.
     */
    override suspend fun cameraPermission(): CameraPermission {

        return try {

            Wearables.checkPermissionStatus(
                Permission.CAMERA,
            ).fold(
                onSuccess = { status ->

                    when (status) {
                        PermissionStatus.GRANTED ->
                            CameraPermission.GRANTED

                        else ->
                            CameraPermission.NOT_DETERMINED
                    }
                },

                onFailure = { error, _ ->

                    Log.e(
                        TAG,
                        "Failed to check camera permission: ${error.description}",
                    )

                    CameraPermission.NOT_DETERMINED
                },
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Exception checking camera permission",
                e,
            )

            CameraPermission.NOT_DETERMINED
        }
    }

    /**
     * Starts the MWDAT registration flow if camera permission
     * has not already been granted.
     */
    override suspend fun requestCameraPermission(): CameraPermission {

        val activity = currentActivity

        if (activity == null) {
            Log.e(
                TAG,
                "Cannot request camera permission: no registered Activity",
            )

            return CameraPermission.DENIED
        }

        try {

            val current = cameraPermission()

            if (current == CameraPermission.GRANTED) {
                Log.i(
                    TAG,
                    "Camera permission already granted",
                )

                return CameraPermission.GRANTED
            }

            Log.i(
                TAG,
                "Starting MWDAT registration/permission flow from ${activity::class.java.simpleName}",
            )

            Wearables.startRegistration(activity)
                .onSuccess {
                    Log.i(
                        TAG,
                        "MWDAT permission/registration flow started",
                    )
                }
                .onFailure { error, _ ->
                    Log.e(
                        TAG,
                        "MWDAT permission/registration failed: ${error.description}",
                    )
                }

            return cameraPermission()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Exception requesting camera permission",
                e,
            )

            return CameraPermission.DENIED
        }
    }

    /**
     * Opens a MWDAT camera session and emits JPEG frames.
     */
    override fun cameraFrames(): Flow<ByteArray> =
        callbackFlow {

            val job = launch(Dispatchers.IO) {

                var session: DeviceSession? = null
                var camera: Camera? = null

                try {

                    Log.i(
                        TAG,
                        "Creating MWDAT camera session",
                    )

                    /*
                     * DAT 0.9.0:
                     *
                     * Wearables.createSession(...)
                     * returns DatResult<DeviceSession, ...>
                     */
                    session = Wearables
                        .createSession(AutoDeviceSelector())
                        .getOrElse { error ->

                            Log.e(
                                TAG,
                                "Failed to create camera session: ${error.description}",
                            )

                            return@launch
                        }

                    Log.i(
                        TAG,
                        "DeviceSession created",
                    )

                    /*
                     * Start the session before adding the camera.
                     */
                    session.start()

                    Log.i(
                        TAG,
                        "DeviceSession.start() called",
                    )

                    /*
                     * DAT 0.9.0:
                     *
                     * addCamera() returns DatResult<Camera, ...>
                     */
                    camera = session
                        .addCamera(
                            StreamConfiguration(
                                videoQuality = VideoQuality.MEDIUM,
                                frameRate = 15,
                            ),
                        )
                        .getOrElse { error ->

                            Log.e(
                                TAG,
                                "Failed to add camera: ${error.description}",
                            )

                            return@launch
                        }

                    Log.i(
                        TAG,
                        "Camera capability added",
                    )

                    /*
                     * Start the actual camera stream.
                     */
                    camera.stream
                        .start()
                        .onSuccess {
                            Log.i(
                                TAG,
                                "Camera stream started",
                            )
                        }
                        .onFailure { error, _ ->

                            Log.e(
                                TAG,
                                "Failed to start camera stream: ${error.description}",
                            )

                            return@launch
                        }

                    Log.i(
                        TAG,
                        "Collecting camera frames",
                    )

                    /*
                     * DAT 0.9.0:
                     *
                     * Camera -> stream -> videoStream
                     */
                    camera.stream.videoStream.collect { frame ->

                        try {

                            val jpeg = videoFrameToJpeg(frame)

                            if (jpeg != null) {

                                trySend(jpeg)

                                Log.d(
                                    TAG,
                                    "Camera frame emitted: ${jpeg.size} bytes",
                                )
                            } else {

                                Log.w(
                                    TAG,
                                    "Camera frame conversion returned null",
                                )
                            }

                        } catch (e: Exception) {

                            Log.e(
                                TAG,
                                "Failed to process camera frame",
                                e,
                            )
                        }
                    }

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Camera stream failed",
                        e,
                    )

                } finally {

                    try {
                        camera?.stop()
                    } catch (e: Exception) {
                        Log.w(
                            TAG,
                            "Failed to stop camera",
                            e,
                        )
                    }

                    try {
                        session?.removeCamera()
                    } catch (e: Exception) {
                        Log.w(
                            TAG,
                            "Failed to remove camera",
                            e,
                        )
                    }

                    try {
                        session?.stop()
                    } catch (e: Exception) {
                        Log.w(
                            TAG,
                            "Failed to stop DeviceSession",
                            e,
                        )
                    }

                    Log.i(
                        TAG,
                        "Glasses camera session stopped",
                    )
                }
            }

            awaitClose {
                job.cancel()
            }
        }

    /**
     * Convert a MWDAT VideoFrame to JPEG.
     *
     * We first try to interpret the frame buffer as an encoded image.
     * If that fails, we fall back to I420/YUV conversion.
     */
    private fun videoFrameToJpeg(
        frame: VideoFrame,
    ): ByteArray? {

        val buffer = frame.buffer

        if (buffer == null) {
            Log.w(
                TAG,
                "VideoFrame buffer is null",
            )

            return null
        }

        val bytes = ByteArray(buffer.remaining())

        buffer.get(bytes)

        /*
         * Try encoded image data first.
         */
        val bitmap = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
        )

        if (bitmap != null) {
            return bitmapToJpeg(bitmap)
        }

        /*
         * Fall back to raw I420.
         */
        return try {

            i420ToJpeg(
                data = bytes,
                width = frame.width,
                height = frame.height,
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to convert I420 frame to JPEG",
                e,
            )

            null
        }
    }

    private fun bitmapToJpeg(
        bitmap: Bitmap,
    ): ByteArray {

        return ByteArrayOutputStream().use { output ->

            bitmap.compress(
                Bitmap.CompressFormat.JPEG,
                85,
                output,
            )

            bitmap.recycle()

            output.toByteArray()
        }
    }

    /**
     * I420:
     *
     * YYYYYYYY
     * UUUU
     * VVVV
     *
     * -> NV21:
     *
     * YYYYYYYY
     * VUVU
     */
    private fun i420ToJpeg(
        data: ByteArray,
        width: Int,
        height: Int,
    ): ByteArray {

        val ySize = width * height
        val uvSize = ySize / 4

        require(
            data.size >= ySize + uvSize + uvSize,
        ) {
            "Invalid I420 buffer: size=${data.size}, " +
                "expected at least ${ySize + uvSize + uvSize}"
        }

        val nv21 = ByteArray(
            ySize + uvSize + uvSize,
        )

        /*
         * Y plane.
         */
        System.arraycopy(
            data,
            0,
            nv21,
            0,
            ySize,
        )

        val uOffset = ySize
        val vOffset = ySize + uvSize

        /*
         * Interleave V/U.
         */
        var outputIndex = ySize

        for (i in 0 until uvSize) {

            nv21[outputIndex++] =
                data[vOffset + i]

            nv21[outputIndex++] =
                data[uOffset + i]
        }

        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            width,
            height,
            null,
        )

        return ByteArrayOutputStream().use { output ->

            yuvImage.compressToJpeg(
                Rect(
                    0,
                    0,
                    width,
                    height,
                ),
                85,
                output,
            )

            output.toByteArray()
        }
    }
}
