package com.lpecom.gemglasses.glasses.real

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
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

class RealGlassesBackend @Inject constructor(
    private val context: Context
) : GlassesBackend {

    private val wearables = Wearables
    private var currentActivity: Activity? = null

    init {
        (context.applicationContext as? Application)
            ?.registerActivityLifecycleCallbacks(
                object : Application.ActivityLifecycleCallbacks {

                    override fun onActivityResumed(activity: Activity) {
                        currentActivity = activity
                    }

                    override fun onActivityPaused(activity: Activity) {
                        if (currentActivity == activity) {
                            currentActivity = null
                        }
                    }

                    override fun onActivityStarted(activity: Activity) {
                        currentActivity = activity
                    }

                    override fun onActivityStopped(activity: Activity) = Unit

                    override fun onActivityCreated(
                        activity: Activity,
                        savedInstanceState: Bundle?
                    ) = Unit

                    override fun onActivitySaveInstanceState(
                        activity: Activity,
                        outState: Bundle
                    ) = Unit

                    override fun onActivityDestroyed(activity: Activity) {
                        if (currentActivity == activity) {
                            currentActivity = null
                        }
                    }
                }
            )
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
                    "Device id=$id, metadata=$metadata, " +
                        "metadataName=${metadata?.name}"
                )

                val deviceName = metadata?.name
                    ?.takeIf {
                        it.isNotBlank() &&
                            !it.equals("Unknown", ignoreCase = true)
                    }
                    ?: "Ray-Ban Meta"

                Log.i(
                    TAG,
                    "Using glasses name: $deviceName"
                )

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

        val activity = currentActivity ?: (context as? Activity)

        Log.i(
            TAG,
            "startRegistration invoked. " +
                "Activity available: $activity"
        )

        if (activity != null) {
            wearables.startRegistration(activity)
        } else {
            Log.e(
                TAG,
                "Failed to start registration: " +
                    "No active Activity found!"
            )
        }
    }

    override suspend fun cameraPermission(): CameraPermission {

        val result =
            wearables.checkPermissionStatus(Permission.CAMERA)

        val status =
            result.getOrNull()
                ?: return CameraPermission.NOT_DETERMINED

        Log.d(
            TAG,
            "Camera permission status: $status"
        )

        return status.toPermissionDomain()
    }

    override suspend fun requestCameraPermission(): CameraPermission {

        val current = cameraPermission()

        if (current == CameraPermission.GRANTED) {
            return current
        }

        val activity =
            currentActivity ?: (context as? Activity)

        if (activity != null) {

            Log.i(
                TAG,
                "Starting Meta camera permission/registration flow"
            )

            wearables.startRegistration(activity)

        } else {

            Log.e(
                TAG,
                "Cannot request camera permission: " +
                    "no active Activity"
            )
        }

        return cameraPermission()
    }

    override fun cameraFrames(): Flow<ByteArray> =
        callbackFlow {

            Log.i(TAG, "========================================")
            Log.i(TAG, "Starting cameraFrames session...")
            Log.i(TAG, "========================================")

            val permission =
                wearables.checkPermissionStatus(Permission.CAMERA)

            Log.i(
                TAG,
                "Camera permission before session: $permission"
            )

            val sessionResult =
                wearables.createSession(
                    AutoDeviceSelector()
                )

            if (sessionResult.isFailure) {

                val error = sessionResult.errorOrNull()

                Log.e(
                    TAG,
                    "createSession FAILED: $error"
                )

                throw IllegalStateException(
                    "Failed to create device session: $error"
                )
            }

            val session: DeviceSession =
                sessionResult.getOrNull()
                    ?: throw IllegalStateException(
                        "Failed to create device session"
                    )

            Log.i(
                TAG,
                "DeviceSession created"
            )

            try {

                session.start()

                Log.i(
                    TAG,
                    "DeviceSession.start() called"
                )

                val streamConfig =
                    StreamConfiguration(
                        videoQuality = VideoQuality.MEDIUM,
                        frameRate = 15,
                        compressVideo = false
                    )

                Log.i(
                    TAG,
                    "Adding camera: " +
                        "quality=MEDIUM, fps=15, compressed=false"
                )

                val cameraResult =
                    session.addCamera(streamConfig)

                if (cameraResult.isFailure) {

                    val error = cameraResult.errorOrNull()

                    Log.e(
                        TAG,
                        "addCamera FAILED: $error"
                    )

                    throw IllegalStateException(
                        "Failed to add camera: $error"
                    )
                }

                val camera: Camera =
                    cameraResult.getOrNull()
                        ?: throw IllegalStateException(
                            "Failed to add camera"
                        )

                Log.i(
                    TAG,
                    "Camera added successfully"
                )

                val stream = camera.stream

                val streamStartResult =
                    stream.start()

                if (streamStartResult.isFailure) {

                    val error =
                        streamStartResult.errorOrNull()

                    Log.e(
                        TAG,
                        "Camera stream.start() FAILED: $error"
                    )

                    camera.stop()
                    session.removeCamera()
                    session.stop()

                    throw IllegalStateException(
                        "Failed to start camera stream: $error"
                    )
                }

                Log.i(TAG, "========================================")
                Log.i(TAG, "CAMERA STREAM STARTED SUCCESSFULLY")
                Log.i(TAG, "========================================")

                val job =
                    launch(Dispatchers.Default) {

                        var frameNumber = 0

                        stream.videoStream.collect { frame ->

                            frameNumber++

                            val buffer =
                                frame.buffer.asReadOnlyBuffer()

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
                                    "Skipping codec config frame"
                                )
                                return@collect
                            }

                            val jpeg =
                                frame.toDownscaledJpeg()

                            if (jpeg != null) {

                                Log.d(
                                    TAG,
                                    "JPEG created: " +
                                        "${jpeg.size} bytes"
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
                                    "VideoFrame conversion returned null"
                                )
                            }
                        }
                    }

                awaitClose {

                    Log.i(
                        TAG,
                        "Stopping cameraFrames session..."
                    )

                    job.cancel()

                    runCatching {
                        stream.stop()
                    }.onFailure {
                        Log.w(
                            TAG,
                            "stream.stop() failed",
                            it
                        )
                    }

                    runCatching {
                        camera.stop()
                    }.onFailure {
                        Log.w(
                            TAG,
                            "camera.stop() failed",
                            it
                        )
                    }

                    runCatching {
                        session.removeCamera()
                    }.onFailure {
                        Log.w(
                            TAG,
                            "session.removeCamera() failed",
                            it
                        )
                    }

                    runCatching {
                        session.stop()
                    }.onFailure {
                        Log.w(
                            TAG,
                            "session.stop() failed",
                            it
                        )
                    }
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Camera session failed",
                    e
                )

                runCatching {
                    session.stop()
                }

                throw e
            }
        }

    /**
     * Converts the MWDAT raw YUV420/I420 frame into JPEG.
     */
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
                "Frame is already JPEG"
            )

            return bytes
        }

        /*
         * We intentionally use compressVideo=false,
         * therefore compressed frames aren't expected here.
         */
        if (isCompressed) {

            Log.w(
                TAG,
                "Received compressed video frame; " +
                    "JPEG conversion skipped"
            )

            return null
        }

        val expectedI420Size =
            width * height * 3 / 2

        if (remaining != expectedI420Size) {

            Log.w(
                TAG,
                "Unexpected raw frame size: " +
                    "actual=$remaining, " +
                    "expected=$expectedI420Size, " +
                    "resolution=${width}x${height}"
            )

            return null
        }

        return try {

            val jpeg =
                i420ToJpeg(
                    bytes = bytes,
                    width = width,
                    height = height,
                    quality = 70
                )

            val bitmap =
                BitmapFactory.decodeByteArray(
                    jpeg,
                    0,
                    jpeg.size
                )

            bitmap?.toDownscaledJpeg()
                ?: jpeg

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to convert I420 frame to JPEG",
                e
            )

            null
        }
    }

    /**
     * I420:
     *
     * YYYYYYYY
     * UUUU
     * VVVV
     *
     * Android YuvImage expects NV21:
     *
     * YYYYYYYY
     * VUVUVUVU
     */
    private fun i420ToJpeg(
        bytes: ByteArray,
        width: Int,
        height: Int,
        quality: Int
    ): ByteArray {

        val frameSize = width * height
        val chromaSize = frameSize / 4

        val yOffset = 0
        val uOffset = frameSize
        val vOffset = frameSize + chromaSize

        val nv21 =
            ByteArray(
                frameSize + chromaSize * 2
            )

        /*
         * Copy Y plane.
         */
        System.arraycopy(
            bytes,
            yOffset,
            nv21,
            0,
            frameSize
        )

        /*
         * I420 -> NV21
         *
         * I420 chroma:
         * UUUU
         * VVVV
         *
         * NV21:
         * VUVUVUVU
         */
        var outputIndex = frameSize

        for (i in 0 until chromaSize) {

            nv21[outputIndex++] =
                bytes[vOffset + i]

            nv21[outputIndex++] =
                bytes[uOffset + i]
        }

        val yuvImage =
            YuvImage(
                nv21,
                ImageFormat.NV21,
                width,
                height,
                null
            )

        return ByteArrayOutputStream().use { output ->

            val success =
                yuvImage.compressToJpeg(
                    Rect(
                        0,
                        0,
                        width,
                        height
                    ),
                    quality,
                    output
                )

            if (!success) {
                throw IllegalStateException(
                    "YuvImage.compressToJpeg() failed"
                )
            }

            output.toByteArray()
        }
    }

    private fun Bitmap.toDownscaledJpeg(): ByteArray {

        val longest =
            maxOf(width, height)

        val scaled =
            if (longest > 768) {

                val ratio =
                    768f / longest

                Bitmap.createScaledBitmap(
                    this,
                    (width * ratio).toInt(),
                    (height * ratio).toInt(),
                    true
                )

            } else {
                this
            }

        return ByteArrayOutputStream().use { output ->

            scaled.compress(
                Bitmap.CompressFormat.JPEG,
                70,
                output
            )

            if (scaled !== this) {
                scaled.recycle()
            }

            output.toByteArray()
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
