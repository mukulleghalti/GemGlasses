package com.geno.veyra.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geno.veyra.R
import com.geno.veyra.live.LiveStreamManager

/**
 * Go Live screen (spike): RTMP destination presets (YouTube / Twitch /
 * custom), stream URL + key, start/stop, and the voice-control toggle
 * that exposes start/stop to the assistant.
 */
@Composable
fun LiveScreen(
    onBack: () -> Unit,
    viewModel: AgentViewModel = hiltViewModel(),
) {
    BackHandler { onBack() }

    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val liveStatus by viewModel.liveStatus.collectAsStateWithLifecycle()
    val startError by viewModel.liveStartError.collectAsStateWithLifecycle()

    var url by remember(prefs.liveStreamUrl) {
        mutableStateOf(prefs.liveStreamUrl)
    }
    var key by remember(prefs.liveStreamKey) {
        mutableStateOf(prefs.liveStreamKey)
    }

    val isLive = liveStatus.state == LiveStreamManager.State.LIVE ||
        liveStatus.state == LiveStreamManager.State.CONNECTING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {

        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector =
                        Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription =
                        stringResource(R.string.camera_back),
                )
            }
            Text(
                text = stringResource(R.string.live_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        val statusText = when (liveStatus.state) {
            LiveStreamManager.State.LIVE ->
                stringResource(R.string.live_status_live)

            LiveStreamManager.State.CONNECTING ->
                stringResource(R.string.live_status_connecting)

            LiveStreamManager.State.ERROR ->
                liveStatus.message
                    ?: stringResource(R.string.live_status_idle)

            LiveStreamManager.State.IDLE ->
                startError ?: stringResource(R.string.live_status_idle)
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyLarge,
            color = if (liveStatus.state == LiveStreamManager.State.LIVE) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    url = YOUTUBE_URL
                    viewModel.setLiveStreamUrl(YOUTUBE_URL)
                },
            ) {
                Text(stringResource(R.string.live_preset_youtube))
            }
            OutlinedButton(
                onClick = {
                    url = TWITCH_URL
                    viewModel.setLiveStreamUrl(TWITCH_URL)
                },
            ) {
                Text(stringResource(R.string.live_preset_twitch))
            }
            OutlinedButton(
                onClick = {
                    url = ""
                    viewModel.setLiveStreamUrl("")
                },
            ) {
                Text(stringResource(R.string.live_preset_custom))
            }
        }

        OutlinedTextField(
            value = url,
            onValueChange = {
                url = it
                viewModel.setLiveStreamUrl(it)
            },
            label = { Text(stringResource(R.string.live_url_label)) },
            singleLine = true,
            enabled = !isLive,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = key,
            onValueChange = {
                key = it
                viewModel.setLiveStreamKey(it)
            },
            label = { Text(stringResource(R.string.live_key_label)) },
            singleLine = true,
            enabled = !isLive,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        if (isLive) {
            Button(
                onClick = { viewModel.stopLive() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.live_stop))
            }
        } else {
            Button(
                onClick = { viewModel.startLive() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.live_start))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.live_voice_control),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.live_voice_control_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = prefs.liveStreamEnabled,
                onCheckedChange = { viewModel.setLiveStreamEnabled(it) },
            )
        }
    }
}

private const val YOUTUBE_URL = "rtmp://a.rtmp.youtube.com/live2"
private const val TWITCH_URL = "rtmp://live.twitch.tv/app"
