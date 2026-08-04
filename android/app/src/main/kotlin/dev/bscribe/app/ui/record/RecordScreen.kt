package dev.bscribe.app.ui.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.bscribe.app.appContainer
import dev.bscribe.app.record.RecorderStatus
import dev.bscribe.app.record.RecordingService
import dev.bscribe.app.util.formatClock

/**
 * The speaking surface: timer, level meter, live transcript area, transport.
 * All state comes from [dev.bscribe.app.record.RecorderStateHolder] — this
 * screen survives process/UI recreation because the service owns the truth.
 */
@Composable
fun RecordScreen(onFinished: (sessionId: String?) -> Unit) {
    val context = LocalContext.current
    val holder = context.appContainer.recorderState

    val status by holder.status.collectAsState()
    val sessionId by holder.sessionId.collectAsState()
    val elapsedMs by holder.elapsedMs.collectAsState()
    val vu by holder.vuMeter.collectAsState()
    val committed by holder.committedText.collectAsState()
    val hypothesis by holder.hypothesisText.collectAsState()

    // Remember the session across the stop transition so we can land on it.
    var lastSessionId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sessionId) { if (sessionId != null) lastSessionId = sessionId }
    LaunchedEffect(status) {
        if (status == RecorderStatus.IDLE && lastSessionId != null) onFinished(lastSessionId)
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = formatClock(elapsedMs),
                style = MaterialTheme.typography.displayLarge,
            )
            Text(
                text = when (status) {
                    RecorderStatus.RECORDING -> "Listening…"
                    RecorderStatus.PAUSED -> "Paused"
                    RecorderStatus.STARTING -> "Starting…"
                    RecorderStatus.STOPPING -> "Saving…"
                    RecorderStatus.IDLE -> ""
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.height(12.dp))
            // No animation: the level already carries its own attack/decay
            // envelope from the capture thread at ~50 Hz, and interpolating
            // between values on top of that only delays the rise.
            LinearProgressIndicator(
                progress = { vu },
                modifier = Modifier.fillMaxWidth().height(8.dp),
            )
            Spacer(Modifier.height(20.dp))

            Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                ) {
                    if (committed.isEmpty() && hypothesis.isEmpty()) {
                        Text(
                            "Your words are being saved.\nLive transcription arrives in the next milestone — for now, speak freely; nothing is lost.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                        )
                    } else {
                        Text(
                            buildString {
                                append(committed)
                                if (hypothesis.isNotEmpty()) {
                                    if (isNotEmpty()) append(' ')
                                    append(hypothesis)
                                }
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                when (status) {
                    RecorderStatus.RECORDING -> {
                        TransportButton("Pause", Icons.Default.Pause) {
                            context.startService(
                                RecordingService.intent(context, RecordingService.ACTION_PAUSE),
                            )
                        }
                    }
                    RecorderStatus.PAUSED -> {
                        TransportButton("Resume", Icons.Default.Mic) {
                            context.startService(
                                RecordingService.intent(context, RecordingService.ACTION_RESUME),
                            )
                        }
                    }
                    else -> Unit
                }
                if (status == RecorderStatus.RECORDING || status == RecorderStatus.PAUSED) {
                    TransportButton("Stop", Icons.Default.Stop) {
                        context.startService(
                            RecordingService.intent(context, RecordingService.ACTION_STOP),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TransportButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    FilledTonalButton(onClick = onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
