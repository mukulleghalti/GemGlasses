package com.lpecom.gemglasses.wakeword

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Lifecycle of the wake-word model files on the device. */
sealed interface WakeWordModelState {

    /** Nothing on disk yet; the model downloads on first use. */
    data object NotDownloaded : WakeWordModelState

    /** A download is in progress; [progress] is 0..1 (-1 when unknown). */
    data class Downloading(val progress: Float) : WakeWordModelState

    /** Model files are present and the engine can start. */
    data object Ready : WakeWordModelState

    /** The last download or load attempt failed. */
    data class Error(val message: String) : WakeWordModelState
}

/**
 * Swappable seam for the always-on wake-word detector.
 *
 * Vosk is the current implementation; a future Porcupine or custom TFLite
 * engine can replace it without touching the service, coordinator, or UI.
 */
interface WakeWordEngine {

    /** Emits the matched phrase every time the wake word is detected. */
    val detections: Flow<String>

    /** Download/readiness state of the underlying model. */
    val modelState: StateFlow<WakeWordModelState>

    /**
     * Starts listening for [phrase]. No-op when already listening for the
     * same phrase. Downloads the model first if needed (can take a while).
     */
    suspend fun start(phrase: String)

    /** Stops listening and releases audio resources. */
    suspend fun stop()
}
