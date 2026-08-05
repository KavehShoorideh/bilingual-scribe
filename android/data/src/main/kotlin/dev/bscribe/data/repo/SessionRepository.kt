package dev.bscribe.data.repo

import dev.bscribe.core.asr.Lang
import dev.bscribe.core.asr.ScriptLang
import dev.bscribe.core.asr.TranscriptPass
import dev.bscribe.core.asr.Word
import dev.bscribe.core.audio.WavRepair
import dev.bscribe.core.audio.WavSpec
import dev.bscribe.core.model.SessionState
import dev.bscribe.data.db.RecordingEntity
import dev.bscribe.data.db.ScribeDatabase
import dev.bscribe.data.db.CorrectionEntity
import dev.bscribe.data.db.LanguageFeedbackEntity
import dev.bscribe.data.db.SessionEntity
import dev.bscribe.data.db.SessionWithRecordings
import dev.bscribe.data.db.TranscriptWordEntity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Sessions, their audio segments, and crash recovery.
 *
 * Audio layout: `<audioRoot>/<sessionId>/seg<idx>.wav`, with only the path
 * relative to [audioRoot] persisted, so a restored DB + audio folder keeps
 * working wherever the app data ends up.
 */
class SessionRepository(
    private val db: ScribeDatabase,
    private val audioRoot: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val sessions get() = db.sessionDao()
    private val recordings get() = db.recordingDao()
    private val transcripts get() = db.transcriptDao()
    private val feedback get() = db.feedbackDao()
    private val corrections get() = db.correctionDao()

    fun observeSessions(): Flow<List<SessionWithRecordings>> = sessions.observeAllWithRecordings()

    fun observeTranscript(recordingId: String): Flow<List<TranscriptWordEntity>> =
        transcripts.observeByRecording(recordingId)

    suspend fun transcriptOf(recordingId: String): List<TranscriptWordEntity> =
        transcripts.byRecording(recordingId)

    /**
     * Replaces a recording's transcript.
     *
     * Delete-then-insert rather than append, so re-running the pass — after a
     * crash, or with a better model — is idempotent instead of doubling every
     * word.
     */
    suspend fun saveTranscript(
        recordingId: String,
        pass: TranscriptPass,
        modelId: String,
        turns: IntArray = IntArray(pass.chosen.size),
    ) {
        transcripts.deleteByRecording(recordingId)

        fun rows(words: List<Word>, variant: String, chosen: Boolean) =
            words.mapIndexed { idx, w ->
                TranscriptWordEntity(
                    recordingId = recordingId,
                    wordIdx = idx,
                    text = w.text,
                    t0Ms = w.t0Ms,
                    t1Ms = w.t1Ms,
                    prob = w.prob,
                    segmentIdx = w.segmentIdx,
                    modelId = modelId,
                    lang = w.lang.code,
                    turnIdx = if (chosen) turns.getOrElse(idx) { 0 } else 0,
                    variant = variant,
                    chosen = chosen,
                )
            }

        // The chosen reading is stored under variant "" so it survives
        // independently of which language produced it; the per-language
        // readings are stored alongside for the comparison view.
        val all = rows(pass.chosen, variant = CHOSEN_VARIANT, chosen = true) +
            pass.alternatives.flatMap { (lang, words) ->
                rows(words, variant = lang, chosen = false)
            }
        if (all.isEmpty()) return
        transcripts.insertAll(all)
    }

    fun observeAllVariants(recordingId: String): Flow<List<TranscriptWordEntity>> =
        transcripts.observeAllVariants(recordingId)

    fun observeLanguageFeedback(recordingId: String): Flow<List<LanguageFeedbackEntity>> =
        feedback.observeByRecording(recordingId)

    /**
     * Records that the user says a stretch of audio was [lang], and switches
     * the transcript to that reading.
     */
    suspend fun chooseLanguage(recordingId: String, t0Ms: Long, t1Ms: Long, lang: String) {
        rewriteCanonical(recordingId, t0Ms, t1Ms, lang)
        // Which lane reads as current in the comparison view.
        transcripts.chooseVariant(recordingId, t0Ms, t1Ms, lang)
        // The words over this span have been replaced, so any correction
        // pointing into it now refers to speech that is no longer there. A
        // correction aimed at the wrong words is worse than none.
        corrections.archiveOverlappingTime(recordingId, t0Ms, t1Ms)
        feedback.deleteOverlapping(recordingId, t0Ms, t1Ms)
        feedback.insert(
            LanguageFeedbackEntity(
                id = UUID.randomUUID().toString(),
                recordingId = recordingId,
                t0Ms = t0Ms,
                t1Ms = t1Ms,
                chosenLang = lang,
                createdAt = clock(),
            ),
        )
    }

    /**
     * Replaces the canonical transcript over a span with another language's
     * reading of it, renumbering the whole recording.
     *
     * The canonical transcript is a single sequence with a single numbering.
     * Flipping visibility between it and the per-language variants instead —
     * which is what this used to do — interleaved two independent wordIdx
     * sequences and scrambled the sentence on screen.
     */
    private suspend fun rewriteCanonical(
        recordingId: String,
        t0Ms: Long,
        t1Ms: Long,
        lang: String,
    ) {
        val rows = transcripts.allRowsOf(recordingId)
        fun overlapsSpan(w: TranscriptWordEntity) = w.t0Ms < t1Ms && w.t1Ms > t0Ms

        val replacement = rows.filter { it.variant == lang && overlapsSpan(it) }
        // Nothing to swap in — leave the transcript alone rather than blanking
        // the span, which would silently delete words.
        if (replacement.isEmpty()) return

        val outsideSpan = rows.filter { it.variant == CHOSEN_VARIANT && !overlapsSpan(it) }
        val rebuilt = (outsideSpan + replacement)
            .sortedBy { it.t0Ms }
            .mapIndexed { idx, w ->
                w.copy(
                    id = 0, // autoGenerate; these are new rows
                    wordIdx = idx,
                    variant = CHOSEN_VARIANT,
                    chosen = true,
                )
            }

        transcripts.deleteCanonical(recordingId)
        transcripts.insertAll(rebuilt)
    }

    /**
     * Marks a session as reviewed.
     *
     * This is an assertion, not a bookmark: everything untouched in the
     * session is taken as correct, which is what lets those words export as
     * weak positives (`accepted` in docs/dataset-format.md). Reviewing a
     * session you have not actually read would put wrong text into training
     * data labelled as right.
     */
    suspend fun markReviewed(sessionId: String) {
        sessions.markReviewed(sessionId, clock())
    }

    suspend fun unmarkReviewed(sessionId: String) {
        val current = sessions.byId(sessionId) ?: return
        sessions.update(current.copy(reviewedAt = null, state = SessionState.TRANSCRIBED))
    }

    fun observeCorrections(recordingId: String): Flow<List<CorrectionEntity>> =
        corrections.observeActiveByRecording(recordingId)

    /**
     * Saves a correction over a span of words.
     *
     * The underlying words are never edited. A correction is an overlay, which
     * is what makes it usable as training data: the pair of what the model
     * heard and what was actually said is the signal, and rewriting the words
     * in place would destroy half of it.
     *
     * An empty [correctedText] means the span was not speech at all.
     */
    suspend fun applyCorrection(
        recordingId: String,
        firstWordIdx: Int,
        lastWordIdx: Int,
        t0Ms: Long,
        t1Ms: Long,
        originalText: String,
        correctedText: String,
    ) {
        // Replace any earlier verdict on overlapping words rather than
        // stacking contradictory corrections on the same audio.
        corrections.archiveOverlapping(recordingId, firstWordIdx, lastWordIdx)
        corrections.insert(
            CorrectionEntity(
                id = UUID.randomUUID().toString(),
                recordingId = recordingId,
                firstWordIdx = firstWordIdx,
                lastWordIdx = lastWordIdx,
                t0Ms = t0Ms,
                t1Ms = t1Ms,
                originalText = originalText,
                correctedText = correctedText,
                archived = false,
                createdAt = clock(),
                updatedAt = clock(),
            ),
        )
    }

    /**
     * Records what languages actually turned up in a session, for the export
     * bundle's `language_hint` (docs/dataset-format.md).
     */
    /**
     * The most recent recording with real audio, for benchmarking against
     * something the user actually said rather than a bundled sample.
     */
    suspend fun newestRecording(minDurationMs: Long = 2_000): Pair<File, Long>? {
        for (rec in recordings.recent(minDurationMs, limit = 5)) {
            val file = resolveWav(rec)
            if (file.isFile) return file to rec.durationMs
        }
        return null
    }

    /** Records what the last pass cost, so "slow" becomes a number. */
    suspend fun recordPassTiming(
        sessionId: String,
        wallMs: Long,
        audioMs: Long,
        model: String,
    ) {
        val current = sessions.byId(sessionId) ?: return
        sessions.update(
            current.copy(
                transcribeWallMs = wallMs,
                transcribeAudioMs = audioMs,
                transcribeModel = model,
            ),
        )
    }

    suspend fun updateLanguageHint(sessionId: String) {
        val langs = recordingsOf(sessionId)
            .flatMap { transcriptOf(it.id) }
            .map {
                when (it.lang) {
                    "en" -> Lang.EN
                    "fa" -> Lang.FA
                    else -> Lang.UND
                }
            }
        val current = sessions.byId(sessionId) ?: return
        sessions.update(current.copy(languageHint = ScriptLang.ofSession(langs)))
    }

    fun observeSession(id: String): Flow<SessionWithRecordings?> = sessions.observeWithRecordings(id)

    suspend fun session(id: String): SessionEntity? = sessions.byId(id)

    suspend fun recordingsOf(sessionId: String): List<RecordingEntity> =
        recordings.bySession(sessionId)

    fun resolveWav(recording: RecordingEntity): File = File(audioRoot, recording.wavPath)

    suspend fun createSession(languageHint: String? = "en"): SessionEntity {
        val session = SessionEntity(
            id = UUID.randomUUID().toString(),
            title = null,
            createdAt = clock(),
            state = SessionState.RECORDING,
            languageHint = languageHint,
            reviewedAt = null,
        )
        sessions.insert(session)
        return session
    }

    /** Registers the next audio segment and returns it with its WAV resolved. */
    suspend fun startSegment(sessionId: String, spec: WavSpec = WavSpec.SCRIBE_DEFAULT): Pair<RecordingEntity, File> {
        val idx = recordings.maxIdx(sessionId) + 1
        val relPath = "$sessionId/seg%03d.wav".format(idx)
        val recording = RecordingEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            idx = idx,
            wavPath = relPath,
            startedWallClock = clock(),
            durationMs = 0,
            sampleRate = spec.sampleRate,
            finalized = false,
        )
        val file = File(audioRoot, relPath)
        file.parentFile?.mkdirs()
        recordings.insert(recording)
        sessions.setState(sessionId, SessionState.RECORDING)
        return recording to file
    }

    suspend fun finalizeSegment(recordingId: String, durationMs: Long) {
        recordings.markFinalized(recordingId, durationMs)
    }

    suspend fun setState(sessionId: String, state: SessionState) = sessions.setState(sessionId, state)

    suspend fun setTitle(sessionId: String, title: String?) = sessions.setTitle(sessionId, title)

    fun observeTrash(): Flow<List<SessionWithRecordings>> = sessions.observeTrashWithRecordings()

    /**
     * Moves a note to the trash. Nothing is erased — the rows stay and the
     * audio stays on disk.
     *
     * A mis-tap on Delete was the last remaining way to lose a recording in an
     * app whose entire premise is that you can't.
     */
    suspend fun trashSession(sessionId: String) {
        sessions.setDeletedAt(sessionId, clock())
    }

    suspend fun restoreSession(sessionId: String) {
        sessions.setDeletedAt(sessionId, null)
    }

    /** Erases a note for real: rows cascade, audio is deleted. */
    suspend fun deleteSession(sessionId: String) {
        sessions.delete(sessionId)
        File(audioRoot, sessionId).deleteRecursively()
    }

    /** @return how many notes were erased. */
    suspend fun emptyTrash(): Int {
        val all = sessions.trashedBefore(Long.MAX_VALUE)
        all.forEach { deleteSession(it.id) }
        return all.size
    }

    /**
     * Purges notes trashed longer ago than [retentionMs].
     *
     * Run at startup rather than on a timer: a note you deleted a month ago
     * and never asked about is safe to remove, but only on the user's own
     * schedule of opening the app.
     *
     * @return how many notes were erased.
     */
    suspend fun purgeExpiredTrash(retentionMs: Long = TRASH_RETENTION_MS): Int {
        val expired = sessions.trashedBefore(clock() - retentionMs)
        expired.forEach { deleteSession(it.id) }
        return expired.size
    }

    /**
     * Called once at app start, before any UI: repairs WAVs of recordings that
     * were being written when the process died, then parks their sessions in
     * STOPPED so their audio is visible and playable. Requirement #1's second
     * half — losing the process must not lose the idea.
     */
    suspend fun recoverInterrupted(): RecoveryReport {
        var repaired = 0
        var rejected = 0
        for (rec in recordings.unfinalized()) {
            val file = File(audioRoot, rec.wavPath)
            when (val result = WavRepair.repair(file)) {
                is WavRepair.Result.Consistent -> {
                    recordings.markFinalized(rec.id, result.durationMs)
                    repaired++
                }
                is WavRepair.Result.Repaired -> {
                    recordings.markFinalized(rec.id, result.durationMs)
                    repaired++
                }
                is WavRepair.Result.Rejected -> {
                    // Nothing usable ever hit the disk (e.g. crash before the
                    // first buffer). Finalize as empty; keep the row for audit.
                    recordings.markFinalized(rec.id, 0)
                    rejected++
                }
            }
        }
        // TRANSCRIBING is included because it can only be true while a process
        // is actively transcribing. Seeing it at startup means the last one
        // died mid-pass — which a native abort inside whisper does without any
        // chance to write a final state — and the session would otherwise show
        // "Transcribing…" forever with nothing running.
        val stuck = sessions.byStates(
            listOf(SessionState.RECORDING, SessionState.PAUSED, SessionState.TRANSCRIBING),
        )
        for (session in stuck) {
            sessions.setState(session.id, SessionState.STOPPED)
        }
        return RecoveryReport(repairedSegments = repaired, emptySegments = rejected, recoveredSessions = stuck.size)
    }

    data class RecoveryReport(
        val repairedSegments: Int,
        val emptySegments: Int,
        val recoveredSessions: Int,
    )

    companion object {
        /** Variant marker for the merged reading the transcript shows. */
        const val CHOSEN_VARIANT = ""

        /**
         * How long a trashed note is kept. Generous on purpose: audio is small
         * next to the cost of losing a thought, and the user can empty the
         * trash themselves whenever they want the space back.
         */
        const val TRASH_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}
