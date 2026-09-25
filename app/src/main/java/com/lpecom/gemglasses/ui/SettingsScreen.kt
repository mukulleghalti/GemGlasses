package com.lpecom.gemglasses.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lpecom.gemglasses.wakeword.WakeWordModelState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: AgentViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()

    val micGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

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
            var voiceMenuOpen by remember { mutableStateOf(false) }

            ExposedDropdownMenuBox(
                expanded = voiceMenuOpen,
                onExpandedChange = { voiceMenuOpen = it },
            ) {
                OutlinedTextField(
                    value = prefs.voiceName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Voice") },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(
                            expanded = voiceMenuOpen,
                        )
                    },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = voiceMenuOpen,
                    onDismissRequest = { voiceMenuOpen = false },
                ) {
                    VOICES.forEach { voice ->
                        DropdownMenuItem(
                            text = { Text(voice) },
                            onClick = {
                                viewModel.setVoice(voice)
                                voiceMenuOpen = false
                            },
                        )
                    }
                }
            }
            Text(
                "Voice names are just identifiers — every voice speaks " +
                    "any language.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Setting(title = "Voice wake-up") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Listen for wake word",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(
                    checked = prefs.wakeWordEnabled,
                    onCheckedChange = { viewModel.setWakeWordEnabled(it) },
                    enabled = micGranted,
                )
            }

            if (!micGranted) {
                Text(
                    "Microphone permission is required. Grant it on the Home tab " +
                        "by tapping \"Start Assistant\" once.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            WAKE_PHRASES.forEach { (phrase, label) ->
                FilterChip(
                    selected = prefs.wakePhrase == phrase,
                    onClick = { viewModel.setWakePhrase(phrase) },
                    label = { Text(label) },
                )
            }

            val modelState by viewModel.wakeWordModelState.collectAsStateWithLifecycle()
            val modelStatusText = when (val state = modelState) {
                WakeWordModelState.NotDownloaded ->
                    "Voice model downloads on first use (~40 MB, Wi-Fi recommended)."
                is WakeWordModelState.Downloading ->
                    if (state.progress < 0f) {
                        "Downloading voice model…"
                    } else {
                        "Downloading voice model… " +
                            "${(state.progress * 100).toInt()}%"
                    }
                WakeWordModelState.Ready ->
                    "Voice model ready."
                is WakeWordModelState.Error ->
                    "Voice model error: ${state.message}"
            }
            Text(
                modelStatusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Setting(title = "Stop phrase") {
            Text(
                "Saying this while the assistant is listening ends the " +
                    "session.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            STOP_PHRASES.forEach { (phrase, label) ->
                FilterChip(
                    selected = prefs.stopPhrase == phrase,
                    onClick = { viewModel.setStopPhrase(phrase) },
                    label = { Text(label) },
                )
            }
        }

        Text(
            "Privacy: transcripts stay on this device and are never synced. " +
                "The API key lives only on the backend; the app uses ephemeral tokens.",
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
    "pt-BR" to "Portuguese",
    "en-US" to "English",
    "es-ES" to "Spanish",
)

// Prebuilt Gemini Live voices (full set of 30). A wrong name makes the
// server close the session, so these must match the API exactly.
private val VOICES = listOf(
    "Zephyr",
    "Puck",
    "Charon",
    "Kore",
    "Fenrir",
    "Leda",
    "Orus",
    "Aoede",
    "Callirrhoe",
    "Autonoe",
    "Enceladus",
    "Iapetus",
    "Umbriel",
    "Algieba",
    "Despina",
    "Erinome",
    "Algenib",
    "Rasalgethi",
    "Laomedeia",
    "Achernar",
    "Alnilam",
    "Schedar",
    "Gacrux",
    "Pulcherrima",
    "Achird",
    "Zubenelgenubi",
    "Vindemiatrix",
    "Sadachbia",
    "Sadaltager",
    "Sulafat",
)

// Wake phrases the user can pick from. Every word must be in the Vosk model
// vocabulary — these all are.
private val WAKE_PHRASES = listOf(
    "hey glasses" to "Hey Glasses",
    "okay glasses" to "Okay Glasses",
    "hello glasses" to "Hello Glasses",
)

// Stop phrases the user can pick from. These are matched against Gemini's
// transcript (not Vosk), so any wording works.
private val STOP_PHRASES = listOf(
    "goodbye glasses" to "Goodbye Glasses",
    "bye glasses" to "Bye Glasses",
    "that's all" to "That's All",
)
