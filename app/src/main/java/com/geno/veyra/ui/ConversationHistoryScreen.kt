package com.geno.veyra.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geno.veyra.state.ArchivedTurn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val sessionDateFormat =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())

private val turnTimeFormat =
    SimpleDateFormat("HH:mm", Locale.getDefault())

private fun formatDate(millis: Long): String =
    sessionDateFormat.format(Date(millis))

private fun speakerLabel(speaker: String): String =
    if (speaker == "USER") "You" else "Veyra"

/**
 * Browse, search, and delete archived assistant sessions. Everything
 * shown here lives only on this device.
 */
@Composable
fun ConversationHistoryScreen(
    onBack: () -> Unit,
    viewModel: ConversationHistoryViewModel = hiltViewModel(),
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val hits by viewModel.hits.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()

    var showClearAllConfirm by remember { mutableStateOf(false) }

    BackHandler {
        if (selected != null) {
            viewModel.closeSession()
        } else {
            onBack()
        }
    }

    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text("Delete all conversations?") },
            text = {
                Text(
                    "This permanently deletes every archived session " +
                        "from this device. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAll()
                        showClearAllConfirm = false
                    },
                ) {
                    Text("Delete all")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearAllConfirm = false },
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        if (selected != null) {
            SessionDetail(
                startedAt = selected!!.startedAt,
                turns = selected!!.turns,
                onBack = { viewModel.closeSession() },
                onDelete = { viewModel.deleteSession(selected!!.id) },
            )
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Conversation history",
                style = MaterialTheme.typography.titleLarge,
            )
            if (sessions.isNotEmpty()) {
                TextButton(
                    onClick = { showClearAllConfirm = true },
                ) {
                    Text("Clear all")
                }
            }
        }

        Text(
            "Past assistant sessions, stored only on this device. " +
                "Tap one to read it, or search across all of them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        OutlinedTextField(
            value = query,
            onValueChange = viewModel::setQuery,
            label = { Text("Search conversations") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (query.isBlank()) {
            if (sessions.isEmpty()) {
                Text(
                    "No archived conversations yet. End an assistant " +
                        "session and it will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sessions, key = { it.id }) { session ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.openSession(session.id)
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme
                                    .surfaceContainerLow,
                            ),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement =
                                    Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    formatDate(session.startedAt),
                                    style = MaterialTheme.typography
                                        .labelMedium,
                                    color = MaterialTheme.colorScheme
                                        .onSurfaceVariant,
                                )
                                Text(
                                    session.preview.ifBlank {
                                        "(no text captured)"
                                    },
                                    style = MaterialTheme.typography
                                        .bodyMedium,
                                    maxLines = 2,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement =
                                        Arrangement.SpaceBetween,
                                    verticalAlignment =
                                        Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "${session.turnCount} turns",
                                        style = MaterialTheme.typography
                                            .labelSmall,
                                        color = MaterialTheme.colorScheme
                                            .onSurfaceVariant,
                                    )
                                    TextButton(
                                        onClick = {
                                            viewModel.deleteSession(
                                                session.id,
                                            )
                                        },
                                    ) {
                                        Text("Delete")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            if (hits.isEmpty()) {
                Text(
                    "No matches for \"$query\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(hits, key = { it.sessionId + it.excerpt }) { hit ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.openSession(hit.sessionId)
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme
                                    .surfaceContainerLow,
                            ),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement =
                                    Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    formatDate(hit.startedAt),
                                    style = MaterialTheme.typography
                                        .labelMedium,
                                    color = MaterialTheme.colorScheme
                                        .onSurfaceVariant,
                                )
                                hit.excerpt.forEach { turn ->
                                    Text(
                                        "${speakerLabel(turn.speaker)}: " +
                                            turn.text,
                                        style = MaterialTheme.typography
                                            .bodySmall,
                                        maxLines = 2,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionDetail(
    startedAt: Long,
    turns: List<ArchivedTurn>,
    onBack: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this conversation?") },
            text = {
                Text(
                    "This permanently deletes the session from " +
                        formatDate(startedAt) + ". This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirm = false },
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) {
            Text("← Back")
        }
        TextButton(onClick = { showDeleteConfirm = true }) {
            Text("Delete")
        }
    }

    Text(
        formatDate(startedAt),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 8.dp),
    )

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(turns) { turn ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        speakerLabel(turn.speaker),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (turn.speaker == "USER") {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.secondary
                        },
                    )
                    Text(
                        turnTimeFormat.format(Date(turn.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    turn.text,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
