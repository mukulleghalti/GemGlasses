package com.lpecom.gemglasses.glasses

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bounded, on-demand access to the glasses camera. A vision burst opens the
 * stream, forwards JPEG frames to [onFrame] for at most [maxDurationMs] (or
 * until [maxFrames] is reached), then closes it. Keeping bursts short is what
 * lets the Live session stay nominally audio-only and dodge the 2-minute video
 * cap.
 */
@Singleton
class GlassesCameraSource @Inject constructor(
    private val backend: GlassesBackend,
) {
    /**
     * Streams frames for the duration of the burst. Suspends until the burst
     * ends. Returns the number of frames delivered.
     */
    suspend fun runBurst(
        maxDurationMs: Long,
        maxFrames: Int = DEFAULT_MAX_FRAMES,
        onFrame: (ByteArray) -> Unit,
    ): Int {
        var count = 0
        withTimeoutOrNull(maxDurationMs) {
            backend.cameraFrames()
                .take(maxFrames)
                .collectFrames { frame ->
                    onFrame(frame)
                    count++
                }
        }
        Log.i(TAG, "vision burst finished after $count frame(s)")
        return count
    }

    private suspend fun Flow<ByteArray>.collectFrames(block: (ByteArray) -> Unit) =
        collect { block(it) }

    private companion object {
        const val TAG = "GlassesCameraSource"
        // ~1 fps over a 20s cap => ~20 frames; cap slightly above to be safe.
        const val DEFAULT_MAX_FRAMES = 24
    }
}
