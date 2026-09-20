package com.lpecom.gemglasses.glasses.real

import android.content.Context
import android.graphics.Bitmap
import com.lpecom.gemglasses.glasses.CameraPermission
import com.lpecom.gemglasses.glasses.GlassesBackend
import com.lpecom.gemglasses.glasses.GlassesDevice
import com.lpecom.gemglasses.glasses.RegistrationState
import com.meta.wearable.mwdat.Wearables
import com.meta.wearable.mwdat.Permission
import com.meta.wearable.mwdat.PermissionStatus
import com.meta.wearable.mwdat.RegistrationStatus
import com.meta.wearable.mwdat.camera.CameraClient
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import java.io.ByteArrayOutputStream
import javax.inject.Inject

class RealGlassesBackend @Inject constructor(
    private val context: Context
) : GlassesBackend {

    // 0.9.0 requires an instance via getInstance()
    private val wearables = Wearables.getInstance(context)

    override val registrationState: Flow<RegistrationState> =
        wearables.registrationStatus.map { it.toRegistrationDomain() }

    override val devices: Flow<List<GlassesDevice>> =
        wearables.getConnectedDevices().map { list ->
            list.map { d ->
                GlassesDevice(id = d.id, name = d.name, connected = true)
            }
        }

    override fun initialize() {
        // Initialization is handled internally by Wearables.getInstance()
    }

    override fun startRegistration() {
        // Triggers the Meta View app registration flow
        wearables.requestRegistration()
    }

    override suspend fun cameraPermission(): CameraPermission =
        wearables.checkPermissionStatus(Permission.CAMERA).toPermissionDomain()

    override suspend fun requestCameraPermission(): CameraPermission =
        wearables.requestPermission(Permission.CAMERA).toPermissionDomain()

    override fun cameraFrames(): Flow<ByteArray> = callbackFlow {
        // 0.9.0 uses CameraClient for frame streaming
        val cameraClient = wearables.createCameraClient()
        
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

    // Explicitly named to prevent Overload Resolution Ambiguity
    private fun RegistrationStatus.toRegistrationDomain(): RegistrationState = when (this) {
        RegistrationStatus.REGISTERED -> RegistrationState.REGISTERED
        RegistrationStatus.REGISTERING -> RegistrationState.REGISTERING
        else -> RegistrationState.NOT_REGISTERED
    }

    // Explicitly named to prevent Overload Resolution Ambiguity
    private fun PermissionStatus.toPermissionDomain(): CameraPermission = when (this) {
        PermissionStatus.GRANTED -> CameraPermission.GRANTED
        PermissionStatus.DENIED -> CameraPermission.DENIED
        else -> CameraPermission.NOT_DETERMINED
    }

    private companion object {
        const val MAX_SIDE = 768
        const val JPEG_QUALITY = 70
    }
}
