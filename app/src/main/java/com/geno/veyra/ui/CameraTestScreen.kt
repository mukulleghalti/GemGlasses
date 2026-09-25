package com.geno.veyra.ui

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.camera.types.VideoFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Minimal camera test screen: full-bleed preview, a slim status bar on top,
 * and a single row of controls at the bottom.
 */
@Composable
fun CameraTestScreen(
    onBack: () -> Unit,
    onCameraSettingsClick: () -> Unit,
    viewModel: CameraTestViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val decoder = remember { H265SurfaceDecoder() }
    var surfaceReady by remember { mutableStateOf(false) }

    BackHandler {
        viewModel.stopPreview()
        decoder.resetDecoder()
        onBack()
    }

    /*
     * The same VideoFrame stream feeds the live H.265 preview and the
     * video recording. Recording itself is handled by CameraTestViewModel.
     */
    LaunchedEffect(Unit) {
        viewModel.frames.collect { frame ->
            withContext(Dispatchers.Default) {
                decoder.queue(frame)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopPreview()
            decoder.release()
        }
    }

    val cameraOn =
        uiState.streaming || uiState.status == "Starting camera…"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Full-bleed preview.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                SurfaceView(context).also { surfaceView ->
                    surfaceView.holder.addCallback(
                        object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                if (holder.surface.isValid) {
                                    decoder.setSurface(holder.surface)
                                    surfaceReady = true
                                }
                            }

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int,
                            ) {
                                if (holder.surface.isValid) {
                                    decoder.setSurface(holder.surface)
                                    surfaceReady = true
                                }
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                surfaceReady = false
                                decoder.clearSurface()
                            }
                        },
                    )
                }
            },
        )

        if (!surfaceReady) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        // Slim top bar: back + status.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    viewModel.stopPreview()
                    decoder.resetDecoder()
                    onBack()
                },
                enabled = !uiState.recording,
            ) {
                Text("Back", color = Color.White)
            }
            Text(
                text = uiState.status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (uiState.error == null) {
                    Color.White.copy(alpha = 0.8f)
                } else {
                    MaterialTheme.colorScheme.error
                },
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }

        // Recording badge.
        if (uiState.recording) {
            Text(
                text = "● REC ${formatRecordingDuration(uiState.recordingDurationMs)}",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 60.dp)
                    .background(
                        Color(0xFFC62828),
                        RoundedCornerShape(16.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }

        // Error banner.
        uiState.error?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 60.dp)
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        RoundedCornerShape(10.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        // Saved confirmation.
        if (uiState.savedVideoUri != null) {
            Text(
                text = "Video saved to Gallery",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 92.dp)
                    .background(
                        Color.Black.copy(alpha = 0.65f),
                        RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        // Captured photo thumbnail (tap ✕ to dismiss).
        uiState.capturedPhoto?.let { bitmap ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 92.dp),
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Captured photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(104.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                TextButton(
                    onClick = viewModel::dismissPhoto,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(36.dp),
                ) {
                    Text("✕", color = Color.White)
                }
            }
        }

        // Single bottom control row.
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ControlButton(
                label = if (cameraOn) "Stop" else "Start",
                onClick = {
                    if (cameraOn) {
                        viewModel.stopPreview()
                        decoder.resetDecoder()
                    } else {
                        viewModel.startPreview()
                    }
                },
                enabled = !uiState.recording && (cameraOn || surfaceReady),
                modifier = Modifier.weight(1f),
            )
            ControlButton(
                label = if (uiState.recording) "Stop" else "Record",
                onClick = {
                    if (uiState.recording) {
                        viewModel.stopRecording()
                    } else {
                        viewModel.startRecording()
                    }
                },
                enabled = uiState.recording ||
                    (uiState.streaming && !uiState.capturing),
                modifier = Modifier.weight(1f),
            )
            ControlButton(
                label = "Photo",
                onClick = viewModel::capturePhoto,
                enabled = uiState.streaming &&
                    !uiState.capturing &&
                    !uiState.recording,
                loading = uiState.capturing,
                modifier = Modifier.weight(1f),
            )
            ControlButton(
                label = "Settings",
                onClick = onCameraSettingsClick,
                enabled = !uiState.recording,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ControlButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = modifier.height(48.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Text(label, maxLines = 1)
        }
    }
}

private fun formatRecordingDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format("%02d:%02d", minutes, seconds)
}

/*
 * H.265 decoder: the MWDAT camera stream sends compressed HEVC frames, which
 * are decoded with Android's MediaCodec and rendered directly to the
 * SurfaceView. Independent from Gemini and audio.
 */
private class H265SurfaceDecoder {

    private val lock = Any()

    private var decoder: MediaCodec? = null

    private var surface: Surface? = null

    private var width = 0

    private var height = 0

    /*
     * ---------------------------------------------------------
     * SURFACE
     * ---------------------------------------------------------
     */

    fun setSurface(
        newSurface: Surface,
    ) {

        synchronized(lock) {

            /*
             * If this is a different Surface, recreate the
             * MediaCodec because it is configured against the
             * Surface.
             */

            if (
                surface !== newSurface
            ) {

                releaseDecoderLocked()
            }

            surface = newSurface
        }
    }

    fun clearSurface() {

        synchronized(lock) {

            surface = null

            releaseDecoderLocked()
        }
    }

    /*
     * ---------------------------------------------------------
     * RESET DECODER
     * ---------------------------------------------------------
     *
     * Used when stopping/restarting the camera.
     *
     * IMPORTANT:
     * We keep the Surface reference because the SurfaceView
     * itself has not been destroyed.
     *
     * The next incoming video frame will create a fresh
     * MediaCodec against the same Surface.
     */

    fun resetDecoder() {

        synchronized(lock) {

            releaseDecoderLocked()

            width = 0
            height = 0
        }
    }

    /*
     * ---------------------------------------------------------
     * QUEUE VIDEO FRAME
     * ---------------------------------------------------------
     */

    fun queue(
        frame: VideoFrame,
    ) {

        /*
         * Camera Test requests compressed video.
         *
         * Ignore raw frames.
         */

        if (!frame.isCompressed) {
            return
        }

        synchronized(lock) {

            val currentSurface =
                surface

            if (
                currentSurface == null ||
                !currentSurface.isValid
            ) {

                return
            }

            /*
             * Create/recreate decoder when resolution changes.
             */

            if (
                decoder == null ||
                width != frame.width ||
                height != frame.height
            ) {

                width = frame.width
                height = frame.height

                createDecoderLocked(
                    outputSurface = currentSurface,
                    frameWidth = width,
                    frameHeight = height,
                )
            }

            val activeDecoder =
                decoder
                    ?: return

            try {

                /*
                 * Find an input buffer.
                 */

                val inputIndex =
                    activeDecoder.dequeueInputBuffer(
                        0,
                    )

                if (inputIndex < 0) {
                    return
                }

                val inputBuffer =
                    activeDecoder.getInputBuffer(
                        inputIndex,
                    )
                        ?: return

                inputBuffer.clear()

                /*
                 * Never modify the original MWDAT ByteBuffer.
                 */

                val source =
                    frame.buffer.duplicate()

                val size =
                    source.remaining()

                if (
                    size <= 0 ||
                    size > inputBuffer.remaining()
                ) {

                    return
                }

                inputBuffer.put(source)

                /*
                 * Codec configuration frames need the
                 * BUFFER_FLAG_CODEC_CONFIG flag.
                 */

                val flags =
                    if (frame.isCodecConfig) {

                        MediaCodec.BUFFER_FLAG_CODEC_CONFIG

                    } else {

                        0
                    }

                activeDecoder.queueInputBuffer(
                    inputIndex,
                    0,
                    size,
                    frame.presentationTimeUs,
                    flags,
                )

                /*
                 * Drain decoded frames to SurfaceView.
                 */

                drainDecoderLocked(
                    activeDecoder,
                )

            } catch (_: IllegalStateException) {

                /*
                 * Codec can become invalid when the Surface is
                 * destroyed/recreated.
                 */

                releaseDecoderLocked()

            } catch (_: Exception) {

                /*
                 * Ignore malformed/unsupported frames.
                 *
                 * The next frame can attempt to continue the
                 * stream.
                 */
            }
        }
    }

    /*
     * ---------------------------------------------------------
     * CREATE DECODER
     * ---------------------------------------------------------
     */

    private fun createDecoderLocked(
        outputSurface: Surface,
        frameWidth: Int,
        frameHeight: Int,
    ) {

        releaseDecoderLocked()

        try {

            val mediaFormat =
                MediaFormat.createVideoFormat(
                    MIME_TYPE,
                    frameWidth,
                    frameHeight,
                )

            /*
             * Decoder hint.
             */

            mediaFormat.setInteger(
                MediaFormat.KEY_FRAME_RATE,
                30,
            )

            val newDecoder =
                MediaCodec.createDecoderByType(
                    MIME_TYPE,
                )

            newDecoder.configure(
                mediaFormat,
                outputSurface,
                null,
                0,
            )

            newDecoder.start()

            decoder =
                newDecoder

        } catch (_: Exception) {

            decoder = null
        }
    }

    /*
     * ---------------------------------------------------------
     * DRAIN DECODER
     * ---------------------------------------------------------
     */

    private fun drainDecoderLocked(
        activeDecoder: MediaCodec,
    ) {

        val bufferInfo =
            MediaCodec.BufferInfo()

        while (true) {

            val outputIndex =
                activeDecoder.dequeueOutputBuffer(
                    bufferInfo,
                    0,
                )

            when {

                outputIndex >= 0 -> {

                    /*
                     * Render decoded frame to SurfaceView.
                     */

                    activeDecoder.releaseOutputBuffer(
                        outputIndex,
                        true,
                    )
                }

                outputIndex ==
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {

                    /*
                     * Surface output does not require additional
                     * handling here.
                     */
                }

                outputIndex ==
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {

                    return
                }

                else -> {

                    return
                }
            }
        }
    }

    /*
     * ---------------------------------------------------------
     * RELEASE
     * ---------------------------------------------------------
     */

    fun release() {

        synchronized(lock) {

            surface = null

            releaseDecoderLocked()
        }
    }

    private fun releaseDecoderLocked() {

        val activeDecoder =
            decoder

        decoder = null

        if (activeDecoder != null) {

            try {
                activeDecoder.stop()
            } catch (_: Exception) {
            }

            try {
                activeDecoder.release()
            } catch (_: Exception) {
            }
        }
    }

    private companion object {

        const val MIME_TYPE =
            "video/hevc"
    }
}
