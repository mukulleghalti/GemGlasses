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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.meta.wearable.dat.camera.types.VideoFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

@Composable
fun CameraTestScreen(
    onBack: () -> Unit,
    viewModel: CameraTestViewModel = hiltViewModel(),
) {

    val uiState by viewModel.uiState.collectAsStateWithLifecycleCompat()

    val context =
        LocalContext.current

    val decoder =
        remember {
            H265SurfaceDecoder()
        }

    var surfaceReady by
        remember {
            mutableStateOf(false)
        }

    BackHandler {

        viewModel.stopPreview()

        decoder.release()

        onBack()
    }

    /*
     * Start the camera only after SurfaceView has a real Surface.
     */
    LaunchedEffect(surfaceReady) {

        if (surfaceReady) {

            viewModel.startPreview()
        }
    }

    /*
     * Feed MWDAT VideoFrame objects into our H.265 MediaCodec decoder.
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {

        /*
         * ---------------------------------------------------------
         * Header
         * ---------------------------------------------------------
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
                    style =
                        MaterialTheme.typography.titleLarge,
                )

                Text(
                    text = uiState.status,
                    style =
                        MaterialTheme.typography.bodySmall,
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
         * ---------------------------------------------------------
         * Live preview
         * ---------------------------------------------------------
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

                                    decoder.setSurface(
                                        holder.surface
                                    )

                                    surfaceReady = true
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
         * ---------------------------------------------------------
         * Capture result
         * ---------------------------------------------------------
         */

        uiState.capturedPhoto?.let { bitmap ->

            CapturedPhotoPreview(
                bitmap = bitmap
            )
        }

        /*
         * ---------------------------------------------------------
         * Error
         * ---------------------------------------------------------
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
         * ---------------------------------------------------------
         * Capture button
         * ---------------------------------------------------------
         */

        Button(
            onClick = viewModel::capturePhoto,
            enabled =
                surfaceReady &&
                    !uiState.capturing,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .height(56.dp),
        ) {

            if (uiState.capturing) {

                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp)
                )

            } else {

                Text("Capture Photo")
            }
        }
    }
}

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
            style =
                MaterialTheme.typography.titleMedium,
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
 * H.265 MediaCodec decoder
 * =============================================================
 *
 * MWDAT 0.9.0's compressed VideoFrame contains:
 *
 *     buffer
 *     width
 *     height
 *     presentationTimeUs
 *     isCompressed
 *     isCodecConfig
 *
 * The AAR's own default VideoFormat uses H.265.
 *
 * We therefore decode the compressed stream directly into the
 * SurfaceView Surface.
 */
private class H265SurfaceDecoder {

    private val lock =
        Any()

    private var decoder:
        MediaCodec? = null

    private var surface:
        Surface? = null

    private var width = 0
    private var height = 0

    fun setSurface(
        newSurface: Surface,
    ) {

        synchronized(lock) {

            surface =
                newSurface

            /*
             * If the surface changes, the MediaCodec must be
             * recreated because it is configured against the
             * previous Surface.
             */
            releaseDecoderLocked()
        }
    }

    fun clearSurface() {

        synchronized(lock) {

            surface = null

            releaseDecoderLocked()
        }
    }

    fun queue(
        frame: VideoFrame,
    ) {

        if (!frame.isCompressed) {

            /*
             * This test screen intentionally requests
             * compressed video, so raw frames are ignored.
             */
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

            if (
                decoder == null ||
                width != frame.width ||
                height != frame.height
            ) {

                width =
                    frame.width

                height =
                    frame.height

                createDecoderLocked(
                    currentSurface,
                    width,
                    height,
                )
            }

            val activeDecoder =
                decoder
                    ?: return

            try {

                val inputIndex =
                    activeDecoder.dequeueInputBuffer(
                        0
                    )

                if (inputIndex < 0) {

                    return
                }

                val inputBuffer =
                    activeDecoder.getInputBuffer(
                        inputIndex
                    )
                    ?: return

                inputBuffer.clear()

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

                drainDecoderLocked(
                    activeDecoder
                )

            } catch (_: IllegalStateException) {

                releaseDecoderLocked()

            } catch (_: Exception) {

                /*
                 * A malformed/unsupported compressed frame should
                 * not crash the camera screen.
                 */
            }
        }
    }

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

            mediaFormat.setInteger(
                MediaFormat.KEY_FRAME_RATE,
                30,
            )

            val newDecoder =
                MediaCodec.createDecoderByType(
                    MIME_TYPE
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

                    activeDecoder.releaseOutputBuffer(
                        outputIndex,
                        true,
                    )
                }

                outputIndex ==
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {

                    /*
                     * Nothing else required for Surface output.
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

/*
 * Small local compatibility helper so this screen does not depend
 * on a particular lifecycle-compose extension version.
 */
@Composable
private fun <T> CameraTestViewModel.collectAsStateWithLifecycleCompat(
    selector: CameraTestViewModel.() -> kotlinx.coroutines.flow.StateFlow<T> =
        { uiState },
): androidx.compose.runtime.State<T> {

    val stateFlow =
        selector()

    return androidx.compose.runtime.collectAsState(
        stateFlow
    )
}
