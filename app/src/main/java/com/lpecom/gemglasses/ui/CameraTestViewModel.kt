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
    val capturedPhoto: android.graphics.Bitmap? = null,
    val error: String? = null,
)

@HiltViewModel
class CameraTestViewModel @Inject constructor(
    private val backend: RealGlassesBackend,
) : ViewModel() {

    private val _uiState =
        MutableStateFlow(
            CameraTestUiState()
        )

    val uiState: StateFlow<CameraTestUiState> =
        _uiState.asStateFlow()

    private val _frames =
        MutableSharedFlow<VideoFrame>(
            replay = 1,
            extraBufferCapacity = 8,
        )

    val frames: SharedFlow<VideoFrame> =
        _frames.asSharedFlow()

    private var previewJob: Job? = null

    /*
     * ---------------------------------------------------------
     * START CAMERA
     * ---------------------------------------------------------
     */

    fun startPreview() {

        /*
         * Don't start another camera stream if one is already
         * running.
         */

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

                    backend
                        .cameraTestFrames()
                        .collect { frame ->

                            /*
                             * Codec configuration frames are
                             * required by MediaCodec, but they do
                             * NOT mean that an actual video frame
                             * has arrived yet.
                             *
                             * Therefore don't mark the camera
                             * as streaming on a codec-config frame.
                             */

                            if (!frame.isCodecConfig) {

                                _uiState.value =
                                    _uiState.value.copy(
                                        status =
                                            "Live — ${frame.width} × ${frame.height}",
                                        streaming = true,
                                        error = null,
                                    )
                            }

                            _frames.emit(frame)
                        }

                    /*
                     * Flow completed normally.
                     */

                    _uiState.value =
                        _uiState.value.copy(
                            status = "Camera stopped",
                            streaming = false,
                        )

                } catch (
                    e: kotlinx.coroutines.CancellationException
                ) {

                    /*
                     * Normal when the user presses Stop Camera
                     * or leaves the screen.
                     */

                    throw e

                } catch (e: Exception) {

                    _uiState.value =
                        _uiState.value.copy(
                            status = "Camera error",
                            streaming = false,
                            error =
                                e.message
                                    ?: e::class.java.simpleName,
                        )
                }
            }
    }

    /*
     * ---------------------------------------------------------
     * CAPTURE PHOTO
     * ---------------------------------------------------------
     */

    fun capturePhoto() {

        if (_uiState.value.capturing) {
            return
        }

        if (!_uiState.value.streaming) {
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

    /*
     * ---------------------------------------------------------
     * STOP CAMERA
     * ---------------------------------------------------------
     */

    fun stopPreview() {

        /*
         * Update UI immediately so the buttons change without
         * waiting for the backend flow to finish cancelling.
         */

        _uiState.value =
            _uiState.value.copy(
                status = "Camera stopped",
                streaming = false,
                capturing = false,
            )

        previewJob?.cancel()
        previewJob = null

        backend.stopCameraTest()
    }

    /*
     * ---------------------------------------------------------
     * CLEANUP
     * ---------------------------------------------------------
     */

    override fun onCleared() {

        previewJob?.cancel()

        backend.stopCameraTest()

        super.onCleared()
    }
}
