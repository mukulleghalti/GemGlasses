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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geno.veyra.R
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
            text = stringResource(R.string.camera_settings_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        Text(
            text =
                stringResource(R.string.camera_settings_note),
            style = MaterialTheme.typography.bodyMedium,
            color =
                MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(
            verticalArrangement =
                Arrangement.spacedBy(10.dp),
        ) {

            Text(
                text = stringResource(R.string.camera_resolution),
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
                            Text(stringResource(resolution.labelRes))
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
                text = stringResource(R.string.camera_frame_rate),
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
                            Text(stringResource(R.string.camera_fps, fps))
                        },
                    )
                }
            }
        }

        Text(
            text =
                stringResource(
                    R.string.camera_current,
                    stringResource(preferences.cameraResolution.labelRes),
                    preferences.cameraFrameRate,
                ),

            style = MaterialTheme.typography.bodyMedium,

            color =
                MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.common_done))
        }
    }
}
