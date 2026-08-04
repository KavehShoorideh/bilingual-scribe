package dev.bscribe.app

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ScribeApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Requirement #1: before anything else can touch the data, repair any
        // recording the last process death left behind.
        appScope.launch {
            val report = container.sessionRepository.recoverInterrupted()
            if (report.repairedSegments + report.recoveredSessions > 0) {
                Log.i(TAG, "startup recovery: $report")
            }

            // Trash expires on app open rather than on a timer: a note deleted
            // a month ago and never asked about is safe to erase, but only on
            // the user's own schedule.
            val purged = container.sessionRepository.purgeExpiredTrash()
            if (purged > 0) Log.i(TAG, "purged $purged expired note(s) from trash")
        }
    }

    companion object {
        private const val TAG = "ScribeApp"
    }
}
