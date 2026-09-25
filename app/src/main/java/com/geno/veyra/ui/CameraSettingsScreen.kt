package com.geno.veyra.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geno.veyra.settings.CameraResolution

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CameraSettingsScreen(
    onBack: () -> Unit,
    viewModel: CameraSettingsViewModel = hiltViewModel(),
) {
    val preferences by
        viewModel.preferences.collectAsStateWithLifecycle()

    val frameRates =
        listOf(2, 7, 15, 24, 30)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement =
            Arrangement.spacedBy(24.dp),
    ) {

        Text(
            text = "Camera Settings",
            style = MaterialTheme.typography.headlineSmall,
        )

        Text(
            text =
                "These settings apply the next time the camera starts.",
            style = MaterialTheme.typography.bodyMedium,
            color =
                MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(
            verticalArrangement =
                Arrangement.spacedBy(10.dp),
        ) {

            Text(
                text = "Resolution",
                style = MaterialTheme.typography.titleMedium,
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp),
                verticalArrangement =
                    Arrangement.spacedBy(8.dp),
            ) {

                CameraResolution.entries.forEach { resolution ->

                    FilterChip(
                        selected =
                            preferences.cameraResolution ==
                                resolution,

                        onClick = {
                            viewModel.setResolution(
                                resolution
                            )
                        },

                        label = {
                            Text(resolution.label)
                        },
                    )
                }
            }
        }

        Column(
            verticalArrangement =
                Arrangement.spacedBy(10.dp),
        ) {

            Text(
                text = "Frame Rate",
                style = MaterialTheme.typography.titleMedium,
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp),
                verticalArrangement =
                    Arrangement.spacedBy(8.dp),
            ) {

                frameRates.forEach { fps ->

                    FilterChip(
                        selected =
                            preferences.cameraFrameRate ==
                                fps,

                        onClick = {
                            viewModel.setFrameRate(fps)
                        },

                        label = {
                            Text("$fps FPS")
                        },
                    )
                }
            }
        }

        Text(
            text =
                "Current: ${preferences.cameraResolution.label}, " +
                    "${preferences.cameraFrameRate} FPS",

            style = MaterialTheme.typography.bodyMedium,

            color =
                MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Done")
        }
    }
}
