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
        // The meter must fall to zero here. Without this it froze at whatever
        // level the last captured buffer happened to hit and sat there for the
        // whole save — reading as if the mic were still live.
        _vuMeter.value = 0f
        _status.value = RecorderStatus.STOPPING
    }

    fun onIdle() {
        _status.value = RecorderStatus.IDLE
        _sessionId.value = null
        _vuMeter.value = 0f
    }

    /**
     * @param level 0..1 instantaneous level for this block.
     *
     * Attack is instantaneous and release is gradual, the way a level meter is
     * expected to behave: the bar should be at the syllable the moment it is
     * spoken, then fall back on its own. Smoothing the rise as well — which is
     * what an animation between values does — reads as lag, because the meter
     * arrives after the sound.
     */
    fun onProgress(elapsedMs: Long, level: Float) {
        _elapsedMs.value = elapsedMs
        val previous = _vuMeter.value
        _vuMeter.value = if (level >= previous) {
            level
        } else {
            maxOf(level, previous - RELEASE_PER_BLOCK)
        }
    }

    fun onLiveText(committed: String, hypothesis: String) {
        _committedText.value = committed
        _hypothesisText.value = hypothesis
    }

    private companion object {
        /**
         * How far the meter may fall per 20 ms block — full scale to silence
         * in about a third of a second. Fast enough to track speech rhythm,
         * slow enough not to strobe.
         */
        const val RELEASE_PER_BLOCK = 0.06f
    }
}
