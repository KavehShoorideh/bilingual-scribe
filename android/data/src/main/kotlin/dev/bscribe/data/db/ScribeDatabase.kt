package dev.bscribe.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
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
    version = 1,
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
        fun build(context: Context): ScribeDatabase =
            Room.databaseBuilder(context, ScribeDatabase::class.java, "scribe.db")
                .build()
    }
}
