package com.lpecom.gemglasses.ui

import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.camera.types.VideoFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.dp

@Composable
fun CameraTestScreen(
    onBack: () -> Unit,
    viewModel: CameraTestViewModel = hiltViewModel(),
) {
    /*
     * IMPORTANT:
     *
     * Use the actual StateFlow directly.
     *
     * Do NOT use the old custom
     * collectAsStateWithLifecycleCompat() helper.
     */
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val context = LocalContext.current

    val decoder = remember {
        H265SurfaceDecoder()
    }

    var surfaceReady by remember {
        mutableStateOf(false)
    }

    /*
     * ---------------------------------------------------------
     * BACK
     * ---------------------------------------------------------
     */

    BackHandler {
        viewModel.stopPreview()
        decoder.release()
        onBack()
    }

    /*
     * ---------------------------------------------------------
     * START CAMERA
     * ---------------------------------------------------------
     *
     * We wait until SurfaceView has a valid Surface before
     * starting the MWDAT camera stream.
     */

    LaunchedEffect(surfaceReady) {
        if (surfaceReady) {
            viewModel.startPreview()
        }
    }

    /*
     * ---------------------------------------------------------
     * VIDEO FRAME PIPELINE
     * ---------------------------------------------------------
     *
     * CameraTestViewModel exposes:
     *
     *     frames: Flow<VideoFrame>
     *
     * MWDAT gives us compressed H.265 frames.
     *
     * The decoder renders those frames directly into the
     * SurfaceView.
     */

    LaunchedEffect(Unit) {
        viewModel.frames.collect { frame ->

            withContext(Dispatchers.Default) {
                decoder.queue(frame)
            }
        }
    }

    /*
     * ---------------------------------------------------------
     * CLEANUP
     * ---------------------------------------------------------
     */

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopPreview()
            decoder.release()
        }
    }

    /*
     * ---------------------------------------------------------
     * SCREEN
     * ---------------------------------------------------------
     */

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {

        /*
         * -----------------------------------------------------
         * HEADER
         * -----------------------------------------------------
         */

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surface
                )
                .padding(
                    horizontal = 16.dp,
                    vertical = 12.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {

            Button(
                onClick = {
                    viewModel.stopPreview()
                    decoder.release()
                    onBack()
                },
            ) {
                Text("Back")
            }

            Spacer(
                modifier = Modifier.size(12.dp)
            )

            Column {

                Text(
                    text = "Camera Test",
                    style = MaterialTheme.typography.titleLarge,
                )

                Text(
                    text = uiState.status,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (uiState.error == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                )
            }
        }

        /*
         * -----------------------------------------------------
         * LIVE PREVIEW
         * -----------------------------------------------------
         */

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {

            AndroidView(
                modifier = Modifier.fillMaxSize(),

                factory = {

                    SurfaceView(context).also { surfaceView ->

                        surfaceView.holder.addCallback(
                            object : SurfaceHolder.Callback {

                                override fun surfaceCreated(
                                    holder: SurfaceHolder,
                                ) {

                                    if (
                                        holder.surface.isValid
                                    ) {

                                        decoder.setSurface(
                                            holder.surface
                                        )

                                        surfaceReady = true
                                    }
                                }

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int,
                                ) {

                                    if (
                                        holder.surface.isValid
                                    ) {

                                        decoder.setSurface(
                                            holder.surface
                                        )

                                        surfaceReady = true
                                    }
                                }

                                override fun surfaceDestroyed(
                                    holder: SurfaceHolder,
                                ) {

                                    surfaceReady = false

                                    decoder.clearSurface()
                                }
                            }
                        )
                    }
                },
            )

            if (
                !surfaceReady &&
                uiState.capturedPhoto == null
            ) {

                CircularProgressIndicator()
            }
        }

        /*
         * -----------------------------------------------------
         * CAPTURE RESULT
         * -----------------------------------------------------
         */

        uiState.capturedPhoto?.let { bitmap ->

            CapturedPhotoPreview(
                bitmap = bitmap,
            )
        }

        /*
         * -----------------------------------------------------
         * ERROR
         * -----------------------------------------------------
         */

        uiState.error?.let { error ->

            Text(
                text = error,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.errorContainer
                    )
                    .padding(12.dp),
                color =
                    MaterialTheme.colorScheme.onErrorContainer,
                style =
                    MaterialTheme.typography.bodySmall,
            )
        }

        /*
         * -----------------------------------------------------
         * CAPTURE BUTTON
         * -----------------------------------------------------
         */

        Button(
            onClick = viewModel::capturePhoto,

            enabled =
    uiState.streaming &&
        !uiState.capturing,

            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .height(56.dp),
        ) {

            if (uiState.capturing) {

                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                )

            } else {

                Text("Capture Photo")
            }
        }
    }
}

/*
 * =============================================================
 * CAPTURED PHOTO
 * =============================================================
 */

@Composable
private fun CapturedPhotoPreview(
    bitmap: Bitmap,
) {

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface
            )
            .padding(12.dp),

        verticalArrangement =
            Arrangement.spacedBy(8.dp),
    ) {

        Text(
            text = "Captured Photo",
            style = MaterialTheme.typography.titleMedium,
        )

        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Captured photo",
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
        )
    }
}

/*
 * =============================================================
 * H.265 MEDIA CODEC DECODER
 * =============================================================
 *
 * MWDAT 0.9.0's VideoFrame exposes compressed video data.
 *
 * The frame contains:
 *
 *     buffer
 *     width
 *     height
 *     presentationTimeUs
 *     isCompressed
 *     isCodecConfig
 *
 * MWDAT's camera stream uses H.265/HEVC.
 *
 * We therefore decode the compressed frames using Android's
 * MediaCodec and render directly to the SurfaceView.
 *
 * This is completely independent from Gemini and audio.
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
             * The camera stream is configured at 24/30 FPS
             * depending on the MWDAT configuration.
             *
             * This value is only a decoder hint.
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
                     * Render decoded frame to the configured
                     * Surface.
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
