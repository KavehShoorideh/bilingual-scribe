package dev.bscribe.app.ui.timeline

import android.content.Context
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.bscribe.app.appContainer
import dev.bscribe.data.repo.SessionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A word positioned on the shared clock. */
data class TimelineWord(
    val text: String,
    val t0Ms: Long,
    val t1Ms: Long,
    val chosen: Boolean,
    val recordingId: String,
)

data class Lanes(
    val english: List<TimelineWord> = emptyList(),
    val farsi: List<TimelineWord> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineViewModel(
    private val repo: SessionRepository,
    private val sessionId: String,
    context: Context,
) : ViewModel() {

    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    /**
     * Both readings on one timeline.
     *
     * Segment offsets are added here so the lanes share a single clock across
     * a paused-and-resumed session; each recording's words are stored relative
     * to their own file.
     */
    val lanes: StateFlow<Lanes> = repo.observeSession(sessionId)
        .flatMapLatest { s ->
            val recs = s?.recordings.orEmpty().filter { it.durationMs > 0 }.sortedBy { it.idx }
            if (recs.isEmpty()) {
                flowOf(Lanes())
            } else {
                val offsets = mutableMapOf<String, Long>()
                var running = 0L
                for (r in recs) {
                    offsets[r.id] = running
                    running += r.durationMs
                }
                _durationMs.value = running

                combine(recs.map { repo.observeAllVariants(it.id) }) { arrays ->
                    val all = arrays.toList().flatten()
                    fun lane(variant: String) = all
                        .filter { it.variant == variant }
                        .map {
                            val offset = offsets[it.recordingId] ?: 0L
                            TimelineWord(
                                text = it.text,
                                t0Ms = it.t0Ms + offset,
                                t1Ms = it.t1Ms + offset,
                                chosen = it.chosen,
                                recordingId = it.recordingId,
                            )
                        }
                        .sortedBy { it.t0Ms }
                    Lanes(english = lane("en"), farsi = lane("fa"))
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Lanes())

    private var queued = false
    private var offsets: List<Pair<String, Long>> = emptyList()

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlaying.value = playing
            }
        })
        // Poll rather than rely on listener callbacks: ExoPlayer reports state
        // changes, not continuous position, and the lanes must move smoothly.
        viewModelScope.launch {
            while (isActive) {
                if (player.isPlaying) _positionMs.value = absolutePosition()
                delay(POLL_MS)
            }
        }
    }

    private fun absolutePosition(): Long {
        val index = player.currentMediaItemIndex
        val base = offsets.getOrNull(index)?.second ?: 0L
        return base + player.currentPosition
    }

    /**
     * Maps session-absolute time back to a recording. Loaded independently of
     * the player, because tapping a word must work before anything has been
     * played — previously the offsets were only populated by playback, so an
     * early tap silently did nothing.
     */
    private suspend fun loadOffsets(): List<Pair<String, Long>> {
        if (offsets.isEmpty()) {
            val recs = repo.recordingsOf(sessionId)
                .filter { it.durationMs > 0 }
                .sortedBy { it.idx }
            var running = 0L
            offsets = recs.map { r -> (r.id to running).also { running += r.durationMs } }
        }
        return offsets
    }

    private suspend fun ensureQueued(): Boolean {
        if (queued) return true
        val recs = repo.recordingsOf(sessionId).filter { it.durationMs > 0 }.sortedBy { it.idx }
        if (recs.isEmpty()) return false
        player.setMediaItems(recs.map { MediaItem.fromUri(repo.resolveWav(it).toUri()) })
        player.prepare()
        loadOffsets()
        queued = true
        return true
    }

    fun togglePlayback() {
        viewModelScope.launch {
            if (!ensureQueued()) return@launch
            if (player.isPlaying) player.pause() else player.play()
        }
    }

    /** Drag-to-scrub; also usable while paused. */
    fun scrubBy(deltaMs: Long) {
        viewModelScope.launch {
            if (!ensureQueued()) return@launch
            val target = (_positionMs.value + deltaMs).coerceIn(0, _durationMs.value)
            _positionMs.value = target
            seekAbsolute(target)
        }
    }

    private fun seekAbsolute(absoluteMs: Long) {
        var index = 0
        var withinMs = absoluteMs
        for ((i, entry) in offsets.withIndex()) {
            if (absoluteMs >= entry.second) {
                index = i
                withinMs = absoluteMs - entry.second
            }
        }
        player.seekTo(index, withinMs)
    }

    /**
     * Records the user's verdict on a stretch of audio and switches the
     * transcript to that reading.
     */
    fun chooseLanguage(lang: String, t0Ms: Long, t1Ms: Long) {
        viewModelScope.launch {
            // Times on the lanes are session-absolute; feedback is stored per
            // recording, so map back through the offsets.
            val entry = loadOffsets().lastOrNull { t0Ms >= it.second } ?: return@launch
            repo.chooseLanguage(
                recordingId = entry.first,
                t0Ms = t0Ms - entry.second,
                t1Ms = t1Ms - entry.second,
                lang = lang,
            )
            _lastChoice.value = if (lang == "fa") "Marked as Farsi" else "Marked as English"
        }
    }

    private val _lastChoice = MutableStateFlow<String?>(null)

    /** Confirmation text for the most recent verdict, shown then cleared. */
    val lastChoice: StateFlow<String?> = _lastChoice.asStateFlow()

    fun clearLastChoice() { _lastChoice.value = null }

    override fun onCleared() {
        player.release()
    }

    companion object {
        private const val POLL_MS = 50L

        fun factory(context: Context, sessionId: String) = viewModelFactory {
            initializer {
                TimelineViewModel(context.appContainer.sessionRepository, sessionId, context)
            }
        }
    }
}
