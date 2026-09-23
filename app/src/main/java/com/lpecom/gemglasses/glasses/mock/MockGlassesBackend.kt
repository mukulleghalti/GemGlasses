package com.lpecom.gemglasses.glasses.mock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.lpecom.gemglasses.glasses.CameraPermission
import com.lpecom.gemglasses.glasses.GlassesBackend
import com.lpecom.gemglasses.glasses.GlassesDevice
import com.lpecom.gemglasses.glasses.RegistrationState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import java.io.ByteArrayOutputStream
import android.app.Activity
import com.lpecom.gemglasses.glasses.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hardware-free stand-in for the Meta DAT SDK. Reports one connected pair of
 * glasses, auto-"registers" after a short delay, and synthesises JPEG frames so
 * the full vision path (capture → encode → Live socket) can be exercised in
 * unit tests, on emulators, and in CI. Selected when [USE_REAL_GLASSES] is off
 * or no SDK is linked.
 */
class MockGlassesBackend : GlassesBackend {

    private val _registration = MutableStateFlow(RegistrationState.NOT_REGISTERED)
    override val registrationState: Flow<RegistrationState> = _registration

    private val _devices = MutableStateFlow(
        listOf(GlassesDevice(id = "mock-raybans", name = "Ray-Ban Meta (Mock)", connected = true)),
    )
    override val devices: Flow<List<GlassesDevice>> = _devices

    override fun initialize() {
        _registration.value = RegistrationState.REGISTERED
    }

    override fun startRegistration() {
        _registration.value = RegistrationState.REGISTERED
    }
    
    override fun setActivity(activity: Activity) {
        // No-op for mock backend
    }

    override fun clearActivity(activity: Activity) {
        // No-op for mock backend
    }
    override suspend fun cameraPermission() = CameraPermission.GRANTED

    override suspend fun requestCameraPermission() = CameraPermission.GRANTED

    private val _connectionState =
    MutableStateFlow(ConnectionState.DISCONNECTED)

    override val connectionState =
    _connectionState.asStateFlow()

    override fun cameraFrames(): Flow<ByteArray> = flow {
        var i = 0
        while (true) {
            emit(syntheticJpeg(i++))
            delay(1_000) // ~1 fps, matching the real capture cadence
        }
    }

    private fun syntheticJpeg(index: Int): ByteArray {
        val bmp = Bitmap.createBitmap(512, 384, Bitmap.Config.RGB_565)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(30, 30, 40))
        val paint = Paint().apply {
            color = Color.rgb((index * 40) % 255, 180, 220)
            textSize = 48f
            isAntiAlias = true
        }
        canvas.drawText("MOCK FRAME #$index", 40f, 200f, paint)
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
            bmp.recycle()
            out.toByteArray()
        }
    }
}
