package dev.bscribe.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.bscribe.app.appContainer
import dev.bscribe.app.record.RecorderStateHolder
import dev.bscribe.app.record.RecorderStatus
import dev.bscribe.app.update.ApkInstaller
import dev.bscribe.data.repo.LiveTranscriptMode
import dev.bscribe.data.repo.SettingsRepository
import dev.bscribe.data.repo.TranscribeLanguages
import dev.bscribe.data.repo.UpdateRepository
import dev.bscribe.data.repo.UpdateState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val updates: UpdateRepository,
    private val recorder: RecorderStateHolder,
    private val appContext: Context,
) : ViewModel() {

    val liveMode: StateFlow<LiveTranscriptMode> = settings.liveTranscriptMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveTranscriptMode.FULL)

    val languages: StateFlow<TranscribeLanguages> = settings.transcribeLanguages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TranscribeLanguages.BOTH)

    val autoTranscribe: StateFlow<Boolean> = settings.autoTranscribe
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val updateState: StateFlow<UpdateState> = updates.state

    fun setLiveMode(mode: LiveTranscriptMode) {
        viewModelScope.launch { settings.setLiveTranscriptMode(mode) }
    }

    fun setLanguages(value: TranscribeLanguages) {
        viewModelScope.launch { settings.setTranscribeLanguages(value) }
    }

    fun setAutoTranscribe(value: Boolean) {
        viewModelScope.launch { settings.setAutoTranscribe(value) }
    }

    fun checkForUpdate() {
        viewModelScope.launch { updates.check() }
    }

    fun download(info: dev.bscribe.data.repo.UpdateInfo) {
        viewModelScope.launch { updates.download(info) }
    }

    /**
     * Installing restarts the process. Requirement #1 is that an idea is never
     * lost, so refuse outright while the recorder is doing anything at all —
     * a paused session still owns un-finalized state.
     */
    fun install(): String? {
        val ready = updateState.value as? UpdateState.ReadyToInstall
            ?: return "Nothing is ready to install."
        if (recorder.status.value != RecorderStatus.IDLE) {
            return "Stop the current recording before updating."
        }
        return ApkInstaller.install(appContext, ready.apk)
    }

    fun dismiss() = updates.reset()

    companion object {
        fun factory(context: Context) = viewModelFactory {
            initializer {
                val container = context.appContainer
                SettingsViewModel(
                    settings = container.settingsRepository,
                    updates = container.updateRepository,
                    recorder = container.recorderState,
                    appContext = context.applicationContext,
                )
            }
        }
    }
}
