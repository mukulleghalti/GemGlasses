package com.geno.veyra.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

enum class CameraResolution(
    val storageValue: String,
    val label: String,
    val width: Int,
    val height: Int,
) {
    LOW(
        storageValue = "low",
        label = "Low — 360 × 640",
        width = 360,
        height = 640,
    ),

    MEDIUM(
        storageValue = "medium",
        label = "Medium — 504 × 896",
        width = 504,
        height = 896,
    ),

    HIGH(
        storageValue = "high",
        label = "High — 720 × 1280",
        width = 720,
        height = 1280,
    );

    companion object {
        fun fromStorageValue(value: String?): CameraResolution {
            return entries.firstOrNull {
                it.storageValue == value
            } ?: MEDIUM
        }
    }
}

/** Where the assistant's voice plays — and, paired with it, which mic listens. */
enum class AudioOutput(
    val storageValue: String,
    val label: String,
) {
    /**
     * Voice through the glasses over the high-quality music channel;
     * the phone's mic listens.
     */
    GLASSES(
        storageValue = "glasses",
        label = "Glasses",
    ),

    /**
     * Voice on the phone speaker; the glasses' mic listens (via the
     * SCO voice channel) while they're connected.
     */
    PHONE_SPEAKER(
        storageValue = "phone_speaker",
        label = "Phone speaker",
    );

    companion object {
        fun fromStorageValue(value: String?): AudioOutput {
            // "phone" is the pre-removal diagnostic value; it meant
            // phone-speaker output, so it maps to the new mode.
            return when (value) {
                PHONE_SPEAKER.storageValue,
                "phone",
                    -> PHONE_SPEAKER

                else -> GLASSES
            }
        }
    }
}

/** User-tunable session and camera preferences. */
data class AgentPreferences(
    val languageCode: String,
    val voiceName: String,
    val cameraResolution: CameraResolution,
    val cameraFrameRate: Int,
    val wakeWordEnabled: Boolean,
    val wakePhrase: String,
    val stopPhrase: String,
    val audioOutput: AudioOutput,
    val bargeInEnabled: Boolean,
    val webSearchEnabled: Boolean,
) {
    /** Built here so the persona text stays in one place. */
    val systemInstruction: String
        get() = buildString {
            append(DEFAULT_SYSTEM_INSTRUCTION)
            append("\n\nCurrent date and time: ")
            append(currentDateTime())
            append(
                ". Use this to resolve relative times like " +
                    "\"in 20 minutes\" or \"at 6pm\" into epoch " +
                    "milliseconds for set_reminder.",
            )
            if (webSearchEnabled) {
                append(
                    "\n\nWeb search is enabled: use it for current, " +
                        "time-sensitive, or explicitly requested online " +
                        "information instead of relying on training data.",
                )
            }
        }

    private fun currentDateTime(): String =
        SimpleDateFormat(
            "EEEE, d MMMM yyyy, HH:mm",
            Locale.getDefault(),
        ).format(Date())

    companion object {

        val DEFAULT = AgentPreferences(
            languageCode = "en",
            voiceName = "Puck",
            cameraResolution = CameraResolution.MEDIUM,
            cameraFrameRate = 24,
            wakeWordEnabled = false,
            wakePhrase = DEFAULT_WAKE_PHRASE,
            stopPhrase = DEFAULT_STOP_PHRASE,
            audioOutput = AudioOutput.GLASSES,
            bargeInEnabled = true,
            webSearchEnabled = false,
        )

        /** Default wake phrase ("Hey Glasses"). Lowercase: Vosk decodes lowercase. */
        const val DEFAULT_WAKE_PHRASE = "hey glasses"

        /**
         * Default stop phrase ("Goodbye Glasses"). Saying it while the
         * assistant is running ends the session. Matched against Gemini's
         * transcript, so unlike the wake phrase it is not limited to the
         * Vosk vocabulary.
         */
        const val DEFAULT_STOP_PHRASE = "goodbye glasses"

        val DEFAULT_SYSTEM_INSTRUCTION = """
            You are a personal voice assistant that speaks through the user's glasses.
            Have a natural conversation and keep responses short, usually 1 to 3 sentences.
            Speak English by default unless the user asks for another language.
            
            Use tools when appropriate:
            - To see what the user is looking at, use capture_vision.
            - To find real places, use search_places. Never invent business or place names.
            - To navigate, use start_navigation.
            - To send a message, use send_message.
            - To control music playback (play, pause, next, previous), use media_control.
              It acts on whichever audio app is active; it cannot pick an app or playlist.
            - For countdown timers, use set_timer with durationSeconds.
              For reminders at a specific time, use set_reminder with triggerAtMillis.
              Use list_alerts and cancel_alert to manage them.
            - To check phone battery or whether the glasses are connected, use get_device_status.
              The glasses' own battery percentage is not available — never invent one.
            - To recall earlier chats, use list_conversations and search_conversations.
            
            If you are unsure about something, ask a brief clarifying question.
        """.trimIndent()
    }
}

private val Context.dataStore by preferencesDataStore(
    name = "veyra_settings"
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val langKey =
        stringPreferencesKey("language_code")

    private val voiceKey =
        stringPreferencesKey("voice_name")

    private val cameraResolutionKey =
        stringPreferencesKey("camera_resolution")

    private val cameraFrameRateKey =
        intPreferencesKey("camera_frame_rate")

    private val wakeWordEnabledKey =
        booleanPreferencesKey("wake_word_enabled")

    private val wakePhraseKey =
        stringPreferencesKey("wake_phrase")

    private val stopPhraseKey =
        stringPreferencesKey("stop_phrase")

    private val audioOutputKey =
        stringPreferencesKey("audio_output")

    private val bargeInEnabledKey =
        booleanPreferencesKey("barge_in_enabled")

    private val webSearchEnabledKey =
        booleanPreferencesKey("web_search_enabled")

    private val translateSourceLangKey =
        stringPreferencesKey("translate_source_lang")

    private val translateTargetLangKey =
        stringPreferencesKey("translate_target_lang")

    /** Persisted source language code for the translator ("auto" = detect). */
    val translateSourceLang: Flow<String> =
        context.dataStore.data.map { prefs ->
            prefs[translateSourceLangKey] ?: "auto"
        }

    /** Persisted target language code for the translator. */
    val translateTargetLang: Flow<String> =
        context.dataStore.data.map { prefs ->
            prefs[translateTargetLangKey] ?: "hi"
        }

    suspend fun setTranslateSourceLang(code: String) {
        context.dataStore.edit {
            it[translateSourceLangKey] = code
        }
    }

    suspend fun setTranslateTargetLang(code: String) {
        context.dataStore.edit {
            it[translateTargetLangKey] = code
        }
    }

    val preferences: Flow<AgentPreferences> =
        context.dataStore.data.map { prefs ->

            AgentPreferences(
                languageCode =
                    prefs[langKey]
                        ?: AgentPreferences.DEFAULT.languageCode,

                voiceName =
                    prefs[voiceKey]
                        ?: AgentPreferences.DEFAULT.voiceName,

                cameraResolution =
                    CameraResolution.fromStorageValue(
                        prefs[cameraResolutionKey]
                    ),

                cameraFrameRate =
                    prefs[cameraFrameRateKey]
                        ?: AgentPreferences.DEFAULT.cameraFrameRate,

                wakeWordEnabled =
                    prefs[wakeWordEnabledKey]
                        ?: AgentPreferences.DEFAULT.wakeWordEnabled,

                wakePhrase =
                    prefs[wakePhraseKey]
                        ?: AgentPreferences.DEFAULT.wakePhrase,

                stopPhrase =
                    prefs[stopPhraseKey]
                        ?: AgentPreferences.DEFAULT.stopPhrase,

                audioOutput =
                    AudioOutput.fromStorageValue(
                        prefs[audioOutputKey]
                    ),

                bargeInEnabled =
                    prefs[bargeInEnabledKey]
                        ?: AgentPreferences.DEFAULT.bargeInEnabled,

                webSearchEnabled =
                    prefs[webSearchEnabledKey]
                        ?: AgentPreferences.DEFAULT.webSearchEnabled,
            )
        }

    suspend fun snapshot(): AgentPreferences =
        preferences.first()

    suspend fun setLanguage(code: String) {
        context.dataStore.edit {
            it[langKey] = code
        }
    }

    suspend fun setVoice(voice: String) {
        context.dataStore.edit {
            it[voiceKey] = voice
        }
    }

    suspend fun setCameraResolution(
        resolution: CameraResolution,
    ) {
        context.dataStore.edit {
            it[cameraResolutionKey] =
                resolution.storageValue
        }
    }

    suspend fun setCameraFrameRate(
        frameRate: Int,
    ) {
        context.dataStore.edit {
            it[cameraFrameRateKey] =
                frameRate
        }
    }

    suspend fun setWakeWordEnabled(
        enabled: Boolean,
    ) {
        context.dataStore.edit {
            it[wakeWordEnabledKey] = enabled
        }
    }

    suspend fun setWakePhrase(
        phrase: String,
    ) {
        context.dataStore.edit {
            it[wakePhraseKey] = phrase
        }
    }

    suspend fun setStopPhrase(
        phrase: String,
    ) {
        context.dataStore.edit {
            it[stopPhraseKey] = phrase
        }
    }

    suspend fun setAudioOutput(
        output: AudioOutput,
    ) {
        context.dataStore.edit {
            it[audioOutputKey] = output.storageValue
        }
    }

    suspend fun setBargeInEnabled(
        enabled: Boolean,
    ) {
        context.dataStore.edit {
            it[bargeInEnabledKey] = enabled
        }
    }

    suspend fun setWebSearchEnabled(
        enabled: Boolean,
    ) {
        context.dataStore.edit {
            it[webSearchEnabledKey] = enabled
        }
    }
}
