package dev.bscribe.core.asr

import kotlinx.coroutines.flow.Flow

/** One recognized word from the final transcription pass. */
data class Word(
    val text: String,
    val t0Ms: Long,
    val t1Ms: Long,
    val prob: Float,
    /** Decoder segment the word came from; groups words into utterances. */
    val segmentIdx: Int,
)

/** Parameters for a whisper decode. Mirrors the JNI surface (M1a). */
data class DecodeParams(
    val nThreads: Int = 4,
    /** ISO 639-1 code, or null for autodetect. Multilingual models only. */
    val language: String? = "en",
    val tokenTimestamps: Boolean = true,
    /** 1 → word-granularity segments for tap targets. */
    val maxLen: Int = 1,
    val beamSize: Int = 1,
    val noContext: Boolean = true,
)

/** Events streamed to the record screen while speaking. */
sealed interface LiveEvent {
    /** Text that will not change again (VAD boundary or LocalAgreement). */
    data class Committed(val text: String) : LiveEvent

    /** Unstable tail; replaces the previous hypothesis wholesale. */
    data class Hypothesis(val text: String) : LiveEvent

    /** Emitted during silence so the UI can show liveness without decodes. */
    data object SilenceHeartbeat : LiveEvent
}

/**
 * Live (while-recording) transcription engine.
 *
 * M0 ships [NoopLiveTranscriber]; M1b provides the whisper.cpp chunked
 * implementation, and this interface is the seam where a streaming
 * Zipformer engine could replace it if the FP4 can't keep up (plan §risks).
 */
interface LiveTranscriber {
    val events: Flow<LiveEvent>

    /** Called from the audio capture thread; must never block. */
    fun feed(samples: ShortArray, offset: Int, length: Int)

    fun start()
    fun stop()
}
