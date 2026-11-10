package app.codeg.android.feature.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.data.isLiveFresh
import app.codeg.android.core.datastore.AppSettings
import app.codeg.android.core.datastore.AppSettingsStore
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.displayMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Drives the Projects (Folders) tab: folder list + per-folder running counts. */
@HiltViewModel
class ProjectsViewModel @Inject constructor(
    private val repository: ServerRepository,
    // 批v174 P2: owns the projects layout preference (single list ⇄ card grid).
    private val appSettingsStore: AppSettingsStore,
) : ViewModel() {

    private val _ui = MutableStateFlow(ProjectsUiState())
    val ui: StateFlow<ProjectsUiState> = _ui.asStateFlow()

    /** 批v174 P2: app settings mirror for the layout toggle (same shape as the
     *  task hub's). Eagerly so the very first frame already knows the layout. */
    val settings: StateFlow<AppSettings> = appSettingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings.INITIAL)

    /** 批v174 P2: flip the projects layout (1 = single list, 2 = card grid). */
    fun setProjectListColumns(columns: Int) {
        viewModelScope.launch { appSettingsStore.setProjectListColumns(columns) }
    }

    private var client: CodegClient? = null
    private var loadedEndpoint: String? = null

    init {
        viewModelScope.launch {
            repository.selectedProfile
                .retryWhen { _, attempt ->
                    // 批v165 R1 (P0): a DataStore read failure upstream must resubscribe — a
                    // bare collector dying here is the v130 crash class (process kill from a
                    // screen entry) AND "deaf for life" if it never re-arms.
                    kotlinx.coroutines.delay(if (attempt < 3) 1_000 else 5_000)
                    true
                }
                .collectLatest { profile -> onProfile(profile) }
        }
        // Refetch when a folder is registered/opened elsewhere (e.g. a worktree
        // created during a session's branch switch).
        viewModelScope.launch {
            repository.foldersChanged.collect { if (client != null && !_ui.value.isBusy) fetch(initial = false) }
        }
    }

    private suspend fun onProfile(profile: ServerProfile?) {
        if (profile == null) {
            client = null; loadedEndpoint = null
            _ui.value = ProjectsUiState()
            return
        }
        val endpoint = "${profile.id}|${profile.baseUrl}"
        val changed = endpoint != loadedEndpoint
        loadedEndpoint = endpoint
        client = repository.client(profile)
        if (changed) _ui.update { ProjectsUiState() }
        fetch(initial = true)
    }

    fun refresh() {
        if (_ui.value.isBusy) return
        viewModelScope.launch { fetch(initial = false) }
    }

    private suspend fun fetch(initial: Boolean) {
        val c = client ?: return
        _ui.update { if (initial) it.copy(loading = true, error = null) else it.copy(refreshing = true) }
        try {
            val result = coroutineScope {
                val f = async { c.listFolders() }
                val conv = async { runCatching { c.listConversations() }.getOrDefault(emptyList()) }
                f.await() to conv.await()
            }
            _ui.update {
                it.copy(folders = result.first, conversations = result.second, hasLoaded = true, loading = false, refreshing = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _ui.update { it.copy(loading = false, refreshing = false, error = e.displayMessage()) }
        }
    }

    fun openFolder(path: String, onResult: (String?) -> Unit) {
        val c = client ?: return onResult("No server selected")
        viewModelScope.launch {
            val err = runCatching { c.openFolder(path) }.exceptionOrNull()?.displayMessage()
            if (err == null) fetch(initial = false)
            onResult(err)
        }
    }

    /** Remove a folder from the workspace view (files stay on disk). Returns an error message or null. */
    suspend fun removeFolder(folderId: Int): String? {
        val c = client ?: return "No server selected"
        return try {
            c.removeFolderFromWorkspace(folderId)
            fetch(initial = false)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.displayMessage()
        }
    }

    /** The server's home directory (for the filesystem browser's initial location). */
    suspend fun homeDirectory(): String =
        client?.let { runCatching { it.getHomeDirectory() }.getOrNull() } ?: "/"

    /** Subdirectories of [path] (browser; dirs only). */
    suspend fun listDirectories(path: String): List<app.codeg.android.core.model.DirectoryEntry> =
        client?.let { runCatching { it.listDirectoryEntries(path) }.getOrDefault(emptyList()) } ?: emptyList()

    /** Clone a repo, then register the new folder. Returns an error message or null on success. */
    suspend fun clone(
        url: String,
        targetDir: String,
        credentials: app.codeg.android.core.model.GitCredentials?,
    ): String? {
        val c = client ?: return "No server selected"
        return try {
            c.cloneRepository(url, targetDir, credentials)
            runCatching { c.openFolder(targetDir) }
            fetch(initial = false)
            null
        } catch (e: Exception) {
            e.displayMessage()
        }
    }
}

data class ProjectsUiState(
    val folders: List<FolderDetail> = emptyList(),
    val conversations: List<ConversationSummary> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    val error: String? = null,
) {
    val isBusy: Boolean get() = loading || refreshing
    // R2D#2: freshness-gated like every other "running" surface — the power-off
    // corpse must not inflate the project card's 运行中 count forever.
    fun runningCount(folderId: Int): Int =
        conversations.count { it.folderId == folderId && it.isLiveFresh() }
}
