package com.geno.veyra.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geno.veyra.R
import com.geno.veyra.agent.AgentStatus
import com.geno.veyra.glasses.ConnectionState
import com.geno.veyra.glasses.GlassesDevice
import com.geno.veyra.glasses.RegistrationState

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onCameraTestClick: () -> Unit = {},
    onTranslateClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    viewModel: AgentViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    val status by
        viewModel.status.collectAsStateWithLifecycle()

    val registration by
        viewModel.registration.collectAsStateWithLifecycle()

    val devices by
        viewModel.devices.collectAsStateWithLifecycle()

    val connectionState by
        viewModel.connectionState.collectAsStateWithLifecycle()

    // Fires on every successful token mint; the dot below re-reads the
    // clock on each recomposition so it reflects token expiry too.
    val tokenTick by
        viewModel.hasLiveToken.collectAsStateWithLifecycle()

    /*
     * IMPORTANT:
     *
     * devices.firstOrNull { it.connected }
     *
     * is intentionally NOT used to determine whether the
     * MWDAT DeviceSession is connected.
     *
     * That "connected" value represents the underlying
     * Bluetooth/device link.
     *
     * connectionState represents our actual MWDAT
     * DeviceSession state.
     */
    val device =
        devices.firstOrNull()

    val micLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->

            if (granted) {
                viewModel.startSession()
            }
        }

    val running =
        status != AgentStatus.IDLE

    val onAssistantClick: () -> Unit = {

        if (running) {

            viewModel.stopSession()

        } else {

            val granted =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED

            if (granted) {

                viewModel.startSession()

            } else {

                micLauncher.launch(
                    Manifest.permission.RECORD_AUDIO
                )
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {

        BrandHeader(
            apiKeyConnected =
                tokenTick && viewModel.hasLiveTokenNow(),
            onApiKeyClick = onSettingsClick,
        )

        GlassesCard(
            registration = registration,
            connectionState = connectionState,
            device = device,
            onRegisterClick = viewModel::registerGlasses,
            onConnectClick = viewModel::connectGlasses,
        )

        AssistantHero(
            running = running,
            status = status,
            onClick = onAssistantClick,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            QuickTile(
                title = stringResource(R.string.home_tile_camera_test),
                subtitle = stringResource(R.string.home_tile_camera_test_sub),
                icon = Icons.Default.PhotoCamera,
                onClick = onCameraTestClick,
                modifier = Modifier.weight(1f),
            )

            QuickTile(
                title = stringResource(R.string.common_translate),
                subtitle = stringResource(R.string.home_tile_translate_sub),
                icon = Icons.Default.Translate,
                onClick = onTranslateClick,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/*
 * ---------------------------------------------------------
 * Brand header: V monogram + wordmark, API-key pill at right.
 * ---------------------------------------------------------
 */

@Composable
private fun BrandHeader(
    apiKeyConnected: Boolean,
    onApiKeyClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {

        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF3B6CFF),
                            Color(0xFF8B5CF6),
                        )
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {

            // Drawn V mark. Fixed colors only — no @Composable
            // theme reads inside a DrawScope.
            Canvas(
                modifier = Modifier.size(26.dp)
            ) {

                val strokeWidth =
                    3.2.dp.toPx()

                drawPath(
                    path = Path().apply {

                        moveTo(
                            size.width * 0.20f,
                            size.height * 0.16f,
                        )

                        lineTo(
                            size.width * 0.50f,
                            size.height * 0.84f,
                        )

                        lineTo(
                            size.width * 0.80f,
                            size.height * 0.16f,
                        )
                    },
                    color = Color.White,
                    style = Stroke(
                        width = strokeWidth,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
        }

        Column(
            modifier = Modifier.padding(start = 12.dp),
        ) {

            Text(
                text = "Veyra",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
            )

            Text(
                text = stringResource(R.string.home_tagline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(
            modifier = Modifier.weight(1f)
        )

        ApiKeyPill(
            connected = apiKeyConnected,
            onClick = onApiKeyClick,
        )
    }
}

/**
 * Compact Gemini connectivity pill: green while we hold a live
 * ephemeral token, red otherwise. Tapping opens Settings so a
 * missing key is one tap away.
 */
@Composable
private fun ApiKeyPill(
    connected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .background(
                if (connected) {
                    colors.primaryContainer
                } else {
                    colors.errorContainer
                },
            )
            .border(
                1.dp,
                if (connected) {
                    colors.primary.copy(alpha = 0.35f)
                } else {
                    colors.error.copy(alpha = 0.35f)
                },
                RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {

        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (connected) {
                        Color(0xFF4CAF50)
                    } else {
                        Color(0xFFEF5350)
                    },
                ),
        )

        Text(
            text = stringResource(R.string.home_api_key),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color =
                if (connected) {
                    colors.onPrimaryContainer
                } else {
                    colors.onErrorContainer
                },
        )
    }
}

/*
 * ---------------------------------------------------------
 * Glasses card: status plus the Register / Connect action.
 *
 * Registration and connection are different states.
 *
 * RegistrationState.REGISTERED means the app is
 * authorized/registered with Meta (via the Meta app on
 * first login).
 *
 * ConnectionState.CONNECTED means our MWDAT DeviceSession
 * has actually started.
 *
 * The Bluetooth link is deliberately NOT used here.
 * ---------------------------------------------------------
 */

@Composable
private fun GlassesCard(
    registration: RegistrationState,
    connectionState: ConnectionState,
    device: GlassesDevice?,
    onRegisterClick: () -> Unit,
    onConnectClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
    ) {

        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {

                GlassesGlyph()

                Text(
                    text = stringResource(R.string.home_glasses_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                text = device?.name ?: stringResource(R.string.home_no_device),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 34.dp),
            )

            Text(
                text = when {

                    connectionState == ConnectionState.CONNECTED ->
                        stringResource(R.string.home_connected)

                    connectionState == ConnectionState.CONNECTING ->
                        stringResource(R.string.common_connecting)

                    connectionState == ConnectionState.ERROR ->
                        stringResource(R.string.home_connection_error)

                    registration == RegistrationState.REGISTERED ->
                        stringResource(R.string.home_registered_not_connected)

                    else ->
                        registration.label()
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 34.dp),
            )

            Spacer(
                modifier = Modifier.height(8.dp)
            )

            when {

                registration != RegistrationState.REGISTERED -> {

                    Button(
                        onClick = onRegisterClick,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) {

                        Text(stringResource(R.string.home_register_glasses))
                    }

                    Text(
                        text =
                            stringResource(R.string.home_register_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            MaterialTheme.colorScheme
                                .onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally),
                    )
                }

                connectionState == ConnectionState.CONNECTING -> {

                    Text(
                        text = stringResource(R.string.home_connecting_to_glasses),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                connectionState == ConnectionState.CONNECTED -> {

                    Text(
                        text = stringResource(R.string.home_glasses_connected),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                connectionState == ConnectionState.ERROR -> {

                    Button(
                        onClick = onConnectClick,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) {

                        Text(stringResource(R.string.home_retry_connection))
                    }
                }

                else -> {

                    Button(
                        onClick = onConnectClick,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) {

                        Text(stringResource(R.string.home_connect_glasses))
                    }
                }
            }
        }
    }
}

/** Minimal line-drawn glasses glyph. Fixed colors only. */
@Composable
private fun GlassesGlyph(
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier.size(24.dp)
    ) {

        val strokeWidth =
            2.2.dp.toPx()

        val lineColor =
            Color(0xFFB9C2FF)

        val stroke =
            Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
            )

        val lensRadius =
            size.width * 0.20f

        val lensY =
            size.height * 0.56f

        // Left lens.
        drawCircle(
            color = lineColor,
            radius = lensRadius,
            center = Offset(
                size.width * 0.22f,
                lensY,
            ),
            style = stroke,
        )

        // Right lens.
        drawCircle(
            color = lineColor,
            radius = lensRadius,
            center = Offset(
                size.width * 0.78f,
                lensY,
            ),
            style = stroke,
        )

        // Bridge.
        drawLine(
            color = lineColor,
            start = Offset(
                size.width * 0.42f,
                size.height * 0.46f,
            ),
            end = Offset(
                size.width * 0.58f,
                size.height * 0.46f,
            ),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )

        // Left temple.
        drawLine(
            color = lineColor,
            start = Offset(
                size.width * 0.03f,
                size.height * 0.46f,
            ),
            end = Offset(
                size.width * 0.11f,
                size.height * 0.28f,
            ),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )

        // Right temple.
        drawLine(
            color = lineColor,
            start = Offset(
                size.width * 0.97f,
                size.height * 0.46f,
            ),
            end = Offset(
                size.width * 0.89f,
                size.height * 0.28f,
            ),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

/*
 * ---------------------------------------------------------
 * Assistant hero: the primary tap-to-talk surface.
 * ---------------------------------------------------------
 */

@Composable
private fun AssistantHero(
    running: Boolean,
    status: AgentStatus,
    onClick: () -> Unit,
) {
    // Brand gradient at rest; warm red while a session is live
    // so "Stop" reads as an active, stoppable state.
    val gradient =
        if (running) {

            Brush.linearGradient(
                listOf(
                    Color(0xFFE05252),
                    Color(0xFFB23A48),
                )
            )

        } else {

            Brush.linearGradient(
                listOf(
                    Color(0xFF3B6CFF),
                    Color(0xFF7C5CFF),
                    Color(0xFFA855F7),
                )
            )
        }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(gradient)
            .clickable(onClick = onClick)
            .padding(20.dp),
    ) {

        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {

                Text(
                    text =
                        if (running) {
                            stringResource(R.string.home_stop_assistant)
                        } else {
                            stringResource(R.string.home_start_assistant)
                        },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                )

                Text(
                    text =
                        if (running) {
                            status.label()
                        } else {
                            stringResource(R.string.home_assistant_hint)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }

            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

/*
 * ---------------------------------------------------------
 * Quick tiles: Camera Test / Translate.
 * ---------------------------------------------------------
 */

@Composable
private fun QuickTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
    ) {

        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )

            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
@ReadOnlyComposable
private fun AgentStatus.label(): String =
    when (this) {

        AgentStatus.IDLE ->
            stringResource(R.string.status_stopped)

        AgentStatus.CONNECTING ->
            stringResource(R.string.common_connecting)

        AgentStatus.LISTENING ->
            stringResource(R.string.status_listening)

        AgentStatus.RECONNECTING ->
            stringResource(R.string.status_reconnecting)

        AgentStatus.ERROR ->
            stringResource(R.string.status_error)
    }

@Composable
@ReadOnlyComposable
private fun RegistrationState.label(): String =
    when (this) {

        RegistrationState.REGISTERED ->
            stringResource(R.string.reg_registered)

        RegistrationState.REGISTERING ->
            stringResource(R.string.reg_registering)

        RegistrationState.NOT_REGISTERED ->
            stringResource(R.string.reg_not_registered)

        RegistrationState.REVOKED ->
            stringResource(R.string.reg_revoked)

        RegistrationState.UNKNOWN ->
            stringResource(R.string.reg_unknown)
    }
