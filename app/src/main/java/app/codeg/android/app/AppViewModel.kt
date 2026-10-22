package app.codeg.android.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.AppSettings
import app.codeg.android.core.datastore.AppSettingsStore
import app.codeg.android.core.datastore.ServerProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App-level state: appearance settings (drives the theme) and the saved servers
 * + selection. Owns server-selection / deletion actions.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    appSettingsStore: AppSettingsStore,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = appSettingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** null = not loaded yet (show a splash); non-null = loaded (possibly empty). */
    val servers: StateFlow<List<ServerProfile>?> = serverRepository.profiles
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val selectedProfile: StateFlow<ServerProfile?> = serverRepository.selectedProfile
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Aggregate "All devices" mode for the Activity tab (see ServerRepository —
    // shared state lives there so feature ViewModels can observe it too).
    val activityAll: StateFlow<Boolean> = serverRepository.activityAll

    fun setActivityAll(all: Boolean) = serverRepository.setActivityAll(all)

    /** Re-poll every device's activity feed while already in "All" mode. */
    fun refreshActivityAll() = serverRepository.requestActivityRefresh()

    fun selectServer(id: String) {
        viewModelScope.launch { serverRepository.select(id) }
    }

    /**
     * Select a server and run [onDone] only after the selection has landed in the
     * store. The fire-and-forget [selectServer] races anyone who reads
     * `selectedProfile.first()` right after (session detail reads it exactly once on
     * entry and would resolve the previous server).
     */
    fun selectServerAndWait(id: String, onDone: () -> Unit) {
        viewModelScope.launch {
            serverRepository.select(id)
            onDone()
        }
    }

    fun deleteServer(id: String) {
        viewModelScope.launch { serverRepository.delete(id) }
    }
}
