package dev.bscribe.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** How much work the live transcriber may do while recording. */
enum class LiveTranscriptMode {
    /** 8 s window / 3.5 s stride. */
    FULL,

    /** 6 s window / 5 s stride — cooler and kinder to the battery. */
    ECONOMY,

    /** Capture only; transcript is produced after Stop. */
    OFF,
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private val keyLiveMode = stringPreferencesKey("live_transcript_mode")

    val liveTranscriptMode: Flow<LiveTranscriptMode> =
        context.dataStore.data.map { prefs ->
            prefs[keyLiveMode]?.let { runCatching { LiveTranscriptMode.valueOf(it) }.getOrNull() }
                ?: LiveTranscriptMode.FULL
        }

    suspend fun setLiveTranscriptMode(mode: LiveTranscriptMode) {
        context.dataStore.edit { it[keyLiveMode] = mode.name }
    }
}
