package com.lpecom.gemglasses.glasses.real

import android.content.Context
import android.graphics.Bitmap
import com.lpecom.gemglasses.glasses.CameraPermission
import com.lpecom.gemglasses.glasses.GlassesBackend
import com.lpecom.gemglasses.glasses.GlassesDevice
import com.lpecom.gemglasses.glasses.RegistrationState

// Corrected Imports based on your JAR inspection (.types)
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationStatus
import com.meta.wearable.dat.camera.StreamSession

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import java.io.ByteArrayOutputStream
import javax.inject.Inject

class RealGlassesBackend @Inject constructor(
    private val context: Context
) : GlassesBackend {

    // In this version, we use the singleton 'Wearables' object
    private val wearables = Wearables

    override val registrationState: Flow<RegistrationState> =
        wearables.registrationStatus.map { it.toRegistrationDomain() }

    override val devices: Flow<List<GlassesDevice>> =
        wearables.devices.map { list ->
            list.map { d ->
                // Verifying property names: id, name, and isConnected
                GlassesDevice(id = d.id, name = d.name, connected = d.isConnected)
            }
        }

    override fun initialize() {
        // You MUST call this for the singleton to work
        wearables.initialize(context)
    }

    override fun startRegistration() {
        wearables.startRegistration(context)
    }

    override suspend fun cameraPermission(): CameraPermission =
        wearables.checkPermissionStatus(Permission.CAMERA).toPermissionDomain()

    override suspend fun requestCameraPermission(): CameraPermission =
        wearables.requestPermission(Permission.CAMERA).toPermissionDomain()

    override fun cameraFrames(): Flow<ByteArray> = callbackFlow {
        // Version 0.5.x/Legacy 0.9.0 uses StreamSession
        val session: StreamSession = wearables.openCameraStream()
        
        session.onFrame { bitmap ->
            trySend(bitmap.toDownscaledJpeg())
        }
        
        session.start()
        
        awaitClose {
            session.stop()
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

    private fun RegistrationStatus.toRegistrationDomain(): RegistrationState = when (this) {
        RegistrationStatus.REGISTERED -> RegistrationState.REGISTERED
        RegistrationStatus.REGISTERING -> RegistrationState.REGISTERING
        else -> RegistrationState.NOT_REGISTERED
    }

    private fun PermissionStatus.toPermissionDomain(): CameraPermission = when (this) {
        PermissionStatus.GRANTED -> CameraPermission.GRANTED
        PermissionStatus.DENIED -> CameraPermission.DENIED
        else -> CameraPermission.NOT_DETERMINED
    }
}
