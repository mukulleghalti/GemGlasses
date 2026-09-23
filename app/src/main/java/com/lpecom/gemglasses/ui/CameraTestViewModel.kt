package com.lpecom.gemglasses.ui

import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lpecom.gemglasses.glasses.real.RealGlassesBackend
import com.meta.wearable.dat.camera.types.VideoFrame
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

data class CameraTestUiState(
    val status: String = "Ready",
    val streaming: Boolean = false,
    val capturing: Boolean = false,
    val recording: Boolean = false,
    val recordingDurationMs: Long = 0L,
    val capturedPhoto: android.graphics.Bitmap? = null,
    val savedVideoUri: android.net.Uri? = null,
    val error: String? = null,
)

@HiltViewModel
class CameraTestViewModel @Inject constructor(
    private val backend: RealGlassesBackend,
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

    fun startPreview() {

        if (previewJob?.isActive == true) {
            return
        }

        _uiState.value =
            _uiState.value.copy(
                status = "Starting camera…",
                streaming = false,
                error = null,
            )

        previewJob =
            viewModelScope.launch {

                try {

                    backend.cameraTestFrames()
                        .collect { frame ->

                            if (!frame.isCodecConfig) {

                                _uiState.value =
                                    _uiState.value.copy(
                                        status =
                                            if (
                                                _uiState.value.recording
                                            ) {
                                                "Recording — ${
                                                    formatDuration(
                                                        _uiState.value.recordingDurationMs
                                                    )
                                                }"
                                            } else {
                                                "Live — ${frame.width} × ${frame.height}"
                                            },
                                        streaming = true,
                                        error = null,
                                    )
                            }

                            if (
                                _uiState.value.recording
                            ) {
                                videoRecorder.writeFrame(frame)
                            }

                            _frames.emit(frame)
                        }

                    if (
                        _uiState.value.recording
                    ) {
                        stopRecording()
                    }

                    _uiState.value =
                        _uiState.value.copy(
                            status = "Camera stopped",
                            streaming = false,
                        )

                } catch (
                    e: kotlinx.coroutines.CancellationException
                ) {

                    throw e

                } catch (e: Exception) {

                    if (
                        _uiState.value.recording
                    ) {
                        videoRecorder.cancel()
                        stopRecordingTimer()
                    }

                    _uiState.value =
                        _uiState.value.copy(
                            status = "Camera error",
                            streaming = false,
                            recording = false,
                            error =
                                e.message
                                    ?: e::class.java.simpleName,
                        )
                }
            }
    }

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

                            _uiState.value =
                                _uiState.value.copy(
                                    capturing = false,
                                    error =
                                        "Photo captured but could not decode JPEG",
                                )

                        } else {

                            _uiState.value =
                                _uiState.value.copy(
                                    capturing = false,
                                    capturedPhoto = bitmap,
                                    status = "Photo captured",
                                    error = null,
                                )
                        }
                    }
                    .onFailure { error ->

                        _uiState.value =
                            _uiState.value.copy(
                                capturing = false,
                                error =
                                    error.message
                                        ?: error::class.java.simpleName,
                                status = "Capture failed",
                            )
                    }

            } catch (e: Exception) {

                _uiState.value =
                    _uiState.value.copy(
                        capturing = false,
                        error =
                            e.message
                                ?: e::class.java.simpleName,
                        status = "Capture failed",
                    )
            }
        }
    }

    fun startRecording() {

        if (_uiState.value.recording) {
            return
        }

        if (!_uiState.value.streaming) {
            return
        }

        if (_uiState.value.capturing) {
            return
        }

        videoRecorder.start()

        recordingStartTimeMs =
            System.currentTimeMillis()

        _uiState.value =
            _uiState.value.copy(
                recording = true,
                recordingDurationMs = 0L,
                savedVideoUri = null,
                error = null,
                status = "Recording — 00:00",
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
                                "Recording — ${
                                    formatDuration(elapsed)
                                }",
                        )
                }
            }
    }

    fun stopRecording() {

        if (!_uiState.value.recording) {
            return
        }

        stopRecordingTimer()

        val uri =
            videoRecorder.stop()

        _uiState.value =
            _uiState.value.copy(
                recording = false,
                recordingDurationMs = 0L,
                savedVideoUri = uri,
                status =
                    if (uri != null) {
                        "Video saved"
                    } else {
                        "Recording stopped"
                    },
                error =
                    if (uri == null) {
                        "No video file was created"
                    } else {
                        null
                    },
            )
    }

    fun stopPreview() {

        if (_uiState.value.recording) {
            stopRecording()
        }

        _uiState.value =
            _uiState.value.copy(
                status = "Camera stopped",
                streaming = false,
                capturing = false,
                recording = false,
            )

        previewJob?.cancel()
        previewJob = null

        backend.stopCameraTest()
    }

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

    override fun onCleared() {

        stopRecordingTimer()

        if (videoRecorder.isRecording()) {
            videoRecorder.cancel()
        }

        previewJob?.cancel()

        backend.stopCameraTest()

        super.onCleared()
    }
}
