package dev.bscribe.app.ui.models

import android.content.Context
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
                    "downloading the weights is the only time the app uses the network.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
) {
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
                is ModelStatus.Absent ->
                    Button(onClick = onDownload, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Download")
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
                    Button(onClick = onDownload) { Text("Try again") }
                }
            }
        }
    }
}
