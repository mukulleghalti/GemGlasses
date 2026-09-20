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
        // Automatically track the foreground Activity so startRegistration can launch the Meta AI UI
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) { currentActivity = activity }
                override fun onActivityPaused(activity: Activity) { if (currentActivity == activity) currentActivity = null }
                override fun onActivityStarted(activity: Activity) { currentActivity = activity }
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) { if (currentActivity == activity) currentActivity = null }
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
                GlassesDevice(
                    id = id.toString(),
                    name = metadata?.name ?: "Meta Glasses",
                    connected = true
                )
            }
        }

    override fun initialize() {
        val result = wearables.initialize(context)
        Log.i(TAG, "Wearables.initialize result: isSuccess=${result.isSuccess}, error=${result.errorOrNull()}")
    }

    override fun startRegistration() {
        val activity = currentActivity ?: (context as? Activity)
        Log.i(TAG, "startRegistration invoked. Activity available: $activity")
        if (activity != null) {
            wearables.startRegistration(activity)
        } else {
            Log.e(TAG, "Failed to start registration: No active Activity found!")
        }
    }

    override suspend fun cameraPermission(): CameraPermission {
        val result = wearables.checkPermissionStatus(Permission.CAMERA)
        val status = result.getOrNull() ?: return CameraPermission.NOT_DETERMINED
        return status.toPermissionDomain()
    }

    override suspend fun requestCameraPermission(): CameraPermission {
        val current = cameraPermission()
        if (current == CameraPermission.GRANTED) return current

        val activity = currentActivity ?: (context as? Activity)
        if (activity != null) {
            wearables.startRegistration(activity)
        }
        return cameraPermission()
    }

    override fun cameraFrames(): Flow<ByteArray> = callbackFlow {
        Log.i(TAG, "Starting cameraFrames session...")
        val sessionResult = wearables.createSession(AutoDeviceSelector())
        val session: DeviceSession = sessionResult.getOrNull()
            ?: throw IllegalStateException("Failed to create device session: ${sessionResult.errorOrNull()}")

        session.start()

        val streamConfig = StreamConfiguration(
            videoQuality = VideoQuality.MEDIUM,
            frameRate = 15,
            compressVideo = false
        )
        val cameraResult = session.addCamera(streamConfig)
        val camera: Camera = cameraResult.getOrNull()
            ?: run {
                session.stop()
                throw IllegalStateException("Failed to add camera: ${cameraResult.errorOrNull()}")
            }

        val stream = camera.stream
        val streamStartResult = stream.start()
        if (streamStartResult.isFailure) {
            camera.stop()
            session.removeCamera()
            session.stop()
            throw IllegalStateException("Failed to start stream: ${streamStartResult.errorOrNull()}")
        }

        val job = launch(Dispatchers.Default) {
            stream.videoStream.collect { frame ->
                if (frame.isCodecConfig) return@collect
                val jpeg = frame.toDownscaledJpeg()
                if (jpeg != null) {
                    trySend(jpeg)
                }
            }
        }

        awaitClose {
            Log.i(TAG, "Stopping cameraFrames session...")
            job.cancel()
            stream.stop()
            camera.stop()
            session.removeCamera()
            session.stop()
        }
    }

    private fun VideoFrame.toDownscaledJpeg(): ByteArray? {
        val buf = buffer.asReadOnlyBuffer()
        val remaining = buf.remaining()
        if (remaining <= 0) return null

        val bytes = ByteArray(remaining)
        buf.get(bytes)

        if (remaining > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            return bytes
        }

        if (remaining == width * height * 4) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
            return bitmap.toDownscaledJpeg()
        }

        if (remaining == width * height * 3 / 2) {
            return try {
                val yuvImage = YuvImage(bytes, ImageFormat.NV21, width, height, null)
                val out = ByteArrayOutputStream()
                yuvImage.compressToJpeg(Rect(0, 0, width, height), 70, out)
                val jpegBytes = out.toByteArray()
                val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                bitmap?.toDownscaledJpeg() ?: jpegBytes
            } catch (e: Exception) {
                null
            }
        }

        return try {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, remaining)
            bitmap?.toDownscaledJpeg()
        } catch (e: Exception) {
            null
        }
    }

    private fun Bitmap.toDownscaledJpeg(): ByteArray {
        val longest = maxOf(width, height)
        val scaled = if (longest > 768) {
            val ratio = 768f / longest
            Bitmap.createScaledBitmap(this, (width * ratio).toInt(), (height * ratio).toInt(), true)
        } else this

        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
            if (scaled !== this) scaled.recycle()
            out.toByteArray()
        }
    }

    private fun MetaRegistrationState.toRegistrationDomain(): RegistrationState = when (this) {
        MetaRegistrationState.REGISTERED -> RegistrationState.REGISTERED
        MetaRegistrationState.REGISTERING -> RegistrationState.REGISTERING
        else -> RegistrationState.NOT_REGISTERED
    }

    private fun PermissionStatus.toPermissionDomain(): CameraPermission = when (this) {
        is PermissionStatus.Granted -> CameraPermission.GRANTED
        is PermissionStatus.Denied -> CameraPermission.DENIED
        else -> CameraPermission.NOT_DETERMINED
    }

    companion object {
        private const val TAG = "RealGlassesBackend"
    }
}
