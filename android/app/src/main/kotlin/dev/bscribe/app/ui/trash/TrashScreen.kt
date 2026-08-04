package dev.bscribe.app.ui.trash

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.bscribe.app.util.formatClock
import dev.bscribe.app.util.formatTimestamp
import dev.bscribe.data.db.SessionWithRecordings
import dev.bscribe.data.repo.SessionRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TrashViewModel(private val repo: SessionRepository) : ViewModel() {

    val trashed: StateFlow<List<SessionWithRecordings>> = repo.observeTrash()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun restore(sessionId: String) {
        viewModelScope.launch { repo.restoreSession(sessionId) }
    }

    fun deleteForever(sessionId: String) {
        viewModelScope.launch { repo.deleteSession(sessionId) }
    }

    fun emptyTrash() {
        viewModelScope.launch { repo.emptyTrash() }
    }

    companion object {
        fun factory(context: Context) = viewModelFactory {
            initializer { TrashViewModel(context.appContainer.sessionRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: TrashViewModel = viewModel(factory = TrashViewModel.factory(context))
    val trashed by vm.trashed.collectAsState()
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trash") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (trashed.isNotEmpty()) {
                        TextButton(onClick = { confirmEmpty = true }) { Text("Empty") }
                    }
                },
            )
        },
    ) { padding ->
        if (trashed.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Trash is empty", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Deleted notes wait here for 30 days before they're erased.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(trashed, key = { it.session.id }) { row ->
                    TrashCard(
                        row = row,
                        onRestore = { vm.restore(row.session.id) },
                        onDelete = { confirmDelete = row.session.id },
                    )
                }
            }
        }
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Empty trash?") },
            text = {
                Text(
                    "This erases ${trashed.size} note${if (trashed.size == 1) "" else "s"} " +
                        "and their audio for good. It can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.emptyTrash(); confirmEmpty = false }) {
                    Text("Erase")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") }
            },
        )
    }

    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete forever?") },
            text = { Text("This note and its audio are erased for good.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteForever(id); confirmDelete = null }) {
                    Text("Erase")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TrashCard(
    row: SessionWithRecordings,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val session = row.session
    val totalMs = row.recordings.sumOf { it.durationMs }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                session.title ?: formatTimestamp(session.createdAt),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "${formatClock(totalMs)} · deleted " +
                    (session.deletedAt?.let(::formatTimestamp) ?: "recently"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRestore) { Text("Restore") }
                TextButton(onClick = onDelete) { Text("Delete forever") }
            }
        }
    }
}
