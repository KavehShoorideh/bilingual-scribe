package dev.bscribe.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.bscribe.app.appContainer
import dev.bscribe.data.repo.LiveTranscriptMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = context.appContainer.settingsRepository
    val scope = rememberCoroutineScope()
    val mode by settings.liveTranscriptMode.collectAsState(initial = LiveTranscriptMode.FULL)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("Live transcript while recording", style = MaterialTheme.typography.titleMedium)
            Text(
                "Applies once live transcription lands (milestone M1b). " +
                    "Economy trades latency for battery; Off records audio only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LiveTranscriptMode.entries.forEach { candidate ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = mode == candidate,
                            onClick = { scope.launch { settings.setLiveTranscriptMode(candidate) } },
                        )
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = mode == candidate,
                        onClick = { scope.launch { settings.setLiveTranscriptMode(candidate) } },
                    )
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(
                            when (candidate) {
                                LiveTranscriptMode.FULL -> "Full"
                                LiveTranscriptMode.ECONOMY -> "Economy"
                                LiveTranscriptMode.OFF -> "Off (capture only)"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}
