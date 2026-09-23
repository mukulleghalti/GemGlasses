package com.lpecom.gemglasses.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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

/** User-tunable session and camera preferences. */
data class AgentPreferences(
    val languageCode: String,
    val voiceName: String,
    val cameraResolution: CameraResolution,
    val cameraFrameRate: Int,
) {
    /** Built here so the persona text stays in one place. */
    val systemInstruction: String
        get() = DEFAULT_SYSTEM_INSTRUCTION

    companion object {

        val DEFAULT = AgentPreferences(
            languageCode = "en",
            voiceName = "Puck",
            cameraResolution = CameraResolution.MEDIUM,
            cameraFrameRate = 24,
        )

        val DEFAULT_SYSTEM_INSTRUCTION = """
            You are a personal voice assistant that speaks through the user's glasses.
            Have a natural conversation and keep responses short, usually 1 to 3 sentences.
            Speak English by default unless the user asks for another language.
            
            Use tools when appropriate:
            - To see what the user is looking at, use capturar_visao.
            - To find real places, use buscar_lugares. Never invent business or place names.
            - To navigate, use iniciar_navegacao.
            - To send a message, use enviar_mensagem.
            
            If you are unsure about something, ask a brief clarifying question.
        """.trimIndent()
    }
}

private val Context.dataStore by preferencesDataStore(
    name = "gemglasses_settings"
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
}
