package com.geno.veyra.glasses

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bounded, on-demand access to the glasses camera.
 *
 * MWDAT can stream at a higher FPS, but Gemini Live vision input is
 * intentionally throttled here so we only send approximately 1 frame/sec.
 */
@Singleton
class GlassesCameraSource @Inject constructor(
    private val backend: GlassesBackend,
) {

    suspend fun runBurst(
        maxDurationMs: Long,
        maxFrames: Int = DEFAULT_MAX_FRAMES,
        onFrame: (ByteArray) -> Unit,
    ): Int {

        var count = 0

        try {

            withTimeoutOrNull(maxDurationMs) {

                backend.cameraFrames()
                    .take(maxFrames)
                    .collect { frame ->

                        try {
                            onFrame(frame)
                            count++

                            Log.d(
                                TAG,
                                "Forwarded vision frame #$count " +
                                    "(${frame.size} bytes)"
                            )

                        } catch (e: Exception) {
                            Log.e(
                                TAG,
                                "Failed while forwarding vision frame",
                                e
                            )
                        }

                        /*
                         * Gemini vision input is intentionally throttled
                         * to approximately one frame per second.
                         */
                        if (count < maxFrames) {
                            delay(FRAME_INTERVAL_MS)
                        }
                    }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Vision burst failed",
                e
            )
        }

        Log.i(
            TAG,
            "Vision burst finished after $count frame(s)"
        )

        return count
    }

    /**
     * Captures a single JPEG frame from the glasses camera.
     *
     * The backend starts the camera stream on demand and stops it when
     * the flow collection ends, so this is self-contained: null means
     * the camera wasn't available in time.
     */
    suspend fun captureFrame(
        timeoutMs: Long = CAPTURE_TIMEOUT_MS,
    ): ByteArray? =
        withTimeoutOrNull(timeoutMs) {
            runCatching {
                backend.cameraFrames().first()
            }.getOrNull()
        }

    private companion object {

        const val TAG = "GlassesCameraSource"

        const val DEFAULT_MAX_FRAMES = 20

        const val FRAME_INTERVAL_MS = 1_000L

        const val CAPTURE_TIMEOUT_MS = 30_000L
    }
}
