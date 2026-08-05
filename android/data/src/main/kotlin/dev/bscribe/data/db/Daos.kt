package dev.bscribe.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import dev.bscribe.core.model.SessionState
import kotlinx.coroutines.flow.Flow

data class SessionWithRecordings(
    @Embedded val session: SessionEntity,
    @Relation(parentColumn = "id", entityColumn = "sessionId")
    val recordings: List<RecordingEntity>,
)

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: SessionEntity)

    @Update
    suspend fun update(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun byId(id: String): SessionEntity?

    @Query("UPDATE sessions SET state = :state WHERE id = :id")
    suspend fun setState(id: String, state: SessionState)

    @Query("UPDATE sessions SET title = :title WHERE id = :id")
    suspend fun setTitle(id: String, title: String?)

    @Query("UPDATE sessions SET reviewedAt = :reviewedAt, state = :state WHERE id = :id")
    suspend fun markReviewed(id: String, reviewedAt: Long, state: SessionState = SessionState.REVIEWED)

    @Transaction
    @Query("SELECT * FROM sessions WHERE deletedAt IS NULL ORDER BY createdAt DESC")
    fun observeAllWithRecordings(): Flow<List<SessionWithRecordings>>

    @Transaction
    @Query("SELECT * FROM sessions WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeTrashWithRecordings(): Flow<List<SessionWithRecordings>>

    @Query("UPDATE sessions SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun setDeletedAt(id: String, deletedAt: Long?)

    @Query("SELECT * FROM sessions WHERE deletedAt IS NOT NULL AND deletedAt < :before")
    suspend fun trashedBefore(before: Long): List<SessionEntity>

    @Transaction
    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeWithRecordings(id: String): Flow<SessionWithRecordings?>

    @Query("SELECT * FROM sessions WHERE state IN (:states)")
    suspend fun byStates(states: List<SessionState>): List<SessionEntity>

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface RecordingDao {
    @Insert
    suspend fun insert(recording: RecordingEntity)

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun byId(id: String): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE sessionId = :sessionId ORDER BY idx")
    suspend fun bySession(sessionId: String): List<RecordingEntity>

    @Query("SELECT COALESCE(MAX(idx), -1) FROM recordings WHERE sessionId = :sessionId")
    suspend fun maxIdx(sessionId: String): Int

    @Query("UPDATE recordings SET finalized = 1, durationMs = :durationMs WHERE id = :id")
    suspend fun markFinalized(id: String, durationMs: Long)

    @Query("SELECT * FROM recordings WHERE finalized = 0")
    suspend fun unfinalized(): List<RecordingEntity>

    @Query(
        "SELECT * FROM recordings WHERE durationMs > :minDurationMs " +
            "ORDER BY startedWallClock DESC LIMIT :limit",
    )
    suspend fun recent(minDurationMs: Long, limit: Int): List<RecordingEntity>
}

@Dao
interface TranscriptDao {
    @Insert
    suspend fun insertAll(words: List<TranscriptWordEntity>)

    @Query("SELECT * FROM transcript_words WHERE recordingId = :recordingId ORDER BY wordIdx")
    suspend fun byRecording(recordingId: String): List<TranscriptWordEntity>

    @Query(
        "SELECT * FROM transcript_words WHERE recordingId = :recordingId " +
            "AND chosen = 1 ORDER BY wordIdx",
    )
    fun observeByRecording(recordingId: String): Flow<List<TranscriptWordEntity>>

    /** Every reading, chosen or not — what the dual-lane timeline renders. */
    @Query("SELECT * FROM transcript_words WHERE recordingId = :recordingId ORDER BY t0Ms")
    fun observeAllVariants(recordingId: String): Flow<List<TranscriptWordEntity>>

    @Query(
        "UPDATE transcript_words SET chosen = (variant = :variant) " +
            "WHERE recordingId = :recordingId AND t0Ms < :t1Ms AND t1Ms > :t0Ms",
    )
    suspend fun chooseVariant(recordingId: String, t0Ms: Long, t1Ms: Long, variant: String)

    @Query("DELETE FROM transcript_words WHERE recordingId = :recordingId")
    suspend fun deleteByRecording(recordingId: String)
}

@Dao
interface CorrectionDao {
    @Insert
    suspend fun insert(correction: CorrectionEntity)

    @Update
    suspend fun update(correction: CorrectionEntity)

    @Query("SELECT * FROM corrections WHERE recordingId = :recordingId AND archived = 0")
    suspend fun activeByRecording(recordingId: String): List<CorrectionEntity>

    @Query("SELECT * FROM corrections WHERE recordingId = :recordingId AND archived = 0")
    fun observeActiveByRecording(recordingId: String): Flow<List<CorrectionEntity>>

    @Query("UPDATE corrections SET archived = 1 WHERE recordingId = :recordingId")
    suspend fun archiveByRecording(recordingId: String)

    /** Retires earlier verdicts covering any of the same words. */
    @Query(
        "UPDATE corrections SET archived = 1 WHERE recordingId = :recordingId " +
            "AND archived = 0 AND firstWordIdx <= :lastWordIdx AND lastWordIdx >= :firstWordIdx",
    )
    suspend fun archiveOverlapping(recordingId: String, firstWordIdx: Int, lastWordIdx: Int)

    @Query("DELETE FROM corrections WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ExportDao {
    @Insert
    suspend fun insert(record: ExportRecordEntity)

    @Query("SELECT * FROM export_records ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ExportRecordEntity>>
}

@Dao
interface LanguageFeedbackDao {
    @Insert
    suspend fun insert(feedback: LanguageFeedbackEntity)

    @Query("SELECT * FROM language_feedback WHERE recordingId = :recordingId ORDER BY t0Ms")
    fun observeByRecording(recordingId: String): Flow<List<LanguageFeedbackEntity>>

    /**
     * Feedback is keyed by the audio span it refers to, so re-judging the same
     * utterance replaces the earlier verdict rather than accumulating both.
     */
    @Query(
        "DELETE FROM language_feedback WHERE recordingId = :recordingId " +
            "AND t0Ms < :t1Ms AND t1Ms > :t0Ms",
    )
    suspend fun deleteOverlapping(recordingId: String, t0Ms: Long, t1Ms: Long)
}
