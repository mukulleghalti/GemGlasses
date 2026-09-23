package com.lpecom.gemglasses.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lpecom.gemglasses.state.CitedPlace
import com.lpecom.gemglasses.state.TranscriptEntry
import android.graphics.BitmapFactory

/**
 * Live transcript plus captured images and Maps-grounded places.
 */
@Composable
fun TranscriptScreen(
    modifier: Modifier = Modifier,
    viewModel: AgentViewModel = hiltViewModel(),
) {
    val entries by viewModel.transcript.collectAsStateWithLifecycle()
    val places by viewModel.places.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Transcription",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 12.dp),
            )
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
