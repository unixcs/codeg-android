package app.codeg.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.AppSettings
import app.codeg.android.core.datastore.AppSettingsStore
import app.codeg.android.core.datastore.DraftFolderPrefsStore
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.core.model.FolderVisibility
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.displayMessage
import app.codeg.android.di.ApplicationScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/** General settings: conversation-tool toggles + multi-agent delegation. */
@HiltViewModel
class GeneralViewModel @Inject constructor(
    private val repository: ServerRepository,
    private val appSettingsStore: AppSettingsStore,
    private val draftFolderPrefs: DraftFolderPrefsStore,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    /** Bumped by every [load]; a stale load must not commit over a newer one. */
    private var loadGen = 0

    /** Server the currently shown data belongs to (null until the first load lands). */
    private var loadedForId: String? = null

    /** Live last-used watcher for the loaded server. */
    private var memoryJob: Job? = null

    private val _ui = MutableStateFlow(GeneralUiState())
    val ui: StateFlow<GeneralUiState> = _ui.asStateFlow()

    /** Local shell prefs: bottom-tab order + cold-start tab. */
    val settings: StateFlow<AppSettings> = appSettingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private var client: CodegClient? = null
    private var delegationRaw: JsonObject = JsonObject(emptyMap())

    init {
        // Every server has its own folder ids and pin. Settings stays on screen
        // across a device switch (back stack keeps it alive), so follow the
        // selection or the pin dialog would write server A's id into server B.
        // Comparing against [loadedForId] (not drop(1)) also covers a switch that
        // lands on the server already loaded — no redundant refetch, no window
        // where the screen shows A's folders while B is selected.
        viewModelScope.launch {
            repository.selectedProfile
                .map { it?.id }
                .distinctUntilChanged()
                .collect { id -> if (id != null && id != loadedForId) load() }
        }
    }

    fun load() {
        val gen = ++loadGen
        _ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val profile = repository.selectedProfile.first() ?: throw IllegalStateException("No server selected")
                val c = repository.client(profile) ?: throw IllegalStateException("Missing token")
                val result = coroutineScope {
                    val feedback = async { runCatching { c.getFeedbackEnabled() }.getOrDefault(true) }
                    val question = async { runCatching { c.getQuestionEnabled() }.getOrDefault(true) }
                    val delegation = async { runCatching { c.getDelegationSettings() }.getOrDefault(JsonObject(emptyMap())) }
                    Triple(feedback.await(), question.await(), delegation.await())
                }
                val delegation = result.third
                // v134 default-folder memory: picker-visible top-level folders of this
                // server + the current pin. Failures here must not fail the whole screen.
                val folderMemory = runCatching { draftFolderPrefs.foldersNow(profile.id) }.getOrNull()
                val draftFolders = runCatching {
                    FolderVisibility.filterTopLevel(c.listOpenFolders())
                        .sortedByDescending { it.lastOpenedAt ?: java.time.Instant.MIN }
                }.getOrDefault(emptyList())
                // A newer load (device switched mid-flight) owns the screen now. The
                // shared fields are committed only PAST this point — assigning them
                // before the guard let a stale load leave the screen talking to the
                // previous server's client (and POST its delegation JSON there).
                if (gen != loadGen) return@launch
                client = c
                delegationRaw = delegation
                loadedForId = profile.id
                _ui.update {
                    it.copy(
                        loading = false,
                        feedbackEnabled = result.first,
                        questionEnabled = result.second,
                        delegationEnabled = delegation["enabled"]?.jsonPrimitive?.booleanOrNull ?: false,
                        depthLimit = delegation["depth_limit"]?.jsonPrimitive?.intOrNull ?: 3,
                        // Committed together with the folders/pin so the pin dialog can
                        // never key a write off a server the list isn't showing.
                        serverId = profile.id,
                        draftFolders = draftFolders,
                        pinnedFolderId = folderMemory?.pinnedFolderId,
                        lastUsedFolderId = folderMemory?.lastUsedFolderId,
                        error = null,
                    )
                }
                watchLastUsed(profile.id)
            } catch (e: Exception) {
                if (gen != loadGen) return@launch
                // The folder row must not keep showing the PREVIOUS server's list while
                // this one failed: clear it (and the server key, so a pin tap is a
                // no-op) and forget what loaded, so re-selecting retries.
                loadedForId = null
                _ui.update {
                    it.copy(
                        loading = false,
                        error = e.displayMessage(),
                        serverId = null,
                        draftFolders = emptyList(),
                        pinnedFolderId = null,
                        lastUsedFolderId = null,
                    )
                }
            }
        }
    }

    /** Keep "上次使用" in step while the screen is alive: the user creates tasks on
     *  other screens, and returning here used to show the value from the last load.
     *  Only last-used is followed — re-emitting the pin would fight the optimistic
     *  update in [setPinnedFolder] for a frame on every write. */
    private fun watchLastUsed(id: String) {
        memoryJob?.cancel()
        memoryJob = viewModelScope.launch {
            draftFolderPrefs.foldersFor(id).collect { prefs ->
                _ui.update { it.copy(lastUsedFolderId = prefs?.lastUsedFolderId) }
            }
        }
    }

    fun setFeedback(enabled: Boolean) {
        _ui.update { it.copy(feedbackEnabled = enabled) }
        val c = client ?: return
        viewModelScope.launch { runCatching { c.setFeedbackEnabled(enabled) }.onFailure { reload() } }
    }

    fun setQuestion(enabled: Boolean) {
        _ui.update { it.copy(questionEnabled = enabled) }
        val c = client ?: return
        viewModelScope.launch { runCatching { c.setQuestionEnabled(enabled) }.onFailure { reload() } }
    }

    fun setDelegationEnabled(enabled: Boolean) {
        _ui.update { it.copy(delegationEnabled = enabled) }
        patchDelegation("enabled", JsonPrimitive(enabled))
    }

    fun setDepth(depth: Int) {
        val clamped = depth.coerceIn(1, 8)
        _ui.update { it.copy(depthLimit = clamped) }
        patchDelegation("depth_limit", JsonPrimitive(clamped))
    }

    private fun patchDelegation(key: String, value: JsonPrimitive) {
        val c = client ?: return
        delegationRaw = JsonObject(delegationRaw + (key to value))
        val snapshot = delegationRaw
        viewModelScope.launch { runCatching { c.setDelegationSettings(snapshot) }.onFailure { reload() } }
    }

    private fun reload() = load()

    fun setHomeTabOrder(routes: List<String>) {
        viewModelScope.launch { appSettingsStore.setHomeTabOrder(routes) }
    }

    fun setStartTab(route: String) {
        viewModelScope.launch { appSettingsStore.setStartTab(route) }
    }

    fun setActivityDeviceSwipe(enabled: Boolean) {
        viewModelScope.launch { appSettingsStore.setActivityDeviceSwipe(enabled) }
    }

    fun setFoldersDeviceSwipe(enabled: Boolean) {
        viewModelScope.launch { appSettingsStore.setFoldersDeviceSwipe(enabled) }
    }

    // ---- Floating ball + font scale (batch v126; device-local prefs) ----

    fun setFloatBallEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettingsStore.setFloatBallEnabled(enabled) }
    }

    fun setFloatBallSize(size: String) {
        viewModelScope.launch { appSettingsStore.setFloatBallSize(size) }
    }

    fun setFontScale(scale: Float) {
        viewModelScope.launch { appSettingsStore.setFontScale(scale) }
    }

    // ---- New-session default folder (batch v134; per-server device-local prefs) ----

    /** Pin (or unpin with null = follow last used) the default folder for new tasks. */
    fun setPinnedFolder(folderId: Int?) {
        // Key off the server the visible folder list belongs to (committed in the same
        // state update), never a bare field that a concurrent load could have moved on.
        // Until load() has resolved a server there is nothing to key the pin on;
        // the dialog is unreachable before then anyway.
        val id = _ui.value.serverId ?: return
        val previous = _ui.value.pinnedFolderId
        _ui.update { it.copy(pinnedFolderId = folderId) }
        // ApplicationScope: the write must land even if the user backs out of
        // Settings the same frame they tap an option.
        appScope.launch {
            runCatching { draftFolderPrefs.setPinned(id, folderId) }
                .onFailure {
                    _ui.update { state ->
                        // Same server AND same value: after a device switch the visible
                        // pin belongs to another server whose ids can collide numerically.
                        if (state.serverId == id && state.pinnedFolderId == folderId) {
                            state.copy(pinnedFolderId = previous)
                        } else state
                    }
                }
        }
    }
}

data class GeneralUiState(
    val loading: Boolean = false,
    val feedbackEnabled: Boolean = true,
    val questionEnabled: Boolean = true,
    val delegationEnabled: Boolean = false,
    val depthLimit: Int = 3,
    /** Server the folder list belongs to — the pin write is keyed off this. */
    val serverId: String? = null,
    val draftFolders: List<FolderDetail> = emptyList(),
    val pinnedFolderId: Int? = null,
    val lastUsedFolderId: Int? = null,
    val error: String? = null,
)
