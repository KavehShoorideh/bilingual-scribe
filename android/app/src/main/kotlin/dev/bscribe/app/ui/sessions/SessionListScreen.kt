package dev.bscribe.app.ui.sessions

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.bscribe.app.BuildConfig
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.bscribe.app.appContainer
import dev.bscribe.app.record.RecordingService
import dev.bscribe.app.util.formatClock
import dev.bscribe.app.util.formatTimestamp
import dev.bscribe.core.model.SessionState
import dev.bscribe.data.db.SessionWithRecordings
import dev.bscribe.data.repo.SessionRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class SessionListViewModel(repo: SessionRepository) : ViewModel() {
    val sessions: StateFlow<List<SessionWithRecordings>> =
        repo.observeSessions().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList(),
        )

    companion object {
        fun factory(context: Context) = viewModelFactory {
            initializer { SessionListViewModel(context.appContainer.sessionRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    onRecord: () -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: SessionListViewModel =
        viewModel(factory = SessionListViewModel.factory(context))
    val sessions by viewModel.sessions.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            context.startForegroundService(
                RecordingService.intent(context, RecordingService.ACTION_START),
            )
            onRecord()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bilingual Scribe") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ),
                    )
                },
                icon = { Icon(Icons.Default.Mic, contentDescription = null) },
                text = { Text("Record") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (sessions.isEmpty()) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("No notes yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Tap Record and just talk — the audio is saved no matter what.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sessions, key = { it.session.id }) { row ->
                        SessionCard(row, onClick = { onOpenSession(row.session.id) })
                    }
                }
            }

            // Visible build stamp: the quickest way to confirm an update
            // actually landed, without digging into Settings.
            Text(
                "v${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun SessionCard(row: SessionWithRecordings, onClick: () -> Unit) {
    val session = row.session
    val totalMs = row.recordings.sumOf { it.durationMs }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                session.title ?: formatTimestamp(session.createdAt),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatClock(totalMs), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(12.dp))
                AssistChip(
                    onClick = onClick,
                    label = { Text(stateLabel(session.state)) },
                )
            }
        }
    }
}

private fun stateLabel(state: SessionState): String = when (state) {
    SessionState.RECORDING -> "Recording…"
    SessionState.PAUSED -> "Paused"
    SessionState.STOPPED -> "Audio saved"
    SessionState.TRANSCRIBING -> "Transcribing…"
    SessionState.TRANSCRIBED -> "Transcribed"
    SessionState.REVIEWED -> "Reviewed"
}
