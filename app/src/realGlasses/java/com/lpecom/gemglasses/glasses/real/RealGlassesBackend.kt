package com.lpecom.gemglasses.glasses.real

import android.content.Context
import android.graphics.Bitmap
import com.lpecom.gemglasses.glasses.CameraPermission
import com.lpecom.gemglasses.glasses.GlassesBackend
import com.lpecom.gemglasses.glasses.GlassesDevice
import com.lpecom.gemglasses.glasses.RegistrationState
import com.meta.wearable.mwdat.core.Wearables
import com.meta.wearable.mwdat.core.Permission
import com.meta.wearable.mwdat.core.PermissionStatus
import com.meta.wearable.mwdat.core.RegistrationStatus
import com.meta.wearable.mwdat.camera.CameraClient
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import java.io.ByteArrayOutputStream
import javax.inject.Inject

class RealGlassesBackend @Inject constructor(
    private val context: Context
) : GlassesBackend {

    // In 0.9.0, we use an instance of the Wearables SDK
    private val wearables = Wearables.getInstance(context)

    override val registrationState: Flow<RegistrationState> =
        wearables.registrationStatus.map { it.toDomain() }

    override val devices: Flow<List<GlassesDevice>> =
        wearables.getConnectedDevices().map { list ->
            list.map { d ->
                GlassesDevice(id = d.id, name = d.name, connected = true)
            }
        }

    override fun initialize() {
        // Initialization is handled by getInstance, but we can warm it up here
    }

    override fun startRegistration() {
        // In 0.9.0, registration is often handled by the Meta View app, 
        // but the SDK provides a helper to trigger it.
        wearables.requestRegistration()
    }

    override suspend fun cameraPermission(): CameraPermission =
        wearables.checkPermissionStatus(Permission.CAMERA).toDomain()

    override suspend fun requestCameraPermission(): CameraPermission =
        wearables.requestPermission(Permission.CAMERA).toDomain()

    override fun cameraFrames(): Flow<ByteArray> = callbackFlow {
        // 0.9.0 uses a CameraClient obtained from the wearables instance
        val cameraClient: CameraClient = wearables.createCameraClient()
        
        val listener = CameraClient.FrameListener { bitmap ->
            trySend(bitmap.toDownscaledJpeg())
        }

        cameraClient.addFrameListener(listener)
        cameraClient.startStreaming()

        awaitClose {
            cameraClient.stopStreaming()
            cameraClient.removeFrameListener(listener)
            cameraClient.close()
        }
    }

    private fun Bitmap.toDownscaledJpeg(): ByteArray {
        val longest = maxOf(width, height)
        val scaled = if (longest > MAX_SIDE) {
            val ratio = MAX_SIDE.toFloat() / longest
            Bitmap.createScaledBitmap(
                this,
                (width * ratio).toInt(),
                (height * ratio).toInt(),
                true,
            )
        } else {
            this
        }
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (scaled !== this) scaled.recycle()
            out.toByteArray()
        }
    }

    private fun RegistrationStatus.toDomain(): RegistrationState = when (this) {
        RegistrationStatus.REGISTERED -> RegistrationState.REGISTERED
        RegistrationStatus.REGISTERING -> RegistrationState.REGISTERING
        RegistrationStatus.NOT_REGISTERED -> RegistrationState.NOT_REGISTERED
        else -> RegistrationState.UNKNOWN
    }

    private fun PermissionStatus.toDomain(): CameraPermission = when (this) {
        PermissionStatus.GRANTED -> CameraPermission.GRANTED
        PermissionStatus.DENIED -> CameraPermission.DENIED
        else -> CameraPermission.NOT_DETERMINED
    }

    private companion object {
        const val MAX_SIDE = 768
        const val JPEG_QUALITY = 70
    }
}
