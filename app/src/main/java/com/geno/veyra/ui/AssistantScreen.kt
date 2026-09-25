package com.geno.veyra.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geno.veyra.agent.AgentStatus
import com.geno.veyra.state.CitedPlace
import com.geno.veyra.state.TranscriptEntry

/**
 * Chat-style home for the voice assistant: a conversation list with
 * the live session on top and archived sessions below. Tapping either
 * opens that conversation; the live one keeps updating while it runs.
 */
@Composable
fun AssistantScreen(
    modifier: Modifier = Modifier,
    viewModel: AgentViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val status by viewModel.status.collectAsStateWithLifecycle()
    val running = status != AgentStatus.IDLE

    var showLive by remember { mutableStateOf(false) }

    val micLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) {
                viewModel.startSession()
                showLive = true
            }
        }

    BackHandler(enabled = showLive) {
        showLive = false
    }

    if (showLive) {
        LiveTranscript(
            modifier = modifier,
            viewModel = viewModel,
            onBack = { showLive = false },
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Assistant",
                style = MaterialTheme.typography.titleLarge,
            )

            if (!running) {
                TextButton(
                    onClick = {
                        val granted =
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED

                        if (granted) {
                            viewModel.startSession()
                            showLive = true
                        } else {
                            micLauncher.launch(
                                Manifest.permission.RECORD_AUDIO,
                            )
                        }
                    },
                ) {
                    Text("+ New")
                }
            }
        }

        if (running) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showLive = true },
                colors = CardDefaults.cardColors(
                    containerColor =
                        MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF34C759)),
                    )
                    Column {
                        Text(
                            "Current session",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Live · tap to view",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme
                                .onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        Text(
            "Past conversations",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        /*
         * The archived-session browser, embedded in compact form.
         * Selecting a session opens its transcript inside it; the
         * outer back handler only deals with the live view.
         */
        Box(modifier = Modifier.weight(1f)) {
            ConversationHistoryScreen(
                onBack = {},
                compact = true,
            )
        }
    }
}

/**
 * The running session's transcript, with the mic mute icon. Shown as
 * the "live conversation" detail of the assistant screen.
 */
@Composable
private fun LiveTranscript(
    modifier: Modifier = Modifier,
    viewModel: AgentViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val entries by viewModel.transcript.collectAsStateWithLifecycle()
    val places by viewModel.places.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val micMuted by viewModel.micMuted.collectAsStateWithLifecycle()
    val assistantRunning = status != AgentStatus.IDLE

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onBack) {
                        Text("← Back")
                    }
                    Text(
                        "Live transcript",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }

                if (assistantRunning) {
                    /*
                     * Mic mute as an icon: filled mic normally, crossed
                     * mic in the error color when muted so the state is
                     * obvious at a glance.
                     */
                    IconButton(
                        onClick = {
                            viewModel.setMicMuted(!micMuted)
                        },
                    ) {
                        Icon(
                            imageVector =
                                if (micMuted) Icons.Default.MicOff
                                else Icons.Default.Mic,
                            contentDescription =
                                if (micMuted) "Unmute mic"
                                else "Mute mic",
                            tint =
                                if (micMuted) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                        )
                    }
                }
            }
        }

        if (entries.isEmpty()) {
            item {
                Text(
                    "The conversation will appear here when the session starts.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(
            entries,
            key = { it.seq },
        ) { entry ->
            TranscriptLine(entry)
        }

        if (places.isNotEmpty()) {
            item {
                HorizontalDivider(
                    Modifier.padding(vertical = 12.dp),
                )

                Text(
                    "Cited Places (Google Maps)",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            items(
                places,
                key = { it.uri },
            ) { place ->
                PlaceCard(place)
            }
        }
    }
}

@Composable
private fun TranscriptLine(
    entry: TranscriptEntry,
) {
    val (label, weight) = when (entry.speaker) {
        TranscriptEntry.Speaker.USER ->
            "You" to FontWeight.SemiBold

        TranscriptEntry.Speaker.ASSISTANT ->
            "Assistant" to FontWeight.Normal

        TranscriptEntry.Speaker.SYSTEM ->
            "System" to FontWeight.Light
    }

    Column(
        Modifier.fillMaxWidth(),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )

        if (entry.imageBytes != null) {
            val bitmap = BitmapFactory.decodeByteArray(
                entry.imageBytes,
                0,
                entry.imageBytes.size,
            )

            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Captured photo",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 500.dp),
                )
            } else {
                Text(
                    "Captured photo could not be displayed.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (entry.text.isNotEmpty()) {
            Text(
                entry.text,
                fontWeight = weight,
            )
        }
    }
}

@Composable
private fun PlaceCard(
    place: CitedPlace,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            Modifier.padding(12.dp),
        ) {
            Text(
                place.title,
                fontWeight = FontWeight.Medium,
            )

            Text(
                place.uri,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
