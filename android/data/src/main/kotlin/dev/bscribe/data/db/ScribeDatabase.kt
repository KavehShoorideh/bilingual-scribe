package dev.bscribe.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.bscribe.core.model.SessionState

class Converters {
    @TypeConverter
    fun sessionStateToString(state: SessionState): String = state.name

    @TypeConverter
    fun stringToSessionState(raw: String): SessionState = SessionState.valueOf(raw)
}

@Database(
    entities = [
        SessionEntity::class,
        RecordingEntity::class,
        TranscriptWordEntity::class,
        CorrectionEntity::class,
        ExportRecordEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class ScribeDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun recordingDao(): RecordingDao
    abstract fun transcriptDao(): TranscriptDao
    abstract fun correctionDao(): CorrectionDao
    abstract fun exportDao(): ExportDao

    companion object {
        /**
         * v1 → v2: per-word language and the decode confidence that produced
         * it, both added by M1a's bilingual final pass.
         *
         * Written as a real migration rather than a destructive fallback
         * because by the time this shipped there were already recordings on
         * the phone, and losing them would violate the one guarantee this app
         * makes.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE transcript_words " +
                        "ADD COLUMN lang TEXT NOT NULL DEFAULT 'und'",
                )
                db.execSQL(
                    "ALTER TABLE transcript_words " +
                        "ADD COLUMN avgLogProb REAL NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * v2 → v3: soft delete. Existing notes are live, so the column
         * defaults to NULL and nothing lands in the trash retroactively.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN deletedAt INTEGER DEFAULT NULL")
            }
        }

        /** v3 → v4: how long the last pass took, and with which model. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN transcribeWallMs INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE sessions ADD COLUMN transcribeAudioMs INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE sessions ADD COLUMN transcribeModel TEXT DEFAULT NULL")
            }
        }

        /** v4 → v5: speaking turn per word. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE transcript_words " +
                        "ADD COLUMN turnIdx INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        fun build(context: Context): ScribeDatabase =
            Room.databaseBuilder(context, ScribeDatabase::class.java, "scribe.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
    }
}
