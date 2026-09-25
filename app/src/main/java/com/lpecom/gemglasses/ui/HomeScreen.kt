package com.lpecom.gemglasses.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.lpecom.gemglasses.agent.AgentStatus
import com.lpecom.gemglasses.glasses.ConnectionState
import com.lpecom.gemglasses.glasses.GlassesDevice
import com.lpecom.gemglasses.glasses.RegistrationState

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onCameraTestClick: () -> Unit = {},
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {

        Text(
            text = "GemGlasses",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        Text(
            text = "Gemini on your Ray-Ban",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ApiKeyStatusRow(
            connected = tokenTick && viewModel.hasLiveTokenNow(),
            onClick = onSettingsClick,
        )

        GlassesCard(
            registration = registration,
            connectionState = connectionState,
            device = device,
        )

        Spacer(
            modifier = Modifier.height(8.dp)
        )

        StatusDot(status)

        val running =
            status != AgentStatus.IDLE

        Button(
            onClick = {

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
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor =
                    if (running) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
            ),
        ) {

            Text(
                if (running) {
                    "Stop Assistant"
                } else {
                    "Start Assistant"
                }
            )
        }

        /*
         * ---------------------------------------------------------
         * Camera Test
         * ---------------------------------------------------------
         *
         * This is intentionally separate from the Assistant.
         *
         * CameraTestScreen does NOT start:
         *
         * - Gemini
         * - MicStreamer
         * - BluetoothAudioRouter
         * - SCO
         */
        Button(
            onClick = onCameraTestClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {

            Text("📷 Camera Test")
        }

        /*
         * ---------------------------------------------------------
         * Registration / MWDAT DeviceSession connection
         * ---------------------------------------------------------
         *
         * Registration and connection are different states.
         *
         * RegistrationState:
         *
         *   REGISTERED
         *
         * means the app is authorized/registered with Meta.
         *
         * ConnectionState:
         *
         *   CONNECTED
         *
         * means our MWDAT DeviceSession has actually started.
         *
         * The Bluetooth link is deliberately NOT used here.
         */

        when {

            registration != RegistrationState.REGISTERED -> {

                Button(
                    onClick = viewModel::registerGlasses,
                    modifier = Modifier.fillMaxWidth(),
                ) {

                    Text("Register glasses")
                }
            }

            connectionState == ConnectionState.CONNECTING -> {

                Text(
                    text = "Connecting to glasses…",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            connectionState == ConnectionState.CONNECTED -> {

                Text(
                    text = "Glasses connected",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            connectionState == ConnectionState.ERROR -> {

                Button(
                    onClick = viewModel::connectGlasses,
                    modifier = Modifier.fillMaxWidth(),
                ) {

                    Text("Retry connection")
                }
            }

            else -> {

                Button(
                    onClick = viewModel::connectGlasses,
                    modifier = Modifier.fillMaxWidth(),
                ) {

                    Text("Connect glasses")
                }
            }
        }
    }
}

@Composable
private fun GlassesCard(
    registration: RegistrationState,
    connectionState: ConnectionState,
    device: GlassesDevice?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {

        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {

            Text(
                text = "Glasses",
                style = MaterialTheme.typography.labelLarge,
            )

            Text(
                text = device?.name ?: "No device found",
                style = MaterialTheme.typography.bodyLarge,
            )

            Text(
                text = when {

                    connectionState == ConnectionState.CONNECTED ->
                        "Connected"

                    connectionState == ConnectionState.CONNECTING ->
                        "Connecting…"

                    connectionState == ConnectionState.ERROR ->
                        "Connection error"

                    registration == RegistrationState.REGISTERED ->
                        "Registered — not connected"

                    else ->
                        registration.label()
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusDot(
    status: AgentStatus,
) {
    val (color, label) =
        when (status) {

            AgentStatus.IDLE ->
                Color.Gray to "Stopped"

            AgentStatus.CONNECTING ->
                Color(0xFFF59E0B) to "Connecting…"

            AgentStatus.LISTENING ->
                Color(0xFF22C55E) to "Listening"

            AgentStatus.RECONNECTING ->
                Color(0xFFF59E0B) to "Reconnecting…"

            AgentStatus.ERROR ->
                Color(0xFFEF4444) to "Error"
        }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {

        Spacer(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(color),
        )

        Text(
            text = label,
            color = color,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun RegistrationState.label(): String =
    when (this) {

        RegistrationState.REGISTERED ->
            "Registered"

        RegistrationState.REGISTERING ->
            "Registering…"

        RegistrationState.NOT_REGISTERED ->
            "Not registered"

        RegistrationState.REVOKED ->
            "Registration revoked — reconnect"

        RegistrationState.UNKNOWN ->
            "Status unknown"
    }


/**
 * Lightweight Gemini connectivity light: a green dot while we hold a
 * live ephemeral token, red otherwise. Tapping it opens Settings so a
 * missing key is one tap away.
 */
@Composable
private fun ApiKeyStatusRow(
    connected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(
                if (connected) {
                    colors.primaryContainer
                } else {
                    colors.errorContainer
                },
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(
                    if (connected) {
                        Color(0xFF2E7D32)
                    } else {
                        Color(0xFFC62828)
                    },
                ),
        )
        Text(
            text =
                if (connected) {
                    "Gemini connected"
                } else {
                    "Gemini not connected — tap to add API key"
                },
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (connected) {
                    colors.onPrimaryContainer
                } else {
                    colors.onErrorContainer
                },
            modifier = Modifier.weight(1f),
        )
    }
}
