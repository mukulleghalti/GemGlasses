package com.lpecom.gemglasses.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.lpecom.gemglasses.agent.AgentStatus
import com.lpecom.gemglasses.glasses.RegistrationState

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: AgentViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val status by viewModel.status.collectAsStateWithLifecycle()
    val registration by viewModel.registration.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.startSession() }

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

        GlassesCard(registration = registration, deviceName = devices.firstOrNull()?.name)

        Spacer(Modifier.height(8.dp))

        StatusDot(status)

        val running = status != AgentStatus.IDLE
        Button(
            onClick = {
                if (running) {
                    viewModel.stopSession()
                } else {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) viewModel.startSession()
                    else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (running) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            ),
        ) {
            Text(if (running) "Encerrar" else "Start Assistant")
        }

        if (registration != RegistrationState.REGISTERED) {
            Button(
                onClick = viewModel::registerGlasses,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Conectar óculos (Meta AI)") }
        }
    }
}

@Composable
private fun GlassesCard(registration: RegistrationState, deviceName: String?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Glasses", style = MaterialTheme.typography.labelLarge)
            Text(
                text = deviceName ?: "Nenhum dispositivo",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = registration.label(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusDot(status: AgentStatus) {
    val (color, label) = when (status) {
        AgentStatus.IDLE -> Color.Gray to "Stopped"
        AgentStatus.CONNECTING -> Color(0xFFF59E0B) to "Conectando…"
        AgentStatus.LISTENING -> Color(0xFF22C55E) to "Ouvindo"
        AgentStatus.RECONNECTING -> Color(0xFFF59E0B) to "Reconectando…"
        AgentStatus.ERROR -> Color(0xFFEF4444) to "Erro"
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(
            Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(label, color = color, fontWeight = FontWeight.Medium)
    }
}

private fun RegistrationState.label(): String = when (this) {
    RegistrationState.REGISTERED -> "Connected to Meta AI"
    RegistrationState.REGISTERING -> "Registrando…"
    RegistrationState.NOT_REGISTERED -> "Não registrado"
    RegistrationState.REVOKED -> "Registro revogado — reconecte"
    RegistrationState.UNKNOWN -> "Status desconhecido"
}
