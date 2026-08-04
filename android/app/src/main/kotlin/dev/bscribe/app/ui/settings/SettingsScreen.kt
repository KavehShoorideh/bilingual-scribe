package dev.bscribe.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.bscribe.app.BuildConfig
import dev.bscribe.data.repo.LiveTranscriptMode
import dev.bscribe.data.repo.TranscribeLanguages
import dev.bscribe.data.repo.UpdateState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenModels: () -> Unit = {}) {
    val context = LocalContext.current
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context))
    val mode by vm.liveMode.collectAsState()
    val update by vm.updateState.collectAsState()
    val languages by vm.languages.collectAsState()
    val autoTranscribe by vm.autoTranscribe.collectAsState()

    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("Transcription", style = MaterialTheme.typography.titleMedium)
            Text(
                "Runs after you stop recording. Everything happens on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenModels)
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Speech models", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Download or switch the model used for transcription",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Transcribe automatically", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Start the pass as soon as you stop recording",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = autoTranscribe, onCheckedChange = vm::setAutoTranscribe)
            }

            Text("Languages", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Both decodes every passage twice and keeps whichever reads better — " +
                    "best for code-switching, about twice the work. Picking one language " +
                    "halves the time and heat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TranscribeLanguages.entries.forEach { candidate ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = languages == candidate,
                            onClick = { vm.setLanguages(candidate) },
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = languages == candidate,
                        onClick = { vm.setLanguages(candidate) },
                    )
                    Text(
                        when (candidate) {
                            TranscribeLanguages.BOTH -> "English and Farsi"
                            TranscribeLanguages.ENGLISH -> "English only"
                            TranscribeLanguages.FARSI -> "Farsi only"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

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
                            onClick = { vm.setLiveMode(candidate) },
                        )
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = mode == candidate,
                        onClick = { vm.setLiveMode(candidate) },
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

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("About", style = MaterialTheme.typography.titleMedium)
            Text(
                "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            UpdateSection(
                state = update,
                onCheck = vm::checkForUpdate,
                onDownload = { vm.download(it) },
                onInstall = {
                    val error = vm.install()
                    if (error != null) scope.launch { snackbars.showSnackbar(error) }
                },
                onDismiss = vm::dismiss,
            )
        }
    }
}

@Composable
private fun UpdateSection(
    state: UpdateState,
    onCheck: () -> Unit,
    onDownload: (dev.bscribe.data.repo.UpdateInfo) -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is UpdateState.Idle -> {
            Button(onClick = onCheck) { Text("Check for updates") }
        }

        is UpdateState.Checking -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.height(20.dp))
                Text("Checking…", Modifier.padding(start = 12.dp))
            }
        }

        is UpdateState.UpToDate -> {
            Text(
                "You're on the latest build.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onCheck) { Text("Check again") }
        }

        is UpdateState.Available -> {
            Text(
                "Version ${state.info.versionName} (build ${state.info.versionCode}) " +
                    "is available.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row {
                Button(onClick = { onDownload(state.info) }) { Text("Download") }
                TextButton(onClick = onDismiss) { Text("Not now") }
            }
        }

        is UpdateState.Downloading -> {
            Text("Downloading ${state.percent}%", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(
                progress = { state.percent / 100f },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }

        is UpdateState.ReadyToInstall -> {
            Text(
                "Version ${state.info.versionName} is verified and ready.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row {
                Button(onClick = onInstall) { Text("Install") }
                TextButton(onClick = onDismiss) { Text("Later") }
            }
        }

        is UpdateState.Failed -> {
            Text(
                state.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onCheck) { Text("Try again") }
        }
    }
}
