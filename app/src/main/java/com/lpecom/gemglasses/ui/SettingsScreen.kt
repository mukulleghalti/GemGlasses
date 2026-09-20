package com.lpecom.gemglasses.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: AgentViewModel = hiltViewModel(),
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        Setting(title = "Response Language") {
            LANGUAGES.forEach { (code, label) ->
                FilterChip(
                    selected = prefs.languageCode == code,
                    onClick = { viewModel.setLanguage(code) },
                    label = { Text(label) },
                )
            }
        }

        Setting(title = "Assistant Voice") {
            VOICES.forEach { voice ->
                FilterChip(
                    selected = prefs.voiceName == voice,
                    onClick = { viewModel.setVoice(voice) },
                    label = { Text(voice) },
                )
            }
        }

        Text(
            "Privacidade: transcrições ficam só no aparelho e nunca são sincronizadas. " +
                "A chave da API vive apenas no backend; o app usa tokens efêmeros.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Setting(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

private val LANGUAGES = listOf(
    "pt-BR" to "Português",
    "en-US" to "English",
    "es-ES" to "Español",
)

// Prebuilt Gemini Live voices (subset).
private val VOICES = listOf("Puck", "Charon", "Kore", "Fenrir", "Aoede")
