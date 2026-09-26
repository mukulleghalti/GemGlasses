package com.geno.veyra.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geno.veyra.settings.AudioOutput
import com.geno.veyra.wakeword.WakeWordModelState

/**
 * Minimal settings: dense rows (title + current value + chevron) grouped
 * under small section labels. Every choice lives in a dialog, so the
 * screen itself stays a quiet list. Only the switch (barge-in) is inline.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: AgentViewModel = hiltViewModel(),
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()

    var showVoiceDialog by remember { mutableStateOf(false) }
    var showAudioDialog by remember { mutableStateOf(false) }
    var showStopPhraseDialog by remember { mutableStateOf(false) }
    var showWakeDialog by remember { mutableStateOf(false) }
    var showMemoriesDialog by remember { mutableStateOf(false) }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }

    val selectedVoice = VOICES.firstOrNull { it.name == prefs.voiceName }
    val voiceLabel = selectedVoice?.let {
        "${it.name} · ${it.gender.label}"
    } ?: prefs.voiceName
    val stopLabel = STOP_PHRASES.firstOrNull {
        it.first == prefs.stopPhrase
    }?.second ?: prefs.stopPhrase
    val wakePhraseLabel = WAKE_PHRASES.firstOrNull {
        it.first == prefs.wakePhrase
    }?.second ?: prefs.wakePhrase
    val wakeSubtitle =
        (if (prefs.wakeWordEnabled) "On" else "Off") +
            " · $wakePhraseLabel"
    val apiKeySet = viewModel.savedApiKey != null

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        SectionLabel("Assistant")
        SettingRow(
            title = "Voice",
            value = voiceLabel,
            onClick = { showVoiceDialog = true },
        )
        SettingRow(
            title = "Audio output",
            value = prefs.audioOutput.label,
            onClick = { showAudioDialog = true },
        )
        SettingRow(
            title = "Barge-in",
            subtitle = "Talking over the assistant cuts it off",
            trailing = {
                Switch(
                    checked = prefs.bargeInEnabled,
                    onCheckedChange = { viewModel.setBargeInEnabled(it) },
                )
            },
        )
        SettingRow(
            title = "Stop phrase",
            value = stopLabel,
            onClick = { showStopPhraseDialog = true },
        )

        SectionLabel("Wake-up")
        SettingRow(
            title = "Voice wake-up",
            subtitle = wakeSubtitle,
            onClick = { showWakeDialog = true },
        )

        SectionLabel("Memory")
        SettingRow(
            title = "Memories",
            value = "${memories.size} saved",
            onClick = { showMemoriesDialog = true },
        )

        SectionLabel("AI Settings")
        SettingRow(
            title = "Web Search",
            subtitle = "Let the assistant access up-to-date online information",
            trailing = {
                Switch(
                    checked = prefs.webSearchEnabled,
                    onCheckedChange = { viewModel.setWebSearchEnabled(it) },
                )
            },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { advancedExpanded = !advancedExpanded }
                .padding(vertical = 13.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "ADVANCED",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                ),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                if (advancedExpanded) "▾" else "▸",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (advancedExpanded) {
            SettingRow(
                title = "Gemini API key",
                value = if (apiKeySet) "Set" else "Not set",
                onClick = { showApiKeyDialog = true },
            )
        }

        Text(
            "Transcripts stay on this device and are never synced.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 20.dp),
        )
    }

    if (showVoiceDialog) {
        VoiceDialog(
            current = prefs.voiceName,
            onSelect = {
                viewModel.setVoice(it)
                showVoiceDialog = false
            },
            onDismiss = { showVoiceDialog = false },
        )
    }
    if (showAudioDialog) {
        AudioOutputDialog(
            current = prefs.audioOutput,
            onSelect = {
                viewModel.setAudioOutput(it)
                showAudioDialog = false
            },
            onDismiss = { showAudioDialog = false },
        )
    }
    if (showStopPhraseDialog) {
        StopPhraseDialog(
            current = prefs.stopPhrase,
            onSelect = {
                viewModel.setStopPhrase(it)
                showStopPhraseDialog = false
            },
            onDismiss = { showStopPhraseDialog = false },
        )
    }
    if (showWakeDialog) {
        WakeUpDialog(
            viewModel = viewModel,
            onDismiss = { showWakeDialog = false },
        )
    }
    if (showMemoriesDialog) {
        MemoriesDialog(
            viewModel = viewModel,
            onDismiss = { showMemoriesDialog = false },
        )
    }
    if (showApiKeyDialog) {
        ApiKeyDialog(
            viewModel = viewModel,
            onDismiss = { showApiKeyDialog = false },
        )
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        ),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
    )
}

/**
 * One dense settings row: title (and optional subtitle) on the left,
 * current value plus a chevron — or a custom trailing control such as
 * a switch — on the right.
 */
@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 13.dp)
    Column {
        Row(
            modifier =
                if (onClick != null) {
                    rowModifier.clickable(onClick = onClick)
                } else {
                    rowModifier
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (value != null) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
            when {
                trailing != null -> trailing()
                onClick != null -> Text(
                    "›",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(
                alpha = 0.5f,
            ),
        )
    }
}

@Composable
private fun VoiceDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assistant voice") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
            ) {
                VoiceGender.entries.forEach { gender ->
                    item {
                        Text(
                            gender.label + " voices",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                    items(
                        VOICES.filter { it.gender == gender },
                    ) { voice ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelect(voice.name)
                                }
                                .padding(vertical = 10.dp),
                            horizontalArrangement =
                                Arrangement.SpaceBetween,
                            verticalAlignment =
                                Alignment.CenterVertically,
                        ) {
                            Text(
                                voice.name,
                                style =
                                    MaterialTheme.typography.bodyLarge,
                            )
                            RadioButton(
                                selected = voice.name == current,
                                onClick = { onSelect(voice.name) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun AudioOutputDialog(
    current: AudioOutput,
    onSelect: (AudioOutput) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Audio output") },
        text = {
            Column {
                Text(
                    "Glasses: voice plays through the glasses in high " +
                        "quality and the phone's mic listens. Phone " +
                        "speaker: voice plays on the phone and the " +
                        "glasses' mic listens while they're connected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                AudioOutput.entries.forEach { output ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(output) }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            output.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        RadioButton(
                            selected = output == current,
                            onClick = { onSelect(output) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun StopPhraseDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var custom by remember { mutableStateOf("") }
    val isPreset = STOP_PHRASES.any { it.first == current }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stop phrase") },
        text = {
            Column {
                Text(
                    "Saying this while the assistant is listening ends " +
                        "the session.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                STOP_PHRASES.forEach { (phrase, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(phrase) }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        RadioButton(
                            selected = phrase == current,
                            onClick = { onSelect(phrase) },
                        )
                    }
                }
                if (!isPreset) {
                    Text(
                        "Custom: “$current”",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    label = { Text("Custom phrase") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            val phrase = custom.trim()
                            if (phrase.isNotEmpty()) {
                                onSelect(phrase)
                            }
                        },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val phrase = custom.trim()
                    if (phrase.isNotEmpty()) {
                        onSelect(phrase)
                    } else {
                        onDismiss()
                    }
                },
            ) {
                Text(if (custom.isBlank()) "Done" else "Save")
            }
        },
    )
}

@Composable
private fun WakeUpDialog(
    viewModel: AgentViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val modelState by viewModel.wakeWordModelState
        .collectAsStateWithLifecycle()
    var custom by remember { mutableStateOf("") }

    val micGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Voice wake-up") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(
                    rememberScrollState(),
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Listen for wake word",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = prefs.wakeWordEnabled,
                        onCheckedChange = {
                            viewModel.setWakeWordEnabled(it)
                        },
                        enabled = micGranted,
                    )
                }

                if (!micGranted) {
                    Text(
                        "Microphone permission is required. Grant it by " +
                            "tapping the + button on the Assistant tab once.",
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    "Wake phrase",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                ChipRow {
                    WAKE_PHRASES.forEach { (phrase, label) ->
                        FilterChip(
                            selected = prefs.wakePhrase == phrase,
                            onClick = { viewModel.setWakePhrase(phrase) },
                            label = { Text(label) },
                        )
                    }
                }

                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    label = { Text("Custom wake phrase") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            val phrase = custom.trim().lowercase()
                            if (phrase.isNotEmpty()) {
                                viewModel.setWakePhrase(phrase)
                            }
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Only words the offline voice model knows will " +
                        "trigger — common English words are safest.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val modelStatusText = when (val state = modelState) {
                    WakeWordModelState.NotDownloaded ->
                        "Voice model downloads on first use (~40 MB, " +
                            "Wi-Fi recommended)."
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
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun MemoriesDialog(
    viewModel: AgentViewModel,
    onDismiss: () -> Unit,
) {
    val memories by viewModel.memories.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Memories") },
        text = {
            if (memories.isEmpty()) {
                Text(
                    "Nothing saved yet. Say “remember this …” in a " +
                        "session to add one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                ) {
                    items(
                        memories,
                        key = { it.id },
                    ) { memory ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement =
                                Arrangement.SpaceBetween,
                            verticalAlignment =
                                Alignment.CenterVertically,
                        ) {
                            Text(
                                memory.text,
                                style =
                                    MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    viewModel.deleteMemory(memory.id)
                                },
                            ) {
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun ApiKeyDialog(
    viewModel: AgentViewModel,
    onDismiss: () -> Unit,
) {
    val apiKeyState by viewModel.apiKeyState.collectAsStateWithLifecycle()
    var keyInput by remember {
        mutableStateOf(viewModel.savedApiKey.orEmpty())
    }
    var passwordVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gemini API key") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(
                    rememberScrollState(),
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("API key") },
                    singleLine = true,
                    visualTransformation =
                        if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                passwordVisible = !passwordVisible
                            },
                        ) {
                            Icon(
                                imageVector =
                                    if (passwordVisible) {
                                        Icons.Filled.VisibilityOff
                                    } else {
                                        Icons.Filled.Visibility
                                    },
                                contentDescription =
                                    if (passwordVisible) {
                                        "Hide key"
                                    } else {
                                        "Show key"
                                    },
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (keyInput.isNotBlank()) {
                                viewModel.saveApiKey(keyInput)
                            }
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { viewModel.saveApiKey(keyInput) },
                        enabled = keyInput.isNotBlank() &&
                            apiKeyState !=
                            AgentViewModel.ApiKeyState.Checking,
                    ) {
                        Text("Save & test")
                    }
                    if (viewModel.savedApiKey != null) {
                        TextButton(
                            onClick = {
                                viewModel.clearApiKey()
                                keyInput = ""
                            },
                        ) {
                            Text("Remove")
                        }
                    }
                }

                val statusText = when (val state = apiKeyState) {
                    AgentViewModel.ApiKeyState.Unchecked ->
                        "Key saved — tap Save & test to verify it."
                    AgentViewModel.ApiKeyState.Checking ->
                        "Verifying key with Google…"
                    AgentViewModel.ApiKeyState.Valid ->
                        "Key verified — the assistant is ready."
                    is AgentViewModel.ApiKeyState.Invalid ->
                        "Key problem: ${state.message}"
                    AgentViewModel.ApiKeyState.Missing ->
                        "No key saved yet."
                }
                val statusColor =
                    if (apiKeyState is AgentViewModel.ApiKeyState.Invalid) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                )
                Text(
                    "Get a free key from Google AI Studio. It's stored " +
                        "encrypted on this phone and sent only to Google — " +
                        "the app mints its own short-lived tokens, no " +
                        "server in the middle.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

/**
 * Wraps selectable chips. Kept separate because full-width children
 * (text fields, rows) misbehave as direct children of a [FlowRow].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        content()
    }
}

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
