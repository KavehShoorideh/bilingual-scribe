package dev.bscribe.app.ui.bench

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.bscribe.app.appContainer
import dev.bscribe.asr.LanguagePlan
import dev.bscribe.asr.WhisperEngine
import dev.bscribe.core.asr.DecodeParams
import dev.bscribe.core.audio.WavReader
import dev.bscribe.data.repo.ModelRepository
import dev.bscribe.data.repo.ModelSpec
import dev.bscribe.data.repo.SessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BenchRow(
    val model: String,
    val languages: String,
    val threads: Int,
    val audioMs: Long,
    val wallMs: Long,
    val words: Int,
) {
    val rtf: Double get() = wallMs.toDouble() / audioMs.coerceAtLeast(1)
}

sealed interface BenchState {
    data object Idle : BenchState
    data class Running(val label: String) : BenchState
    data class Done(val rows: List<BenchRow>) : BenchState
    data class Unavailable(val reason: String) : BenchState
}

/**
 * Times the real decode path against real audio.
 *
 * Uses the newest recording rather than a bundled sample: a synthetic clip
 * would not reflect this device, this microphone, or how the user actually
 * speaks, and it would add megabytes to the APK to be less informative.
 */
class BenchmarkViewModel(
    private val repo: SessionRepository,
    private val models: ModelRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<BenchState>(BenchState.Idle)
    val state: StateFlow<BenchState> = _state.asStateFlow()

    fun run() {
        if (_state.value is BenchState.Running) return
        viewModelScope.launch {
            val installed = models.installedModels()
            if (installed.isEmpty()) {
                _state.value = BenchState.Unavailable(
                    "No models installed. Download one in Settings → Speech models.",
                )
                return@launch
            }

            val sample = newestSample()
            if (sample == null) {
                _state.value = BenchState.Unavailable(
                    "No audio to test with. Record a note first.",
                )
                return@launch
            }

            val rows = mutableListOf<BenchRow>()
            for (spec in installed) {
                for (plan in listOf(LanguagePlan.ENGLISH_ONLY, LanguagePlan.DUAL)) {
                    for (threads in THREAD_STEPS) {
                        val label = "${spec.label} · ${plan.codes.joinToString("+")} · ${threads}t"
                        _state.value = BenchState.Running(label)
                        runCatching { measure(spec, plan, threads, sample) }
                            .onSuccess { rows += it }
                        _state.value = BenchState.Done(rows.toList())
                    }
                }
            }
            _state.value = BenchState.Done(rows)
        }
    }

    private suspend fun measure(
        spec: ModelSpec,
        plan: LanguagePlan,
        threads: Int,
        sample: Sample,
    ): BenchRow {
        val engine = WhisperEngine(models.fileFor(spec), plan, threads)
        try {
            val startedAt = System.currentTimeMillis()
            val words = engine.transcribeWindowed(
                durationMs = sample.durationMs,
                params = DecodeParams(nThreads = threads),
            ) { s, e -> WavReader.readRange(sample.file, s, e) }
            return BenchRow(
                model = spec.label,
                languages = plan.codes.joinToString("+"),
                threads = threads,
                audioMs = sample.durationMs,
                wallMs = System.currentTimeMillis() - startedAt,
                words = words.chosen.size,
            )
        } finally {
            engine.close()
        }
    }

    private data class Sample(val file: java.io.File, val durationMs: Long)

    private suspend fun newestSample(): Sample? {
        val (file, durationMs) = repo.newestRecording() ?: return null
        // Cap the clip: every row decodes the whole sample and there are
        // several rows, so an unbounded sample turns a sweep into an hour.
        return Sample(file, minOf(durationMs, MAX_SAMPLE_MS))
    }

    companion object {
        private val THREAD_STEPS = listOf(2, 4, 8)
        private const val MAX_SAMPLE_MS = 30_000L

        fun factory(context: Context) = viewModelFactory {
            initializer {
                val c = context.appContainer
                BenchmarkViewModel(c.sessionRepository, c.modelRepository)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: BenchmarkViewModel = viewModel(factory = BenchmarkViewModel.factory(context))
    val state by vm.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Benchmark") },
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
                "Times the real decode path on up to 30 seconds of your newest " +
                    "recording. Lower ×realtime is faster: 1.0× means a minute of " +
                    "audio takes a minute. This will make the phone warm.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (val s = state) {
                is BenchState.Idle ->
                    Button(onClick = vm::run, modifier = Modifier.padding(top = 16.dp)) {
                        Text("Run benchmark")
                    }

                is BenchState.Unavailable -> Text(
                    s.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 16.dp),
                )

                is BenchState.Running -> Row(
                    Modifier.padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp))
                    Text(s.label, style = MaterialTheme.typography.bodyMedium)
                }

                is BenchState.Done -> {
                    s.rows.forEach { row -> BenchCard(row) }
                    Button(onClick = vm::run, modifier = Modifier.padding(top = 16.dp)) {
                        Text("Run again")
                    }
                }
            }
        }
    }
}

@Composable
private fun BenchCard(row: BenchRow) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "${row.model} · ${row.languages} · ${row.threads} threads",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "${"%.2f".format(row.rtf)}× realtime · ${row.wallMs / 1000}s for " +
                    "${row.audioMs / 1000}s · ${row.words} words",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
