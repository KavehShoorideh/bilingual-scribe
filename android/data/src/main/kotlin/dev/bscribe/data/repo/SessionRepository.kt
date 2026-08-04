package dev.bscribe.data.repo

import dev.bscribe.core.asr.Lang
import dev.bscribe.core.asr.ScriptLang
import dev.bscribe.core.asr.Word
import dev.bscribe.core.audio.WavRepair
import dev.bscribe.core.audio.WavSpec
import dev.bscribe.core.model.SessionState
import dev.bscribe.data.db.RecordingEntity
import dev.bscribe.data.db.ScribeDatabase
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
    suspend fun saveTranscript(recordingId: String, words: List<Word>, modelId: String) {
        transcripts.deleteByRecording(recordingId)
        if (words.isEmpty()) return
        transcripts.insertAll(
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
                )
            },
        )
    }

    /**
     * Records what languages actually turned up in a session, for the export
     * bundle's `language_hint` (docs/dataset-format.md).
     */
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
        val stuck = sessions.byStates(listOf(SessionState.RECORDING, SessionState.PAUSED))
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
        /**
         * How long a trashed note is kept. Generous on purpose: audio is small
         * next to the cost of losing a thought, and the user can empty the
         * trash themselves whenever they want the space back.
         */
        const val TRASH_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}
