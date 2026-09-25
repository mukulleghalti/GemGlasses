package com.geno.veyra.wakeword

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresPermission
import com.geno.veyra.audio.MicStreamer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [WakeWordEngine] backed by Vosk — fully on-device, no account or API key.
 *
 * The ~40 MB acoustic model is downloaded once on first use (never committed
 * to git) and unzipped under [Context.getFilesDir]. Audio comes from
 * [MicStreamer], so while the glasses are the routed communication device the
 * wake word is heard through the glasses microphone.
 *
 * Model loading takes ~1-2 s and always runs off the main thread. Both
 * [Model] and [Recognizer] are closed when listening stops.
 */
@Singleton
class VoskWakeWordEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val micStreamer: MicStreamer,
    private val http: OkHttpClient,
) : WakeWordEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val startMutex = Mutex()

    private val _detections = MutableSharedFlow<String>(extraBufferCapacity = 1)
    override val detections: SharedFlow<String> = _detections.asSharedFlow()

    private val _modelState: MutableStateFlow<WakeWordModelState> =
        MutableStateFlow(WakeWordModelState.NotDownloaded)
    override val modelState: StateFlow<WakeWordModelState> = _modelState.asStateFlow()

    private var listenJob: Job? = null
    private var activePhrase: String? = null
    private var lastEmitMs = 0L

    init {
        // Surface a cached model immediately so Settings can show "ready"
        // without waiting for the first start().
        if (isModelReady(modelDir())) {
            _modelState.value = WakeWordModelState.Ready
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun start(phrase: String) {
        startMutex.withLock {
            if (listenJob?.isActive == true && activePhrase == phrase) return

            // Hold the mutex across the (potentially long) model download so a
            // concurrent stop() cannot leave a half-started listener behind.
            stopLocked()

            val dir = ensureModel()
            activePhrase = phrase

            listenJob = scope.launch(Dispatchers.IO) {
                try {
                    listenLoop(dir, phrase)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "wake-word listen loop failed", e)
                }
            }
        }
    }

    override suspend fun stop() {
        startMutex.withLock { stopLocked() }
    }

    private fun stopLocked() {
        // Cancellation is enough: listenLoop's finally blocks close the Vosk
        // handles, and the callbackFlow stops the AudioRecord.
        listenJob?.cancel()
        listenJob = null
        activePhrase = null
    }

    // Callers hold RECORD_AUDIO (the engine is only started when the
    // permission is granted); the annotation lives on start().
    @SuppressLint("MissingPermission")
    private suspend fun listenLoop(modelDir: File, phrase: String) {
        // Heavy native init (~1-2 s). We are already on Dispatchers.IO.
        val model = Model(modelDir.absolutePath)
        try {
            val recognizer = Recognizer(model, SAMPLE_RATE_HZ, grammarJson(phrase))
            try {
                micStreamer.stream().collect { chunk ->
                    processChunk(recognizer, phrase, chunk)
                }
            } finally {
                recognizer.close()
            }
        } finally {
            model.close()
        }
    }

    private suspend fun processChunk(
        recognizer: Recognizer,
        phrase: String,
        chunk: ByteArray,
    ) {
        recognizer.acceptWaveForm(chunk, chunk.size)

        val partial = runCatching {
            JSONObject(recognizer.partialResult).optString("partial")
        }.getOrDefault("")

        if (partial.contains(phrase, ignoreCase = true)) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastEmitMs > EMIT_COOLDOWN_MS) {
                lastEmitMs = now
                Log.i(TAG, "wake word detected (heard \"$partial\")")
                _detections.emit(phrase)
            }
        }
    }

    /**
     * Returns the model directory, downloading and unzipping it first when
     * needed. Updates [modelState] along the way; throws on failure.
     */
    private suspend fun ensureModel(): File = withContext(Dispatchers.IO) {
        val dir = modelDir()
        if (isModelReady(dir)) {
            _modelState.value = WakeWordModelState.Ready
            return@withContext dir
        }

        Log.i(TAG, "downloading wake-word model (~40 MB)")
        _modelState.value = WakeWordModelState.Downloading(0f)

        val zipFile = File(context.filesDir, MODEL_ZIP_NAME)
        try {
            download(zipFile)
            unzip(zipFile, context.filesDir)
            zipFile.delete()

            if (!isModelReady(dir)) {
                throw IOException("model files missing after unzip")
            }
            _modelState.value = WakeWordModelState.Ready
            dir
        } catch (e: Exception) {
            zipFile.delete()
            val message = e.message ?: "download failed"
            _modelState.value = WakeWordModelState.Error(message)
            Log.e(TAG, "wake-word model download failed", e)
            throw e
        }
    }

    private suspend fun download(zipFile: File) {
        val request = Request.Builder().url(MODEL_URL).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("model download HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("empty download body")
            val total = body.contentLength()

            body.byteStream().use { input ->
                FileOutputStream(zipFile).use { output ->
                    val buf = ByteArray(8192)
                    var done = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        val progress =
                            if (total > 0) done.toFloat() / total else -1f
                        _modelState.value = WakeWordModelState.Downloading(progress)
                    }
                }
            }
        }
    }

    /** Unzips with a zip-slip guard; entries must stay inside [destDir]. */
    private fun unzip(zipFile: File, destDir: File) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val out = File(destDir, entry.name)
                if (!out.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) {
                    throw IOException("zip entry outside destination: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fileOut -> zip.copyTo(fileOut) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun modelDir(): File = File(context.filesDir, MODEL_DIR_NAME)

    private fun isModelReady(dir: File): Boolean =
        dir.isDirectory && File(dir, "am/final.mdl").exists()

    private fun grammarJson(phrase: String): String {
        // Grammar-constrained decoding: only the wake phrase (plus [unk] for
        // everything else) is in the search graph — fast and low false-accept.
        val clean = phrase.replace("\"", "").trim()
        return "[\"$clean\", \"[unk]\"]"
    }

    private companion object {
        const val TAG = "VoskWakeWordEngine"
        const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        const val MODEL_DIR_NAME = "vosk-model-small-en-us-0.15"
        const val MODEL_ZIP_NAME = "vosk-model-small-en-us-0.15.zip"
        const val SAMPLE_RATE_HZ = 16000.0f
        const val EMIT_COOLDOWN_MS = 3_000L
    }
}
