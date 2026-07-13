package com.lpecom.gemglasses.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** User-tunable session preferences. */
data class AgentPreferences(
    val languageCode: String,
    val voiceName: String,
) {
    /** Built here so the persona text stays in one place. */
    val systemInstruction: String
        get() = DEFAULT_SYSTEM_INSTRUCTION

    companion object {
        val DEFAULT = AgentPreferences(languageCode = "pt-BR", voiceName = "Puck")

        val DEFAULT_SYSTEM_INSTRUCTION = """
            Você é um assistente pessoal de voz que fala pelos óculos do usuário.
            Você conversa — então responda curto e natural, em 1 a 3 frases.
            Fale em português do Brasil, a menos que peçam outra língua.
            Use as ferramentas quando fizer sentido: para ver o que o usuário
            está olhando use capturar_visao; para achar lugares reais use
            buscar_lugares (nunca invente nomes de estabelecimentos); para navegar
            use iniciar_navegacao; para mandar mensagem use enviar_mensagem.
            Se não tiver certeza, pergunte de forma breve.
        """.trimIndent()
    }
}

private val Context.dataStore by preferencesDataStore(name = "gemglasses_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val langKey = stringPreferencesKey("language_code")
    private val voiceKey = stringPreferencesKey("voice_name")

    val preferences: Flow<AgentPreferences> = context.dataStore.data.map { prefs ->
        AgentPreferences(
            languageCode = prefs[langKey] ?: AgentPreferences.DEFAULT.languageCode,
            voiceName = prefs[voiceKey] ?: AgentPreferences.DEFAULT.voiceName,
        )
    }

    suspend fun snapshot(): AgentPreferences = preferences.first()

    suspend fun setLanguage(code: String) {
        context.dataStore.edit { it[langKey] = code }
    }

    suspend fun setVoice(voice: String) {
        context.dataStore.edit { it[voiceKey] = voice }
    }
}
