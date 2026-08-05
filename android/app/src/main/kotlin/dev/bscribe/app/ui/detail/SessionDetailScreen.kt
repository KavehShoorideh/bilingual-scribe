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
import androidx.compose.ui.text.style.TextDecoration
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
import kotlinx.coroutines.launch

/** A contiguous span of words picked for correction. */
data class Selection(val recordingId: String, val first: Int, val last: Int)

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

    /** (recordingId, wordIdx) pairs currently selected for correction. */
    private val _selection = MutableStateFlow<Selection?>(null)
    val selection: StateFlow<Selection?> = _selection.asStateFlow()

    fun toggleSelection(recordingId: String, wordIdx: Int) {
        val current = _selection.value
        _selection.value = when {
            current == null || current.recordingId != recordingId ->
                Selection(recordingId, wordIdx, wordIdx)
            // Tapping inside the selection collapses it back to one word;
            // tapping outside extends to cover everything between.
            wordIdx in current.first..current.last && current.first != current.last ->
                Selection(recordingId, wordIdx, wordIdx)
            else -> Selection(
                recordingId,
                minOf(current.first, wordIdx),
                maxOf(current.last, wordIdx),
            )
        }
    }

    fun clearSelection() { _selection.value = null }

    suspend fun selectedWords(): List<TranscriptWordEntity> {
        val sel = _selection.value ?: return emptyList()
        return repo.transcriptOf(sel.recordingId)
            .filter { it.wordIdx in sel.first..sel.last }
            .sortedBy { it.wordIdx }
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

    fun saveCorrection(text: String) {
        val sel = _selection.value ?: return
        viewModelScope.launch {
            val words = selectedWords()
            if (words.isEmpty()) return@launch
            repo.applyCorrection(
                recordingId = sel.recordingId,
                firstWordIdx = sel.first,
                lastWordIdx = sel.last,
                t0Ms = words.first().t0Ms,
                t1Ms = words.last().t1Ms,
                originalText = words.joinToString(" ") { it.text },
                correctedText = text.trim(),
            )
            _selection.value = null
        }
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
    val selection by viewModel.selection.collectAsState()
    val corrections by viewModel.corrections.collectAsState()
    val dictation by viewModel.dictation.collectAsState()
    val dictated by viewModel.dictated.collectAsState()
    var correcting by remember { mutableStateOf(false) }
    var selectedText by remember { mutableStateOf("") }

    // The sheet needs the words themselves, which live behind a suspend read.
    LaunchedEffect(correcting, selection) {
        if (correcting) {
            selectedText = viewModel.selectedWords().joinToString(" ") { it.text }
        }
    }

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

            if (selection != null) {
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${selection!!.last - selection!!.first + 1} word(s) selected",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { correcting = true }) { Text("Correct") }
                        TextButton(onClick = viewModel::clearSelection) { Text("Cancel") }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (session != null && transcript.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ReviewCard(
                    reviewedAt = session.reviewedAt,
                    onToggle = viewModel::toggleReviewed,
                )
                Spacer(Modifier.height(8.dp))
            }

            TranscriptCard(
                groups = transcript,
                session = session,
                outcome = outcome,
                selection = selection,
                corrections = corrections,
                onWordTap = viewModel::playWord,
                onWordLongPress = viewModel::toggleSelection,
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

    if (correcting) {
        CorrectionSheet(
            originalText = selectedText,
            dictation = dictation,
            dictated = dictated,
            onDismiss = {
                correcting = false
                viewModel.resetDictation()
            },
            onSave = { text ->
                viewModel.saveCorrection(text)
                correcting = false
                viewModel.resetDictation()
            },
            onStartDictation = { viewModel.startDictation(context) },
            onStopDictation = { viewModel.stopDictation(context) },
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranscriptCard(
    groups: List<TranscriptGroup>,
    session: SessionEntity?,
    outcome: TranscribeOutcome?,
    selection: Selection?,
    corrections: Map<String, List<CorrectionEntity>>,
    onWordTap: (recordingId: String, t0Ms: Long) -> Unit,
    onWordLongPress: (recordingId: String, wordIdx: Int) -> Unit,
    onTranscribe: () -> Unit,
    onStop: () -> Unit,
    onCompare: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Transcript",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (session?.state == SessionState.TRANSCRIBING) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    // A session can sit in TRANSCRIBING with nothing running
                    // if the process died mid-pass, so this must always be
                    // reachable rather than only when a job exists.
                    TextButton(onClick = onStop) { Text("Stop") }
                } else {
                    if (groups.isNotEmpty()) {
                        TextButton(onClick = onCompare) { Text("Both") }
                    }
                    TextButton(onClick = onTranscribe) {
                        Text(if (groups.isEmpty()) "Transcribe" else "Redo")
                    }
                }
            }

            // What the last pass actually cost. "Slow" is not actionable;
            // "1.8x realtime on Base with both languages" tells you which
            // setting to change.
            if (session?.transcribeWallMs != null && session.transcribeAudioMs != null) {
                val rtf = session.transcribeWallMs!!.toDouble() /
                    session.transcribeAudioMs!!.coerceAtLeast(1)
                Text(
                    "Took ${formatClock(session.transcribeWallMs!!)} for " +
                        "${formatClock(session.transcribeAudioMs!!)} of audio " +
                        "(${"%.1f".format(rtf)}× realtime)" +
                        (session.transcribeModel?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Why nothing happened, when nothing happened. A pass that fails
            // silently is indistinguishable from one that is merely slow.
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

                groups.isEmpty() -> Text(
                    "Not transcribed yet. Download a model in Settings, then tap " +
                        "Transcribe. Your audio is safe either way.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> groups.forEach { group ->
                    // Split first by speaking turn, then by language. A turn
                    // change means someone else started talking, which deserves
                    // a visible break more than a language switch does.
                    group.words.groupBy { it.turnIdx }.entries
                        .sortedBy { it.key }
                        .forEach { (turnIdx, turnWords) ->
                            if (turnIdx > 0) {
                                Text(
                                    "— new voice —",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                            LanguageRuns(
                                words = turnWords,
                                recordingId = group.recordingId,
                                selection = selection,
                                corrections = corrections[group.recordingId].orEmpty(),
                                onWordTap = onWordTap,
                                onWordLongPress = onWordLongPress,
                            )
                        }
                }
            }
        }
    }
}

/**
 * Renders words as runs of a single language, each on its own line with its
 * own direction — mixing scripts inside one flowing row put Farsi and English
 * in the wrong visual order, because bidi reordering fights the layout
 * direction.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageRuns(
    words: List<TranscriptWordEntity>,
    recordingId: String,
    selection: Selection?,
    corrections: List<CorrectionEntity>,
    onWordTap: (recordingId: String, t0Ms: Long) -> Unit,
    onWordLongPress: (recordingId: String, wordIdx: Int) -> Unit,
) {
    Column {
        languageRuns(words).forEach { run ->
            val rtl = run.lang == "fa"
            CompositionLocalProvider(
                LocalLayoutDirection provides
                    if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    run.words.forEach { word ->
                        // A correction covers a span, so only its first word
                        // renders the replacement; the rest are folded into it.
                        val covering = corrections.firstOrNull {
                            word.wordIdx in it.firstWordIdx..it.lastWordIdx
                        }
                        if (covering != null && word.wordIdx != covering.firstWordIdx) {
                            return@forEach
                        }
                        WordChip(
                            word = word,
                            corrected = covering?.correctedText,
                            selected = selection != null &&
                                selection.recordingId == recordingId &&
                                word.wordIdx in selection.first..selection.last,
                            onTap = { onWordTap(recordingId, word.t0Ms) },
                            onLongPress = { onWordLongPress(recordingId, word.wordIdx) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WordChip(
    word: TranscriptWordEntity,
    corrected: String?,
    selected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    // Low-confidence words are muted rather than hidden — knowing whisper was
    // unsure is more useful than a confident-looking wrong word.
    val alpha = if (word.prob < LOW_CONFIDENCE) 0.45f else 1f
    val deleted = corrected != null && corrected.isBlank()

    Text(
        text = when {
            deleted -> word.text
            corrected != null -> corrected
            else -> word.text
        },
        style = MaterialTheme.typography.bodyLarge,
        textDecoration = if (deleted) TextDecoration.LineThrough else null,
        color = when {
            deleted -> MaterialTheme.colorScheme.onSurfaceVariant
            // A corrected word is yours, not the model's, and should read that
            // way at a glance.
            corrected != null -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
        },
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                },
            )
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/** A stretch of consecutive words sharing one language. */
private data class LanguageRun(val lang: String, val words: List<TranscriptWordEntity>)

/**
 * Splits a transcript into runs at each language change, so the UI can put a
 * line break between them and give each its own direction.
 *
 * Script-neutral words ("und" — numbers, punctuation) continue the run they
 * are in rather than starting a new one; otherwise a single digit mid-sentence
 * would break the line in two.
 */
private fun languageRuns(words: List<TranscriptWordEntity>): List<LanguageRun> {
    val runs = mutableListOf<LanguageRun>()
    var currentLang: String? = null
    var current = mutableListOf<TranscriptWordEntity>()

    for (word in words) {
        val lang = word.lang
        if (lang == "und" || lang == currentLang || currentLang == null) {
            if (currentLang == null && lang != "und") currentLang = lang
            current += word
        } else {
            runs += LanguageRun(currentLang, current)
            currentLang = lang
            current = mutableListOf(word)
        }
    }
    if (current.isNotEmpty()) runs += LanguageRun(currentLang ?: "und", current)
    return runs
}

private const val LOW_CONFIDENCE = 0.55f

/**
 * Marking a session reviewed is an assertion about its contents: that what you
 * did not correct is correct. That is what allows untouched words to be
 * exported as weak positives, so the copy has to say so rather than reading
 * like a bookmark.
 */
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
