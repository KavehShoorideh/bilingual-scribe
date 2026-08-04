package dev.bscribe.app.record

import android.util.Log
import dev.bscribe.asr.LanguagePlan
import dev.bscribe.asr.ModelFiles
import dev.bscribe.asr.WhisperEngine
import dev.bscribe.core.asr.DecodeParams
import dev.bscribe.core.asr.SpeakerChange
import dev.bscribe.core.asr.Word
import dev.bscribe.core.audio.Mfcc
import dev.bscribe.core.audio.WavReader
import dev.bscribe.core.model.SessionState
import dev.bscribe.data.repo.ModelCatalog
import dev.bscribe.data.repo.ModelRepository
import dev.bscribe.data.repo.SessionRepository
import dev.bscribe.data.repo.SettingsRepository
import dev.bscribe.data.repo.TranscribeLanguages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Progress for the foreground notification, measured in audio time rather
 * than segments. A single-segment recording only ever produced one segment
 * update, which for a long note is indistinguishable from a hang.
 */
data class TranscribeProgress(val doneMs: Long, val totalMs: Long) {
    val percent: Int get() = if (totalMs <= 0) 0 else ((doneMs * 100) / totalMs).toInt()
}

sealed interface TranscribeOutcome {
    data object Done : TranscribeOutcome
    data class NoModel(val wanted: String) : TranscribeOutcome
    data class Failed(val reason: String) : TranscribeOutcome
}

/**
 * Runs the accurate pass over a finished session.
 *
 * Kept out of [RecordingService] so it can also be driven from a re-transcribe
 * action, and so the service keeps doing only what it is uniquely able to do:
 * hold the microphone and stay foregrounded.
 */
class TranscriptionRunner(
    private val repo: SessionRepository,
    private val settings: SettingsRepository,
    private val models: ModelRepository,
) {

    private val _lastOutcome = MutableStateFlow<TranscribeOutcome?>(null)

    /**
     * Why the last pass ended. Surfaced in the UI because a transcription that
     * silently does nothing — no model, or a native failure — is impossible to
     * tell apart from one that is simply slow.
     */
    val lastOutcome: StateFlow<TranscribeOutcome?> = _lastOutcome.asStateFlow()

    fun clearOutcome() { _lastOutcome.value = null }

    suspend fun run(
        sessionId: String,
        onProgress: (TranscribeProgress) -> Unit = {},
    ): TranscribeOutcome {
        val modelFileName = settings.finalModelFile.first()
        val spec = ModelCatalog.byFileName(modelFileName) ?: ModelCatalog.defaultFinal
        if (!models.isInstalled(spec)) {
            // Not an error: the audio is safe and the pass can be re-run once
            // the model is downloaded.
            Log.i(TAG, "no model installed (${spec.fileName}); leaving session STOPPED")
            return TranscribeOutcome.NoModel(spec.label).also { _lastOutcome.value = it }
        }

        val plan = when (settings.transcribeLanguages.first()) {
            TranscribeLanguages.BOTH -> LanguagePlan.DUAL
            TranscribeLanguages.ENGLISH -> LanguagePlan.ENGLISH_ONLY
            TranscribeLanguages.FARSI -> LanguagePlan.FARSI_ONLY
        }
        val threads = settings.nThreads.first()
        val modelFile = models.fileFor(spec)

        repo.setState(sessionId, SessionState.TRANSCRIBING)

        var engine: WhisperEngine? = null
        return try {
            engine = WhisperEngine(modelFile, plan, threads)
            val modelId = ModelFiles.modelId(modelFile)

            val segments = repo.recordingsOf(sessionId).filter { it.durationMs > 0 }
            val totalMs = segments.sumOf { it.durationMs }
            var completedMs = 0L

            val startedAt = System.currentTimeMillis()
            segments.forEach { recording ->
                val wav = repo.resolveWav(recording)
                if (!wav.isFile) {
                    Log.w(TAG, "segment ${recording.id} has no audio file; skipping")
                    completedMs += recording.durationMs
                    return@forEach
                }
                val base = completedMs
                // Read 30s at a time rather than the whole segment: sessions
                // can run to hours, and holding one entirely in memory as
                // PCM16 would OOM before whisper saw any of it.
                val pass = engine.transcribeWindowed(
                    durationMs = recording.durationMs,
                    params = DecodeParams(nThreads = threads),
                    onWindow = { doneMs, _ ->
                        onProgress(TranscribeProgress(base + doneMs, totalMs))
                    },
                ) { startMs, endMs -> WavReader.readRange(wav, startMs, endMs) }
                // Saved per segment, so cancelling or crashing mid-session
                // keeps whatever was already transcribed.
                repo.saveTranscript(
                    recording.id,
                    pass,
                    modelId,
                    speakerTurns(wav, pass.chosen),
                )
                completedMs += recording.durationMs
            }
            val wallMs = System.currentTimeMillis() - startedAt
            val rtf = "%.2f".format(wallMs.toDouble() / totalMs.coerceAtLeast(1))
            Log.i(
                TAG,
                "transcribed ${totalMs}ms of audio in ${wallMs}ms (RTF $rtf), " +
                    "model=${spec.label}, langs=${plan.codes}, threads=$threads",
            )

            repo.recordPassTiming(
                sessionId = sessionId,
                wallMs = wallMs,
                audioMs = totalMs,
                model = "${spec.label} · ${plan.codes.joinToString("+")} · ${threads}t",
            )
            repo.updateLanguageHint(sessionId)
            repo.setState(sessionId, SessionState.TRANSCRIBED)
            TranscribeOutcome.Done.also { _lastOutcome.value = it }
        } catch (e: CancellationException) {
            // Whatever finished is already saved; park the session so it can
            // be resumed rather than leaving it stuck in TRANSCRIBING.
            withContext(NonCancellable) {
                runCatching { repo.setState(sessionId, SessionState.STOPPED) }
            }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "transcription failed for $sessionId", e)
            // Back to STOPPED, not stuck in TRANSCRIBING: the audio is intact
            // and a retry must be possible.
            runCatching { repo.setState(sessionId, SessionState.STOPPED) }
            TranscribeOutcome.Failed(e.message ?: e.javaClass.simpleName)
                .also { _lastOutcome.value = it }
        } finally {
            engine?.close()
        }
    }

    /**
     * Marks where the voice changes, so the transcript can start a new
     * paragraph instead of running two people together.
     *
     * Cheap next to the decode — an FFT every 10 ms against a model that takes
     * seconds per window — so it runs unconditionally rather than behind a
     * setting. Failure is non-fatal: a transcript with no turn breaks is what
     * we had before, and is far better than no transcript.
     */
    private fun speakerTurns(wav: java.io.File, words: List<Word>): IntArray {
        if (words.size < 2) return IntArray(words.size)
        return try {
            val mfcc = Mfcc()
            val frames = mutableListOf<DoubleArray>()
            val end = words.last().t1Ms
            var at = 0L
            // Same windowed read as the decode, for the same reason: a long
            // recording must never be held in memory whole.
            while (at < end) {
                val to = minOf(at + FEATURE_WINDOW_MS, end)
                frames += mfcc.analyse(WavReader.readRange(wav, at, to))
                at = to
            }
            if (frames.isEmpty()) return IntArray(words.size)

            val times = words.map { it.t0Ms to it.t1Ms }
            SpeakerChange.assignTurns(
                wordCount = words.size,
                candidates = SpeakerChange.candidates(times),
                frames = frames,
                frameOf = mfcc::frameAt,
            )
        } catch (e: Exception) {
            Log.w(TAG, "speaker-change detection failed; transcript keeps one turn", e)
            IntArray(words.size)
        }
    }

    private companion object {
        const val TAG = "TranscriptionRunner"
        const val FEATURE_WINDOW_MS = 30_000L
    }
}
