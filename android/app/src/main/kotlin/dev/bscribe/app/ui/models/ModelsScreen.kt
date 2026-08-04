package dev.bscribe.app.ui.models

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.bscribe.app.appContainer
import dev.bscribe.data.repo.ModelCatalog
import dev.bscribe.data.repo.ModelRepository
import dev.bscribe.data.repo.ModelSpec
import dev.bscribe.data.repo.ModelStatus
import dev.bscribe.data.repo.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ModelsViewModel(
    private val models: ModelRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    val statuses: StateFlow<Map<String, ModelStatus>> = models.statuses

    val selectedFinal: StateFlow<String> = settings.finalModelFile
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ModelCatalog.defaultFinal.fileName,
        )

    private val jobs = mutableMapOf<String, Job>()

    init {
        models.refresh()
    }

    fun download(spec: ModelSpec) {
        if (jobs[spec.fileName]?.isActive == true) return
        jobs[spec.fileName] = viewModelScope.launch { models.download(spec) }
    }

    /** Installs a model file the user picked from storage. */
    fun import(spec: ModelSpec, uri: Uri, resolver: ContentResolver) {
        if (jobs[spec.fileName]?.isActive == true) return
        jobs[spec.fileName] = viewModelScope.launch {
            models.importFrom(spec) { resolver.openInputStream(uri) }
        }
    }

    fun cancel(spec: ModelSpec) {
        jobs.remove(spec.fileName)?.cancel()
    }

    fun delete(spec: ModelSpec) {
        viewModelScope.launch { models.delete(spec) }
    }

    fun selectForFinalPass(spec: ModelSpec) {
        viewModelScope.launch { settings.setFinalModelFile(spec.fileName) }
    }

    companion object {
        fun factory(context: Context) = viewModelFactory {
            initializer {
                val c = context.appContainer
                ModelsViewModel(c.modelRepository, c.settingsRepository)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: ModelsViewModel = viewModel(factory = ModelsViewModel.factory(context))
    val statuses by vm.statuses.collectAsState()
    val selected by vm.selectedFinal.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Speech models") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                "Models run entirely on this phone. Nothing you say is uploaded — " +
                    "fetching the weights is the only time the app uses the network.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "If Download fails, the app has no network access. Tap Copy link, " +
                    "download the file in your browser, then tap Import file. " +
                    "Imports are checked against the same checksum.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            ModelCatalog.all.forEach { spec ->
                ModelRow(
                    spec = spec,
                    status = statuses[spec.fileName] ?: ModelStatus.Absent,
                    isSelected = selected == spec.fileName,
                    onDownload = { vm.download(spec) },
                    onCancel = { vm.cancel(spec) },
                    onDelete = { vm.delete(spec) },
                    onSelect = { vm.selectForFinalPass(spec) },
                    onImport = { uri -> vm.import(spec, uri, context.contentResolver) },
                    onCopyUrl = {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText(spec.fileName, spec.url))
                    },
                )
            }
        }
    }
}

@Composable
private fun ModelRow(
    spec: ModelSpec,
    status: ModelStatus,
    isSelected: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
    onImport: (Uri) -> Unit,
    onCopyUrl: () -> Unit,
) {
    // "*/*" rather than a MIME type: .bin has no registered type and most
    // file pickers hide it under anything narrower.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) onImport(uri) }

    Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (status is ModelStatus.Installed) {
                    RadioButton(selected = isSelected, onClick = onSelect)
                }
                Column(Modifier.weight(1f)) {
                    Text("${spec.label} · ${spec.sizeMb} MB", style = MaterialTheme.typography.titleMedium)
                    Text(
                        spec.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when (status) {
                is ModelStatus.Absent -> Row(
                    Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = onDownload) { Text("Download") }
                    TextButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                        Text("Import file")
                    }
                    TextButton(onClick = onCopyUrl) { Text("Copy link") }
                }

                is ModelStatus.Downloading -> {
                    LinearProgressIndicator(
                        progress = { status.percent / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${status.percent}%",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                }

                is ModelStatus.Installed -> Row {
                    Text(
                        if (isSelected) "Used for transcription" else "Downloaded",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(top = 14.dp),
                    )
                    TextButton(onClick = onDelete) { Text("Delete") }
                }

                is ModelStatus.Failed -> Column(Modifier.padding(top = 8.dp)) {
                    Text(
                        status.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = onDownload) { Text("Try again") }
                        TextButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                            Text("Import file")
                        }
                        TextButton(onClick = onCopyUrl) { Text("Copy link") }
                    }
                }
            }
        }
    }
}
