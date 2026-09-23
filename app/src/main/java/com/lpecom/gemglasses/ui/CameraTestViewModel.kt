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
    val photoSaved: Boolean = false,
)

@HiltViewModel
class CameraTestViewModel @Inject constructor(
    private val backend: RealGlassesBackend,
    private val mediaSaver: CameraMediaSaver,
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

        if (previewJob?.isActive == true) {
            return
        }

        _uiState.value =
            _uiState.value.copy(
                status = "Starting camera…",
                streaming = false,
                error = null,
                photoSaved = false,
            )

        previewJob =
            viewModelScope.launch {

                try {

                    backend
                        .cameraTestFrames()
                        .collect { frame ->

                            if (!frame.isCodecConfig) {

                                _uiState.value =
                                    _uiState.value.copy(
                                        status =
                                            "Live — " +
                                                "${frame.width} × " +
                                                "${frame.height}",

                                        streaming = true,
                                        error = null,
                                    )
                            }

                            _frames.emit(frame)
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
                    photoSaved = false,
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

                            return@onSuccess
                        }

                        /*
                         * Save the exact JPEG returned by MWDAT.
                         *
                         * This means the Gallery copy is not a
                         * recompressed version of the preview bitmap.
                         */
                        val saveResult =
                            mediaSaver.savePhoto(jpeg)

                        saveResult
                            .onSuccess { fileName ->

                                _uiState.value =
                                    _uiState.value.copy(
                                        capturing = false,
                                        capturedPhoto = bitmap,
                                        status =
                                            "Photo saved to Gallery",
                                        error = null,
                                        photoSaved = true,
                                    )
                            }
                            .onFailure { error ->

                                _uiState.value =
                                    _uiState.value.copy(
                                        capturing = false,
                                        capturedPhoto = bitmap,
                                        status =
                                            "Photo captured",
                                        error =
                                            "Photo captured, " +
                                                "but Gallery save failed: " +
                                                (
                                                    error.message
                                                        ?: error::class.java.simpleName
                                                ),
                                        photoSaved = false,
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
