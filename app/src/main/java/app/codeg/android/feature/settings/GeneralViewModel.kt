package app.codeg.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.data.materializeChipMove
import app.codeg.android.core.data.moveProjectInOrder
import app.codeg.android.core.datastore.AppSettings
import app.codeg.android.core.datastore.AppSettingsStore
import app.codeg.android.core.datastore.DraftFolderPrefsStore
import app.codeg.android.core.data.SessionProjectRepository
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
    /** 批v160 W9: the settings section's project list (names + order editing). */
    val sessionProjectRepo: SessionProjectRepository,
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

    fun setHomeTabOrder(routes: List<String>) = launchPersist {
        appSettingsStore.setHomeTabOrder(routes)
    }

    fun setStartTab(route: String) = launchPersist {
        appSettingsStore.setStartTab(route)
    }

    // 批v153: v151 convention — best-effort persist; a full disk must not crash the
    // process from a settings toggle. (R5: every DataStore setter in this file is wrapped.)
    private fun launchPersist(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /** 批v152: show/hide a bottom tab (Settings itself is force-kept by the store). */
    fun setTabHidden(route: String, hidden: Boolean) = launchPersist {
        appSettingsStore.setTabHidden(route, hidden)
    }

    /** 批v152: cold-start default for the workbench 总览区. */
    fun setWorkbenchOverviewDefaultExpanded(expanded: Boolean) = launchPersist {
        appSettingsStore.setWorkbenchOverviewExpandedDefault(expanded)
    }

    /** 批v152: cold-start default for the workbench 需要处理区. */
    fun setWorkbenchAttentionDefaultExpanded(expanded: Boolean) = launchPersist {
        appSettingsStore.setWorkbenchAttentionExpandedDefault(expanded)
    }

    fun setActivityDeviceSwipe(enabled: Boolean) = launchPersist {
        appSettingsStore.setActivityDeviceSwipe(enabled)
    }

    /** 批v160 W5: task-board horizontal swipe semantics (segments|devices). */
    fun setTaskSwipeMode(mode: String) = launchPersist {
        appSettingsStore.setTaskSwipeMode(mode)
    }

    // ---- 批v168: session row's left/right swipe bindings ----

    fun setSessionSwipeLeft(action: String) = launchPersist {
        appSettingsStore.setSessionSwipeLeftAction(action)
    }

    fun setSessionSwipeRight(action: String) = launchPersist {
        appSettingsStore.setSessionSwipeRightAction(action)
    }

    // 批v171 N7: the swipe master switches + the inbox bindings; N3: classic look.
    fun setSessionSwipeEnabled(enabled: Boolean) = launchPersist {
        appSettingsStore.setSessionSwipeEnabled(enabled)
    }

    fun setInboxSwipeLeft(action: String) = launchPersist {
        appSettingsStore.setInboxSwipeLeftAction(action)
    }

    fun setInboxSwipeRight(action: String) = launchPersist {
        appSettingsStore.setInboxSwipeRightAction(action)
    }

    fun setInboxSwipeEnabled(enabled: Boolean) = launchPersist {
        appSettingsStore.setInboxSwipeEnabled(enabled)
    }

    fun setClassicLook(enabled: Boolean) = launchPersist {
        appSettingsStore.setClassicLook(enabled)
    }

    // ---- 批v161: task-board shape + per-layout column count ----

    /** "kanban" (web-parity board) or "list" (four status tabs + one list). */
    fun setTaskBoardLayout(layout: String) = launchPersist {
        appSettingsStore.setTaskBoardLayout(layout)
    }

    /** Columns per kanban page on a phone (1 or 2; tablets stay at four). */
    fun setTaskBoardKanbanColumns(columns: Int) = launchPersist {
        appSettingsStore.setTaskBoardKanbanColumns(columns)
    }

    /** List mode's column count (1 or 2). */
    fun setTaskBoardListColumns(columns: Int) = launchPersist {
        appSettingsStore.setTaskBoardListColumns(columns)
    }

    // ---- 批v160 W9: session chip row — project order + count sort ----

    /** Sort the chip row by per-project session count (descending). When on, the
     *  manual order is ignored (the counts change under the user's feet). */
    fun setProjectChipSortByCount(enabled: Boolean) = launchPersist {
        appSettingsStore.setProjectChipSortByCount(enabled)
    }

    /** Persist a reordered chip sequence (the row's left→right display order). */
    fun setProjectChipOrder(order: List<String>) = launchPersist {
        appSettingsStore.setProjectChipOrder(order)
    }

    /** R2-A P3: the authoritative chip order while rearranging — rapid ↑/↓ taps
     *  must not re-read the last-PERSISTED snapshot, whose DataStore echo can lag
     *  the previous tap (a double-tap would move one slot instead of two). */
    private var chipOrderOverride: List<String>? = null

    /** The project-id set [chipOrderOverride] was derived from — a project
     *  created/removed while this page is open invalidates it (R4-A P3-2). */
    private var chipOrderOverrideIds: Set<String> = emptySet()

    /** Move one project [step] slots (+1 right / -1 left) in the manual order.
     *  Operates on the row's EFFECTIVE display order (saved ids first, then the
     *  never-moved rest) — a move on a partial saved list must still move the
     *  row the user sees. Persists the materialized order. */
    fun moveProjectChip(id: String, step: Int) {
        val projectIds = sessionProjectRepo.projects.value.mapTo(mutableSetOf()) { it.id }
        if (chipOrderOverride != null && chipOrderOverrideIds != projectIds) {
            // The override predates the current project set — it can't name the
            // new id, so every move on it would silently no-op. Re-derive.
            chipOrderOverride = null
        }
        val moved = chipOrderOverride
            ?.let { moveProjectInOrder(it, id, step) }
            ?: materializeChipMove(
                sessionProjectRepo.projects.value,
                settings.value.projectChipOrder,
                id,
                step,
            )
        chipOrderOverride = moved
        chipOrderOverrideIds = projectIds
        setProjectChipOrder(moved)
    }

    // ---- Floating ball + font scale (batch v126; device-local prefs) ----

    fun setFloatBallEnabled(enabled: Boolean) = launchPersist {
        appSettingsStore.setFloatBallEnabled(enabled)
    }

    fun setFloatBallSize(size: String) = launchPersist {
        appSettingsStore.setFloatBallSize(size)
    }

    fun setInboxNewIdeaStyle(style: String) = launchPersist {
        appSettingsStore.setInboxNewIdeaStyle(style)
    }

    /** 批v160 W8: 「会话交互提醒」通知 toggle（权限申请是 UI 层的事）. */
    fun setNotifyInteractiveEnabled(enabled: Boolean) = launchPersist {
        appSettingsStore.setNotifyInteractiveEnabled(enabled)
    }

    /** 系统级通知体系: 任务完成提醒 toggle. */
    fun setNotifyTaskResultsEnabled(enabled: Boolean) = launchPersist {
        appSettingsStore.setNotifyTaskResultsEnabled(enabled)
    }

    /** 系统级通知体系: 设备离线提醒 toggle. */
    fun setNotifyDeviceEventsEnabled(enabled: Boolean) = launchPersist {
        appSettingsStore.setNotifyDeviceEventsEnabled(enabled)
    }

    /** 批v160 W8: keep-alive 前台服务 toggle——只写偏好；服务的启停由
     *  Application 的设置收集器执行（唯一事实源，冷启动与改设置同路）. */
    fun setKeepAliveEnabled(enabled: Boolean) = launchPersist {
        appSettingsStore.setKeepAliveEnabled(enabled)
    }

    fun setFontScale(scale: Float) = launchPersist {
        appSettingsStore.setFontScale(scale)
    }

    /** 批v172 D3: per-interface font scales (0 = follow global). */
    fun setSessionFontScale(scale: Float) = launchPersist {
        appSettingsStore.setSessionFontScale(scale)
    }

    fun setInboxFontScale(scale: Float) = launchPersist {
        appSettingsStore.setInboxFontScale(scale)
    }

    fun setTaskhubFontScale(scale: Float) = launchPersist {
        appSettingsStore.setTaskhubFontScale(scale)
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
