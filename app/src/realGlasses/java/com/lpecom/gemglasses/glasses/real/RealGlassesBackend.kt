package com.lpecom.gemglasses.glasses.real

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.lpecom.gemglasses.glasses.CameraPermission
import com.lpecom.gemglasses.glasses.GlassesBackend
import com.lpecom.gemglasses.glasses.GlassesDevice
import com.lpecom.gemglasses.glasses.RegistrationState
import com.meta.wearable.mwdat.Wearables
import com.meta.wearable.mwdat.Permission
import com.meta.wearable.mwdat.RegistrationStatus
import com.meta.wearable.mwdat.StreamSession
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import java.io.ByteArrayOutputStream

/**
 * Real integration with the Meta Wearables Device Access Toolkit.
 *
 * Compiled only when the DAT SDK is on the classpath (see
 * `settings.gradle.kts` + `gemglasses.useRealGlasses`). Instantiated reflectively
 * by the DI layer so `src/main` never hard-references the SDK. The method bodies
 * follow the DAT surface described in the Meta Wearables developer docs; verify
 * against your installed SDK version, as the API is still evolving.
 */
@Suppress("unused")
class RealGlassesBackend(private val context: Context) : GlassesBackend {

    override val registrationState: Flow<RegistrationState> =
        Wearables.registrationState.map { it.toDomain() }

    override val devices: Flow<List<GlassesDevice>> =
        Wearables.devices.map { list ->
            list.map { d ->
                GlassesDevice(id = d.id, name = d.name, connected = d.isConnected)
            }
        }

    override fun initialize() {
        Wearables.initialize(context)
    }

    override fun startRegistration() {
        // Registration must be launched from an Activity; the app routes this
        // through GlassesManager which holds the current Activity reference.
        Wearables.startRegistration(context)
    }

    override suspend fun cameraPermission(): CameraPermission =
        Wearables.checkPermissionStatus(Permission.CAMERA).toDomain()

    override suspend fun requestCameraPermission(): CameraPermission =
        Wearables.requestPermission(Permission.CAMERA).toDomain()

    override fun cameraFrames(): Flow<ByteArray> = callbackFlow {
        val session: StreamSession = Wearables.openCameraStream()
        session.onFrame { bitmap ->
            trySend(bitmap.toDownscaledJpeg())
        }
        session.start()
        awaitClose { session.stop() }
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
        RegistrationStatus.REVOKED -> RegistrationState.REVOKED
        else -> RegistrationState.UNKNOWN
    }

    private fun com.meta.wearable.mwdat.PermissionStatus.toDomain(): CameraPermission = when (this) {
        com.meta.wearable.mwdat.PermissionStatus.GRANTED -> CameraPermission.GRANTED
        com.meta.wearable.mwdat.PermissionStatus.DENIED -> CameraPermission.DENIED
        else -> CameraPermission.NOT_DETERMINED
    }

    private companion object {
        const val TAG = "RealGlassesBackend"
        const val MAX_SIDE = 768
        const val JPEG_QUALITY = 70
    }
}
