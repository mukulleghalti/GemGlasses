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

class RealGlassesBackend(private val context: Context) : GlassesBackend {

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
        // 0.9.0 handles init via getInstance
    }

    override fun startRegistration() {
        wearables.requestRegistration()
    }

    override suspend fun cameraPermission(): CameraPermission =
        wearables.checkPermissionStatus(Permission.CAMERA).toDomain()

    override suspend fun requestCameraPermission(): CameraPermission =
        wearables.requestPermission(Permission.CAMERA).toDomain()

    override fun cameraFrames(): Flow<ByteArray> = callbackFlow {
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

    private fun RegistrationStatus.toDomain(): RegistrationState = when (this) {
        RegistrationStatus.REGISTERED -> RegistrationState.REGISTERED
        RegistrationStatus.REGISTERING -> RegistrationState.REGISTERING
        else -> RegistrationState.NOT_REGISTERED
    }

    private fun PermissionStatus.toDomain(): CameraPermission = when (this) {
        PermissionStatus.GRANTED -> CameraPermission.GRANTED
        PermissionStatus.DENIED -> CameraPermission.DENIED
        else -> CameraPermission.NOT_DETERMINED
    }
}            
