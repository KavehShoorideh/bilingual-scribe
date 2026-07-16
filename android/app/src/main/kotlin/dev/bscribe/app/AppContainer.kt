package dev.bscribe.app

import android.content.Context
import dev.bscribe.app.record.RecorderStateHolder
import dev.bscribe.data.db.ScribeDatabase
import dev.bscribe.data.repo.SessionRepository
import dev.bscribe.data.repo.SettingsRepository
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

    /** Single source of truth the record screen and the service both observe. */
    val recorderState: RecorderStateHolder = RecorderStateHolder()
}

val Context.appContainer: AppContainer
    get() = (applicationContext as ScribeApplication).container
