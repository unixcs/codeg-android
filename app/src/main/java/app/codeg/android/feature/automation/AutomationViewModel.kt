package app.codeg.android.feature.automation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.CodegApplication
import app.codeg.android.core.data.AGG_DEVICE_TIMEOUT_MS
import app.codeg.android.core.data.DispatchTargetLoader
import app.codeg.android.core.data.DispatchTargets
import app.codeg.android.core.data.DeviceLoadStatus
import app.codeg.android.core.data.GlobalEventWatcher
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.network.ApiError
import app.codeg.android.core.network.isUnsupportedMethod
import app.codeg.android.core.network.displayMessage
import app.codeg.android.core.model.AutomationInfo
import app.codeg.android.core.model.AutomationRunInfo
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.model.buildAutomationDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.concurrent.TimeoutException
import javax.inject.Inject

/**
 * Automations（舰队 M2）：每设备扇出聚合 + 60s 轮询（无事件推送，PRD Q5）。
 * Fan-out/生成代仲裁/失败保旧片沿 TaskHubViewModel 的既有纪律；能力闸：老
 * 服务端没有 automation 路由（404/unknown method）→ 该设备 `supported=false`
 * 不出新建/不出假按钮，其余失败走 FAILED + 重试（不降级成"无自动化"）。
 */
@HiltViewModel
class AutomationViewModel @Inject constructor(
    private val repository: ServerRepository,
    private val targetLoader: DispatchTargetLoader,
    /** 批v173 C1: process-wide push layer — automation://changed ticks the poll. */
    private val globalEvents: GlobalEventWatcher,
    @ApplicationContext appContext: Context,
) : ViewModel() {

    data class DeviceAutomations(
        val serverId: String,
        val deviceName: String,
        val status: DeviceLoadStatus = DeviceLoadStatus.LOADING,
        val error: String? = null,
        /** Last good list; a FAILED round keeps the stale slice on screen (v137). */
        val automations: List<AutomationInfo> = emptyList(),
        /** automation_list 404/unknown-method → server predates the feature. */
        val supported: Boolean = true,
    )

    data class UiState(
        val devices: List<DeviceAutomations> = emptyList(),
        val hasLoaded: Boolean = false,
        val lastRefreshed: Instant? = null,
        /** serverId:id of an in-flight row action (switch/run/delete/save). */
        val busyKey: String? = null,
        val actionError: String? = null,
        val actionNote: String? = null,
    ) {
        val loading: Boolean get() = devices.any { it.status == DeviceLoadStatus.LOADING }
        val anySupported: Boolean get() = devices.any { it.supported }
        val supportedDeviceCount: Int get() = devices.count { it.supported }
    }

    data class RunsState(
        val automation: AutomationInfo,
        /** Owning device — the 查看会话 jump and cancel need it. */
        val serverId: String,
        val runs: List<AutomationRunInfo> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
    )

    data class EditorState(
        /** null = create, non-null = edit that automation. */
        val editing: AutomationInfo? = null,
        val serverId: String? = null,
        val name: String = "",
        val prompt: String = "",
        val scheduled: Boolean = true,
        val preset: CronPreset = CronPreset.DAILY,
        val minute: Int = 0,
        val hour: Int = 9,
        val customCron: String = "",
        /** IANA zone evaluated SERVER-side (never converted client-side). New
         *  drafts default to the phone's zone id — an IANA id, not a conversion. */
        val timezone: String = java.time.ZoneId.systemDefault().id,
        val isolation: String = "worktree_per_run",
        val enabled: Boolean = true,
        val agentType: AgentType? = null,
        val folderId: Int? = null,
        val targets: DispatchTargets = DispatchTargets(),
        /** automation_compute_next_run preview (RFC3339) for the current cron. */
        val nextRun: String? = null,
        val nextRunStale: Boolean = false,
        /** The (cron, timezone) the current [nextRun] was computed for. */
        val nextRunSig: Pair<String, String>? = null,
        val saving: Boolean = false,
        val error: String? = null,
    ) {
        val isManual: Boolean get() = !scheduled
        val titleIsEdit: Boolean get() = editing != null
    }

    private val foreground = (appContext as CodegApplication).appForeground

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _runs = MutableStateFlow<RunsState?>(null)
    val runs: StateFlow<RunsState?> = _runs.asStateFlow()

    private val _editor = MutableStateFlow<EditorState?>(null)
    val editor: StateFlow<EditorState?> = _editor.asStateFlow()

    private val _active = MutableStateFlow(false)
    fun setActive(active: Boolean) { _active.value = active }

    private val _refreshTick = MutableStateFlow(0)
    private var generation = 0
    private var lastSeenTick = -1

    /** 批v173 C1 审查R1: the board re-entry edge — must survive across
     *  collectLatest restarts to be detectable (the TaskHubViewModel pattern). */
    private var lastActive = false

    /** Live visible profiles for the editor's device chips — the loader
     *  deliberately leaves targets.profiles empty (a dead device must never
     *  blank the chips); same resubscribe loop as the dispatch hosts. */
    private val visibleProfiles = MutableStateFlow<List<ServerProfile>>(emptyList())

    init {
        viewModelScope.launch { pollLoop() }
        // 批v173 C1: a pushed automation://changed ticks the existing 60s-poll
        // arbitration (same shape as the task hub) — payload carries only ids,
        // so the refetch IS the reaction. Poll stays as the floor.
        globalEvents.events
            .filter { it.channel == GlobalEventWatcher.CHANNEL_AUTOMATION }
            .onEach { refresh() }
            .launchIn(viewModelScope)
        viewModelScope.launch {
            while (currentCoroutineContext().isActive) {
                try {
                    repository.visibleProfiles.collect { profiles ->
                        visibleProfiles.value = profiles
                        _editor.update { it?.copy(targets = it.targets.copy(profiles = profiles)) }
                    }
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    delay(2_000)
                }
            }
        }
    }

    fun refresh() { _refreshTick.value += 1 }

    fun clearActionError() { _ui.update { it.copy(actionError = null) } }

    /** Editor target loading seq (device switch may race a slow load). */
    private var targetSeq = 0

    private fun pollLoop() {
        viewModelScope.launch {
            combine(
                _refreshTick,
                _active,
                foreground,
                repository.selectedId,
                repository.tasksAll,
            ) { tick, active, fg, _, _ -> PollInputs(tick, active, fg) }
                .retryWhen { _, attempt ->
                        // 批v165 R1 (P0): a DataStore read failure upstream must resubscribe — a
                        // bare collector dying here is the v130 crash class (process kill from a
                        // screen entry) AND "deaf for life" if it never re-arms.
                        kotlinx.coroutines.delay(if (attempt < 3) 1_000 else 5_000)
                        true
                }
                .collectLatest { input ->
                    // 批v173 C1 审查R1: tick 分支与 task hub 同形——push 落在
                    // 隐藏/后台时只记账不拉取（每 run 约 2-3 发帧，无闸会每帧
                    // 白拉一轮全设备）。重进屏幕由 entered 边立即补一轮，不用
                    // 等下一个 60s beat。
                    val tickNew = input.tick > 0 && input.tick != lastSeenTick
                    if (tickNew) lastSeenTick = input.tick
                    val entered = input.active && !lastActive
                    lastActive = input.active
                    // 初始拉取并入 active 分支：段不可见时不白拉全设备。
                    if (input.active && input.fg) {
                        if (input.tick == 0 || tickNew || entered) fetchAll()
                        while (currentCoroutineContext().isActive) {
                            delay(POLL_INTERVAL_MS)
                            fetchAll()
                        }
                    }
                }
        }
    }

    private data class PollInputs(val tick: Int, val active: Boolean, val fg: Boolean)

    private suspend fun fetchAll() {
        try {
            fetchAllInner()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            android.util.Log.w("AutomationViewModel", "fetch round failed")
        }
    }

    private suspend fun fetchAllInner() {
        val profiles = repository.profiles.first()
        val devices = profiles.filter {
            it.id in scopeIds(profiles, repository.tasksAll.value, repository.selectedId.first())
        }
        val ids = devices.map { it.id }.toSet()
        if (devices.isEmpty()) {
            _ui.update { it.copy(devices = emptyList(), lastRefreshed = Instant.now(), hasLoaded = true) }
            return
        }
        generation += 1
        val gen = generation
        _ui.update { state ->
            state.copy(
                devices = state.devices.filter { it.serverId in ids }.let { kept ->
                    devices.map { d ->
                        kept.firstOrNull { it.serverId == d.id }
                            ?: DeviceAutomations(d.id, d.name)
                    }
                },
            )
        }
        coroutineScope {
            devices.forEach { profile ->
                launch { fetchDevice(profile, gen) }
            }
        }
        if (gen == generation) _ui.update { it.copy(lastRefreshed = Instant.now(), hasLoaded = true) }
    }

    private suspend fun fetchDevice(profile: ServerProfile, gen: Int) {
        val result = runCatching {
            val client = repository.client(profile)
                ?: throw IllegalStateException("Token 缺失，无法加载该设备")
            withTimeoutOrNull(AGG_DEVICE_TIMEOUT_MS) {
                client.automationList()
            } ?: throw TimeoutException("device timeout")
        }
        if (gen != generation) return
        result.fold(
            onSuccess = { list ->
                _ui.update { state ->
                    state.copy(
                        devices = state.devices.map {
                            if (it.serverId == profile.id) {
                                it.copy(
                                    status = DeviceLoadStatus.OK, error = null,
                                    automations = list, supported = true,
                                )
                            } else it
                        },
                    )
                }
            },
            onFailure = { e ->
                if (e is CancellationException) throw e
                val unsupported = isUnsupportedServer(e)
                _ui.update { state ->
                    state.copy(
                        devices = state.devices.map {
                            if (it.serverId == profile.id) {
                                it.copy(
                                    // Unsupported servers "succeed" as an empty,
                                    // non-createable slice; real failures keep the
                                    // stale slice + a retryable FAILED banner row.
                                    status = if (unsupported) DeviceLoadStatus.OK else DeviceLoadStatus.FAILED,
                                    error = (e as? ApiError)?.displayMessage() ?: e.message ?: "加载失败",
                                    supported = !unsupported,
                                )
                            } else it
                        },
                    )
                }
            },
        )
    }

    /** Old-server gate（共享实现见 core/network.ApiUnsupported）。 */
    private fun isUnsupportedServer(e: Throwable): Boolean = isUnsupportedMethod(e)

    private fun scopeIds(profiles: List<ServerProfile>, allMode: Boolean, selectedId: String?): Set<String> =
        if (allMode) {
            profiles.filter { it.visible && it.includeInAll }.map { it.id }.toSet()
        } else {
            profiles.filter { it.id == selectedId }.map { it.id }.toSet()
        }

    private suspend fun clientFor(serverId: String) =
        repository.profiles.first().firstOrNull { it.id == serverId }?.let { repository.client(it) }

    // ----- row actions -----

    fun setEnabled(automation: AutomationInfo, serverId: String, enabled: Boolean) {
        val key = "enabled:${automation.id}"
        viewModelScope.launch {
            val client = clientFor(serverId) ?: run {
                _ui.update { it.copy(actionError = "无法连接该设备") }
                return@launch
            }
            _ui.update { it.copy(busyKey = key) }
            // Optimistic row flip; revert on failure (v-row discipline).
            updateAutomationLocally(serverId, automation.id) { it.copy(enabled = enabled) }
            try {
                client.automationSetEnabled(automation.id, enabled)
                _ui.update { it.copy(busyKey = null) }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateAutomationLocally(serverId, automation.id) { it.copy(enabled = !enabled) }
                _ui.update { it.copy(busyKey = null, actionError = (e as? ApiError)?.displayMessage() ?: e.message ?: "操作失败") }
            }
        }
    }

    fun delete(automation: AutomationInfo, serverId: String) {
        val key = "delete:${automation.id}"
        viewModelScope.launch {
            val client = clientFor(serverId) ?: run {
                _ui.update { it.copy(actionError = "无法连接该设备") }
                return@launch
            }
            _ui.update { it.copy(busyKey = key) }
            try {
                client.automationDelete(automation.id)
                _ui.update { state ->
                    state.copy(
                        busyKey = null,
                        actionNote = "「${automation.name}」已删除",
                        devices = state.devices.map { d ->
                            if (d.serverId == serverId) d.copy(automations = d.automations.filter { it.id != automation.id }) else d
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update {
                    it.copy(busyKey = null, actionError = (e as? ApiError)?.displayMessage() ?: e.message ?: "删除失败")
                }
            }
        }
    }

    /**
     * 立即运行：fire-and-confirm. `run_now` returns the run id but the run
     * settles async server-side; we optimistically note the trigger, refresh
     * the device slice, then re-check once after a beat so the runs sheet /
     * row meta can pick up the new run.
     */
    fun runNow(automation: AutomationInfo, serverId: String) {
        val key = "run:${automation.id}"
        viewModelScope.launch {
            val client = clientFor(serverId) ?: run {
                _ui.update { it.copy(actionError = "无法连接该设备") }
                return@launch
            }
            _ui.update { it.copy(busyKey = key) }
            try {
                val runId = client.automationRunNow(automation.id)
                _ui.update { it.copy(busyKey = null, actionNote = "已触发「${automation.name}」（运行 #${runId}），结果稍后见运行历史") }
                refresh()
                delay(RUN_CONFIRM_DELAY_MS)
                if (_ui.value.busyKey == null) refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update {
                    it.copy(busyKey = null, actionError = (e as? ApiError)?.displayMessage() ?: e.message ?: "触发失败")
                }
            }
        }
    }

    private fun updateAutomationLocally(serverId: String, id: Int, transform: (AutomationInfo) -> AutomationInfo) {
        _ui.update { state ->
            state.copy(
                devices = state.devices.map { d ->
                    if (d.serverId == serverId) {
                        d.copy(automations = d.automations.map { if (it.id == id) transform(it) else it })
                    } else d
                },
            )
        }
    }

    // ----- runs history -----

    /** Opens the runs sheet: list (limit 100) + mark_seen so the failure badge
     *  clears (server-wide unseen counter, Q3). */
    fun openRuns(automation: AutomationInfo, serverId: String) {
        val seq = ++runsSeq
        _runs.value = RunsState(automation, serverId)
        viewModelScope.launch {
            val client = clientFor(serverId)
            if (client == null) {
                if (seq == runsSeq) _runs.update { it?.copy(loading = false, error = "无法连接该设备") }
                return@launch
            }
            try {
                val list = client.automationRuns(automation.id, RUNS_LIMIT)
                if (seq != runsSeq) return@launch
                _runs.update { it?.copy(runs = list, loading = false, error = null) }
                if (automation.unseenFailures > 0) {
                    runCatching { client.automationMarkSeen() }
                    // mark_seen 是服务端全局计数：本地把所有行的徽标一并清零，
                    // 不然其余行要等下一轮 60s 轮询才消失（抖动）。
                    _ui.update { state ->
                        state.copy(
                            devices = state.devices.map { d ->
                                d.copy(automations = d.automations.map { it.copy(unseenFailures = 0) })
                            },
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (seq != runsSeq) return@launch
                _runs.update {
                    it?.copy(loading = false, error = (e as? ApiError)?.displayMessage() ?: e.message ?: "加载失败")
                }
            }
        }
    }

    fun closeRuns() { _runs.value = null }

    fun cancelRun(runId: Int, serverId: String) {
        viewModelScope.launch {
            val client = clientFor(serverId) ?: return@launch
            try {
                client.automationCancelRun(runId)
                _runs.update { state ->
                    state?.copy(runs = state.runs.map { if (it.id == runId) it.copy(status = "cancelled") else it })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best-effort: the next runs reload shows the server truth.
            }
        }
    }

    // ----- editor -----

    fun openEditor(serverId: String, deviceName: String) {
        _editor.value = EditorState(serverId = serverId)
        loadEditorTargets(serverId)
    }

    fun openEditor(existing: AutomationInfo, serverId: String) {
        val preset = matchCronPreset(existing.cron)
        val time = cronTimeOfDay(existing.cron)
        _editor.value = EditorState(
            editing = existing,
            serverId = serverId,
            name = existing.name,
            prompt = promptOf(existing),
            scheduled = existing.isScheduled,
            preset = preset,
            minute = time?.first ?: 0,
            hour = time?.second ?: 9,
            customCron = existing.cron.orEmpty(),
            timezone = existing.timezone.ifBlank { java.time.ZoneId.systemDefault().id },
            isolation = existing.isolation,
            enabled = existing.enabled,
            agentType = existing.agentType.takeIf { it != AgentType.UNKNOWN },
            folderId = existing.rootFolderId,
        )
        loadEditorTargets(serverId)
        scheduleNextRunPreview()
    }

    fun closeEditor() { _editor.value = null }

    fun updateEditor(transform: (EditorState) -> EditorState) {
        _editor.update { it?.let(transform) }
    }

    /** Device chip change → wipe downstream selections, reload its targets
     *  (seq-guard, same as the dispatch hosts). */
    fun editorSelectDevice(serverId: String) {
        if (_editor.value?.serverId == serverId) return
        _editor.update { it?.copy(serverId = serverId, folderId = null, agentType = null) }
        loadEditorTargets(serverId)
    }

    private fun loadEditorTargets(serverId: String) {
        val seq = ++targetSeq
        viewModelScope.launch {
            _editor.update { it?.copy(targets = it.targets.copy(folders = emptyList(), agents = emptyList(), foldersLoading = true)) }
            val targets = try {
                targetLoader.load(serverId, foldersLoading = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (seq != targetSeq) return@launch
            _editor.update { state ->
                state?.copy(
                    targets = targets ?: state.targets.copy(foldersLoading = false, error = "无法连接目标设备"),
                    // Drop selections the fresh lists no longer contain.
                    folderId = state.folderId?.takeIf { id -> targets?.folders?.any { it.id == id } == true },
                    agentType = state.agentType?.takeIf { agent -> targets?.agents?.contains(agent) == true },
                )
            }
        }
    }

    /** compute_next_run preview — debounced; only re-fires when the cron inputs
     *  changed since the last preview (server-side evaluation, never client). */
    fun scheduleNextRunPreview() {
        val state = _editor.value ?: return
        if (state.isManual) {
            _editor.update { it?.copy(nextRun = null, nextRunStale = false) }
            return
        }
        val cron = state.preset.buildFor(state.minute, state.hour, state.customCron)
            ?: run {
                // 自定义表达式清空 = 当前 cron 无效：旧预览不能残留。
                _editor.update { it?.copy(nextRun = null, nextRunStale = false, nextRunSig = null) }
                return
            }
        val sig = cron to state.timezone
        if (!state.nextRunStale && state.nextRunSig == sig) return
        _editor.update { it?.copy(nextRunStale = true, nextRunSig = sig) }
        val seq = ++previewSeq
        viewModelScope.launch {
            val client = state.serverId?.let { clientFor(it) }
            val next = if (client != null && isPlausibleCron(cron)) {
                try {
                    withTimeoutOrNull(PREVIEW_TIMEOUT_MS) { client.automationComputeNextRun(cron, state.timezone) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            } else null
            if (seq != previewSeq) return@launch
            _editor.update { it?.copy(nextRun = next, nextRunStale = false) }
        }
    }

    private var previewSeq = 0

    /** Runs-sheet seq: closing/reopening supersedes the previous load. */
    private var runsSeq = 0

    /** Reconstruct the editable prompt text from the captured config blob
     *  (text block preferred; display_text as the fallback). */
    private fun promptOf(info: AutomationInfo): String {
        val blocks = info.config["prompt_blocks"] as? kotlinx.serialization.json.JsonArray
        val firstText = blocks?.firstNotNullOfOrNull { block ->
            (block as? kotlinx.serialization.json.JsonObject)?.let { obj ->
                val type = (obj["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                val text = (obj["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                if (type == "text") text else null
            }
        }
        return firstText
            ?: (info.config["display_text"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
    }

    fun saveEditor() {
        val state = _editor.value ?: return
        val serverId = state.serverId ?: return
        if (state.saving) return
        // 进编辑器即置位：双击保存不得双建（校验失败在下方各出口回落）。
        _editor.update { it?.copy(saving = true, error = null) }
        viewModelScope.launch {
            val cron = if (state.isManual) null else {
                val built = state.preset.buildFor(state.minute, state.hour, state.customCron)
                if (built == null || !isPlausibleCron(built)) {
                    _editor.update { it?.copy(saving = false, error = "cron 表达式无效") }
                    return@launch
                }
                built
            }
            val draft = buildAutomationDraft(
                name = state.name,
                enabled = state.enabled,
                triggerKind = if (state.isManual) "manual" else "schedule",
                cron = cron,
                timezone = state.timezone,
                agentType = state.agentType
                    ?: state.targets.folders.firstOrNull { it.id == state.folderId }?.defaultAgentType
                    ?: AgentType.ZCODE,
                rootFolderId = state.folderId,
                isolation = state.isolation,
                promptText = state.prompt,
            )
            if (draft.name.isBlank()) {
                _editor.update { it?.copy(saving = false, error = "名称不能为空") }
                return@launch
            }
            if (state.prompt.isBlank()) {
                _editor.update { it?.copy(saving = false, error = "Prompt 不能为空") }
                return@launch
            }
            val client = clientFor(serverId) ?: run {
                _editor.update { it?.copy(saving = false, error = "无法连接该设备") }
                return@launch
            }
            try {
                val editing = state.editing
                if (editing == null) client.automationCreate(draft) else client.automationUpdate(editing.id, draft)
                _editor.value = null
                _ui.update { it.copy(actionNote = if (editing == null) "已创建「${draft.name}」" else "已保存「${draft.name}」") }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _editor.update {
                    it?.copy(saving = false, error = (e as? ApiError)?.displayMessage() ?: e.message ?: "保存失败")
                }
            }
        }
    }

    companion object {
        /** PRD M2: no event push — 60s poll while the automation pane is visible. */
        private const val POLL_INTERVAL_MS = 60_000L
        private const val RUN_CONFIRM_DELAY_MS = 3_000L
        private const val RUNS_LIMIT = 100
        private const val PREVIEW_TIMEOUT_MS = 8_000L
    }
}

