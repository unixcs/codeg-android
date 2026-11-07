package app.codeg.android.feature.activity

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.CodegApplication
import app.codeg.android.core.data.AGG_DEVICE_TIMEOUT_MS
import app.codeg.android.core.data.ATTENTION_COUNT_TIMEOUT_MS
import app.codeg.android.core.data.DeviceLoad
import app.codeg.android.core.data.DeviceLoadStatus
import app.codeg.android.core.data.InteractiveRequestWatcher
import app.codeg.android.core.data.PendingInteraction
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.data.TaskBoardEntry
import app.codeg.android.core.data.TaskSegment
import app.codeg.android.core.data.buildBoard
import app.codeg.android.core.data.participating
import app.codeg.android.core.datastore.AppSettingsStore
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.datastore.WorkbenchDisplay
import app.codeg.android.core.network.ApiError
import app.codeg.android.core.network.displayMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * One integer per workbench stat card (批v150): running tasks, tasks waiting for
 * the user, and how many in-scope devices answered this round. [attentionPreview]
 * is the same ATTENTION slice the task board would show (same [buildBoard]
 * projection, same sort), capped for the preview rows.
 *
 * fleet M1: [attentionTasks] prefers the SERVER's own per-device count
 * (`work_task_attention_count`, the web sidebar badge's definition) summed
 * across the scope; it falls back to the board-derived ATTENTION size when any
 * in-scope device failed to report one (old server / failed round) — the badge
 * never mixes the two sources.
 */
data class WorkbenchStats(
    val runningTasks: Int = 0,
    val attentionTasks: Int = 0,
    val attentionPreview: List<TaskBoardEntry> = emptyList(),
    /** 批v160 W7: pending permission/question/plan rows the watcher saw — the
     *  需要处理 card's 「等待你确认」 section (already re-scoped + sorted). */
    val interactiveSessions: List<PendingInteraction> = emptyList(),
    val onlineDevices: Int = 0,
    val totalDevices: Int = 0,
    /** 批v163（舰队 M2，Q3）：summed unseen_failures across the scope. Only
     *  counted when [automationFailuresKnown] — a failed probe on ANY in-scope
     *  device hides the whole contribution (never mixes known with unknown). */
    val automationFailures: Int = 0,
    val automationFailuresKnown: Boolean = false,
    /** Per-device rows with unseen_failures > 0 (device name resolved). */
    val automationFailureDevices: List<AutomationFailureDevice> = emptyList(),
    /** 批v163（M3）：今日 token 总量（sum），[usageTodayKnown] 才展示。 */
    val usageTodayTokens: Long = 0,
    val usageTodayKnown: Boolean = false,
    /** False until the first fetch round completes: without it the cards would
     *  flash "0 everywhere" while the spinners are still running. */
    val hasLoaded: Boolean = false,
)

/** One 琥珀区 row: a device whose automations have unseen failures. */
data class AutomationFailureDevice(
    val serverId: String,
    val deviceName: String,
    val failures: Int,
)

/** 需要处理 everywhere = blocked tasks + agents waiting + automation failures. */
internal val WorkbenchStats.attentionTotal: Int
    get() = attentionTasks + interactiveSessions.size +
        if (automationFailuresKnown) automationFailures else 0

/** The five poll/board inputs of the stats projection — the typed combine
 *  overload caps at 5 flows, so they travel as one (批v160 W7 nesting). */
private data class BoardInputs(
    val round: RoundState,
    val tasks: List<TaskBoardEntry>,
    val profiles: List<ServerProfile>,
    val selectedId: String?,
    val allMode: Boolean,
)

/** One combine emission of the poll loop. */
private data class RoundSignal(
    val tick: Int,
    val active: Boolean,
    val foreground: Boolean,
    val selectedId: String?,
    val allMode: Boolean,
)

/** Per-device round states + hasLoaded (deviceStates re-scoped in the
 *  projection). Top-level so [BoardInputs] — which also lives at file scope —
 *  can name it (a class-private nested type isn't visible up here). */
private data class RoundState(
    val deviceStates: List<DeviceLoad> = emptyList(),
    val hasLoaded: Boolean = false,
)

/**
 * The 动态 workbench's task-side counterweight to [ActivityViewModel] (批v150):
 * a LIGHT, read-only poll of every in-scope device's task list, projected through
 * the same [buildBoard] the task board uses — the numbers on the workbench can
 * never disagree with the 任务板 tab, they ARE the board's segments.
 *
 * Deliberately separate from [TaskHubViewModel] (which lives behind the 任务板
 * tab and carries detail caches / action machinery): the workbench only feeds
 * counters, so its poll runs at 60s (vs the board's 25s), holds no details, and
 * takes no actions. Steady-state polling never overlaps — tabs are mutually
 * exclusive and each VM gates on its own active flag (a tab switch's short
 * cross-fade may briefly hold both composed; each gate only allows one-shot
 * rounds then, never a second running loop).
 */
@HiltViewModel
class WorkbenchViewModel @Inject constructor(
    private val repository: ServerRepository,
    private val appSettingsStore: AppSettingsStore,
    private val watcher: InteractiveRequestWatcher,
    @ApplicationContext appContext: Context,
) : ViewModel() {

    // 批v152 折叠语义改为「设置里的冷启动默认 + 本进程手动覆盖」：
    // - _manual 为 null = 用户本次启动还没动过 → 跟随设置里的默认（展开/折叠）；
    // - 用户点过任一条轨 → _manual 写入当前两区状态，此后本进程内设置变更不再
    //   把用户手选的态掰回去（跨启动恢复默认，v151 的跨启动记忆退役）。
    private val _manual = MutableStateFlow<WorkbenchDisplay?>(null)

    // Seed from the store snapshot so a collapsed-by-default workbench does not
    // flash expanded for the frames before the DataStore flow re-emits (the VM is
    // only built after settingsLoaded, so the snapshot is already populated).
    private val _display = MutableStateFlow(
        appSettingsStore.current?.let {
            WorkbenchDisplay(
                overviewCollapsed = !it.workbenchOverviewExpandedDefault,
                attentionCollapsed = !it.workbenchAttentionExpandedDefault,
            )
        } ?: WorkbenchDisplay(),
    )
    val display: StateFlow<WorkbenchDisplay> = _display.asStateFlow()

    init {
        viewModelScope.launch {
            // 批v153: 中途 DataStore 读失败不再终止整条链——catch{} 会让两条折叠轨
            // 从此点击失灵直到 VM 重建。与 stats 同款退避重试；delay 抛出的取消
            // 照常穿透（结构化取消不受影响）。
            combine(_manual, appSettingsStore.settings) { manual, settings ->
                manual ?: WorkbenchDisplay(
                    overviewCollapsed = !settings.workbenchOverviewExpandedDefault,
                    attentionCollapsed = !settings.workbenchAttentionExpandedDefault,
                )
            }
                .retryWhen { _, attempt ->
                    delay(if (attempt < 3) 1_000 else 5_000)
                    true
                }
                .collect { _display.value = it }
        }
    }

    fun setOverviewCollapsed(collapsed: Boolean) {
        _manual.value = (_manual.value ?: _display.value).copy(overviewCollapsed = collapsed)
    }

    fun setAttentionCollapsed(collapsed: Boolean) {
        _manual.value = (_manual.value ?: _display.value).copy(attentionCollapsed = collapsed)
    }

    /** Raw per-device slices (ids are per-server) — every device ever fetched in
     *  this VM's lifetime; the projection re-scopes instantly from this cache. */
    private val tasks = MutableStateFlow<List<TaskBoardEntry>>(emptyList())

    /**
     * fleet M1: per-device server attention counts (`work_task_attention_count`).
     * Null value = that device failed to report this round (old server / failed
     * RPC) — ANY null in scope degrades the whole badge to the board-derived
     * number, so the two sources never mix. Cleared for departed devices like
     * [tasks].
     */
    private val attentionCounts = MutableStateFlow<Map<String, Int?>>(emptyMap())

    /**
     * 批v163（舰队 M2，Q3）: per-device summed unseen_failures. Present key +
     * non-null value = probed OK (0 = none, or an old server without the
     * automation routes); null value = probe failed this round; ABSENT key =
     * unsupported/never probed (treated as unknown → contribution hidden when
     * any in-scope device lacks a solid value).
     */
    private val automationFailures = MutableStateFlow<Map<String, Int?>>(emptyMap())

    /** 批v163（M3）：今日 token 总量 per device（非 null=已探测；旧服务端=0；
     *  失败=null→未知）。全已知才进统计卡，绝不混算。 */
    private val usageTodayTokens = MutableStateFlow<Map<String, Long?>>(emptyMap())

    private val round = MutableStateFlow(RoundState())

    val stats: StateFlow<WorkbenchStats> =
        combine(
            // 批v160 W7: the typed combine overload caps at 5 flows — the poll
            // inputs travel as one [BoardInputs], the watcher's request list rides
            // on the outer combine. Watcher state is app-wide; it is re-scoped here
            // so a device switch (or All↔single) reflects instantly.
            combine(
                round,
                tasks,
                repository.profiles,
                repository.selectedId,
                repository.activityAll,
            ) { round, tasks, profiles, selectedId, allMode ->
                BoardInputs(round, tasks, profiles, selectedId, allMode)
            },
            combine(attentionCounts, watcher.requests, automationFailures, usageTodayTokens) { counts, interactive, autoFails, usage ->
                combine4(counts, interactive, autoFails, usage)
            },
        ) { inputs, (counts, interactive, autoFails, usage) ->
            val scope = scopeIds(inputs.profiles, inputs.allMode, inputs.selectedId)
            val board = buildBoard(inputs.tasks.filter { it.serverId in scope })
            val scopeCounts = counts.filterKeys { it in scope }
            val serverAttention = scopeCounts.values.sumOf { it ?: 0 }
            val allReported = scopeCounts.isNotEmpty() && scopeCounts.values.all { it != null }
            val scopeAuto = autoFails.filterKeys { it in scope }
            val autoKnown = scope.isNotEmpty() && scopeAuto.keys.containsAll(scope) &&
                scopeAuto.values.all { it != null }
            val autoTotal = if (autoKnown) scopeAuto.values.sumOf { it ?: 0 } else 0
            val autoDevices = if (autoKnown) {
                scopeAuto.entries
                    .filter { (it.value ?: 0) > 0 }
                    .mapNotNull { (id, count) ->
                        inputs.round.deviceStates.firstOrNull { it.serverId == id }
                            ?.let { d -> AutomationFailureDevice(id, d.name, count ?: 0) }
                    }
            } else {
                emptyList()
            }
            val scopeUsage = usage.filterKeys { it in scope }
            val usageKnown = scope.isNotEmpty() && scopeUsage.keys.containsAll(scope) &&
                scopeUsage.values.all { it != null }
            val usageTotal = if (usageKnown) scopeUsage.values.sumOf { it ?: 0L } else 0L
            WorkbenchStats(
                runningTasks = board.segments[TaskSegment.RUNNING].orEmpty().size,
                attentionTasks = if (allReported) {
                    serverAttention
                } else {
                    board.segments[TaskSegment.ATTENTION].orEmpty().size
                },
                attentionPreview = board.segments[TaskSegment.ATTENTION].orEmpty().take(PREVIEW_MAX),
                interactiveSessions = interactive
                    .filter { it.serverId in scope }
                    .sortedBy { it.since },
                automationFailures = autoTotal,
                automationFailuresKnown = autoKnown,
                automationFailureDevices = autoDevices,
                usageTodayTokens = usageTotal,
                usageTodayKnown = usageKnown,
                onlineDevices = inputs.round.deviceStates.count { it.serverId in scope && it.status == DeviceLoadStatus.OK },
                totalDevices = inputs.round.deviceStates.count { it.serverId in scope },
                hasLoaded = inputs.round.hasLoaded,
            )
        }
            // Upstream (profiles) is a DataStore flow: a read failure must keep the
            // stats ALIVE, not silently dead (same policy as TaskHubViewModel.board).
            .retryWhen { _, attempt ->
                delay(if (attempt < 3) 1_000 else 5_000)
                true
            }
            .distinctUntilChanged()
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkbenchStats())

    private val foreground = (appContext as CodegApplication).appForeground

    /** Workbench-tab visibility — the poll only runs while the tab is composed. */
    private val _active = MutableStateFlow(false)
    fun setActive(active: Boolean) { _active.value = active }

    private val _refreshTick = MutableStateFlow(0)
    fun refresh() { _refreshTick.value += 1 }

    private var generation = 0
    private var lastSeenTick = -1
    private var lastSeenScope: Pair<String?, Boolean>? = null
    private var wasVisible = false

    init {
        viewModelScope.launch { pollLoop() }
        // Same trigger as TaskHubViewModel: the aggregate participant set drives
        // the device fan-out; a change re-runs the round immediately.
        repository.profiles
            .map { list -> list.filter { it.visible && it.includeInAll } }
            .distinctUntilChanged()
            // 批v165 R1 (P2): the FIRST profiles emission (cold-start DataStore
            // read) is not a change — pollLoop's tick==0 branch already fetches;
            // the initial bump double-fetched on every screen entry.
            .drop(1)
            .onEach { _refreshTick.value += 1 }
            .retryWhen { _, attempt ->
                delay(if (attempt < 3) 1_000 else 5_000)
                true
            }
            .launchIn(viewModelScope)
    }

    private fun scopeIds(profiles: List<ServerProfile>, allMode: Boolean, selectedId: String?): Set<String> =
        if (allMode) {
            profiles.filter { it.visible && it.includeInAll }.map { it.id }.toSet()
        } else {
            profiles.filter { it.id == selectedId }.map { it.id }.toSet()
        }

    private fun pollLoop() {
        viewModelScope.launch {
            combine(
                _refreshTick,
                _active,
                foreground,
                repository.selectedId,
                repository.activityAll,
            ) { tick, active, fg, selectedId, allMode ->
                    RoundSignal(tick, active, fg, selectedId, allMode) }
                .retryWhen { _, attempt ->
                        // 批v165 R1 (P0): a DataStore read failure upstream must resubscribe — a
                        // bare collector dying here is the v130 crash class (process kill from a
                        // screen entry) AND "deaf for life" if it never re-arms.
                        kotlinx.coroutines.delay(if (attempt < 3) 1_000 else 5_000)
                        true
                }
                .collectLatest { signal ->
                    val (tick, active, fg, selectedId, allMode) = signal
                    if (active && fg) {
                        // Scope is only tracked while the workbench is actually
                        // visible: leaving the tab must not consume the change,
                        // and re-entering always runs one fresh round, so the
                        // user never sees minute-old counters after a tab switch.
                        val reentered = !wasVisible
                        wasVisible = true
                        val scope = selectedId to allMode
                        val scopeChanged = scope != lastSeenScope
                        lastSeenScope = scope
                        if (reentered || tick == 0 || tick != lastSeenTick || scopeChanged) {
                            lastSeenTick = tick
                            fetchRound()
                        }
                        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                            delay(POLL_INTERVAL_MS)
                            fetchRound()
                        }
                    } else {
                        wasVisible = false
                        // First-ever round runs even off-tab so the tab opens warm.
                        if (tick == 0) fetchRound()
                    }
                }
        }
    }

    private suspend fun fetchRound() {
        // Bare init/poll coroutine: a DataStore failure must degrade to "this
        // round failed", never kill the process (v130 class).
        try {
            fetchRoundInner()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            android.util.Log.w("WorkbenchViewModel", "round failed")
        }
    }

    private suspend fun fetchRoundInner() {
        // Bump first: even an empty or cancelled round must invalidate older
        // in-flight per-device writes (a profile may vanish mid-round).
        generation += 1
        val gen = generation
        val profiles = repository.profiles.first()
        val devices = profiles.filter {
            it.id in scopeIds(profiles, repository.activityAll.value, repository.selectedId.first())
        }
        val ids = devices.map { it.id }.toSet()
        if (devices.isEmpty()) {
            round.value = RoundState(deviceStates = emptyList(), hasLoaded = true)
            // Keep other devices' cached slices (they re-enter scope on a switch);
            // drop only DELETED profiles' slices — those are ghosts forever.
            val known = profiles.map { it.id }.toSet()
            tasks.value = tasks.value.filter { it.serverId in known }
            return
        }
        round.update { r ->
            r.copy(
                deviceStates = r.deviceStates.participating(ids).let { kept ->
                    devices.map { d ->
                        kept.firstOrNull { it.serverId == d.id }
                            ?: DeviceLoad(d.id, d.name, DeviceLoadStatus.LOADING)
                    }
                },
            )
        }
        // A device that left the aggregate must not keep feeding its stale slice
        // into the stats (same ghost rule as TaskHubViewModel). Per-device
        // completions below use atomic update{} too — two devices landing in
        // the same instant must not lose a slice to a read-modify-write race.
        tasks.update { it.filter { e -> e.serverId in ids } }
        attentionCounts.update { it.filterKeys { id -> id in ids } }
        automationFailures.update { it.filterKeys { id -> id in ids } }
        usageTodayTokens.update { it.filterKeys { id -> id in ids } }
        coroutineScope {
            devices.forEach { profile ->
                launch {
                    val result = runCatching {
                        val client = repository.client(profile)
                            ?: throw IllegalStateException("Token 缺失，无法加载该设备")
                        withTimeoutOrNull(AGG_DEVICE_TIMEOUT_MS) {
                            val list = client.workTaskList()
                            // fleet M1: separate best-effort round-trip with its own
                            // tighter budget — a slow count degrades the badge, it
                            // must not consume the task list's device deadline.
                            // Cancellation rethrown so device timeouts still cancel.
                            val count = try {
                                withTimeoutOrNull(ATTENTION_COUNT_TIMEOUT_MS) {
                                    client.workTaskAttentionCount()
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                android.util.Log.w("WorkbenchViewModel", "attention_count unavailable on ${profile.id}")
                                null
                            }
                            // 批v163（Q3）: same best-effort shape as the count —
                            // own tighter budget, 404/old server pins a solid 0
                            // (supported=false semantics), any other failure leaves
                            // a null (this round's contribution hides).
                            // Both probes are nested inside the 10s outer device
                            // budget, so their own budget is capped at 3s (M3 审查
                            // M1): a slow probe must degrade to null, never eat the
                            // task list's window or sink the whole round.
                            val autoFails = try {
                                withTimeoutOrNull(AUTOMATION_PROBE_TIMEOUT_MS) {
                                    client.automationList().sumOf { it.unseenFailures }
                                } ?: run {
                                    android.util.Log.w("WorkbenchViewModel", "automation_list timeout on ${profile.id}")
                                    null
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                if (app.codeg.android.core.network.isUnsupportedMethod(e)) 0 else null
                            }
                            automationFailures.update { it + (profile.id to autoFails) }
                            // 批v163（M3）: today's token total, same degrade
                            // shape as the failure probe — old server pins 0
                            // (nothing to report), a failed probe leaves null
                            // and the stat card hides the whole sum.
                            val usageToday: Long? = try {
                                val r = withTimeoutOrNull(USAGE_PROBE_TIMEOUT_MS) {
                                    client.tokenUsageReport(
                                        app.codeg.android.feature.usage.usageFilter(
                                            app.codeg.android.feature.usage.UsagePeriod.TODAY,
                                        ),
                                    )
                                }
                                if (r == null) {
                                    android.util.Log.w("WorkbenchViewModel", "token_usage_report timeout on ${profile.id}")
                                    null
                                } else {
                                    r.totals.totalTokens
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                if (app.codeg.android.core.network.isUnsupportedMethod(e)) 0L else null
                            }
                            usageTodayTokens.update { it + (profile.id to usageToday) }
                            list to count
                        } ?: throw java.util.concurrent.TimeoutException("device timeout")
                    }
                    if (gen != generation) return@launch
                    result.fold(
                        onSuccess = { (list, count) ->
                            round.update { r ->
                                r.copy(
                                    deviceStates = r.deviceStates.map {
                                        if (it.serverId == profile.id) it.copy(status = DeviceLoadStatus.OK, error = null) else it
                                    },
                                )
                            }
                            tasks.update { cur ->
                                cur.filter { e -> e.serverId != profile.id } +
                                    (list.orEmpty().map { t -> TaskBoardEntry(profile.id, profile.name, t) })
                            }
                            attentionCounts.update { it + (profile.id to count) }
                        },
                        onFailure = { e ->
                            if (e is CancellationException) throw e
                            // A dead device must not keep feeding its stale count
                            // into the server-truth sum — invalidate it. The usage
                            // map gets the same treatment (M3 审查 H1): a stale
                            // token total must not pose as "known" on a dead device.
                            automationFailures.update { it + (profile.id to null) }
                            attentionCounts.update { it + (profile.id to null) }
                            usageTodayTokens.update { it + (profile.id to null) }
                            round.update { r ->
                                r.copy(
                                    deviceStates = r.deviceStates.map {
                                        if (it.serverId == profile.id) {
                                            it.copy(
                                                status = DeviceLoadStatus.FAILED,
                                                error = (e as? ApiError)?.displayMessage()
                                                    ?: e.message ?: "加载失败",
                                            )
                                        } else it
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }
        if (gen == generation) round.update { it.copy(hasLoaded = true) }
    }

    companion object {
        /** automation_list 探测独立预算——嵌在 10s 外层设备预算内，所以封顶 3s
         *  （M3 审查 M1）：慢设备探测降级为 null（琥珀行该轮隐藏，下轮补上），
         *  绝不吃掉任务列表的时间窗、更不能把整轮打成 FAILED。 */
        private const val AUTOMATION_PROBE_TIMEOUT_MS = 3_000L

        /** token_usage_report 探测（今日总量）独立预算，同上 3s 封顶。 */
        private const val USAGE_PROBE_TIMEOUT_MS = 3_000L
        /** Workbench poll cadence — counters, not a live board (the board tab
         *  polls at 25s); foreground-gated by the poll combine. */
        const val POLL_INTERVAL_MS = 60_000L

        /** 需要处理 preview cap (new mandate §1.3: ≤3 rows). */
        const val PREVIEW_MAX = 3

        /** 批v160 W7: 「等待你确认」 rows cap — same preview discipline as tasks. */
        const val INTERACTIVE_MAX = 3
    }
}

/** The stats combine rides a 4-way inner combine; the typed overload caps at
 *  5 params, so the payload travels as one holder. */
private data class StatsPayload(
    val counts: Map<String, Int?>,
    val interactive: List<PendingInteraction>,
    val autoFails: Map<String, Int?>,
    val usage: Map<String, Long?>,
)

private fun combine4(
    counts: Map<String, Int?>,
    interactive: List<PendingInteraction>,
    autoFails: Map<String, Int?>,
    usage: Map<String, Long?>,
) = StatsPayload(counts, interactive, autoFails, usage)
