package com.example.concurrenteventtrackersdk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun TrackerDemoScreen(viewModel: MainViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "ConcurrentEventTracker Demo",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        val buttonsEnabled = !uiState.isUploading && !uiState.isShutDown

        Button(
            onClick = viewModel::trackSingleEvent,
            modifier = Modifier.fillMaxWidth(),
            enabled = buttonsEnabled
        ) {
            Text("Track Single Event")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = viewModel::trackBatch,
            modifier = Modifier.fillMaxWidth(),
            enabled = buttonsEnabled
        ) {
            Text("Track 5 Events (triggers auto-flush)")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = viewModel::uploadFlushedEvents,
            modifier = Modifier.fillMaxWidth(),
            enabled = buttonsEnabled
        ) {
            if (uiState.isUploading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
            } else {
                Text("Upload Flushed Events")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = viewModel::shutdown,
            modifier = Modifier.fillMaxWidth(),
            enabled = buttonsEnabled
        ) {
            Text("Shutdown Tracker")
        }

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = uiState.statusMessage,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (uiState.submittedCount > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Total submitted: ${uiState.submittedCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (uiState.statusMessage.contains("Logcat")) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Filter Logcat by tag: ConcurrentEventTracker",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
