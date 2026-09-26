package com.geno.veyra.ui

import android.Manifest
import android.app.Activity
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geno.veyra.R
import com.geno.veyra.settings.AppLanguage
import com.geno.veyra.settings.AudioOutput
import com.geno.veyra.settings.LIVE_MODELS
import com.geno.veyra.settings.LocaleHelper
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
    var showModelDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }

    val selectedVoice = VOICES.firstOrNull { it.name == prefs.voiceName }
    val voiceLabel = selectedVoice?.let {
        "${it.name} · ${stringResource(it.gender.labelRes)}"
    } ?: prefs.voiceName
    val stopLabel = STOP_PHRASES.firstOrNull {
        it.first == prefs.stopPhrase
    }?.second ?: prefs.stopPhrase
    val wakePhraseLabel = WAKE_PHRASES.firstOrNull {
        it.first == prefs.wakePhrase
    }?.second ?: prefs.wakePhrase
    val wakeSubtitle =
        (if (prefs.wakeWordEnabled) stringResource(R.string.settings_wake_on) else stringResource(R.string.settings_wake_off)) +
            " · $wakePhraseLabel"
    val apiKeySet = viewModel.savedApiKey != null
    val apiKeyState by viewModel.apiKeyState.collectAsStateWithLifecycle()
    val appLanguageTag by viewModel.appLanguage.collectAsStateWithLifecycle()
    val appLanguage = AppLanguage.fromTag(appLanguageTag)
    val activity = LocalContext.current as? Activity

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
    ) {
        Text(
            stringResource(R.string.common_settings),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        SectionLabel(stringResource(R.string.settings_section_general))
        SettingRow(
            title = stringResource(R.string.settings_language),
            value =
                if (appLanguage == AppLanguage.SYSTEM) {
                    stringResource(R.string.settings_language_system)
                } else {
                    LocaleHelper.displayName(appLanguage.tag!!)
                },
            onClick = { showLanguageDialog = true },
        )

        SectionLabel(stringResource(R.string.settings_section_assistant))
        SettingRow(
            title = stringResource(R.string.settings_voice),
            value = voiceLabel,
            onClick = { showVoiceDialog = true },
        )
        SettingRow(
            title = stringResource(R.string.settings_audio_output),
            value = stringResource(prefs.audioOutput.labelRes),
            onClick = { showAudioDialog = true },
        )
        SettingRow(
            title = stringResource(R.string.settings_barge_in),
            subtitle = stringResource(R.string.settings_barge_in_sub),
            trailing = {
                Switch(
                    checked = prefs.bargeInEnabled,
                    onCheckedChange = { viewModel.setBargeInEnabled(it) },
                )
            },
        )
        SettingRow(
            title = stringResource(R.string.settings_stop_phrase),
            value = stopLabel,
            onClick = { showStopPhraseDialog = true },
        )

        SectionLabel(stringResource(R.string.settings_section_wakeup))
        SettingRow(
            title = stringResource(R.string.settings_voice_wakeup),
            subtitle = wakeSubtitle,
            onClick = { showWakeDialog = true },
        )

        SectionLabel(stringResource(R.string.settings_section_memory))
        SettingRow(
            title = stringResource(R.string.settings_memories_title),
            value = pluralStringResource(R.plurals.settings_memories_saved, memories.size, memories.size),
            onClick = { showMemoriesDialog = true },
        )

        SectionLabel(stringResource(R.string.settings_section_ai))
        SettingRow(
            title = stringResource(R.string.settings_web_search),
            subtitle = stringResource(R.string.settings_web_search_sub),
            trailing = {
                Switch(
                    checked = prefs.webSearchEnabled,
                    onCheckedChange = { viewModel.setWebSearchEnabled(it) },
                )
            },
        )
        SettingRow(
            title = stringResource(R.string.settings_gemini_model),
            value = LIVE_MODELS.firstOrNull { it.id == prefs.liveModel }
                ?.label ?: prefs.liveModel,
            onClick = { showModelDialog = true },
        )
        SettingRow(
            title = stringResource(R.string.settings_auto_titles),
            subtitle = stringResource(R.string.settings_auto_titles_sub),
            trailing = {
                Switch(
                    checked = prefs.autoHistoryTitles,
                    onCheckedChange = { viewModel.setAutoHistoryTitles(it) },
                )
            },
        )
        SettingRow(
            title = stringResource(R.string.settings_qr_scan),
            subtitle = stringResource(R.string.settings_qr_scan_sub),
            trailing = {
                Switch(
                    checked = prefs.qrScanEnabled,
                    onCheckedChange = { viewModel.setQrScanEnabled(it) },
                )
            },
        )
        SettingRow(
            title = stringResource(R.string.settings_ocr),
            subtitle = stringResource(R.string.settings_ocr_sub),
            trailing = {
                Switch(
                    checked = prefs.ocrEnabled,
                    onCheckedChange = { viewModel.setOcrEnabled(it) },
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
                stringResource(R.string.settings_section_advanced).uppercase(),
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
                title = stringResource(R.string.settings_api_key),
                value = if (apiKeySet) stringResource(R.string.settings_api_key_set) else stringResource(R.string.settings_api_key_not_set),
                onClick = { showApiKeyDialog = true },
            )
            SettingRow(
                title = stringResource(R.string.settings_check_connection),
                value = when (val state = apiKeyState) {
                    is AgentViewModel.ApiKeyState.Checking -> stringResource(R.string.settings_conn_checking)
                    is AgentViewModel.ApiKeyState.Valid -> stringResource(R.string.settings_conn_ok)
                    is AgentViewModel.ApiKeyState.Invalid ->
                        (state.message
                            ?: stringResource(R.string.settings_conn_fallback)).take(40)

                    is AgentViewModel.ApiKeyState.Missing -> stringResource(R.string.settings_conn_missing)
                    is AgentViewModel.ApiKeyState.Unchecked -> stringResource(R.string.settings_conn_tap)
                },
                onClick = { viewModel.checkConnection() },
            )
        }

        Text(
            stringResource(R.string.settings_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 20.dp),
        )
    }

    if (showLanguageDialog) {
        LanguageDialog(
            current = appLanguage,
            onSelect = { option ->
                showLanguageDialog = false
                if (option != appLanguage) {
                    viewModel.setAppLanguage(option.tag)
                    activity?.recreate()
                }
            },
            onDismiss = { showLanguageDialog = false },
        )
    }
    if (showModelDialog) {
        ModelDialog(
            current = prefs.liveModel,
            onSelect = {
                viewModel.setLiveModel(it)
                showModelDialog = false
            },
            onDismiss = { showModelDialog = false },
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
        title = { Text(stringResource(R.string.settings_voice_dialog_title)) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
            ) {
                VoiceGender.entries.forEach { gender ->
                    item {
                        Text(
                            stringResource(R.string.settings_voices_group, stringResource(gender.labelRes)),
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        },
    )
}

@Composable
private fun LanguageDialog(
    current: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language_dialog_title)) },
        text = {
            Column {
                AppLanguage.entries.forEach { option ->
                    val label =
                        if (option == AppLanguage.SYSTEM) {
                            stringResource(R.string.settings_language_system)
                        } else {
                            LocaleHelper.displayName(option.tag!!)
                        }
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(option) }
                                .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = option == current,
                            onClick = { onSelect(option) },
                        )
                        Text(
                            label,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_done))
            }
        },
    )
}

@Composable
private fun ModelDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_gemini_model)) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
            ) {
                items(LIVE_MODELS) { model ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(model.id) }
                            .padding(vertical = 10.dp),
                        horizontalArrangement =
                            Arrangement.SpaceBetween,
                        verticalAlignment =
                            Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                model.label,
                                style =
                                    MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                stringResource(model.subtitleRes),
                                style =
                                    MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme
                                    .onSurfaceVariant,
                            )
                        }
                        RadioButton(
                            selected = model.id == current,
                            onClick = { onSelect(model.id) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
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
        title = { Text(stringResource(R.string.settings_audio_dialog_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.settings_audio_desc),
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
                            stringResource(output.labelRes),
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
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
        title = { Text(stringResource(R.string.settings_stop_dialog_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.settings_stop_desc),
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
                        stringResource(R.string.settings_stop_custom, current),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    label = { Text(stringResource(R.string.settings_stop_custom_label)) },
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
                Text(
                    if (custom.isBlank()) {
                        stringResource(R.string.common_done)
                    } else {
                        stringResource(R.string.settings_save)
                    },
                )
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
        title = { Text(stringResource(R.string.settings_wake_dialog_title)) },
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
                        stringResource(R.string.settings_wake_listen),
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
                        stringResource(R.string.settings_wake_mic_needed),
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    stringResource(R.string.settings_wake_phrase_label),
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
                    label = { Text(stringResource(R.string.settings_wake_custom_label)) },
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
                    stringResource(R.string.settings_wake_vocab_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val modelStatusText = when (val state = modelState) {
                    WakeWordModelState.NotDownloaded ->
                        stringResource(R.string.settings_wake_model_download)
                    is WakeWordModelState.Downloading ->
                        if (state.progress < 0f) {
                            stringResource(R.string.settings_wake_model_downloading)
                        } else {
                            stringResource(
                                R.string.settings_wake_model_downloading_pct,
                                (state.progress * 100).toInt(),
                            )
                        }
                    WakeWordModelState.Ready ->
                        stringResource(R.string.settings_wake_model_ready)
                    is WakeWordModelState.Error ->
                        stringResource(R.string.settings_wake_model_error, state.message)
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
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
        title = { Text(stringResource(R.string.settings_memories_title)) },
        text = {
            if (memories.isEmpty()) {
                Text(
                    stringResource(R.string.settings_memories_empty),
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
                                Text(stringResource(R.string.common_delete))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
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
        title = { Text(stringResource(R.string.settings_apikey_dialog_title)) },
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
                    label = { Text(stringResource(R.string.settings_apikey_label)) },
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
                                        stringResource(R.string.settings_apikey_hide)
                                    } else {
                                        stringResource(R.string.settings_apikey_show)
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
                        Text(stringResource(R.string.settings_apikey_save_test))
                    }
                    if (viewModel.savedApiKey != null) {
                        TextButton(
                            onClick = {
                                viewModel.clearApiKey()
                                keyInput = ""
                            },
                        ) {
                            Text(stringResource(R.string.settings_apikey_remove))
                        }
                    }
                }

                val statusText = when (val state = apiKeyState) {
                    AgentViewModel.ApiKeyState.Unchecked ->
                        stringResource(R.string.settings_apikey_unchecked)
                    AgentViewModel.ApiKeyState.Checking ->
                        stringResource(R.string.settings_apikey_checking)
                    AgentViewModel.ApiKeyState.Valid ->
                        stringResource(R.string.settings_apikey_valid)
                    is AgentViewModel.ApiKeyState.Invalid ->
                        stringResource(
                        R.string.settings_apikey_invalid,
                        state.message
                            ?: stringResource(R.string.settings_conn_fallback),
                    )
                    AgentViewModel.ApiKeyState.Missing ->
                        stringResource(R.string.settings_apikey_missing)
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
                    stringResource(R.string.settings_apikey_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
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
private enum class VoiceGender(@StringRes val labelRes: Int) {
    FEMALE(R.string.settings_voice_female),
    MALE(R.string.settings_voice_male),
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
