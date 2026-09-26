package com.geno.veyra.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geno.veyra.R
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

@Composable
private fun speakerLabel(speaker: String): String =
    if (speaker == "USER") stringResource(R.string.transcript_you) else "Veyra"

/**
 * Browse, search, and delete archived assistant sessions. Everything
 * shown here lives only on this device.
 */
@Composable
fun ConversationHistoryScreen(
    onBack: () -> Unit,
    compact: Boolean = false,
    viewModel: ConversationHistoryViewModel = hiltViewModel(),
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val hits by viewModel.hits.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()

    var showClearAllConfirm by remember { mutableStateOf(false) }

    BackHandler(enabled = selected != null) {
        viewModel.closeSession()
    }

    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text(stringResource(R.string.history_delete_all_title)) },
            text = {
                Text(
                    stringResource(R.string.history_delete_all_message),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAll()
                        showClearAllConfirm = false
                    },
                ) {
                    Text(stringResource(R.string.history_delete_all_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearAllConfirm = false },
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                if (compact) {
                    PaddingValues(vertical = 8.dp)
                } else {
                    PaddingValues(16.dp)
                },
            ),
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

        if (!compact) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.history_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (sessions.isNotEmpty()) {
                    TextButton(
                        onClick = { showClearAllConfirm = true },
                    ) {
                        Text(stringResource(R.string.history_clear_all))
                    }
                }
            }

            Text(
                stringResource(R.string.history_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        if (compact && sessions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = { showClearAllConfirm = true },
                ) {
                    Text(stringResource(R.string.history_clear_all))
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = viewModel::setQuery,
            label = { Text(stringResource(R.string.history_search_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (query.isBlank()) {
            if (sessions.isEmpty()) {
                Text(
                    stringResource(R.string.history_empty),
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
                                    session.title
                                        ?: formatDate(session.startedAt),
                                    style = MaterialTheme.typography
                                        .labelMedium,
                                    color = MaterialTheme.colorScheme
                                        .onSurfaceVariant,
                                )
                                Text(
                                    session.preview.ifBlank {
                                        stringResource(R.string.history_no_text)
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
                                        pluralStringResource(R.plurals.history_turns, session.turnCount, session.turnCount),
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
                                        Text(stringResource(R.string.common_delete))
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
                    stringResource(R.string.history_no_matches, query),
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
            title = { Text(stringResource(R.string.history_delete_one_title)) },
            text = {
                Text(
                    stringResource(R.string.history_delete_one_message, formatDate(startedAt)),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                ) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirm = false },
                ) {
                    Text(stringResource(R.string.common_cancel))
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
            Text(stringResource(R.string.history_back))
        }
        TextButton(onClick = { showDeleteConfirm = true }) {
            Text(stringResource(R.string.common_delete))
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
