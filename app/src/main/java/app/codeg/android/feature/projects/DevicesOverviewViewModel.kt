package app.codeg.android.feature.projects

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.R
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.AppSettings
import app.codeg.android.core.datastore.AppSettingsStore
import app.codeg.android.core.datastore.LastBrowseStore
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.core.network.displayMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/**
 * One device's slice of the projects overview (批v180-A): a probe result (online
 * / offline / still probing), the home directory (the "all files" entry), and the
 * folders registered in THAT device's workspace. The old projects screen only
 * ever talked to the globally-selected device; the overview fans out to all of
 * them, so every state here is pinned to one [ServerProfile].
 */
data class DeviceOverviewState(
    val profile: ServerProfile,
    val probing: Boolean = true,
    val online: Boolean = false,
    val home: String? = null,
    val folders: List<FolderDetail> = emptyList(),
    val error: String? = null,
)

/**
 * 批v180-A: drives the devices-overview page. On load every visible device gets
 * a parallel probe (getHomeDirectory, 8s timeout) plus a folder-list fetch; a
 * device that answers is [DeviceOverviewState.online] with its folders, one that
 * times out/errors shows an offline section with retry. [repository.foldersChanged]
 * refetches folder lists so a worktree registered elsewhere keeps this page honest.
 */
@HiltViewModel
class DevicesOverviewViewModel @Inject constructor(
    private val repository: ServerRepository,
    private val appSettingsStore: AppSettingsStore,
    private val lastBrowseStore: LastBrowseStore,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _devices = MutableStateFlow<List<DeviceOverviewState>>(emptyList())
    val devices: StateFlow<List<DeviceOverviewState>> = _devices.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    // The selected device sorts first on the page — the one FAB operations talk to.
    val selectedServerId: StateFlow<String?> = repository.selectedId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // 批v181: the folder-chip grid layout (1..2), persisted across launches.
    val deviceChipColumns: StateFlow<Int> = appSettingsStore.settings
        .map { it.deviceChipColumns }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings.DEFAULT_DEVICE_CHIP_COLUMNS)

    private var initialProbeStarted = false
    private val probes = mutableMapOf<String, Job>()

    init {
        viewModelScope.launch {
            repository.visibleProfiles.collect { profiles ->
                val previous = _devices.value.associateBy { it.profile.id }
                _devices.update { current ->
                    // 审查R2: keep the probe state but NOT a frozen profile — a
                    // renamed/re-pointed device (same id) must show its new name
                    // and probe its new address.
                    profiles.map { p ->
                        previous[p.id]?.let { if (it.profile != p) it.copy(profile = p) else it }
                            ?: DeviceOverviewState(profile = p)
                    }
                }
                if (!initialProbeStarted) {
                    initialProbeStarted = true
                    refresh()
                } else {
                    // A device added while the screen is open probes on its own;
                    // existing ones keep their state instead of flashing back to probing.
                    profiles.filter { previous[it.id] == null }.forEach { probe(it.id) }
                }
            }
        }
        viewModelScope.launch {
            repository.foldersChanged.collect { refreshFolders() }
        }
    }

    /** Pull-to-refresh: re-probe every device from scratch. */
    fun refresh() {
        _refreshing.value = true
        viewModelScope.launch {
            val jobs = _devices.value.map { probe(it.profile.id) }
            jobs.forEach { it.join() }
            _refreshing.value = false
        }
    }

    /** Retry just one device's probe (its offline section is tappable). */
    fun retry(profileId: String) {
        probe(profileId)
    }

    /** 批v181: flip the overview folder-chip grid (1↔2). */
    fun setDeviceChipColumns(columns: Int) {
        viewModelScope.launch { appSettingsStore.setDeviceChipColumns(columns) }
    }

    /** 批v181: where the user last browsed on this device — the "all files"
     *  entry reopens there (null = never browsed → the device home). */
    suspend fun lastBrowseRoot(profileId: String): String? = lastBrowseStore.lastNow(profileId)

    /**
     * Unregister a workspace folder on the SPECIFIC device that owns it. Returns
     * a user-facing error string, or null on success — the folder list refetches
     * on its own afterwards.
     */
    suspend fun removeFolder(profileId: String, folderId: Int): String? {
        val state = _devices.value.firstOrNull { it.profile.id == profileId } ?: return null
        val client = repository.client(state.profile)
            ?: return appContext.getString(R.string.sessions_missing_token)
        return try {
            client.removeFolderFromWorkspace(folderId)
            refreshFolders()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.displayMessage()
        }
    }

    private fun probe(profileId: String): Job {
        probes.remove(profileId)?.cancel()
        val job = viewModelScope.launch { probeNow(profileId) }
        probes[profileId] = job
        return job
    }

    private suspend fun probeNow(profileId: String) {
        val state = _devices.value.firstOrNull { it.profile.id == profileId } ?: return
        setProbing(profileId)
        val client = repository.client(state.profile)
        if (client == null) {
            setOffline(profileId, appContext.getString(R.string.sessions_missing_token))
            return
        }
        try {
            val home = withTimeout(PROBE_TIMEOUT_MS) { client.getHomeDirectory() }
            val folders = try {
                withTimeout(PROBE_TIMEOUT_MS) { client.listFolders() }
            } catch (e: CancellationException) {
                // Folders are a bonus on top of a healthy probe — a timeout (or any
                // list failure) shows the device with an empty list instead of
                // calling the whole device offline. A real cancellation still bubbles.
                if (e is TimeoutCancellationException) emptyList() else throw e
            } catch (_: Exception) {
                emptyList()
            }
            _devices.update { list ->
                list.map {
                    if (it.profile.id == profileId) {
                        it.copy(probing = false, online = true, home = home, folders = folders, error = null)
                    } else {
                        it
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            setOffline(profileId, e.displayMessage())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setOffline(profileId, e.displayMessage())
        }
    }

    /** foldersChanged broadcast: refetch folder lists for devices that are online. */
    private fun refreshFolders() {
        _devices.value.filter { it.online }.forEach { state ->
            viewModelScope.launch {
                val client = repository.client(state.profile) ?: return@launch
                try {
                    val folders = withTimeout(PROBE_TIMEOUT_MS) { client.listFolders() }
                    _devices.update { list ->
                        list.map { if (it.profile.id == state.profile.id) it.copy(folders = folders) else it }
                    }
                } catch (e: TimeoutCancellationException) {
                    // Stale list beats a blank page — keep what we have.
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun setProbing(profileId: String) {
        _devices.update { list ->
            list.map { if (it.profile.id == profileId) it.copy(probing = true, error = null) else it }
        }
    }

    private fun setOffline(profileId: String, error: String) {
        _devices.update { list ->
            list.map {
                if (it.profile.id == profileId) {
                    it.copy(probing = false, online = false, home = null, folders = emptyList(), error = error)
                } else {
                    it
                }
            }
        }
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 8_000L
    }
}
