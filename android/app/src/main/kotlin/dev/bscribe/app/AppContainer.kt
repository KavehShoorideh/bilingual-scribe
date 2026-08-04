package dev.bscribe.app

import android.content.Context
import dev.bscribe.app.record.RecorderStateHolder
import dev.bscribe.app.record.TranscriptionRunner
import dev.bscribe.data.db.ScribeDatabase
import dev.bscribe.data.repo.ModelRepository
import dev.bscribe.data.repo.SessionRepository
import dev.bscribe.data.repo.SettingsRepository
import dev.bscribe.data.repo.UpdateRepository
import java.io.File

/**
 * Hand-rolled dependency container — one developer, three modules; a DI
 * framework would cost more than it saves (plan §2.1).
 */
class AppContainer(context: Context) {

    val database: ScribeDatabase by lazy { ScribeDatabase.build(context) }

    val audioRoot: File = File(context.filesDir, "audio").apply { mkdirs() }

    val sessionRepository: SessionRepository by lazy {
        SessionRepository(database, audioRoot)
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(context)
    }

    val updateRepository: UpdateRepository by lazy {
        UpdateRepository(
            cacheDir = context.cacheDir,
            currentVersionCode = BuildConfig.VERSION_CODE,
        )
    }

    val modelRepository: ModelRepository by lazy {
        ModelRepository(context.filesDir).also { it.refresh() }
    }

    val transcriptionRunner: TranscriptionRunner by lazy {
        TranscriptionRunner(sessionRepository, settingsRepository, modelRepository)
    }

    /** Single source of truth the record screen and the service both observe. */
    val recorderState: RecorderStateHolder = RecorderStateHolder()
}

val Context.appContainer: AppContainer
    get() = (applicationContext as ScribeApplication).container
