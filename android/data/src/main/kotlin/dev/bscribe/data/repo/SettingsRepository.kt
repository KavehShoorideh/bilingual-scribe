package dev.bscribe.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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

/**
 * Which languages the final pass decodes each window in.
 *
 * [BOTH] is the default: it costs roughly twice the compute but does not
 * depend on language detection being right, and this is a bilingual notebook.
 * Restricting to one language halves the work and the heat.
 */
enum class TranscribeLanguages { BOTH, ENGLISH, FARSI }

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private val keyLiveMode = stringPreferencesKey("live_transcript_mode")
    private val keyFinalModel = stringPreferencesKey("final_model_file")
    private val keyLanguages = stringPreferencesKey("transcribe_languages")
    private val keyThreads = intPreferencesKey("n_threads")
    private val keyAutoTranscribe = booleanPreferencesKey("auto_transcribe")

    val liveTranscriptMode: Flow<LiveTranscriptMode> =
        context.dataStore.data.map { prefs ->
            prefs[keyLiveMode]?.let { runCatching { LiveTranscriptMode.valueOf(it) }.getOrNull() }
                ?: LiveTranscriptMode.FULL
        }

    suspend fun setLiveTranscriptMode(mode: LiveTranscriptMode) {
        context.dataStore.edit { it[keyLiveMode] = mode.name }
    }

    /** File name of the model the final pass should use. */
    val finalModelFile: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[keyFinalModel] ?: ModelCatalog.defaultFinal.fileName
    }

    suspend fun setFinalModelFile(fileName: String) {
        context.dataStore.edit { it[keyFinalModel] = fileName }
    }

    val transcribeLanguages: Flow<TranscribeLanguages> = context.dataStore.data.map { prefs ->
        prefs[keyLanguages]?.let { runCatching { TranscribeLanguages.valueOf(it) }.getOrNull() }
            ?: TranscribeLanguages.BOTH
    }

    suspend fun setTranscribeLanguages(value: TranscribeLanguages) {
        context.dataStore.edit { it[keyLanguages] = value.name }
    }

    /**
     * Threads across all concurrent decodes.
     *
     * Defaults to every core: dual-language decoding splits this budget in
     * two, so a low value leaves most of the chip idle while the user waits.
     */
    val nThreads: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[keyThreads] ?: Runtime.getRuntime().availableProcessors())
            .coerceIn(1, 8)
    }

    suspend fun setNThreads(value: Int) {
        context.dataStore.edit { it[keyThreads] = value.coerceIn(1, 8) }
    }

    /** Whether stopping a recording kicks off the final pass automatically. */
    val autoTranscribe: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[keyAutoTranscribe] ?: true
    }

    suspend fun setAutoTranscribe(value: Boolean) {
        context.dataStore.edit { it[keyAutoTranscribe] = value }
    }
}
