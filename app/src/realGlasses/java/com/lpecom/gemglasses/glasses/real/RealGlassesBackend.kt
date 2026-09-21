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
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealGlassesBackend @Inject constructor(
    private val context: Context
) : GlassesBackend {

    private val wearables = Wearables

    /*
     * MainActivity explicitly registers itself here.
     *
     * We do NOT rely on Application.ActivityLifecycleCallbacks for the
     * permission/registration flow anymore.
     */
    @Volatile
    private var currentActivity: Activity? = null

    /**
     * Called by MainActivity when it is available.
     */
    fun setActivity(activity: Activity) {
        currentActivity = activity
        Log.i(TAG, "Host Activity registered: ${activity.javaClass.simpleName}")
    }

    /**
     * Called when MainActivity is being destroyed.
     */
    fun clearActivity(activity: Activity) {
        if (currentActivity === activity) {
            currentActivity = null
            Log.i(TAG, "Host Activity cleared")
        }
    }

    override val registrationState: Flow<RegistrationState> =
        wearables.registrationState.map {
            Log.d(TAG, "Meta registrationState changed: $it")
            it.toRegistrationDomain()
        }

    override val devices: Flow<List<GlassesDevice>> =
        wearables.devices.map { idSet ->
            Log.d(TAG, "Meta devices set: $idSet")

            idSet.map { id ->
                val metadata = wearables.devicesMetadata[id]?.value

                Log.d(
                    TAG,
                    "Device id=$id, metadata=$metadata, metadataName=${metadata?.name}"
                )

                val deviceName = metadata?.name
                    ?.takeIf {
                        it.isNotBlank() &&
                            !it.equals("Unknown", ignoreCase = true)
                    }
                    ?: "Ray-Ban Meta"

                Log.i(TAG, "Using glasses name: $deviceName")

                GlassesDevice(
                    id = id.toString(),
                    name = deviceName,
                    connected = true
                )
            }
        }

    override fun initialize() {
        val result = wearables.initialize(context)

        Log.i(
            TAG,
            "Wearables.initialize result: " +
                "isSuccess=${result.isSuccess}, " +
                "error=${result.errorOrNull()}"
        )
    }

    override fun startRegistration() {
        val activity = currentActivity

        Log.i(
            TAG,
            "startRegistration invoked. " +
                "Activity=${activity?.javaClass?.simpleName}"
        )

        if (activity != null) {
            wearables.startRegistration(activity)
        } else {
            Log.e(
                TAG,
                "Failed to start registration: no registered Activity"
            )
        }
    }

    override suspend fun cameraPermission(): CameraPermission {
        val result = wearables.checkPermissionStatus(Permission.CAMERA)

        val status = result.getOrNull()

        if (status == null) {
            Log.e(
                TAG,
                "Unable to determine camera permission: " +
                    "${result.errorOrNull()}"
            )
            return CameraPermission.NOT_DETERMINED
        }

        Log.i(TAG, "Camera permission status: $status")

        return status.toPermissionDomain()
    }

    override suspend fun requestCameraPermission(): CameraPermission {
        Log.i(TAG, "Requesting camera permission...")

        val current = cameraPermission()

        Log.i(TAG, "Current camera permission: $current")

        if (current == CameraPermission.GRANTED) {
            Log.i(TAG, "Camera permission already granted")
            return current
        }

        val activity = currentActivity

        if (activity == null) {
            Log.e(
                TAG,
                "Cannot request camera permission: no registered Activity"
            )
            return current
        }

        Log.i(
            TAG,
            "Starting Meta registration/permission flow using " +
                activity.javaClass.simpleName
        )

        try {
            wearables.startRegistration(activity)

            Log.i(
                TAG,
                "Meta registration flow started successfully"
            )
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Failed to start Meta registration flow",
                e
            )
            return CameraPermission.NOT_DETERMINED
        }

        /*
         * IMPORTANT:
         *
         * startRegistration() launches an asynchronous UI flow.
         * We cannot immediately assume the permission has changed here.
         *
         * Therefore we check again, but the Activity remains registered.
         * If the user still needs to approve the permission, the next
         * vision attempt can check it again.
         */
        val afterRequest = cameraPermission()

        Log.i(
            TAG,
            "Camera permission after registration request: $afterRequest"
        )

        return afterRequest
    }

    override fun cameraFrames(): Flow<ByteArray> = callbackFlow {
        Log.i(TAG, "Starting cameraFrames session...")

        val sessionResult = wearables.createSession(
            AutoDeviceSelector()
        )

        val session: DeviceSession = sessionResult.getOrNull()
            ?: throw IllegalStateException(
                "Failed to create device session: " +
                    "${sessionResult.errorOrNull()}"
            )

        Log.i(TAG, "Device session created")

        session.start()

        Log.i(TAG, "Device session started")

        val streamConfig = StreamConfiguration(
            videoQuality = VideoQuality.MEDIUM,
            frameRate = 15,
            compressVideo = false
        )

        Log.i(
            TAG,
            "Adding camera: quality=MEDIUM, fps=15, compressVideo=false"
        )

        val cameraResult = session.addCamera(streamConfig)

        val camera: Camera = cameraResult.getOrNull()
            ?: run {
                Log.e(
                    TAG,
                    "Failed to add camera: ${cameraResult.errorOrNull()}"
                )

                session.stop()

                throw IllegalStateException(
                    "Failed to add camera: " +
                        "${cameraResult.errorOrNull()}"
                )
            }

        Log.i(TAG, "Camera added successfully")

        val stream = camera.stream

        val streamStartResult = stream.start()

        if (streamStartResult.isFailure) {
            Log.e(
                TAG,
                "Failed to start camera stream: " +
                    "${streamStartResult.errorOrNull()}"
            )

            camera.stop()
            session.removeCamera()
            session.stop()

            throw IllegalStateException(
                "Failed to start stream: " +
                    "${streamStartResult.errorOrNull()}"
            )
        }

        Log.i(TAG, "CAMERA STREAM STARTED SUCCESSFULLY")

        val job = launch(Dispatchers.Default) {
            var frameNumber = 0

            stream.videoStream.collect { frame ->
                frameNumber++

                val buffer = frame.buffer.asReadOnlyBuffer()

                Log.d(
                    TAG,
                    "VideoFrame #$frameNumber: " +
                        "${frame.width}x${frame.height}, " +
                        "bytes=${buffer.remaining()}, " +
                        "compressed=${frame.isCompressed}, " +
                        "codecConfig=${frame.isCodecConfig}"
                )

                if (frame.isCodecConfig) {
                    Log.d(
                        TAG,
                        "Ignoring codec config frame #$frameNumber"
                    )
                    return@collect
                }

                val jpeg = frame.toDownscaledJpeg()

                if (jpeg != null) {
                    Log.d(
                        TAG,
                        "JPEG created: ${jpeg.size} bytes"
                    )

                    try {
                        trySend(jpeg)
                    } catch (e: Exception) {
                        Log.w(
                            TAG,
                            "Failed to send JPEG to camera flow",
                            e
                        )
                    }
                } else {
                    Log.w(
                        TAG,
                        "Could not convert VideoFrame #$frameNumber " +
                            "to JPEG"
                    )
                }
            }
        }

        awaitClose {
            Log.i(TAG, "Stopping cameraFrames session...")

            job.cancel()

            try {
                stream.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping stream", e)
            }

            try {
                camera.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping camera", e)
            }

            try {
                session.removeCamera()
            } catch (e: Exception) {
                Log.w(TAG, "Error removing camera", e)
            }

            try {
                session.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping session", e)
            }

            Log.i(TAG, "Camera session stopped")
        }
    }

    private fun VideoFrame.toDownscaledJpeg(): ByteArray? {
        val buf = buffer.asReadOnlyBuffer()
        val remaining = buf.remaining()

        if (remaining <= 0) {
            return null
        }

        val bytes = ByteArray(remaining)
        buf.get(bytes)

        /*
         * Already JPEG.
         */
        if (
            remaining >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) {
            Log.d(
                TAG,
                "VideoFrame is already JPEG"
            )

            return bytes
        }

        /*
         * If MWDAT says this frame is compressed but it isn't JPEG,
         * don't try to interpret it as raw YUV.
         */
        if (isCompressed) {
            Log.w(
                TAG,
                "VideoFrame is compressed but not JPEG. " +
                    "Cannot decode frame."
            )
            return null
        }

        /*
         * Expected size for I420/YUV420 planar:
         *
         * Y  = width * height
         * U  = width * height / 4
         * V  = width * height / 4
         *
         * Total = width * height * 3 / 2
         */
        val expectedI420Size = width * height * 3 / 2

        if (remaining != expectedI420Size) {
            Log.w(
                TAG,
                "Unexpected raw frame size: " +
                    "actual=$remaining, " +
                    "expectedI420=$expectedI420Size, " +
                    "resolution=${width}x$height"
            )
            return null
        }

        return try {
            val jpeg = i420ToJpeg(
                bytes = bytes,
                width = width,
                height = height,
                quality = 70
            )

            val bitmap = BitmapFactory.decodeByteArray(
                jpeg,
                0,
                jpeg.size
            )

            bitmap?.toDownscaledJpeg() ?: jpeg
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Failed to convert I420 frame to JPEG",
                e
            )
            null
        }
    }

    private fun i420ToJpeg(
        bytes: ByteArray,
        width: Int,
        height: Int,
        quality: Int
    ): ByteArray {
        val frameSize = width * height
        val chromaSize = frameSize / 4

        val uOffset = frameSize
        val vOffset = frameSize + chromaSize

        /*
         * Android YuvImage expects NV21:
         *
         * YYYYYYYY
         * VUVUVUVU
         */
        val nv21 = ByteArray(
            frameSize + chromaSize * 2
        )

        System.arraycopy(
            bytes,
            0,
            nv21,
            0,
            frameSize
        )

        var outputIndex = frameSize

        for (i in 0 until chromaSize) {
            nv21[outputIndex++] = bytes[vOffset + i]
            nv21[outputIndex++] = bytes[uOffset + i]
        }

        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            width,
            height,
            null
        )

        val out = ByteArrayOutputStream()

        yuvImage.compressToJpeg(
            Rect(0, 0, width, height),
            quality,
            out
        )

        return out.toByteArray()
    }

    private fun Bitmap.toDownscaledJpeg(): ByteArray {
        val longest = maxOf(width, height)

        val scaled = if (longest > 768) {
            val ratio = 768f / longest

            Bitmap.createScaledBitmap(
                this,
                (width * ratio).toInt(),
                (height * ratio).toInt(),
                true
            )
        } else {
            this
        }

        return ByteArrayOutputStream().use { out ->
            scaled.compress(
                Bitmap.CompressFormat.JPEG,
                70,
                out
            )

            if (scaled !== this) {
                scaled.recycle()
            }

            out.toByteArray()
        }
    }

    private fun MetaRegistrationState.toRegistrationDomain():
        RegistrationState =
        when (this) {
            MetaRegistrationState.REGISTERED ->
                RegistrationState.REGISTERED

            MetaRegistrationState.REGISTERING ->
                RegistrationState.REGISTERING

            else ->
                RegistrationState.NOT_REGISTERED
        }

    private fun PermissionStatus.toPermissionDomain():
        CameraPermission =
        when (this) {
            is PermissionStatus.Granted ->
                CameraPermission.GRANTED

            is PermissionStatus.Denied ->
                CameraPermission.DENIED
        }

    companion object {
        private const val TAG = "RealGlassesBackend"
    }
}
