package com.geno.veyra.ui

import android.graphics.BitmapFactory
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geno.veyra.glasses.real.RealGlassesBackend
import com.geno.veyra.glasses.CameraPermission
import com.geno.veyra.settings.CameraResolution
import com.geno.veyra.settings.SettingsRepository
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * User-visible camera state, kept as data (not raw strings) so the
 * composable can render it through string resources.
 */
sealed interface CameraTestStatus {
    data object Ready : CameraTestStatus
    data object Starting : CameraTestStatus
    data object PermissionNeeded : CameraTestStatus
    data object Stopped : CameraTestStatus
    data object Error : CameraTestStatus
    data object PhotoCaptured : CameraTestStatus
    data object CaptureFailed : CameraTestStatus
    data object RecordingFailed : CameraTestStatus
    data object VideoSaved : CameraTestStatus
    data object VideoSavedNoAudio : CameraTestStatus
    data object RecordingStopped : CameraTestStatus
    data class Live(val width: Int, val height: Int) : CameraTestStatus
    data class Recording(val durationText: String) : CameraTestStatus
}

/**
 * User-visible camera error, kept as data so the composable can render it
 * through string resources. [Runtime] carries an unexpected exception
 * message, which is intentionally never translated.
 */
sealed interface CameraTestError {
    data object PermissionNeeded : CameraTestError
    data object JpegDecodeFailed : CameraTestError
    data object NoVideoFile : CameraTestError
    data class RecorderFrame(val detail: String) : CameraTestError
    data class Runtime(val message: String) : CameraTestError
}

data class CameraTestUiState(
    val status: CameraTestStatus = CameraTestStatus.Ready,
    val streaming: Boolean = false,
    val capturing: Boolean = false,
    val recording: Boolean = false,
    val recordingDurationMs: Long = 0L,
    val capturedPhoto: android.graphics.Bitmap? = null,
    val savedVideoUri: android.net.Uri? = null,
    val error: CameraTestError? = null,
)

@HiltViewModel
class CameraTestViewModel @Inject constructor(
    private val backend: RealGlassesBackend,
    private val settings: SettingsRepository,
    private val videoRecorder: CameraVideoRecorder,
) : ViewModel() {

    private val _uiState =
        MutableStateFlow(
            CameraTestUiState(),
        )

    val uiState:
        StateFlow<CameraTestUiState> =
        _uiState.asStateFlow()

    private val _frames =
        MutableSharedFlow<VideoFrame>(
            replay = 1,
            extraBufferCapacity = 8,
        )

    val frames:
        SharedFlow<VideoFrame> =
        _frames.asSharedFlow()

    private var previewJob: Job? = null

    private var recordingStartTimeMs = 0L

    private var recordingTimerJob: Job? = null

    private var totalFrameCount = 0L
    private var recordingFrameCount = 0L

    // =========================================================================
    // CAMERA PREVIEW
    // =========================================================================

    fun startPreview() {

        if (previewJob?.isActive == true) {
            Log.d(
                TAG,
                "startPreview ignored - preview already active",
            )
            return
        }

        Log.i(
            TAG,
            "Starting camera preview",
        )

        totalFrameCount = 0L
        recordingFrameCount = 0L

        _uiState.value =
            _uiState.value.copy(
                status = CameraTestStatus.Starting,
                streaming = false,
                error = null,
            )

        previewJob =
            viewModelScope.launch {

                try {

                    /*
                     * The Meta Wearables CAMERA permission is NOT
                     * requested by cameraTestFrames() — it only checks.
                     *
                     * Request it here (the assistant flow has its own
                     * separate prompt, which is why the Camera Test
                     * screen silently showed "Camera stopped" until the
                     * assistant had been run once).
                     *
                     * requestCameraPermission() is a no-op prompt when
                     * the permission is already granted.
                     */
                    val cameraPermission =
                        backend.requestCameraPermission()

                    if (
                        cameraPermission !=
                            CameraPermission.GRANTED
                    ) {

                        Log.w(
                            TAG,
                            "Camera Test: Meta camera permission " +
                                "not granted ($cameraPermission)",
                        )

                        _uiState.value =
                            _uiState.value.copy(
                                status = CameraTestStatus.PermissionNeeded,
                                error = CameraTestError.PermissionNeeded,
                                streaming = false,
                            )

                        return@launch
                    }

                    /*
                     * Read the latest persisted Camera Test settings
                     * immediately before creating the MWDAT camera stream.
                     *
                     * This means changes made in Camera Settings apply
                     * the next time Start Camera is pressed.
                     */
                    val preferences =
                        settings.snapshot()

                    val videoQuality =
                        preferences.cameraResolution.toVideoQuality()

                    val frameRate =
                        preferences.cameraFrameRate

                    Log.i(
                        TAG,
                        "Applying Camera Test settings: " +
                            "resolution=${preferences.cameraResolution.label}, " +
                            "quality=$videoQuality, " +
                            "fps=$frameRate",
                    )

                    /*
                     * IMPORTANT:
                     *
                     * This only configures the Camera Test stream.
                     *
                     * RealGlassesBackend.cameraFrames() — the Gemini
                     * vision path — remains independently fixed at
                     * MEDIUM / 24 FPS.
                     */
                    backend.setCameraTestConfiguration(
                        videoQuality = videoQuality,
                        frameRate = frameRate,
                    )

                    backend.cameraTestFrames()
                        .collect { frame ->

                            totalFrameCount++

                            if (
                                totalFrameCount == 1L
                            ) {
                                Log.i(
                                    TAG,
                                    "FIRST CAMERA FRAME received " +
                                        "size=${frame.buffer.remaining()} " +
                                        "resolution=${frame.width}x${frame.height} " +
                                        "timestampUs=${frame.presentationTimeUs} " +
                                        "compressed=${frame.isCompressed} " +
                                        "codecConfig=${frame.isCodecConfig}",
                                )
                            }

                            if (
                                frame.isCodecConfig
                            ) {
                                Log.d(
                                    TAG,
                                    "Codec config frame received " +
                                        "size=${frame.buffer.remaining()} " +
                                        "timestampUs=${frame.presentationTimeUs}",
                                )
                            }

                            if (!frame.isCodecConfig) {

                                _uiState.value =
                                    _uiState.value.copy(
                                        status =
                                            if (
                                                _uiState.value.recording
                                            ) {
                                                CameraTestStatus.Recording(
                                                    formatDuration(
                                                        _uiState.value.recordingDurationMs
                                                    ),
                                                )
                                            } else {
                                                CameraTestStatus.Live(
                                                    frame.width,
                                                    frame.height,
                                                )
                                            },
                                        streaming = true,
                                        error = null,
                                    )
                            }

                            if (
                                _uiState.value.recording
                            ) {

                                recordingFrameCount++

                                if (
                                    recordingFrameCount == 1L
                                ) {
                                    Log.i(
                                        TAG,
                                        "FIRST RECORDING FRAME -> recorder " +
                                            "size=${frame.buffer.remaining()} " +
                                            "resolution=${frame.width}x${frame.height} " +
                                            "timestampUs=${frame.presentationTimeUs} " +
                                            "compressed=${frame.isCompressed} " +
                                            "codecConfig=${frame.isCodecConfig}",
                                    )
                                }

                                if (
                                    recordingFrameCount % 30L == 0L
                                ) {
                                    Log.i(
                                        TAG,
                                        "Recording frame #$recordingFrameCount " +
                                            "totalFrame=$totalFrameCount " +
                                            "size=${frame.buffer.remaining()} " +
                                            "timestampUs=${frame.presentationTimeUs} " +
                                            "codecConfig=${frame.isCodecConfig}",
                                    )
                                }

                                try {

                                    videoRecorder.writeFrame(frame)

                                } catch (e: Exception) {

                                    Log.e(
                                        TAG,
                                        "videoRecorder.writeFrame() FAILED " +
                                            "at recordingFrame=$recordingFrameCount",
                                        e,
                                    )

                                    _uiState.value =
                                        _uiState.value.copy(
                                            error =
                                                CameraTestError.RecorderFrame(
                                                    e.message
                                                        ?: e::class.java.simpleName,
                                                ),
                                        )
                                }
                            }

                            _frames.emit(frame)
                        }

                    Log.i(
                        TAG,
                        "cameraTestFrames() flow completed " +
                            "totalFrames=$totalFrameCount " +
                            "recordingFrames=$recordingFrameCount",
                    )

                    if (
                        _uiState.value.recording
                    ) {
                        stopRecording()
                    }

                    _uiState.value =
                        _uiState.value.copy(
                            status = CameraTestStatus.Stopped,
                            streaming = false,
                        )

                } catch (
                    e: kotlinx.coroutines.CancellationException
                ) {

                    Log.d(
                        TAG,
                        "Camera preview coroutine cancelled",
                    )

                    throw e

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Camera preview failed",
                        e,
                    )

                    if (
                        _uiState.value.recording
                    ) {
                        videoRecorder.cancel()
                        stopRecordingTimer()
                    }

                    _uiState.value =
                        _uiState.value.copy(
                            status = CameraTestStatus.Error,
                            streaming = false,
                            recording = false,
                            error =
                                CameraTestError.Runtime(
                                    e.message
                                        ?: e::class.java.simpleName,
                                ),
                        )
                }
            }
    }

    // =========================================================================
    // PHOTO
    // =========================================================================

    fun capturePhoto() {

        if (_uiState.value.capturing) {
            return
        }

        if (!_uiState.value.streaming) {
            return
        }

        if (_uiState.value.recording) {
            return
        }

        Log.i(
            TAG,
            "Capture photo requested",
        )

        viewModelScope.launch {

            _uiState.value =
                _uiState.value.copy(
                    capturing = true,
                    error = null,
                )

            try {

                val result =
                    backend.captureCameraTestPhoto()

                result
                    .onSuccess { jpeg ->

                        val bitmap =
                            BitmapFactory.decodeByteArray(
                                jpeg,
                                0,
                                jpeg.size,
                            )

                        if (bitmap == null) {

                            Log.e(
                                TAG,
                                "Photo captured but JPEG decode failed",
                            )

                            _uiState.value =
                                _uiState.value.copy(
                                    capturing = false,
                                    error = CameraTestError.JpegDecodeFailed,
                                )

                        } else {

                            Log.i(
                                TAG,
                                "Photo captured successfully " +
                                    "bytes=${jpeg.size}",
                            )

                            _uiState.value =
                                _uiState.value.copy(
                                    capturing = false,
                                    capturedPhoto = bitmap,
                                    status = CameraTestStatus.PhotoCaptured,
                                    error = null,
                                )
                        }
                    }
                    .onFailure { error ->

                        Log.e(
                            TAG,
                            "Photo capture failed",
                            error,
                        )

                        _uiState.value =
                            _uiState.value.copy(
                                capturing = false,
                                error =
                                    CameraTestError.Runtime(
                                        error.message
                                            ?: error::class.java.simpleName,
                                    ),
                                status = CameraTestStatus.CaptureFailed,
                            )
                    }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Photo capture threw exception",
                    e,
                )

                _uiState.value =
                    _uiState.value.copy(
                        capturing = false,
                        error =
                            CameraTestError.Runtime(
                                e.message
                                    ?: e::class.java.simpleName,
                            ),
                        status = CameraTestStatus.CaptureFailed,
                    )
            }
        }
    }

    // =========================================================================
    // VIDEO RECORDING
    // =========================================================================

    fun startRecording() {

        if (_uiState.value.recording) {
            Log.d(
                TAG,
                "startRecording ignored - already recording",
            )
            return
        }

        if (!_uiState.value.streaming) {
            Log.w(
                TAG,
                "startRecording ignored - camera is not streaming",
            )
            return
        }

        if (_uiState.value.capturing) {
            Log.w(
                TAG,
                "startRecording ignored - photo capture in progress",
            )
            return
        }

        Log.i(
            TAG,
            "Starting video recording " +
                "totalFramesBeforeRecording=$totalFrameCount",
        )

        recordingFrameCount = 0L

        try {

            videoRecorder.start()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "videoRecorder.start() FAILED",
                e,
            )

            _uiState.value =
                _uiState.value.copy(
                    error =
                        CameraTestError.Runtime(
                            e.message
                                ?: e::class.java.simpleName,
                        ),
                    status = CameraTestStatus.RecordingFailed,
                )

            return
        }

        recordingStartTimeMs =
            System.currentTimeMillis()

        _uiState.value =
            _uiState.value.copy(
                recording = true,
                recordingDurationMs = 0L,
                savedVideoUri = null,
                error = null,
                status = CameraTestStatus.Recording(formatDuration(0L)),
            )

        Log.i(
            TAG,
            "Recording state active; waiting for camera frames",
        )

        recordingTimerJob =
            viewModelScope.launch {

                while (true) {

                    kotlinx.coroutines.delay(250L)

                    val elapsed =
                        System.currentTimeMillis() -
                            recordingStartTimeMs

                    _uiState.value =
                        _uiState.value.copy(
                            recordingDurationMs = elapsed,
                            status =
                                CameraTestStatus.Recording(
                                    formatDuration(elapsed),
                                ),
                        )
                }
            }
    }

    fun stopRecording() {

        if (!_uiState.value.recording) {
            Log.d(
                TAG,
                "stopRecording ignored - not recording",
            )
            return
        }

        val elapsed =
            System.currentTimeMillis() -
                recordingStartTimeMs

        Log.i(
            TAG,
            "Stopping video recording " +
                "elapsedMs=$elapsed " +
                "recordingFrames=$recordingFrameCount " +
                "totalFrames=$totalFrameCount " +
                "recorderActive=${videoRecorder.isRecording()}",
        )

        stopRecordingTimer()

        val uri =
            try {
                videoRecorder.stop()
            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "videoRecorder.stop() FAILED",
                    e,
                )

                null
            }

        Log.i(
            TAG,
            "videoRecorder.stop() returned uri=$uri",
        )

        _uiState.value =
            _uiState.value.copy(
                recording = false,
                recordingDurationMs = 0L,
                savedVideoUri = uri,
                status =
                    if (uri != null) {
                        if (videoRecorder.lastRecordingHadAudio()) {
                            CameraTestStatus.VideoSaved
                        } else {
                            CameraTestStatus.VideoSavedNoAudio
                        }
                    } else {
                        CameraTestStatus.RecordingStopped
                    },
                error =
                    if (uri == null) {
                        CameraTestError.NoVideoFile
                    } else {
                        null
                    },
            )
    }

    // =========================================================================
    // STOP PREVIEW
    // =========================================================================

    fun dismissPhoto() {
        _uiState.value =
            _uiState.value.copy(
                capturedPhoto = null,
            )
    }

    fun stopPreview() {

        Log.i(
            TAG,
            "Stopping camera preview " +
                "totalFrames=$totalFrameCount " +
                "recordingFrames=$recordingFrameCount",
        )

        if (_uiState.value.recording) {
            stopRecording()
        }

        _uiState.value =
            _uiState.value.copy(
                status = CameraTestStatus.Stopped,
                streaming = false,
                capturing = false,
                recording = false,
            )

        previewJob?.cancel()
        previewJob = null

        backend.stopCameraTest()
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private fun stopRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
    }

    private fun formatDuration(
        durationMs: Long,
    ): String {

        val totalSeconds =
            durationMs / 1_000L

        val minutes =
            totalSeconds / 60L

        val seconds =
            totalSeconds % 60L

        return String.format(
            "%02d:%02d",
            minutes,
            seconds,
        )
    }

    /**
     * Convert the app's persisted camera resolution setting to
     * the corresponding MWDAT VideoQuality.
     *
     * MWDAT's stream dimensions are portrait:
     *
     * LOW    = 360 × 640
     * MEDIUM = 504 × 896
     * HIGH   = 720 × 1280
     */
    private fun CameraResolution.toVideoQuality():
        VideoQuality {

        return when (this) {

            CameraResolution.LOW ->
                VideoQuality.LOW

            CameraResolution.MEDIUM ->
                VideoQuality.MEDIUM

            CameraResolution.HIGH ->
                VideoQuality.HIGH
        }
    }

    // =========================================================================
    // LIFECYCLE
    // =========================================================================

    override fun onCleared() {

        Log.i(
            TAG,
            "CameraTestViewModel cleared " +
                "totalFrames=$totalFrameCount " +
                "recordingFrames=$recordingFrameCount",
        )

        stopRecordingTimer()

        if (videoRecorder.isRecording()) {
            videoRecorder.cancel()
        }

        previewJob?.cancel()

        backend.stopCameraTest()

        super.onCleared()
    }

    private companion object {
        const val TAG = "CameraTestViewModel"
    }
}
