package dev.bscribe.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.bscribe.core.model.SessionState

/** One note-taking session: possibly several recordings (pause/resume). */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String?,
    val createdAt: Long,
    val state: SessionState,
    /** "en" | "fa" | "mixed" | null (autodetect). Drives decode + export. */
    val languageHint: String?,
    val reviewedAt: Long?,
    /**
     * When the note was moved to the trash, or null if it is live.
     *
     * Deleting is a soft delete: the audio stays on disk until the note is
     * purged. The whole point of this app is that an idea is never lost, and
     * a mis-tap on Delete was the one remaining way to lose one.
     */
    val deletedAt: Long? = null,
    /**
     * How long the last transcription pass took, and how much audio it covered.
     *
     * Kept so the cost of a pass is visible per note instead of guessed at —
     * "slow" is not actionable, "1.8x realtime on Base with both languages" is.
     */
    val transcribeWallMs: Long? = null,
    val transcribeAudioMs: Long? = null,
    /** Which model produced the current transcript, for the same reason. */
    val transcribeModel: String? = null,
)

/**
 * One contiguous stretch of captured audio — a session gets a new row (and a
 * new WAV) on every resume. [wavPath] is relative to the app's audio root so
 * DB backups stay valid across restores.
 */
@Entity(
    tableName = "recordings",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class RecordingEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    /** 0-based position within the session. */
    val idx: Int,
    val wavPath: String,
    val startedWallClock: Long,
    val durationMs: Long,
    val sampleRate: Int,
    /** False while actively being written; false at app start ⇒ crashed. */
    val finalized: Boolean,
)

/** A word from the final (accurate) transcription pass. Immutable. */
@Entity(
    tableName = "transcript_words",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("recordingId")],
)
data class TranscriptWordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: String,
    /** 0-based order within the recording; tap targets key on this. */
    val wordIdx: Int,
    val text: String,
    val t0Ms: Long,
    val t1Ms: Long,
    val prob: Float,
    /** Decoder segment index — groups words into utterances for display/export. */
    val segmentIdx: Int,
    /** Which model produced this pass, e.g. "ggml-base-q5_1:sha256:ab12". */
    val modelId: String,
    /**
     * "en" | "fa" | "und", from the word's Unicode script. Feeds the
     * `language_hint` field of the export bundle.
     */
    val lang: String = "und",
    /**
     * Mean log probability of the decode this word came from. Kept so a
     * questionable transcript can be checked against the arbitration decision
     * that produced it — with dual-language decoding, "why did it pick Farsi
     * here?" is otherwise unanswerable after the fact.
     */
    val avgLogProb: Float = 0f,
)

/**
 * A user correction over a span of transcript words. Overlay semantics: the
 * underlying words are never mutated, so corrections double as training pairs
 * even after a re-transcription replaces the word rows.
 */
@Entity(
    tableName = "corrections",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("recordingId")],
)
data class CorrectionEntity(
    @PrimaryKey val id: String,
    val recordingId: String,
    val firstWordIdx: Int,
    val lastWordIdx: Int,
    /** Audio span of the corrected words at correction time. */
    val t0Ms: Long,
    val t1Ms: Long,
    /** Space-joined original words, frozen when the correction was made. */
    val originalText: String,
    /** Empty string ⇒ the words were deleted (misfire, noise). */
    val correctedText: String,
    /** True once the word rows it pointed at were replaced by a new pass. */
    val archived: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Log of dataset exports (audit + incremental export later). */
@Entity(tableName = "export_records")
data class ExportRecordEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val treeUri: String,
    val schemaVersion: Int,
    val sessionCount: Int,
    val clipCount: Int,
)
