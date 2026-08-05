package dev.bscribe.app.ui.detail

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.bscribe.app.appContainer
import dev.bscribe.app.record.QuickRecorder
import dev.bscribe.app.record.RecorderStatus
import dev.bscribe.app.record.RecordingService
import dev.bscribe.app.record.TranscribeOutcome
import dev.bscribe.app.util.formatClock
import dev.bscribe.app.util.formatTimestamp
import dev.bscribe.core.asr.TextDiff
import dev.bscribe.core.model.SessionState
import dev.bscribe.data.db.CorrectionEntity
import dev.bscribe.data.db.SessionEntity
import dev.bscribe.data.db.SessionWithRecordings
import dev.bscribe.data.db.TranscriptWordEntity
import dev.bscribe.data.repo.SessionRepository
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the voice route is doing, if anything. */
sealed interface DictationState {
    data object Idle : DictationState
    data object Recording : DictationState
    data object Transcribing : DictationState
    data class Failed(val reason: String) : DictationState
}

/** A recording's transcript as the user sees and edits it. */
data class EditableTranscript(
    val recordingId: String,
    val idx: Int,
    /** What the model produced, for reverting and for knowing an edit exists. */
    val modelText: String,
    val text: String,
    val edited: Boolean,
)

/** One recording's worth of transcript. */
data class TranscriptGroup(val recordingId: String, val words: List<TranscriptWordEntity>)

@OptIn(ExperimentalCoroutinesApi::class)
class SessionDetailViewModel(
    private val repo: SessionRepository,
    private val sessionId: String,
    context: Context,
) : ViewModel() {

    val session: StateFlow<SessionWithRecordings?> =
        repo.observeSession(sessionId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private var queued = false

    /**
     * recordingId → index in the ExoPlayer playlist.
     *
     * Not the same as [dev.bscribe.data.db.RecordingEntity.idx]: the playlist
     * skips zero-length segments, so a session with an empty segment would
     * otherwise seek to the wrong audio when a word is tapped.
     */
    private var playlistIndex: Map<String, Int> = emptyMap()

    /** Transcript words per recording, in playback order. */
    val transcript: StateFlow<List<TranscriptGroup>> =
        repo.observeSession(sessionId)
            .flatMapLatest { s ->
                val recs = s?.recordings.orEmpty().sortedBy { it.idx }
                if (recs.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(recs.map { repo.observeTranscript(it.id) }) { arrays ->
                        recs.mapIndexed { i, rec -> TranscriptGroup(rec.id, arrays[i]) }
                            .filter { it.words.isNotEmpty() }
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }
        })
    }

    private fun ensureQueued(): Boolean {
        val current = session.value ?: return false
        if (queued) return true
        val playable = current.recordings.filter { it.durationMs > 0 }.sortedBy { it.idx }
        if (playable.isEmpty()) return false
        player.setMediaItems(playable.map { MediaItem.fromUri(repo.resolveWav(it).toUri()) })
        player.prepare()
        playlistIndex = playable.mapIndexed { i, r -> r.id to i }.toMap()
        queued = true
        return true
    }

    fun togglePlayback() {
        if (!ensureQueued()) return
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0)
            player.play()
        }
    }

    /** Plays from the moment a tapped word was spoken. */
    fun playWord(recordingId: String, t0Ms: Long) {
        if (!ensureQueued()) return
        val index = playlistIndex[recordingId] ?: return
        // Nudge back slightly: word onsets land a touch late, and starting
        // mid-syllable makes a correct timestamp feel wrong.
        player.seekTo(index, (t0Ms - PLAY_LEAD_IN_MS).coerceAtLeast(0))
        player.play()
    }

    /** Corrections overlaying the current transcript, per recording. */
    val corrections: StateFlow<Map<String, List<CorrectionEntity>>> =
        repo.observeSession(sessionId)
            .flatMapLatest { s ->
                val recs = s?.recordings.orEmpty().sortedBy { it.idx }
                if (recs.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    combine(recs.map { repo.observeCorrections(it.id) }) { arrays ->
                        recs.mapIndexed { i, rec -> rec.id to arrays[i] }.toMap()
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * The transcript as editable text, one entry per recording.
     *
     * Editing the whole thing is the interaction; the correction pairs that
     * training needs are recovered afterwards by diffing (see [TextDiff]).
     * Selecting spans and fixing them individually was more precise and much
     * harder to use, and precision nobody uses is worth nothing.
     */
    val editable: StateFlow<Map<String, EditableTranscript>> =
        repo.observeSession(sessionId)
            .flatMapLatest { s ->
                val recs = s?.recordings.orEmpty().filter { it.durationMs > 0 }.sortedBy { it.idx }
                if (recs.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    combine(
                        recs.map { rec ->
                            combine(
                                repo.observeTranscript(rec.id),
                                repo.observeEdit(rec.id),
                            ) { words, edit ->
                                val model = words.joinToString(" ") { it.text }
                                rec.id to EditableTranscript(
                                    recordingId = rec.id,
                                    idx = rec.idx,
                                    modelText = model,
                                    text = edit?.editedText ?: model,
                                    edited = edit != null && edit.editedText != edit.baselineText,
                                )
                            }
                        },
                    ) { it.toMap() }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val saveJobs = mutableMapOf<String, Job>()

    private val _savedAt = MutableStateFlow<Long?>(null)
    val savedAt: StateFlow<Long?> = _savedAt.asStateFlow()

    /**
     * Debounced, so a write does not happen on every keystroke — but short
     * enough that putting the phone down mid-sentence still keeps the edit.
     */
    fun onTextChanged(recordingId: String, text: String) {
        saveJobs.remove(recordingId)?.cancel()
        saveJobs[recordingId] = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            repo.saveEdit(recordingId, text)
            _savedAt.value = System.currentTimeMillis()
        }
    }

    fun revertEdit(recordingId: String) {
        viewModelScope.launch { repo.revertEdit(recordingId) }
    }

    /** Plays from wherever the cursor sits, mapping character offset to time. */
    fun playFromCursor(recordingId: String, charOffset: Int) {
        viewModelScope.launch {
            val words = repo.transcriptOf(recordingId)
            if (words.isEmpty()) return@launch
            var consumed = 0
            for (w in words) {
                consumed += w.text.length + 1 // the joining space
                if (charOffset <= consumed) {
                    playWord(recordingId, w.t0Ms)
                    return@launch
                }
            }
            playWord(recordingId, words.last().t0Ms)
        }
    }

    // ---- dictation ----

    private val _dictation = MutableStateFlow<DictationState>(DictationState.Idle)
    val dictation: StateFlow<DictationState> = _dictation.asStateFlow()

    private val _dictated = MutableStateFlow<String?>(null)
    val dictated: StateFlow<String?> = _dictated.asStateFlow()

    private var recorder: QuickRecorder? = null

    fun startDictation(context: Context) {
        if (context.appContainer.recorderState.status.value != RecorderStatus.IDLE) {
            _dictation.value = DictationState.Failed("Stop the current recording first.")
            return
        }
        _dictated.value = null
        val file = File(context.cacheDir, "dictation.wav")
        file.delete()
        recorder = QuickRecorder(file).also { it.start() }
        _dictation.value = DictationState.Recording
    }

    fun stopDictation(context: Context) {
        val file = recorder?.stop()
        recorder = null
        if (file == null) {
            _dictation.value = DictationState.Failed("Nothing was recorded.")
            return
        }
        _dictation.value = DictationState.Transcribing
        viewModelScope.launch {
            val text = context.appContainer.transcriptionRunner.transcribeClip(file)
            _dictation.value = if (text == null) {
                DictationState.Failed("Could not transcribe that. Type it instead.")
            } else {
                _dictated.value = text
                DictationState.Idle
            }
            file.delete()
        }
    }

    fun resetDictation() {
        recorder?.stop()
        recorder = null
        _dictation.value = DictationState.Idle
        _dictated.value = null
    }

    /** Appends dictated text at the cursor rather than replacing everything. */
    fun applyDictation(recordingId: String, current: String, insertAt: Int, dictated: String) {
        val at = insertAt.coerceIn(0, current.length)
        val separator = if (at > 0 && !current[at - 1].isWhitespace()) " " else ""
        val text = current.substring(0, at) + separator + dictated + current.substring(at)
        onTextChanged(recordingId, text)
    }

    fun toggleReviewed() {
        viewModelScope.launch {
            val current = session.value?.session ?: return@launch
            if (current.reviewedAt == null) repo.markReviewed(sessionId)
            else repo.unmarkReviewed(sessionId)
        }
    }

    fun retranscribe(context: Context) {
        context.appContainer.transcriptionRunner.clearOutcome()
        context.startForegroundService(
            RecordingService.transcribeIntent(context, sessionId),
        )
    }

    /**
     * Stops a pass and clears the session's state.
     *
     * Also resets the row directly, because a session can be left showing
     * "Transcribing…" with nothing running at all — a native crash inside
     * whisper kills the process before any final state is written. Cancelling
     * the service alone would not fix those.
     */
    fun stopTranscribing(context: Context) {
        // Plain startService, not startForegroundService: this is only ever
        // called from a visible screen, and a foreground start would oblige a
        // service that may not even be running to promote itself in 5s.
        runCatching {
            context.startService(
                RecordingService.intent(context, RecordingService.ACTION_CANCEL_TRANSCRIBE),
            )
        }
        viewModelScope.launch { repo.setState(sessionId, SessionState.STOPPED) }
    }

    fun rename(title: String) {
        viewModelScope.launch { repo.setTitle(sessionId, title.ifBlank { null }) }
    }

    /** Moves the note to the trash; nothing is erased until it's purged. */
    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            player.stop()
            repo.trashSession(sessionId)
            onDone()
        }
    }

    override fun onCleared() {
        player.release()
    }

    companion object {
        fun factory(context: Context, sessionId: String) = viewModelFactory {
            initializer {
                SessionDetailViewModel(context.appContainer.sessionRepository, sessionId, context)
            }
        }

        /**
         * Word onsets sit a little late even with DTW alignment, and starting
         * playback mid-syllable makes an accurate timestamp feel broken.
         */
        private const val PLAY_LEAD_IN_MS = 120L

        /** Long enough not to write per keystroke, short enough to be safe. */
        private const val SAVE_DEBOUNCE_MS = 700L
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    sessionId: String,
    onBack: () -> Unit,
    onCompare: () -> Unit = {},
) {
    val context = LocalContext.current
    val viewModel: SessionDetailViewModel =
        viewModel(factory = SessionDetailViewModel.factory(context, sessionId))
    val sessionData by viewModel.session.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val transcript by viewModel.transcript.collectAsState()
    val outcome by context.appContainer.transcriptionRunner.lastOutcome.collectAsState()
    val editable by viewModel.editable.collectAsState()
    val savedAt by viewModel.savedAt.collectAsState()
    val dictation by viewModel.dictation.collectAsState()
    val dictated by viewModel.dictated.collectAsState()

    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    val session = sessionData?.session
    val recordings = sessionData?.recordings.orEmpty()
    val totalMs = recordings.sumOf { it.durationMs }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(session?.title ?: session?.createdAt?.let(::formatTimestamp) ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showRename = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Rename")
                    }
                    IconButton(onClick = { showDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { viewModel.togglePlayback() }) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(formatClock(totalMs), style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${recordings.size} segment${if (recordings.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.padding(8.dp))

            if (session != null && editable.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ReviewCard(
                    reviewedAt = session.reviewedAt,
                    onToggle = viewModel::toggleReviewed,
                )
                Spacer(Modifier.height(8.dp))
            }

            TranscriptCard(
                editable = editable,
                session = session,
                outcome = outcome,
                savedAt = savedAt,
                dictation = dictation,
                dictated = dictated,
                onStartDictation = { viewModel.startDictation(context) },
                onStopDictation = { viewModel.stopDictation(context) },
                onDictationConsumed = viewModel::resetDictation,
                onInsertDictation = viewModel::applyDictation,
                onTextChanged = viewModel::onTextChanged,
                onRevert = viewModel::revertEdit,
                onPlayFrom = viewModel::playFromCursor,
                onTranscribe = { viewModel.retranscribe(context) },
                onStop = { viewModel.stopTranscribing(context) },
                onCompare = onCompare,
            )
        }
    }

    if (showRename) {
        var draft by remember(session) { mutableStateOf(session?.title ?: "") }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename note") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text("Title") },
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.rename(draft); showRename = false }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text("Cancel") }
            },
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Move to trash?") },
            text = {
                Text(
                    "The note moves to the trash and stays there for 30 days. " +
                        "Nothing is erased until you empty it.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showDelete = false; viewModel.delete(onDone = onBack) }) {
                    Text("Move to trash")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Word chips from the final pass.
 *
 * Tapping a chip plays from that word — the reason M1a uses DTW alignment
 * rather than whisper's heuristic timestamps, which jitter enough to land on
 * a neighbouring word.
 */
@Composable
private fun TranscriptCard(
    editable: Map<String, EditableTranscript>,
    session: SessionEntity?,
    outcome: TranscribeOutcome?,
    savedAt: Long?,
    dictation: DictationState,
    dictated: String?,
    onStartDictation: () -> Unit,
    onStopDictation: () -> Unit,
    onDictationConsumed: () -> Unit,
    onInsertDictation: (recordingId: String, current: String, at: Int, text: String) -> Unit,
    onTextChanged: (recordingId: String, text: String) -> Unit,
    onRevert: (recordingId: String) -> Unit,
    onPlayFrom: (recordingId: String, charOffset: Int) -> Unit,
    onTranscribe: () -> Unit,
    onStop: () -> Unit,
    onCompare: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Transcript",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (session?.state == SessionState.TRANSCRIBING) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    TextButton(onClick = onStop) { Text("Stop") }
                } else {
                    if (editable.isNotEmpty()) {
                        TextButton(onClick = onCompare) { Text("Both") }
                    }
                    TextButton(onClick = onTranscribe) {
                        Text(if (editable.isEmpty()) "Transcribe" else "Redo")
                    }
                }
            }

            val wall = session?.transcribeWallMs
            val audio = session?.transcribeAudioMs
            if (wall != null && audio != null) {
                Text(
                    "Took ${formatClock(wall)} for ${formatClock(audio)} of audio " +
                        "(${"%.1f".format(wall.toDouble() / audio.coerceAtLeast(1))}× realtime)" +
                        (session.transcribeModel?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (val o = outcome) {
                is TranscribeOutcome.NoModel -> Text(
                    "No speech model installed. Open Settings → Speech models and " +
                        "download or import ${o.wanted}, then tap Transcribe.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                is TranscribeOutcome.Failed -> Text(
                    "Transcription failed: ${o.reason}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Unit
            }

            when {
                session?.state == SessionState.TRANSCRIBING -> Text(
                    "Working through the audio. You can leave this screen — " +
                        "progress is in the notification.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                editable.isEmpty() -> Text(
                    "Not transcribed yet. Download a model in Settings, then tap " +
                        "Transcribe. Your audio is safe either way.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> {
                    Text(
                        "Fix anything that's wrong. It saves as you type, and what you " +
                            "change becomes training data.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                    editable.values.sortedBy { it.idx }.forEach { item ->
                        EditableSegment(
                            item = item,
                            dictation = dictation,
                            dictated = dictated,
                            onTextChanged = { onTextChanged(item.recordingId, it) },
                            onRevert = { onRevert(item.recordingId) },
                            onPlayFrom = { onPlayFrom(item.recordingId, it) },
                            onStartDictation = onStartDictation,
                            onStopDictation = onStopDictation,
                            onDictationConsumed = onDictationConsumed,
                            onInsertDictation = { current, at, text ->
                                onInsertDictation(item.recordingId, current, at, text)
                            },
                        )
                    }
                    if (savedAt != null) {
                        Text(
                            "Saved",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One recording's transcript, editable in place.
 *
 * The field owns its text while focused rather than being driven from the
 * database on each keystroke: a round trip through Room would fight the cursor,
 * which is the standard way a text field becomes unusable.
 */
@Composable
private fun EditableSegment(
    item: EditableTranscript,
    dictation: DictationState,
    dictated: String?,
    onTextChanged: (String) -> Unit,
    onRevert: () -> Unit,
    onPlayFrom: (Int) -> Unit,
    onStartDictation: () -> Unit,
    onStopDictation: () -> Unit,
    onDictationConsumed: () -> Unit,
    onInsertDictation: (current: String, at: Int, text: String) -> Unit,
) {
    var value by remember(item.recordingId) { mutableStateOf(TextFieldValue(item.text)) }

    // Adopt outside changes — a re-transcription, a revert — but only real
    // ones, so typing is never interrupted by an echo of itself.
    LaunchedEffect(item.text) {
        if (item.text != value.text) value = value.copy(text = item.text)
    }

    // Dictated text is inserted at the cursor rather than replacing the field:
    // speaking a correction should behave like typing one.
    LaunchedEffect(dictated) {
        val spoken = dictated ?: return@LaunchedEffect
        onInsertDictation(value.text, value.selection.start, spoken)
        onDictationConsumed()
    }

    Column(Modifier.padding(bottom = 12.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                value = it
                onTextChanged(it.text)
            },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyLarge,
            minLines = 3,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onPlayFrom(value.selection.start) }) {
                Text("Play from cursor")
            }
            when (dictation) {
                is DictationState.Recording ->
                    TextButton(onClick = onStopDictation) { Text("Stop") }
                is DictationState.Transcribing ->
                    Text(
                        "Transcribing…",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                is DictationState.Failed -> Text(
                    dictation.reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                is DictationState.Idle ->
                    TextButton(onClick = onStartDictation) { Text("Dictate") }
            }
            if (item.edited) {
                TextButton(onClick = onRevert) { Text("Revert") }
            }
        }
    }
}

@Composable
private fun ReviewCard(reviewedAt: Long?, onToggle: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (reviewedAt == null) "Not reviewed" else "Reviewed",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    if (reviewedAt == null) {
                        "Marking this reviewed says the words you did not correct are " +
                            "right, which lets them count as training examples."
                    } else {
                        "Marked ${formatTimestamp(reviewedAt)}. Untouched words count " +
                            "as correct."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onToggle) {
                Text(if (reviewedAt == null) "Mark reviewed" else "Undo")
            }
        }
    }
}
