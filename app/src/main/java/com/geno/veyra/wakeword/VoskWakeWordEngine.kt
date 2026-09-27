package com.geno.veyra.wakeword

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresPermission
import com.geno.veyra.audio.MicStreamer
import com.geno.veyra.settings.AppLocaleStore
import com.geno.veyra.settings.wakeBaseLanguage
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
import kotlinx.coroutines.withTimeoutOrNull
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
    private var activeLang: String? = null
    private var activePattern: Regex? = null
    private var lastEmitMs = 0L

    init {
        // Surface a cached model immediately so Settings can show "ready"
        // without waiting for the first start().
        refreshModelState()
    }

    /**
     * Recomputes [modelState] for the current app language. Call when
     * Settings opens — the cached model may belong to another language.
     */
    fun refreshModelState() {
        val dir = modelDirFor(resolveLanguage())
        _modelState.value =
            if (isModelReady(dir)) {
                WakeWordModelState.Ready
            } else {
                WakeWordModelState.NotDownloaded
            }
    }

    /** Base language for the wake model, from the app language picker. */
    private fun resolveLanguage(): String =
        wakeBaseLanguage(AppLocaleStore.cachedAppLanguageTag(context))

    private fun modelDirFor(lang: String): File {
        val spec = MODEL_SPECS[lang] ?: MODEL_SPECS.getValue("en")
        return File(context.filesDir, spec.dirName)
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun start(phrase: String) {
        startMutex.withLock {
            val lang = resolveLanguage()
            if (
                listenJob?.isActive == true &&
                activePhrase == phrase &&
                activeLang == lang
            ) {
                return
            }

            stopLocked()
            startLocked(phrase, lang)
        }
    }

    /**
     * Starts listening; callers hold [startMutex] (the model download is
     * long, and the mutex keeps a concurrent stop() from leaving a
     * half-started listener behind).
     */
    private suspend fun startLocked(
        phrase: String,
        lang: String,
    ) {
        val dir = ensureModel(lang)
        activePhrase = phrase
        activeLang = lang
        activePattern = phrasePattern(phrase)

        listenJob = scope.launch(Dispatchers.IO) {
            try {
                listenLoop(dir)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "wake-word listen loop failed", e)
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
        activeLang = null
        activePattern = null
    }

    /**
     * Test mode: listens for [timeoutMs] with the current language's model
     * and reports whether [phrase] would have triggered, using the exact
     * same decoder and phrase matcher as live detection. Each decoded
     * fragment goes to [onPartial] so the UI can show what was heard.
     *
     * Any active wake-word listening is paused first (so only one model is
     * ever in memory) and resumed afterwards.
     *
     * Caller must hold RECORD_AUDIO.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun testDecode(
        phrase: String,
        timeoutMs: Long,
        onPartial: (String) -> Unit,
    ): Boolean =
        withContext(Dispatchers.IO) {
            startMutex.withLock {
                val lang = resolveLanguage()
                val resumePhrase = activePhrase
                val resumeLang = activeLang
                val wasListening = listenJob?.isActive == true
                stopLocked()
                try {
                    val dir = ensureModel(lang)
                    val pattern = phrasePattern(phrase)
                    var matched = false
                    val model = Model(dir.absolutePath)
                    try {
                        val recognizer = Recognizer(model, SAMPLE_RATE_HZ)
                        try {
                            withTimeoutOrNull(timeoutMs) {
                                micStreamer.stream().collect { chunk ->
                                    ensureActive()
                                    recognizer.acceptWaveForm(chunk, chunk.size)
                                    val partial =
                                        runCatching {
                                            JSONObject(
                                                recognizer.partialResult,
                                            ).optString("partial")
                                        }.getOrDefault("")
                                    if (partial.isNotBlank()) {
                                        onPartial(partial)
                                    }
                                    if (pattern.containsMatchIn(partial)) {
                                        matched = true
                                        // Heard enough — stop early so the
                                        // verdict lands quickly.
                                        return@withTimeoutOrNull
                                    }
                                }
                            }
                        } finally {
                            recognizer.close()
                        }
                    } finally {
                        model.close()
                    }
                    matched
                } finally {
                    if (
                        wasListening &&
                        resumePhrase != null &&
                        resumeLang != null
                    ) {
                        startLocked(resumePhrase, resumeLang)
                    }
                }
            }
        }

    // Callers hold RECORD_AUDIO (the engine is only started when the
    // permission is granted); the annotation lives on start().
    @SuppressLint("MissingPermission")
    private suspend fun listenLoop(modelDir: File) {
        // Heavy native init (~1-2 s). We are already on Dispatchers.IO.
        val model = Model(modelDir.absolutePath)
        try {
            /*
             * Full-vocabulary decoding — deliberately no grammar constraint.
             * The old two-word grammar (wake phrase + [unk]) forced the
             * decoder to emit the wake phrase for any vaguely similar
             * sound, which is why random speech triggered detections. With
             * the full vocabulary, ordinary speech decodes as ordinary
             * words and the phrase only appears when actually spoken.
             */
            val recognizer = Recognizer(model, SAMPLE_RATE_HZ)
            try {
                micStreamer.stream().collect { chunk ->
                    processChunk(recognizer, chunk)
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
        chunk: ByteArray,
    ) {
        recognizer.acceptWaveForm(chunk, chunk.size)

        val partial = runCatching {
            JSONObject(recognizer.partialResult).optString("partial")
        }.getOrDefault("")

        val pattern = activePattern ?: return
        if (pattern.containsMatchIn(partial)) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastEmitMs > EMIT_COOLDOWN_MS) {
                lastEmitMs = now
                Log.i(TAG, "wake word detected (heard \"$partial\")")
                _detections.emit(activePhrase ?: return)
            }
        }
    }

    /**
     * Returns the model directory for [lang], downloading and unzipping it
     * first when needed. Updates [modelState] along the way; throws on
     * failure.
     */
    private suspend fun ensureModel(lang: String): File =
        withContext(Dispatchers.IO) {
            val spec = MODEL_SPECS[lang] ?: MODEL_SPECS.getValue("en")
            val dir = File(context.filesDir, spec.dirName)
            if (isModelReady(dir)) {
                _modelState.value = WakeWordModelState.Ready
                return@withContext dir
            }

            Log.i(TAG, "downloading wake-word model for '$lang' (~40 MB)")
            _modelState.value = WakeWordModelState.Downloading(0f)

            val zipFile = File(context.filesDir, "${spec.dirName}.zip")
            try {
                download(spec.url, zipFile)
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

    private suspend fun download(
        url: String,
        zipFile: File,
    ) {
        val request = Request.Builder().url(url).build()
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

    private fun isModelReady(dir: File): Boolean =
        dir.isDirectory && File(dir, "am/final.mdl").exists()

    /**
     * Builds a word-boundary regex for the wake phrase so "hey glasses"
     * doesn't match inside longer words or word salads — the phrase must
     * appear as its own words.
     */
    private fun phrasePattern(phrase: String): Regex {
        val words = phrase.trim()
            .split("\\s+".toRegex())
            .filter { it.isNotEmpty() }
        val body = words.joinToString("\\s+") { Regex.escape(it) }
        return Regex("\\b$body\\b", RegexOption.IGNORE_CASE)
    }

    private data class ModelSpec(
        val dirName: String,
    ) {
        val url: String
            get() = "https://alphacephei.com/vosk/models/$dirName.zip"
    }

    /**
     * One small (~40 MB) on-device model per supported app language, from
     * the official Vosk model list (all Apache 2.0, built for Android).
     * Downloaded on demand the first time the language is used for wake
     * words.
     */
    private val MODEL_SPECS =
        mapOf(
            "en" to ModelSpec("vosk-model-small-en-us-0.15"),
            "de" to ModelSpec("vosk-model-small-de-0.15"),
            "fr" to ModelSpec("vosk-model-small-fr-0.22"),
            "es" to ModelSpec("vosk-model-small-es-0.42"),
            "pt" to ModelSpec("vosk-model-small-pt-0.3"),
            "it" to ModelSpec("vosk-model-small-it-0.22"),
        )

    private companion object {
        const val TAG = "VoskWakeWordEngine"
        const val SAMPLE_RATE_HZ = 16000.0f
        const val EMIT_COOLDOWN_MS = 3_000L
    }
}
