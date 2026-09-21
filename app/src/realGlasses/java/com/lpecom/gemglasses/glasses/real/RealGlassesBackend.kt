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
import com.meta.wearable.dat.camera.removeCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState as MetaRegistrationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

@Singleton
class RealGlassesBackend @Inject constructor(
    @ApplicationContext
    private val context: Context,
) : GlassesBackend {

    companion object {
        private const val TAG = "RealGlassesBackend"
    }

    /*
     * The Activity is required by MWDAT for registration / permission flows.
     *
     * MainActivity calls:
     *
     *     glassesBackend.setActivity(this)
     *
     * before Compose starts.
     */
    @Volatile
    private var currentActivity: Activity? = null

    private val wearables: Wearables = Wearables

    /**
     * Called by MainActivity when it becomes the foreground Activity.
     */
    fun setActivity(activity: Activity) {
        currentActivity = activity

        Log.i(
            TAG,
            "Host Activity registered: ${activity::class.java.simpleName}",
        )
    }

    /**
     * Called by MainActivity when it is destroyed.
     *
     * We only clear the reference if it is the same Activity that registered
     * itself. This prevents an old Activity from clearing a newer Activity.
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

    override val registrationState: Flow<RegistrationState> =
        wearables.registrationState.map { state ->
            when (state) {
                MetaRegistrationState.REGISTERED ->
                    RegistrationState.REGISTERED

                MetaRegistrationState.REGISTERING ->
                    RegistrationState.REGISTERING

                MetaRegistrationState.NOT_REGISTERED ->
                    RegistrationState.NOT_REGISTERED

                MetaRegistrationState.REVOKED ->
                    RegistrationState.REVOKED

                else ->
                    RegistrationState.UNKNOWN
            }
        }

    override val devices: Flow<List<GlassesDevice>> =
        callbackFlow {
            val job = launch(Dispatchers.Default) {
                try {
                    while (true) {
                        val selector = AutoDeviceSelector()

                        val session = DeviceSession.create(
                            context,
                            selector,
                        )

                        val devices = try {
                            val device = session.device

                            if (device != null) {
                                val rawName = device.metadata.name

                                val displayName =
                                    if (
                                        rawName.isNullOrBlank() ||
                                        rawName.equals("Unknown", ignoreCase = true)
                                    ) {
                                        "Ray-Ban Meta"
                                    } else {
                                        rawName
                                    }

                                listOf(
                                    GlassesDevice(
                                        id = device.id,
                                        name = displayName,
                                        connected = true,
                                    ),
                                )
                            } else {
                                emptyList()
                            }
                        } catch (e: Exception) {
                            Log.e(
                                TAG,
                                "Failed to read glasses device",
                                e,
                            )

                            emptyList()
                        }

                        trySend(devices)

                        session.close()

                        kotlinx.coroutines.delay(2_000L)
                    }
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "Device monitoring stopped",
                        e,
                    )
                }
            }

            awaitClose {
                job.cancel()
            }
        }

    override fun initialize() {
        try {
            Log.i(TAG, "Initializing MWDAT")

            wearables.initialize(context)

            Log.i(TAG, "MWDAT initialized")
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Failed to initialize MWDAT",
                e,
            )
        }
    }

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
            wearables.startRegistration(activity)

            Log.i(
                TAG,
                "Meta registration request started",
            )
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Failed to start registration",
                e,
            )
        }
    }

    override suspend fun cameraPermission(): CameraPermission {
        return try {
            when (
                wearables.checkPermissionStatus(
                    Permission.CAMERA,
                )
            ) {
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
                e,
            )

            CameraPermission.NOT_DETERMINED
        }
    }

    override suspend fun requestCameraPermission(): CameraPermission {
        Log.i(
            TAG,
            "Requesting camera permission",
        )

        val activity = currentActivity

        if (activity == null) {
            Log.e(
                TAG,
                "Cannot request camera permission: no registered Activity",
            )

            return CameraPermission.DENIED
        }

        return try {
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

            wearables.startRegistration(activity)

            /*
             * MWDAT's registration/permission flow is asynchronous.
             *
             * We check immediately here, but if the permission UI is shown,
             * the next camera attempt will check again.
             */
            val afterRequest = cameraPermission()

            Log.i(
                TAG,
                "Camera permission after request: $afterRequest",
            )

            afterRequest
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Failed to request camera permission",
                e,
            )

            CameraPermission.DENIED
        }
    }

    /**
     * Provides JPEG frames from the glasses camera.
     *
     * Each emitted ByteArray is a JPEG image suitable for sending to Gemini.
     */
    override fun cameraFrames(): Flow<ByteArray> =
        callbackFlow {

            val job = launch(Dispatchers.IO) {
                var session: DeviceSession? = null
                var camera: Camera? = null

                try {
                    Log.i(
                        TAG,
                        "Starting glasses camera stream",
                    )

                    val selector = AutoDeviceSelector()

                    session = DeviceSession.create(
                        context,
                        selector,
                    )

                    Log.i(
                        TAG,
                        "Device session created",
                    )

                    camera = session.addCamera(
                        StreamConfiguration(
                            videoQuality = VideoQuality.MEDIUM,
                            frameRate = 15,
                            compressVideo = false,
                        ),
                    )

                    Log.i(
                        TAG,
                        "Camera added to device session",
                    )

                    camera?.videoStream?.collect { frame ->
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
                                    "Camera frame could not be converted to JPEG",
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
                        camera?.let {
                            session?.removeCamera(it)
                        }
                    } catch (e: Exception) {
                        Log.w(
                            TAG,
                            "Failed to remove camera",
                            e,
                        )
                    }

                    try {
                        session?.close()
                    } catch (e: Exception) {
                        Log.w(
                            TAG,
                            "Failed to close device session",
                            e,
                        )
                    }

                    Log.i(
                        TAG,
                        "Glasses camera stream stopped",
                    )
                }
            }

            awaitClose {
                job.cancel()
            }
        }

    /**
     * Converts the MWDAT VideoFrame into JPEG.
     *
     * Handles:
     * 1. Already-JPEG frames
     * 2. I420/YUV frames
     */
    private fun videoFrameToJpeg(
        frame: VideoFrame,
    ): ByteArray? {

        /*
         * Some MWDAT versions expose compressed frames through the buffer
         * directly. Try JPEG decoding first.
         */
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
         * If this is already JPEG data, preserve it.
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
         * Otherwise interpret it as I420/YUV.
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
     * Converts planar I420:
     *
     * YYYYYYYY
     * UUUU
     * VVVV
     *
     * into Android's NV21:
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

        require(data.size >= ySize + uvSize + uvSize) {
            "Invalid I420 buffer. size=${data.size}, expected=${ySize + uvSize + uvSize}"
        }

        val nv21 = ByteArray(ySize + uvSize + uvSize)

        /*
         * Copy Y plane.
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
         * Convert U/V planes to interleaved V/U.
         */
        var outputIndex = ySize

        for (i in 0 until uvSize) {
            nv21[outputIndex++] = data[vOffset + i]
            nv21[outputIndex++] = data[uOffset + i]
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
