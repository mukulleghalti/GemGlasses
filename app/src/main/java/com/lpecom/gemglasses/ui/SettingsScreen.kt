package com.lpecom.gemglasses.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lpecom.gemglasses.settings.AudioOutput
import com.lpecom.gemglasses.settings.PlaybackQuality
import com.lpecom.gemglasses.wakeword.WakeWordModelState

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

            val selectedVoice =
                VOICES.firstOrNull { it.name == prefs.voiceName }

            Box {
                OutlinedTextField(
                    value = selectedVoice?.let {
                        "${it.name} · ${it.gender.label}"
                    } ?: prefs.voiceName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Voice") },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                DropdownMenu(
                    expanded = voiceMenuOpen,
                    onDismissRequest = { voiceMenuOpen = false },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    VoiceGender.entries.forEach { gender ->
                        Text(
                            gender.label + " voices",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(
                                horizontal = 16.dp,
                                vertical = 6.dp,
                            ),
                        )
                        VOICES
                            .filter { it.gender == gender }
                            .forEach { voice ->
                                DropdownMenuItem(
                                    text = { Text(voice.name) },
                                    onClick = {
                                        viewModel.setVoice(voice.name)
                                        voiceMenuOpen = false
                                    },
                                )
                            }
                    }
                }
                // A read-only field doesn't emit clicks itself; this overlay
                // turns the whole row into the menu toggle.
                Spacer(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { voiceMenuOpen = true },
                )
            }
            Text(
                "Voice names are just identifiers — every voice speaks " +
                    "any language.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Setting(title = "Assistant audio output") {
            AudioOutput.entries.forEach { output ->
                FilterChip(
                    selected = prefs.audioOutput == output,
                    onClick = { viewModel.setAudioOutput(output) },
                    label = { Text(output.label) },
                )
            }
            Text(
                "Diagnostic: play the assistant through the phone speaker " +
                    "instead of the glasses to check whether choppy audio " +
                    "comes from the Bluetooth link.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Setting(title = "Playback quality") {
            PlaybackQuality.entries.forEach { quality ->
                FilterChip(
                    selected = prefs.playbackQuality == quality,
                    onClick = { viewModel.setPlaybackQuality(quality) },
                    label = { Text(quality.label) },
                )
            }
            Text(
                "Call uses the voice-call Bluetooth channel; Media uses " +
                    "the high-quality music channel. Applies to the next " +
                    "session.",
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

            var wakeInput by remember(prefs.wakePhrase) {
                mutableStateOf(prefs.wakePhrase)
            }
            OutlinedTextField(
                value = wakeInput,
                onValueChange = { wakeInput = it },
                label = { Text("Custom wake phrase") },
                supportingText = {
                    Text(
                        "Type your own and press Done. Only words the " +
                            "offline voice model knows will trigger — " +
                            "common English words are safest.",
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        val phrase = wakeInput.trim().lowercase()
                        if (phrase.isNotEmpty()) {
                            viewModel.setWakePhrase(phrase)
                        }
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
            )

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

            val downloadProgress =
                (modelState as? WakeWordModelState.Downloading)
                    ?.progress
                    ?.takeIf { it >= 0f }
            if (downloadProgress != null) {
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
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

            var stopInput by remember(prefs.stopPhrase) {
                mutableStateOf(prefs.stopPhrase)
            }
            OutlinedTextField(
                value = stopInput,
                onValueChange = { stopInput = it },
                label = { Text("Custom stop phrase") },
                supportingText = {
                    Text(
                        "Type your own and press Done. Any wording works — " +
                            "it's matched against the assistant's transcript.",
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        val phrase = stopInput.trim()
                        if (phrase.isNotEmpty()) {
                            viewModel.setStopPhrase(phrase)
                        }
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
            )
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
//
// Google doesn't publish gender labels; the grouping below follows the
// community-maintained male/female split for these voices.
private enum class VoiceGender(val label: String) {
    FEMALE("Female"),
    MALE("Male"),
}

private data class GeminiVoice(
    val name: String,
    val gender: VoiceGender,
)

private val VOICES = listOf(
    GeminiVoice("Aoede", VoiceGender.FEMALE),
    GeminiVoice("Kore", VoiceGender.FEMALE),
    GeminiVoice("Leda", VoiceGender.FEMALE),
    GeminiVoice("Zephyr", VoiceGender.FEMALE),
    GeminiVoice("Autonoe", VoiceGender.FEMALE),
    GeminiVoice("Callirrhoe", VoiceGender.FEMALE),
    GeminiVoice("Despina", VoiceGender.FEMALE),
    GeminiVoice("Erinome", VoiceGender.FEMALE),
    GeminiVoice("Gacrux", VoiceGender.FEMALE),
    GeminiVoice("Laomedeia", VoiceGender.FEMALE),
    GeminiVoice("Pulcherrima", VoiceGender.FEMALE),
    GeminiVoice("Sulafat", VoiceGender.FEMALE),
    GeminiVoice("Vindemiatrix", VoiceGender.FEMALE),
    GeminiVoice("Achernar", VoiceGender.FEMALE),
    GeminiVoice("Puck", VoiceGender.MALE),
    GeminiVoice("Charon", VoiceGender.MALE),
    GeminiVoice("Fenrir", VoiceGender.MALE),
    GeminiVoice("Orus", VoiceGender.MALE),
    GeminiVoice("Achird", VoiceGender.MALE),
    GeminiVoice("Algenib", VoiceGender.MALE),
    GeminiVoice("Algieba", VoiceGender.MALE),
    GeminiVoice("Alnilam", VoiceGender.MALE),
    GeminiVoice("Enceladus", VoiceGender.MALE),
    GeminiVoice("Iapetus", VoiceGender.MALE),
    GeminiVoice("Rasalgethi", VoiceGender.MALE),
    GeminiVoice("Sadachbia", VoiceGender.MALE),
    GeminiVoice("Sadaltager", VoiceGender.MALE),
    GeminiVoice("Schedar", VoiceGender.MALE),
    GeminiVoice("Umbriel", VoiceGender.MALE),
    GeminiVoice("Zubenelgenubi", VoiceGender.MALE),
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
