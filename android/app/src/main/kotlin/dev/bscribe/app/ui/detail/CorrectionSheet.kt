package dev.bscribe.app.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** What the voice route is doing, if anything. */
sealed interface DictationState {
    data object Idle : DictationState
    data object Recording : DictationState
    data object Transcribing : DictationState
    data class Failed(val reason: String) : DictationState
}

/**
 * Correcting a span of transcript.
 *
 * The original is shown above the field rather than replaced by it: a
 * correction is a *pair* — what the model heard and what was actually said —
 * and that pair is the entire training signal. Losing sight of the original
 * while editing makes it easy to "fix" a word into something that was never
 * spoken, which would poison the dataset rather than improve it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CorrectionSheet(
    originalText: String,
    dictation: DictationState,
    dictated: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onStartDictation: () -> Unit,
    onStopDictation: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember(originalText) { mutableStateOf(originalText) }

    // Speaking the correction fills the field rather than saving directly, so
    // a misheard dictation can be fixed instead of replacing one error with
    // another.
    LaunchedEffect(dictated) { dictated?.let { text = it } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text("Heard", style = MaterialTheme.typography.labelMedium)
            Text(
                originalText.ifBlank { "(nothing)" },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Actually said") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )

            Spacer(Modifier.height(8.dp))
            when (dictation) {
                is DictationState.Idle -> OutlinedButton(onClick = onStartDictation) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Spacer(Modifier.height(0.dp))
                    Text("  Say it instead")
                }

                is DictationState.Recording -> Button(onClick = onStopDictation) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Text("  Stop and transcribe")
                }

                is DictationState.Transcribing -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.height(20.dp))
                    Text("  Transcribing…", style = MaterialTheme.typography.bodyMedium)
                }

                is DictationState.Failed -> Column {
                    Text(
                        dictation.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = onStartDictation) { Text("Try again") }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onSave(text) }) { Text("Save") }
                Spacer(Modifier.height(0.dp))
                // Saving nothing marks the span as not speech — a misfire or
                // background noise — which the export must exclude rather than
                // treat as a label.
                TextButton(onClick = { onSave("") }) { Text("Not speech") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    }
}
