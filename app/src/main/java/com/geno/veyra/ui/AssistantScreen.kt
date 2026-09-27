package com.geno.veyra.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geno.veyra.R
import com.geno.veyra.agent.AgentStatus
import com.geno.veyra.state.CitedPlace
import com.geno.veyra.state.TranscriptEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The assistant tab: the live session is the screen itself. When a
 * session is running the transcript fills the view; when idle, a calm
 * placeholder with the Veyra mark and a start button. The hamburger
 * opens a drawer with conversation search and history.
 */
@Composable
fun AssistantScreen(
    modifier: Modifier = Modifier,
    viewModel: AgentViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val status by viewModel.status.collectAsStateWithLifecycle()
    val running = status != AgentStatus.IDLE
    val micMuted by viewModel.micMuted.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(
        initialValue = DrawerValue.Closed,
    )
    val scope = rememberCoroutineScope()

    /*
     * The drawer content stays composed while closed, so the history
     * ViewModel's init-time load goes stale. Refresh every time the
     * drawer opens so newly archived sessions appear.
     */
    val historyViewModel: ConversationHistoryViewModel = hiltViewModel()
    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Open) {
            historyViewModel.refresh()
        }
    }

    val micLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) {
                viewModel.startSession()
            }
        }

    fun startConversation() {
        val granted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            viewModel.startSession()
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(300.dp),
            ) {
                ConversationDrawerContent(
                    canStartNew = !running,
                    onNewConversation = {
                        scope.launch { drawerState.close() }
                        startConversation()
                    },
                    historyViewModel = historyViewModel,
                )
            }
        },
    ) {
        Scaffold(
            modifier = modifier,
            topBar = {
                AssistantTopBar(
                    onMenuClick = {
                        scope.launch { drawerState.open() }
                    },
                    running = running,
                    micMuted = micMuted,
                    onToggleMic = {
                        viewModel.setMicMuted(!micMuted)
                    },
                )
            },
            floatingActionButton = {
                if (!running) {
                    StartConversationButton(
                        onClick = ::startConversation,
                    )
                }
            },
        ) { innerPadding ->
            if (running) {
                LiveTranscript(
                    modifier = Modifier.padding(innerPadding),
                    viewModel = viewModel,
                    onRetry = {
                        viewModel.stopSession()
                        startConversation()
                    },
                )
            } else {
                IdleAssistant(
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssistantTopBar(
    onMenuClick: () -> Unit,
    running: Boolean,
    micMuted: Boolean,
    onToggleMic: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.common_assistant)) },
        navigationIcon = {
            IconButton(onClick = onMenuClick) {
                Icon(
                    imageVector = Icons.Filled.Menu,
                    contentDescription = stringResource(R.string.assistant_menu_desc),
                )
            }
        },
        actions = {
            if (running) {
                /*
                 * Mic mute as an icon: filled mic normally, crossed
                 * mic in the error color when muted so the state is
                 * obvious at a glance.
                 */
                IconButton(onClick = onToggleMic) {
                    Icon(
                        imageVector =
                            if (micMuted) Icons.Default.MicOff
                            else Icons.Default.Mic,
                        contentDescription =
                            if (micMuted) stringResource(R.string.assistant_unmute_desc)
                            else stringResource(R.string.assistant_mute_desc),
                        tint =
                            if (micMuted) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                    )
                }
            }
        },
    )
}

/**
 * Drawer content: a New-conversation entry plus the archived
 * session browser (search, list, delete) in compact form.
 */
@Composable
private fun ConversationDrawerContent(
    canStartNew: Boolean,
    onNewConversation: () -> Unit,
    historyViewModel: ConversationHistoryViewModel,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.assistant_drawer_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(
                horizontal = 20.dp,
                vertical = 16.dp,
            ),
        )

        if (canStartNew) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNewConversation)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.assistant_new_conversation),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            HorizontalDivider()
        }

        Box(modifier = Modifier.weight(1f)) {
            ConversationHistoryScreen(
                onBack = {},
                compact = true,
                viewModel = historyViewModel,
            )
        }
    }
}

/**
 * Calm idle state: the Veyra mark breathing with expanding ripple
 * rings on a loop, and a short line of text. The + button (bottom
 * right, via Scaffold) starts a session.
 */
@Composable
private fun IdleAssistant(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        RipplingVLogo()

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            stringResource(R.string.assistant_idle_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RipplingVLogo() {
    val ripple1 = remember { Animatable(0f) }
    val ripple2 = remember { Animatable(0f) }
    // Read in the @Composable context: Canvas {} is a DrawScope, where
    // MaterialTheme.colorScheme is not callable.
    val logoPrimary = MaterialTheme.colorScheme.primary

    val breathe = rememberInfiniteTransition(label = "vBreathe")
    val coreScale by breathe.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "vCoreScale",
    )

    LaunchedEffect(Unit) {
        launch {
            while (isActive) {
                ripple1.snapTo(0f)
                ripple1.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = 2600,
                        easing = LinearEasing,
                    ),
                )
            }
        }
        launch {
            delay(1300)
            while (isActive) {
                ripple2.snapTo(0f)
                ripple2.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = 2600,
                        easing = LinearEasing,
                    ),
                )
            }
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(140.dp),
    ) {
        RippleRing(progress = ripple1.value)
        RippleRing(progress = ripple2.value)

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(84.dp)
                .graphicsLayer {
                    scaleX = coreScale
                    scaleY = coreScale
                }
                .clip(CircleShape)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerLow,
                )
                .border(
                    width = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary.copy(
                        alpha = 0.55f,
                    ),
                    shape = CircleShape,
                ),
        ) {
            Canvas(modifier = Modifier.size(40.dp)) {
                val path = Path().apply {
                    moveTo(
                        size.width * 0.22f,
                        size.height * 0.2f,
                    )
                    lineTo(
                        size.width * 0.5f,
                        size.height * 0.8f,
                    )
                    lineTo(
                        size.width * 0.78f,
                        size.height * 0.2f,
                    )
                }
                drawPath(
                    path = path,
                    color = logoPrimary,
                    style = Stroke(
                        width = 12f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
        }
    }
}

@Composable
private fun RippleRing(progress: Float) {
    val scale = 0.62f + 0.73f * progress
    Box(
        modifier = Modifier
            .size(124.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = 0.55f * (1f - progress)
            }
            .border(
                width = 1.5.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape,
            ),
    )
}

/**
 * The new-conversation button: a 48dp circle (a touch smaller than
 * the standard FAB) with a soft pulse ring on the same rhythm as
 * the idle logo, so the motion leads the eye to it.
 */
@Composable
private fun StartConversationButton(onClick: () -> Unit) {
    val pulse = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        while (isActive) {
            pulse.snapTo(0f)
            pulse.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 2600,
                    easing = LinearEasing,
                ),
            )
        }
    }

    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .graphicsLayer {
                    val s = 1f + 0.5f * pulse.value
                    scaleX = s
                    scaleY = s
                    alpha = 0.45f * (1f - pulse.value)
                }
                .border(
                    width = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                ),
        )
        Surface(
            onClick = onClick,
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shadowElevation = 6.dp,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.assistant_new_conversation),
                )
            }
        }
    }
}

/**
 * The running session's transcript. This is the assistant tab's main
 * view while a session is live; the drawer stays reachable via the
 * hamburger for history.
 */
@Composable
private fun LiveTranscript(
    modifier: Modifier = Modifier,
    viewModel: AgentViewModel = hiltViewModel(),
    onRetry: () -> Unit,
) {
    val entries by viewModel.transcript.collectAsStateWithLifecycle()
    val places by viewModel.places.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val connectionError by viewModel.connectionError.collectAsStateWithLifecycle()
    val assistantRunning = status != AgentStatus.IDLE
    val failed = status == AgentStatus.ERROR

    /*
     * Popup, shown once per failure: the user gets the reason
     * immediately instead of staring at a "connecting" screen.
     * Dismissing leaves the inline error card in place; a new
     * failure re-arms the popup.
     */
    var dialogDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(connectionError) { dialogDismissed = false }
    if (failed && !dialogDismissed) {
        ConnectionFailedDialog(
            detail = connectionError,
            onRetry = {
                dialogDismissed = true
                onRetry()
            },
            onDismiss = { dialogDismissed = true },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        if (failed) MaterialTheme.colorScheme.error
                        else Color(0xFF34C759)
                    ),
            )
            Text(
                text = when {
                    failed -> stringResource(R.string.assistant_connection_failed)
                    assistantRunning -> stringResource(R.string.assistant_live)
                    else -> stringResource(R.string.assistant_ended)
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (failed) {
            ConnectionErrorCard(
                detail = connectionError,
                onRetry = onRetry,
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (entries.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.assistant_empty_transcript),
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
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
                        stringResource(R.string.assistant_cited_places),
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
}

/**
 * Shown when the assistant connection failed instead of hanging on
 * "connecting". Displays the server's reason when one was captured,
 * with an explicit retry.
 */
@Composable
private fun ConnectionErrorCard(
    detail: String?,
    onRetry: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.assistant_connection_failed_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                detail?.takeIf { it.isNotBlank() }
                    ?: stringResource(
                        R.string.assistant_connection_failed_generic
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Button(onClick = onRetry) {
                Text(stringResource(R.string.assistant_retry))
            }
        }
    }
}

/**
 * Popup shown once per connection failure. Quota rejections — the
 * common free-tier case — get specific guidance; anything else shows
 * the server's reason when one was captured.
 */
@Composable
private fun ConnectionFailedDialog(
    detail: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val message = when {
        isQuotaFailure(detail) ->
            stringResource(R.string.assistant_connection_failed_quota)

        !detail.isNullOrBlank() -> detail
        else ->
            stringResource(R.string.assistant_connection_failed_generic)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    R.string.assistant_connection_failed_dialog_title
                ),
            )
        },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.assistant_retry))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.assistant_dismiss))
            }
        },
    )
}

/** True when the setup failure looks like an API quota rejection. */
private fun isQuotaFailure(detail: String?): Boolean {
    if (detail.isNullOrBlank()) return false
    val d = detail.lowercase()
    return d.contains("quota") ||
        d.contains("429") ||
        d.contains("resource_exhausted") ||
        d.contains("rate limit")
}

@Composable
private fun TranscriptLine(
    entry: TranscriptEntry,
) {
    val (label, weight) = when (entry.speaker) {
        TranscriptEntry.Speaker.USER ->
            stringResource(R.string.transcript_you) to FontWeight.SemiBold

        TranscriptEntry.Speaker.ASSISTANT ->
            stringResource(R.string.common_assistant) to FontWeight.Normal

        TranscriptEntry.Speaker.SYSTEM ->
            stringResource(R.string.transcript_system) to FontWeight.Light
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
                    contentDescription = stringResource(R.string.captured_photo_desc),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 500.dp),
                )
            } else {
                Text(
                    stringResource(R.string.captured_photo_error),
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

