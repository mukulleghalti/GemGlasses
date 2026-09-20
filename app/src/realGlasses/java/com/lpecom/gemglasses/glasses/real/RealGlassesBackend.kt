package com.lpecom.gemglasses.glasses.real

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import com.lpecom.gemglasses.glasses.CameraPermission
import com.lpecom.gemglasses.glasses.GlassesBackend
import com.lpecom.gemglasses.glasses.GlassesDevice
import com.lpecom.gemglasses.glasses.RegistrationState

// These imports are 1:1 matches for your provided sdk_map.txt
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationStatus
import com.meta.wearable.dat.camera.Camera

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import java.io.ByteArrayOutputStream
import javax.inject.Inject

class RealGlassesBackend @Inject constructor(
    private val context: Context
) : GlassesBackend {

    // Use the public Wearables object found in classes.jar
    private val wearables = Wearables

    override val registrationState: Flow<RegistrationState> =
        wearables.registrationStatus.map { it.toRegistrationDomain() }

    override val devices: Flow<List<GlassesDevice>> =
        wearables.getConnectedDevices().map { list ->
            list.map { d ->
                // Based on types/Device.class in your map
                GlassesDevice(
                    id = d.id.toString(), 
                    name = d.name, 
                    connected = true 
                )
            }
        }

    override fun initialize() {
        // Required initialization found in your core package
        wearables.initialize(context)
    }

    override fun startRegistration() {
        // Cast context to activity to satisfy the Meta SDK UI requirement
        val activity = context as? Activity
        if (activity != null) {
            wearables.startRegistration(activity)
        }
    }

    override suspend fun cameraPermission(): CameraPermission =
        wearables.checkPermissionStatus(Permission.CAMERA).toPermissionDomain()

    override suspend fun requestCameraPermission(): CameraPermission =
        wearables.requestPermission(Permission.CAMERA).toPermissionDomain()

    override fun cameraFrames(): Flow<ByteArray> = callbackFlow {
        // Access the Camera class confirmed in your camera-classes.jar
        val camera: Camera = wearables.camera
        
        val listener = Camera.FrameListener { bitmap ->
            trySend(bitmap.toDownscaledJpeg())
        }

        camera.addFrameListener(listener)
        camera.start()

        awaitClose {
            camera.stop()
            camera.removeFrameListener(listener)
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
        is PermissionStatus.Granted -> CameraPermission.GRANTED
        is PermissionStatus.Denied -> CameraPermission.DENIED
        else -> CameraPermission.NOT_DETERMINED
    }
}
