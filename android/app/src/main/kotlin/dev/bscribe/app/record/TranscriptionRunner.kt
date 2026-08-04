package dev.bscribe.app.record

import android.util.Log
import dev.bscribe.asr.LanguagePlan
import dev.bscribe.asr.ModelFiles
import dev.bscribe.asr.WhisperEngine
import dev.bscribe.core.asr.DecodeParams
import dev.bscribe.core.audio.WavReader
import dev.bscribe.core.model.SessionState
import dev.bscribe.data.repo.ModelCatalog
import dev.bscribe.data.repo.ModelRepository
import dev.bscribe.data.repo.SessionRepository
import dev.bscribe.data.repo.SettingsRepository
import dev.bscribe.data.repo.TranscribeLanguages
import kotlinx.coroutines.flow.first

/** Progress for the foreground notification while the final pass runs. */
data class TranscribeProgress(val segment: Int, val totalSegments: Int)

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
            return TranscribeOutcome.NoModel(spec.label)
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
            segments.forEachIndexed { index, recording ->
                onProgress(TranscribeProgress(index + 1, segments.size))
                val wav = repo.resolveWav(recording)
                if (!wav.isFile) {
                    Log.w(TAG, "segment ${recording.id} has no audio file; skipping")
                    return@forEachIndexed
                }
                val pcm = WavReader.readRange(wav, 0, recording.durationMs)
                val words = engine.transcribe(pcm, DecodeParams(nThreads = threads))
                repo.saveTranscript(recording.id, words, modelId)
            }

            repo.updateLanguageHint(sessionId)
            repo.setState(sessionId, SessionState.TRANSCRIBED)
            TranscribeOutcome.Done
        } catch (e: Exception) {
            Log.e(TAG, "transcription failed for $sessionId", e)
            // Back to STOPPED, not stuck in TRANSCRIBING: the audio is intact
            // and a retry must be possible.
            runCatching { repo.setState(sessionId, SessionState.STOPPED) }
            TranscribeOutcome.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            engine?.close()
        }
    }

    private companion object {
        const val TAG = "TranscriptionRunner"
    }
}
