package com.lpecom.gemglasses.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lpecom.gemglasses.glasses.real.RealGlassesBackend
import com.meta.wearable.dat.camera.types.VideoFrame
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
    val capturing: Boolean = false,
    val capturedPhoto: Bitmap? = null,
    val error: String? = null,
    val streaming: Boolean = false,
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

    fun startPreview() {

        if (previewJob?.isActive == true) {
            return
        }

        _uiState.value =
            _uiState.value.copy(
                status = "Starting camera…",
                streaming = false,
                capturing = false,
                error = null,
            )

        previewJob =
            viewModelScope.launch {

                try {

                    backend.cameraTestFrames()
                        .collect { frame ->

                            /*
                             * The first frame means the MWDAT camera
                             * is definitely active and streaming.
                             */

                            if (
                                !_uiState.value.streaming
                            ) {

                                _uiState.value =
                                    _uiState.value.copy(
                                        status =
                                            "Live — ${frame.width} × ${frame.height}",
                                        streaming = true,
                                        error = null,
                                    )

                            } else {

                                _uiState.value =
                                    _uiState.value.copy(
                                        status =
                                            "Live — ${frame.width} × ${frame.height}",
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
                            capturing = false,
                        )

                } catch (e: CancellationException) {

                    throw e

                } catch (e: Exception) {

                    _uiState.value =
                        _uiState.value.copy(
                            status = "Camera error",
                            streaming = false,
                            capturing = false,
                            error =
                                e.message
                                    ?: e::class.java.simpleName,
                        )
                }
            }
    }

    fun capturePhoto() {

        /*
         * Do not allow capture until the MWDAT camera has actually
         * produced a live frame.
         */

        if (!_uiState.value.streaming) {

            _uiState.value =
                _uiState.value.copy(
                    error =
                        "Camera is still starting. Wait for the live stream.",
                )

            return
        }

        if (_uiState.value.capturing) {
            return
        }

        viewModelScope.launch {

            _uiState.value =
                _uiState.value.copy(
                    capturing = true,
                    error = null,
                    status = "Capturing photo…",
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
                                    streaming = true,
                                    status = "Live",
                                    error =
                                        "Photo captured but could not decode JPEG",
                                )

                        } else {

                            _uiState.value =
                                _uiState.value.copy(
                                    capturing = false,
                                    capturedPhoto = bitmap,
                                    streaming = true,
                                    status = "Photo captured",
                                    error = null,
                                )
                        }
                    }
                    .onFailure { error ->

                        _uiState.value =
                            _uiState.value.copy(
                                capturing = false,
                                streaming = true,
                                status = "Live",
                                error =
                                    error.message
                                        ?: error::class.java.simpleName,
                            )
                    }

            } catch (e: Exception) {

                _uiState.value =
                    _uiState.value.copy(
                        capturing = false,
                        streaming = true,
                        status = "Live",
                        error =
                            e.message
                                ?: e::class.java.simpleName,
                    )
            }
        }
    }

    fun stopPreview() {

        previewJob?.cancel()
        previewJob = null

        backend.stopCameraTest()

        _uiState.value =
            _uiState.value.copy(
                status = "Camera stopped",
                streaming = false,
                capturing = false,
            )
    }

    override fun onCleared() {

        previewJob?.cancel()

        backend.stopCameraTest()

        super.onCleared()
    }
}
