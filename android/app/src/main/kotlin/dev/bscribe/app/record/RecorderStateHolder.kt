package dev.bscribe.app.record

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RecorderStatus { IDLE, STARTING, RECORDING, PAUSED, STOPPING }

/**
 * Observable recorder state shared between [RecordingService] (writer) and the
 * UI (reader). Living outside the service means the record screen can render
 * correct state even across service restarts or process reconnection.
 */
class RecorderStateHolder {

    private val _status = MutableStateFlow(RecorderStatus.IDLE)
    val status: StateFlow<RecorderStatus> = _status.asStateFlow()

    private val _sessionId = MutableStateFlow<String?>(null)
    val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    /** 0..1 meter position; drive a progress bar directly. */
    private val _vuMeter = MutableStateFlow(0f)
    val vuMeter: StateFlow<Float> = _vuMeter.asStateFlow()

    /** Live transcript regions (M1b feeds these; empty in M0). */
    private val _committedText = MutableStateFlow("")
    val committedText: StateFlow<String> = _committedText.asStateFlow()

    private val _hypothesisText = MutableStateFlow("")
    val hypothesisText: StateFlow<String> = _hypothesisText.asStateFlow()

    fun onStarting(sessionId: String) {
        _sessionId.value = sessionId
        _elapsedMs.value = 0
        _committedText.value = ""
        _hypothesisText.value = ""
        _status.value = RecorderStatus.STARTING
    }

    fun onRecording() {
        _status.value = RecorderStatus.RECORDING
    }

    fun onPaused() {
        _vuMeter.value = 0f
        _status.value = RecorderStatus.PAUSED
    }

    fun onStopping() {
        _status.value = RecorderStatus.STOPPING
    }

    fun onIdle() {
        _status.value = RecorderStatus.IDLE
        _sessionId.value = null
        _vuMeter.value = 0f
    }

    fun onProgress(elapsedMs: Long, vuMeter: Float) {
        _elapsedMs.value = elapsedMs
        _vuMeter.value = vuMeter
    }

    fun onLiveText(committed: String, hypothesis: String) {
        _committedText.value = committed
        _hypothesisText.value = hypothesis
    }
}
