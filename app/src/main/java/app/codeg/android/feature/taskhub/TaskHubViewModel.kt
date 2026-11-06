package app.codeg.android.feature.taskhub

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.CodegApplication
import app.codeg.android.R
import app.codeg.android.core.data.AGG_DEVICE_TIMEOUT_MS
import app.codeg.android.core.data.DeviceLoad
import app.codeg.android.core.data.DeviceLoadStatus
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.data.BoardConversationEntry
import app.codeg.android.core.data.TaskBoardData
import app.codeg.android.core.data.TaskBoardEntry
import app.codeg.android.core.data.TaskSegment
import app.codeg.android.core.data.archiveAllCandidates
import app.codeg.android.core.data.buildBoard
import app.codeg.android.core.data.taskSegmentOf
import app.codeg.android.core.data.participating
import app.codeg.android.core.data.retryableIds
import app.codeg.android.core.data.retrying
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.model.WorkTaskChangedFile
import app.codeg.android.core.model.WorkTaskEvent
import app.codeg.android.core.model.ConversationStatus
import app.codeg.android.core.model.WorkTaskInfo
import app.codeg.android.core.model.WorkTaskTemplateInfo
import app.codeg.android.core.network.ApiError
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.displayMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import javax.inject.Inject

/**
 * The task hub (ui重构 阶段2+3): the Codeg native work-task board projected onto
 * the four web-parity segments, scoped by the GLOBAL device selection (the
 * global bar in [app.codeg.android.core.designsystem.component.GlobalDeviceBar]
 * — this page renders no second device row) plus its own folder/search/filter
 * toolbar. Pure projection — tasks, statuses and their transitions live on the
 * servers; this VM only fetches, groups and relays actions. One offline device
 * degrades its own slice (banner + retry), never the board (v137 mechanism).
 *
 * Realtime: a 25s poll + pull-to-refresh; switching the global device re-runs
 * the round immediately (selectedId/tasksAll feed the poll combine).
 */
@HiltViewModel
class TaskHubViewModel @Inject constructor(
    private val repository: ServerRepository,
    @ApplicationContext private val appContext: Context,
    // 批v155 宿主 C: the board's 新建任务 form rides the same dispatch pipeline
    // (ledger double-create guard) as the inbox and the ball.
    private val dispatchService: app.codeg.android.core.data.DispatchService,
    private val targetLoader: app.codeg.android.core.data.DispatchTargetLoader,
    private val agentOptionsProber: app.codeg.android.core.data.AgentOptionsProber,
    override val drafts: app.codeg.android.core.datastore.DraftStore,
    override val attachmentStager: app.codeg.android.core.data.AttachmentStager,
    private val appSettingsStore: app.codeg.android.core.datastore.AppSettingsStore,
    /** P1: tracked-conversation ledger — the board's only local membership source. */
    private val boardTrack: app.codeg.android.core.datastore.BoardTrackStore,
    /** 批v160 W5: the shell's 「切任务状态」 swipe steps — the screen collects
     *  them to step the kanban's columns (same singleton the shell emits into). */
    val segmentBus: app.codeg.android.core.data.TaskSegmentBus,
) : ViewModel(),
    app.codeg.android.feature.dispatch.DispatchFormHost {

    data class TaskDetail(
        val events: List<WorkTaskEvent>? = null,
        val changedFiles: List<WorkTaskChangedFile>? = null,
        val diff: String? = null,
        val diffLoading: Boolean = false,
        val diffError: String? = null,
        /** Set when the detail loads failed — the body shows it with a retry. */
        val loadError: String? = null,
    )

    data class UiState(
        val deviceStates: List<DeviceLoad> = emptyList(),
        val tasks: List<TaskBoardEntry> = emptyList(),
        val lastRefreshed: Instant? = null,
        /** Web-parity board filters (web defaults: canceled shown, archived not). */
        val showCanceled: Boolean = true,
        val showArchived: Boolean = false,
        /** Folder filter by NAME (folder ids are per-server, names travel). */
        val folderFilter: String? = null,
        /** serverId -> (folderId -> folder name), best-effort for filter + cards. */
        val folderNames: Map<String, Map<Int, String>> = emptyMap(),
        val searchQuery: String = "",
        /** serverId -> (taskId -> detail); fetched lazily when a task expands. */
        val details: Map<String, Map<Int, TaskDetail>> = emptyMap(),
        /** P1: tracked conversations of the scoped devices, straight from the wire. */
        val conversations: List<BoardConversationEntry> = emptyList(),
        /** serverId:taskId of an in-flight action, for row spinners. */
        val busyKey: String? = null,
        val actionError: String? = null,
        /** Non-fatal action outcome worth telling (merge queued behind the
         *  folder slot) — styled as info, cleared with the next action. */
        val actionNote: String? = null,
        /** False until the first fetch round completes: without it the board
         * flashes "no tasks" while the spinners are still running. */
        val hasLoaded: Boolean = false,
    ) {
        val failedDevices: Int get() = deviceStates.count { it.status == DeviceLoadStatus.FAILED }
        val archivedHidden: Int
            get() = tasks.count { it.task.isArchivedLike }.takeIf { !showArchived } ?: 0
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** 批v156: the segment an acted task just landed in, consumed once by the
     *  screen. After 启动 the card leaves 待办 for 进行中 — the board used to keep
     *  sitting on the (now empty) old segment, so the card "vanished" one tab
     *  away. The screen auto-follows the task to its new segment. */
    private val _actedLandedSegment = MutableStateFlow<TaskSegment?>(null)
    val actedLandedSegment: StateFlow<TaskSegment?> = _actedLandedSegment.asStateFlow()

    fun consumeActedLandedSegment() {
        _actedLandedSegment.value = null
    }

    private val foreground = (appContext as CodegApplication).appForeground

    private val _refreshTick = MutableStateFlow(0)
    private var generation = 0
    /** The tick already fetched: combine re-emits on every active/foreground
     * toggle, and without this each one would redo the tick>0 refetch forever. */
    private var lastSeenTick = -1
    /** Bumped by post(): in-flight loadDetail rounds from before an action must
     * not repopulate the dropped (pre-action) detail afterwards. */
    private var detailEpoch = 0
    /** Folder lists load once per device (they change rarely); refreshed on
     * pull-to-refresh via [refreshFolders]. */
    private var foldersLoadedFor = setOf<String>()

    /**
     * Board projection: global device scope + folder/search/filters → segments.
     * Counts ignore the search query so the segment bar stays a stable map of
     * what exists while typing.
     */
    val board: StateFlow<TaskBoardData> =
        combine(
            _ui,
            repository.profiles,
            repository.selectedId,
            repository.tasksAll,
        ) { state, profiles, selectedId, allMode ->
            val names = profiles.associate { it.id to it.name }
            val scopeIds = scopeIds(profiles, allMode, selectedId)
            val inScope = state.tasks.asSequence()
                .filter { it.serverId in scopeIds }
                .map { it.copy(serverName = names[it.serverId] ?: it.serverName) }
                .toList()
            val folderMatch: (TaskBoardEntry) -> Boolean = { entry ->
                state.folderFilter == null ||
                    state.folderNames[entry.serverId]?.get(entry.task.folderId) == state.folderFilter
            }
            val searchMatch: (TaskBoardEntry) -> Boolean = { entry ->
                val q = state.searchQuery.trim()
                q.isEmpty() ||
                    entry.task.title.contains(q, ignoreCase = true) ||
                    entry.task.resultSummary?.contains(q, ignoreCase = true) == true
            }
            val base = inScope.filter { folderMatch(it) && boardFilter(it, state) }
            // P1: conversations follow the SAME scope, folder filter and search —
            // a board that mixes "all devices" tasks with "one device" sessions
            // would lie about what the user is looking at.
            val convInScope = state.conversations
                .filter { it.serverId in scopeIds }
                .map { it.copy(serverName = names[it.serverId] ?: it.serverName) }
            val convBase = convInScope.filter { entry ->
                (state.folderFilter == null ||
                    state.folderNames[entry.serverId]?.get(entry.conversation.folderId) == state.folderFilter)
            }
            val convSearch = convBase.filter { entry ->
                val q = state.searchQuery.trim()
                q.isEmpty() || entry.conversation.trimmedTitle?.contains(q, ignoreCase = true) == true
            }
            val counts = buildBoard(
                base, state.showCanceled, state.showArchived, convBase,
            ).counts
            val searchable = if (state.searchQuery.isBlank()) base else base.filter(searchMatch)
            buildBoard(searchable, state.showCanceled, state.showArchived, convSearch)
                .copy(counts = counts)
        }
            // Upstream (profiles) is a DataStore flow: a read failure must keep
            // the board ALIVE, not silently dead. `.catch` would terminate the
            // sharing coroutine forever (board frozen until VM recreation);
            // retryWhen re-subscribes so the next good emission repaints.
            .retryWhen { _, attempt ->
                kotlinx.coroutines.delay(if (attempt < 3) 1_000 else 5_000)
                true
            }
            .distinctUntilChanged()
            // Sorting/filtering a few thousand rows belongs off the main thread:
            // every 25s poll emission and every keystroke re-runs this block.
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskBoardData())

    /** Folder names present on the currently scoped devices, sorted — the picker. */
    val folderOptions: StateFlow<List<String>> =
        combine(_ui, repository.profiles, repository.selectedId, repository.tasksAll) { state, profiles, selectedId, allMode ->
            val ids = scopeIds(profiles, allMode, selectedId)
            ids.flatMap { state.folderNames[it]?.values.orEmpty() }
                .distinct()
                .sorted()
        }
            .retryWhen { _, attempt ->
                kotlinx.coroutines.delay(if (attempt < 3) 1_000 else 5_000)
                true
            }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun scopeIds(profiles: List<ServerProfile>, allMode: Boolean, selectedId: String?): Set<String> =
        if (allMode) {
            profiles.filter { it.visible && it.includeInAll }.map { it.id }.toSet()
        } else {
            // Single-device mode follows the global bar; a selected-but-hidden
            // device is still fetchable (the bar only lists visible ones).
            profiles.filter { it.id == selectedId }.map { it.id }.toSet()
        }

    private fun boardFilter(entry: TaskBoardEntry, state: UiState): Boolean {
        val task = entry.task
        if (task.isArchivedLike && !state.showArchived) return false
        if ((task.status == "canceled" || task.status == "cancelled") && !state.showCanceled) return false
        return true
    }

    /**
     * Task tab visibility — the poll only runs while the board is on screen.
     * MUST stay above the init block: `viewModelScope.launch` runs the
     * coroutine body synchronously on Dispatchers.Main.immediate during
     * construction, and pollLoop()'s combine collects this flow — a
     * declaration below init is still null there (the v33 任务页 NPE, stack:
     * TaskHubViewModel.<init> → combineInternal → Flow.collect on null).
     */
    private val _active = MutableStateFlow(false)
    fun setActive(active: Boolean) { _active.value = active }

    /** 批v152: one-shot segment jump from the workbench cards / preview — the
     *  board reads then clears it so a plain tab return keeps the user's choice. */
    fun consumeSegmentRequest(): TaskSegment? = repository.consumeTaskSegment()

    /** 批v163（舰队 M2，Q3）：琥珀区「自动化失败」行请求打开自动化段。 */
    fun consumeAutomationTabRequest(): Boolean = repository.consumeAutomationTabRequest()

    init {
        viewModelScope.launch { pollLoop() }
        // 批v155: same crash-recovery sweep as every dispatch surface — the board
        // is now one (its 新建任务 form goes through the ledger).
        viewModelScope.launch {
            runCatching { dispatchService.sweepPendingToUnknown() }
        }
        // 宿主 C: the form's device chips come from the live visible profiles
        // (same resubscribe loop as the ball's host — a DataStore failure
        // degrades to a stale list, never a dead VM).
        viewModelScope.launch {
            while (currentCoroutineContext().isActive) {
                try {
                    repository.visibleProfiles.collect { profiles ->
                        _formTargets.value = _formTargets.value.copy(profiles = profiles)
                    }
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    delay(2_000)
                }
            }
        }
        repository.profiles
            .map { list -> list.filter { it.visible && it.includeInAll } }
            .distinctUntilChanged()
            .onEach { _refreshTick.value += 1 }
            // DataStore failure on the profiles read must not kill the process
            // (bare init coroutine = crash from the Activity tab, v130 class).
            // retryWhen (not .catch) so the collection survives and the next
            // successful profiles emission re-arms the tick.
            .retryWhen { _, attempt ->
                kotlinx.coroutines.delay(if (attempt < 3) 1_000 else 5_000)
                true
            }
            .launchIn(viewModelScope)
        // 批v161.2: the membership ledger is written from five other surfaces
        // (Chats long-press, detail ⋮ menu, Activity, Search, dispatch) — none
        // of them can reach this VM, so the ledger flow itself is the
        // "just added" signal: every write bumps the tick and the poll round
        // starts immediately instead of at the next 25s beat. drop(1) so the
        // initial emission doesn't double-fetch against the cold-start round.
        // Converges: a prune write re-triggers one round, which then finds no
        // stale keys and writes nothing; an idempotent track() edit that
        // changes nothing emits nothing.
        boardTrack.tracked
            .drop(1)
            .distinctUntilChanged()
            .onEach { _refreshTick.value += 1 }
            .launchIn(viewModelScope)
    }

    fun refresh() { _refreshTick.value += 1 }

    fun setShowCanceled(show: Boolean) { _ui.update { it.copy(showCanceled = show) } }

    fun setShowArchived(show: Boolean) { _ui.update { it.copy(showArchived = show) } }

    fun setFolderFilter(name: String?) { _ui.update { it.copy(folderFilter = name) } }

    fun setSearchQuery(query: String) { _ui.update { it.copy(searchQuery = query) } }

    fun retryFailedDevices() {
        val retryIds = _ui.value.deviceStates.retryableIds()
        if (retryIds.isEmpty()) return
        viewModelScope.launch {
            try {
                _ui.update { it.copy(deviceStates = it.deviceStates.retrying(retryIds)) }
                fetchDevices(retryIds)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Bare DataStore read in fetchDevices' targets branch must not
                // crash the process; the devices keep their FAILED state.
            }
        }
    }

    private fun pollLoop() {
        viewModelScope.launch {
            // Global device scope feeds the poll: switching the bar re-runs the
            // round immediately (collectLatest cancels any round in flight).
            combine(
                _refreshTick,
                _active,
                foreground,
                repository.selectedId,
                repository.tasksAll,
            ) { tick, active, fg, _, _ -> PollInputs(tick, active, fg) }
                .collectLatest { input ->
                    // Pull-to-refresh / device-set change: refetch now (any new tick
                    // cancels this collector, superseding a round in flight)...
                    if (input.tick > 0 && input.tick != lastSeenTick) {
                        lastSeenTick = input.tick
                        fetchAll()
                    }
                    when {
                        // ...then ALWAYS resume the foreground poll (a one-shot
                        // `tick>0 -> return` would kill the 25s loop forever, v139 H1).
                        input.active && input.fg -> {
                            if (input.tick == 0) fetchAll()
                            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                                delay(25_000)
                                fetchAll()
                            }
                        }
                        // Board hidden or app backgrounded: cold start does one fetch, no poll.
                        input.tick == 0 -> fetchAll()
                    }
                }
        }
    }

    private data class PollInputs(val tick: Int, val active: Boolean, val fg: Boolean)

    private suspend fun fetchAll() {
        // Top-level guard: this runs from bare init/poll coroutines, and a
        // DataStore failure on the profiles read must degrade to "this round
        // failed" — never kill the process from the Activity tab's VM.
        try {
            fetchAllInner()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Profiles unreadable: skip this round (poll retries in ≤25s) —
            // never kill the process from the Activity tab's VM.
            android.util.Log.w("TaskHubViewModel", "fetchAll round failed")
        }
    }

    private suspend fun fetchAllInner() {
        val profiles = repository.profiles.first()
        val devices = profiles.filter { it.id in scopeIds(profiles, repository.tasksAll.value, repository.selectedId.first()) }
        if (devices.isEmpty()) {
            _ui.update { it.copy(deviceStates = emptyList(), tasks = emptyList(), lastRefreshed = Instant.now(), hasLoaded = true) }
            return
        }
        generation += 1
        val gen = generation
        _ui.update { state ->
            val ids = devices.map { it.id }.toSet()
            state.copy(
                deviceStates = state.deviceStates.participating(ids).let { kept ->
                    devices.map { d ->
                        kept.firstOrNull { it.serverId == d.id }
                            ?: DeviceLoad(d.id, d.name, DeviceLoadStatus.LOADING)
                    }
                },
                // A deleted or "skipped in All" device must not leave ghost rows
                // (rows, and the detail cache that back-expansion would serve).
                tasks = state.tasks.filter { it.serverId in ids },
                details = state.details.filterKeys { it in ids },
            )
        }
        loadFolderNames(devices)
        fetchDevices(devices.map { it.id }.toSet(), devices, gen)
        fetchTrackedConversations(devices, gen)
        if (gen == generation) _ui.update { it.copy(lastRefreshed = Instant.now(), hasLoaded = true) }
    }

    /** Best-effort per-device folder lists (names for the filter + cards). */
    private fun loadFolderNames(devices: List<ServerProfile>) {
        val todo = devices.filter { it.id !in foldersLoadedFor }
        if (todo.isEmpty()) return
        foldersLoadedFor += todo.map { it.id }
        viewModelScope.launch {
            coroutineScope {
                todo.forEach { profile ->
                    launch {
                        val folders = runCatching {
                            repository.client(profile)?.listFolders().orEmpty()
                        }.getOrDefault(emptyList())
                            // 批v149：worktree 子目录不进 folderNames——任务的 folder_id
                            // 恒为项目根（服务端 attach_worktree 写的是独立的
                            // worktree_folder_id 列），worktree 名字只会污染过滤选择器。
                            .filter { it.parentId == null }
                        if (folders.isNotEmpty()) {
                            _ui.update { state ->
                                state.copy(
                                    folderNames = state.folderNames +
                                        (profile.id to folders.associate { it.id to it.name }),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** Pull-to-refresh also reloads folder lists (a new project should appear). */
    fun refreshFolders() {
        foldersLoadedFor = emptySet()
        viewModelScope.launch {
            runCatching { loadFolderNames(repository.profiles.first().filter { it.id in _ui.value.deviceStates.map { d -> d.serverId } }) }
        }
    }

    private suspend fun fetchDevices(ids: Set<String>, devices: List<ServerProfile>? = null, gen: Int = generation) {
        val targets = devices ?: repository.profiles.first().filter { it.id in ids }
        coroutineScope {
            targets.filter { it.id in ids }.forEach { profile ->
                launch {
                    val result = runCatching {
                        // A null here is a TIMEOUT, not "zero tasks" — collapsing the two
                        // would mark a slow device healthy and wipe its rows (v139 H3).
                        val client = repository.client(profile)
                            ?: throw IllegalStateException("Token 缺失，无法加载该设备")
                        withTimeoutOrNull(AGG_DEVICE_TIMEOUT_MS) {
                            client.workTaskList()
                        } ?: throw java.util.concurrent.TimeoutException("device timeout")
                    }
                    if (gen != generation) return@launch
                    result.fold(
                        onSuccess = { tasks ->
                            _ui.update { state ->
                                val fresh = tasks.orEmpty().map { t -> TaskBoardEntry(profile.id, profile.name, t) }
                                // 批v161.1 Bug A: a card moved by the engine or
                                // another client used to teleport across pager
                                // pages with zero cue (「删一张另一张也消失」—
                                // the other card had just landed on another
                                // page). Announce where it went; rounds caused
                                // by the user's own action hold the busy slot
                                // and are skipped (they already jump segments).
                                val moveNote = boardMoveNote(
                                    old = state.tasks.filter { it.serverId == profile.id },
                                    new = fresh,
                                    busy = state.busyKey != null,
                                )
                                state.copy(
                                    deviceStates = state.deviceStates.map {
                                        if (it.serverId == profile.id) {
                                            it.copy(status = DeviceLoadStatus.OK, error = null)
                                        } else it
                                    },
                                    tasks = state.tasks.filter { e -> e.serverId != profile.id } + fresh,
                                    actionNote = moveNote ?: state.actionNote,
                                )
                            }
                        },
                        onFailure = { e ->
                            if (e is CancellationException) throw e
                            // Failure keeps the device's last slice on screen (stale rows
                            // plus the banner) instead of silently emptying it (v137 rule).
                            _ui.update { state ->
                                state.copy(
                                    deviceStates = state.deviceStates.map {
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
    }

    /**
     * P1: refresh the board's conversation slice. One folder-scoped list call per
     * device that actually has tracked members, plus the live-connection table so
     * a card can tell "running" from "row exists, nothing attached".
     *
     * Pruning is deliberately timid: `list_all_conversations` hides loop-kind
     * rows, delegation child sessions and conversations under soft-deleted
     * projects, so a single miss proves nothing. A member is dropped only after
     * TWO consecutive COMPLETE rounds (no timeout, no error) failed to list it.
     */
    private val conversationMissRounds = mutableMapOf<String, Int>()

    private suspend fun fetchTrackedConversations(devices: List<ServerProfile>, gen: Int) {
        val tracked = runCatching { boardTrack.trackedNow() }.getOrDefault(emptyList())
        if (tracked.isEmpty()) {
            _ui.update { it.copy(conversations = emptyList()) }
            return
        }
        val byServer = tracked.groupBy { it.serverId }
        coroutineScope {
            devices.forEach { profile ->
                val mine = byServer[profile.id] ?: return@forEach
                launch {
                    val client = repository.client(profile) ?: return@launch
                    val result = runCatching {
                        withTimeoutOrNull(AGG_DEVICE_TIMEOUT_MS) {
                            client.listConversations(folderIds = mine.map { it.folderId }.distinct())
                        } ?: throw java.util.concurrent.TimeoutException("device timeout")
                    }
                    if (gen != generation) return@launch
                    val liveIds = runCatching { client.liveConversationIds() }.getOrNull()
                    result.onSuccess { convs ->
                        val byId = convs.associateBy { it.id }
                        val present = mutableSetOf<String>()
                        val rows = mine.mapNotNull { member ->
                            val conversation = byId[member.conversationId] ?: return@mapNotNull null
                            present += member.key
                            BoardConversationEntry(
                                serverId = profile.id,
                                serverName = profile.name,
                                conversation = conversation,
                                // Probe failed (old server) → fall back to the status
                                // flag, never to "not running" (which would dim a
                                // genuinely live session).
                                live = liveIds?.contains(conversation.id) ?: conversation.status.isLive,
                                archived = member.archivedAt != null,
                            )
                        }
                        _ui.update { state ->
                            state.copy(
                                conversations = state.conversations
                                    .filterNot { it.serverId == profile.id } + rows,
                            )
                        }
                        val stale = mutableSetOf<String>()
                        mine.forEach { member ->
                            if (member.key in present) {
                                conversationMissRounds.remove(member.key)
                            } else {
                                val rounds = (conversationMissRounds[member.key] ?: 0) + 1
                                if (rounds >= 2) {
                                    stale += member.key
                                    conversationMissRounds.remove(member.key)
                                } else {
                                    conversationMissRounds[member.key] = rounds
                                }
                            }
                        }
                        if (stale.isNotEmpty()) runCatching { boardTrack.prune(stale) }
                    }.onFailure { e ->
                        if (e is CancellationException) throw e
                        // Keep the last slice on screen (same rule as tasks).
                    }
                }
            }
        }
    }

    /** P1: add / remove a conversation from the board (long-press entry points). */
    fun trackConversation(serverId: String, conversationId: Int, folderId: Int) {
        viewModelScope.launch {
            runCatching { boardTrack.track(serverId, conversationId, folderId) }
            _refreshTick.value += 1
        }
    }

    fun untrackConversation(serverId: String, conversationId: Int) {
        // Drop it locally first: the card must disappear on tap, not on the next
        // poll (which can be 25s away).
        _ui.update { state ->
            state.copy(
                conversations = state.conversations.filterNot {
                    it.serverId == serverId && it.conversation.id == conversationId
                },
            )
        }
        viewModelScope.launch {
            runCatching { boardTrack.untrack(serverId, conversationId) }
        }
    }

    /**
     * P2: change a tracked session's status straight from its board card. The
     * column is DERIVED from `status`, so this is optimistic — a card must move
     * on tap, not on the next 25s poll. A rejection rolls the old status back:
     * a card parked in the wrong column is worse than a failed tap.
     */
    fun setConversationStatus(serverId: String, conversationId: Int, status: ConversationStatus) {
        val before = _ui.value.conversations
            .firstOrNull { it.serverId == serverId && it.conversation.id == conversationId }
            ?.conversation?.status ?: return
        if (before == status) return
        _ui.update { state ->
            state.copy(
                conversations = state.conversations.map {
                    if (it.serverId == serverId && it.conversation.id == conversationId) {
                        it.copy(conversation = it.conversation.copy(status = status))
                    } else {
                        it
                    }
                },
            )
        }
        viewModelScope.launch {
            val result = runCatching {
                val profile = deviceById(serverId) ?: error("设备不存在")
                val client = repository.client(profile) ?: error("Token 缺失")
                client.setStatus(conversationId, status.wire)
            }
            result.onFailure { e ->
                if (e is CancellationException) throw e
                _ui.update { state ->
                    state.copy(
                        conversations = state.conversations.map {
                            if (it.serverId == serverId && it.conversation.id == conversationId) {
                                it.copy(conversation = it.conversation.copy(status = before))
                            } else {
                                it
                            }
                        },
                        actionError = (e as? ApiError)?.displayMessage() ?: e.message ?: "操作失败",
                    )
                }
            }
            result.onSuccess {
                repository.notifyConversationsChanged()
                generation += 1
                fetchDevices(setOf(serverId))
            }
        }
    }

    fun setConversationArchived(serverId: String, conversationId: Int, archived: Boolean) {
        _ui.update { state ->
            state.copy(
                conversations = state.conversations.map {
                    if (it.serverId == serverId && it.conversation.id == conversationId) {
                        it.copy(archived = archived)
                    } else it
                },
            )
        }
        viewModelScope.launch {
            runCatching { boardTrack.setArchived(serverId, conversationId, archived) }
        }
    }

    // region Actions — every one goes straight to the owning server, then refreshes
    // that device's slice. Server errors surface verbatim in [UiState.actionError].

    private fun key(serverId: String, taskId: Int) = "$serverId:$taskId"

    private suspend fun deviceById(serverId: String): ServerProfile? =
        repository.profiles.first().firstOrNull { it.id == serverId }

    /** [onSettled] fires exactly once when the action resolves — 批v155(R2C#4):
     *  the edit sheet holds itself open on a rejection (inline error, draft
     *  intact) instead of closing and destroying the user's edits behind a
     *  board-top banner they may never scroll to. */
    private fun post(
        serverId: String,
        taskId: Int,
        onSettled: (ok: Boolean, error: String?) -> Unit = { _, _ -> },
        block: suspend (CodegClient) -> Unit,
    ) {
        if (_ui.value.busyKey != null) {
            // 批v161.1 Bug B: the gate message used to ride onSettled only, and
            // every action caller passes the default no-op — the tap died with
            // zero on-screen feedback. Surface it like any other note (board
            // banner + detail sheet both render actionNote).
            _ui.update { it.copy(actionNote = "已有操作进行中，请稍候") }
            onSettled(false, "已有操作进行中，请稍候")
            return
        }
        // Reserve the slot synchronously (main thread): setting it inside the
        // coroutine would let a second tap slip past the gate before launch runs
        // and double-submit merge/cancel (v139 H2).
        _ui.update { it.copy(busyKey = key(serverId, taskId), actionError = null, actionNote = null) }
        viewModelScope.launch {
            val (ok, err) = runPost(serverId, taskId, block)
            onSettled(ok, err)
        }
    }

    /** One action round with the busy slot ALREADY reserved by the caller —
     *  [post] reserves it synchronously (v139 H2); [archiveAllDone] reserves it
     *  per iteration so its serial sweep stays exclusive against user actions. */
    private suspend fun runPost(
        serverId: String,
        taskId: Int,
        block: suspend (CodegClient) -> Unit,
    ): Pair<Boolean, String?> {
        detailEpoch += 1
        val epoch = detailEpoch
        var ok = true
        var err: String? = null
        try {
            val profile = deviceById(serverId) ?: error("设备不存在")
            val client = repository.client(profile) ?: error("Token 缺失")
            // 批v161.1 Bug B: bound the action round. OkHttp's 30s read timeout
            // covers a silent socket, but a trickling/half-open response could
            // still hold the global busy slot well past any sane wait — and a
            // held slot disables every zone button with no visible reason (the
            // reported 合并无反应). Past this ceiling the round is an error.
            try {
                withTimeout(ACTION_TIMEOUT_MS) { block(client) }
            } catch (e: TimeoutCancellationException) {
                throw ApiError.Transport("操作超时（45 秒无响应），请重试")
            }
            // The action moved the task (status/列/events); the cached detail
            // is a pre-action snapshot and loadDetail's existing!=null check
            // would keep serving it — drop it so the next expand refetches.
            _ui.update { state ->
                if (!state.details.containsKey(serverId)) state
                else state.copy(details = state.details + (serverId to (state.details[serverId].orEmpty() - taskId)))
            }
            // The user's action IS the newest round: bump the generation so a
            // poll that started mid-action can't invalidate this refresh
            // (the board would otherwise show the pre-action state for up to
            // one 25s poll cycle — inviting a second click).
            generation += 1
            fetchDevices(setOf(serverId))
            // 批v156: the action moved the task; report where it landed so the
            // screen can follow it (the board stays on the user's segment
            // otherwise — an empty 待办 with the card one tab away). Not found
            // (deleted/archived-away) → null → no jump.
            _actedLandedSegment.value = _ui.value.tasks
                .firstOrNull { it.serverId == serverId && it.task.id == taskId }
                ?.let { taskSegmentOf(it.task) }
        } catch (e: CancellationException) {
            ok = false
            throw e
        } catch (e: Exception) {
            ok = false
            err = (e as? ApiError)?.displayMessage() ?: e.message ?: "操作失败"
            _ui.update {
                it.copy(actionError = err)
            }
        } finally {
            _ui.update { it.copy(busyKey = null) }
        }
        return ok to err
    }

    /** 批v160 W5: the DONE column's 全部归档 — every finished row still on the
     *  board ([archiveAllCandidates]: status=="done", none already archived),
     *  one set_archived per row, serially. A user action in between (the busy
     *  slot) stops the sweep — it never fights the user for the board. */
    fun archiveAllDone() {
        if (_ui.value.busyKey != null) return
        val targets = archiveAllCandidates(board.value.segments[TaskSegment.DONE].orEmpty())
        if (targets.isEmpty()) return
        viewModelScope.launch {
            for (entry in targets) {
                if (_ui.value.busyKey != null) return@launch
                _ui.update {
                    it.copy(busyKey = key(entry.serverId, entry.task.id), actionError = null, actionNote = null)
                }
                runPost(entry.serverId, entry.task.id) { it.workTaskArchive(entry.task.id, true) }
            }
        }
    }

    /** M1 全部启动: one (device, folder) sweep target derived from the visible
     *  待办 rows. folderId null means the server-wide sweep — only offered when
     *  the board has no search query (a searched view shows a subset of the
     *  folder, but work_task_start_all claims the whole folder: showing a
     *  searched subset as the confirm list would understate the blast radius). */
    data class StartAllTarget(
        val serverId: String,
        val deviceName: String,
        val folderId: Int?,
        /** null = the device's whole board (no folder on these rows). */
        val folderName: String?,
        val visibleCount: Int,
    )

    fun startAllTargets(): List<StartAllTarget> {
        val state = _ui.value
        if (state.searchQuery.isNotBlank()) return emptyList()
        val deviceNames = state.deviceStates.associate { it.serverId to it.name }
        return board.value.segments[TaskSegment.TODO].orEmpty()
            .groupBy { it.serverId to it.task.folderId }
            .map { (group, entries) ->
                val (serverId, folderId) = group
                StartAllTarget(
                    serverId = serverId,
                    deviceName = deviceNames[serverId] ?: serverId,
                    folderId = folderId,
                    folderName = folderId?.let { fid -> state.folderNames[serverId]?.get(fid) },
                    visibleCount = entries.size,
                )
            }
            .sortedWith(compareBy({ it.deviceName }, { it.folderName ?: "" }))
    }

    /** M1 全部启动: one work_task_start_all per (device, folder) target, serially
     *  — same discipline as [archiveAllDone]: a user action between iterations
     *  stops the sweep, each round rides the global busy slot and its refresh
     *  (the started rows leave 待办 for 进行中 as their round lands). */
    fun startAll(targets: List<StartAllTarget>) {
        if (_ui.value.busyKey != null) return
        if (targets.isEmpty()) return
        viewModelScope.launch {
            var claimed = 0
            val okServers = mutableSetOf<String>()
            val failed = mutableListOf<String>()
            for (target in targets) {
                if (_ui.value.busyKey != null) return@launch
                _ui.update {
                    it.copy(busyKey = key(target.serverId, -1), actionError = null, actionNote = null)
                }
                var started: Int? = null
                val (ok, _) = runPost(target.serverId, -1) { client ->
                    started = client.workTaskStartAll(target.folderId)
                }
                val got = started
                if (ok && got != null) {
                    claimed += got
                    okServers += target.serverId
                } else {
                    failed += target.deviceName + (target.folderName?.let { " · $it" } ?: "")
                }
            }
            _ui.update {
                it.copy(actionNote = startAllResultNote(claimed, okServers.size, targets.size, failed))
            }
        }
    }

    fun start(serverId: String, taskId: Int) = post(serverId, taskId) { it.workTaskStart(taskId) }

    /** 批v155: 取消 takes an optional reason (web parity); the reason rides the
     *  server's event log. deleteWorktree matches web's checkbox (default off). */
    fun cancel(serverId: String, taskId: Int, deleteWorktree: Boolean = false, reason: String? = null) =
        post(serverId, taskId) { it.workTaskCancel(taskId, deleteWorktree, reason?.trim()?.takeIf(String::isNotEmpty)) }

    /** 批v155: 重试 takes an optional note (web parity). */
    fun retry(serverId: String, taskId: Int, note: String? = null) =
        post(serverId, taskId) { it.workTaskRetry(taskId, note?.trim()?.takeIf(String::isNotEmpty)) }

    /** 批v155: 重新排队 takes an optional note (web parity). */
    fun requeue(serverId: String, taskId: Int, note: String? = null) =
        post(serverId, taskId) { it.workTaskRequeue(taskId, note?.trim()?.takeIf(String::isNotEmpty)) }

    /** 批v155: 定时运行 (todo only, web parity) — ISO-8601 UTC string. */
    fun schedule(serverId: String, taskId: Int, scheduledAtIsoUtc: String?) =
        post(serverId, taskId) { it.workTaskSchedule(taskId, scheduledAtIsoUtc) }

    /** 批v155: 编辑任务 (todo/failed/canceled only, web parity) — the draft is a
     *  FULL overwrite server-side: folder_id/title/config all resent.
     *  [onSettled] (R2C#4): the sheet stays open on a rejection with the error
     *  inline — never close on an unverified send. */
    fun updateTask(
        serverId: String,
        taskId: Int,
        folderId: Int,
        title: String,
        config: kotlinx.serialization.json.JsonObject,
        onSettled: (ok: Boolean, error: String?) -> Unit = { _, _ -> },
    ) = post(serverId, taskId, onSettled) { it.workTaskUpdate(taskId, folderId, title, config) }

    fun merge(serverId: String, taskId: Int, message: String? = null) =
        post(serverId, taskId) { client ->
            // work_task_merge returns Json<bool>: true = queued behind the
            // folder's merge slot (same-project merges run one at a time).
            // Without this note the card just sits in 等你处理 and users click
            // again — the queue reply is the difference between "queued" and
            // "ignored".
            val queued = client.workTaskMerge(taskId, message)
            if (queued) {
                _ui.update { it.copy(actionNote = "已加入合并队列：同项目任务正在串行合并，稍后自动执行") }
            }
        }

    fun complete(serverId: String, taskId: Int) = post(serverId, taskId) { it.workTaskComplete(taskId) }

    /** 验收退回（review → agent 重做），feedback 必填（服务端 ReturnParams）。 */
    fun returnTask(serverId: String, taskId: Int, feedback: String) =
        post(serverId, taskId) { it.workTaskReturn(taskId, feedback) }

    /** 交付 PR（review → PR 出门，任务随 markDone 落完成）。 */
    fun deliverPr(serverId: String, taskId: Int) =
        post(serverId, taskId) { it.workTaskDeliverPr(taskId) }

    fun setArchived(serverId: String, taskId: Int, archived: Boolean) =
        post(serverId, taskId) { it.workTaskArchive(taskId, archived) }

    fun setDeferred(serverId: String, taskId: Int, deferred: Boolean) =
        post(serverId, taskId) { it.workTaskDefer(taskId, deferred) }

    fun deleteTask(serverId: String, taskId: Int) =
        post(serverId, taskId) { it.workTaskDelete(taskId) }

    // endregion

    // region DispatchFormHost (批v155 宿主 C) — the board's 新建任务 form.
    // Same shape as the ball's host B: loader-driven targets, ledger-guarded
    // send; the form is rendered in newTaskMode (task mode locked, no branch,
    // no schedule — 方案 A: base follows HEAD, 定时 lives on the card menu).

    override val settings: StateFlow<app.codeg.android.core.datastore.AppSettings> =
        appSettingsStore.settings.stateIn(
            viewModelScope, SharingStarted.Eagerly,
            app.codeg.android.core.datastore.AppSettings.INITIAL,
        )

    private val _formTargets = MutableStateFlow(app.codeg.android.core.data.DispatchTargets())
    override val targets: StateFlow<app.codeg.android.core.data.DispatchTargets> = _formTargets.asStateFlow()

    private val _formUi = MutableStateFlow(app.codeg.android.feature.dispatch.DispatchFormUi())
    override val formUi: StateFlow<app.codeg.android.feature.dispatch.DispatchFormUi> = _formUi.asStateFlow()

    fun clearFormError() {
        if (!_formUi.value.dispatching) {
            _formUi.value = _formUi.value.copy(error = null)
        }
    }

    // ── Fleet M5 任务模板 ───────────────────────────────────────────────────
    // The composer's chip-row source (per-device). A failed/old-server load
    // degrades to empty and the form hides the row — same degradation contract
    // as modelProviderEntries. Seq guard like formTargetSeq: a slow reply for a
    // switched-away device must not overwrite a newer one; the list is CLEARED
    // up front and tagged with its owner so the form never renders device A's
    // chips inside device B's composer (ids are per-device spaces — a stale
    // chip's ✕ could otherwise delete a lookalike row on the wrong device).

    private val _taskTemplates = MutableStateFlow<List<WorkTaskTemplateInfo>>(emptyList())
    override val taskTemplates: StateFlow<List<WorkTaskTemplateInfo>> = _taskTemplates.asStateFlow()

    private var templatesSeq = 0

    /** Device the current [taskTemplates] list belongs to (null = stale/cleared). */
    private var templatesServerId: String? = null

    override fun loadTaskTemplates(serverId: String) {
        val seq = ++templatesSeq
        templatesServerId = serverId
        _taskTemplates.value = emptyList()
        viewModelScope.launch {
            val list = runCatching {
                deviceById(serverId)?.let { repository.client(it)?.workTaskTemplateList() }
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                android.util.Log.w("TaskHubViewModel", "work_task_template_list failed: ${e.message}")
                null
            }
            if (seq == templatesSeq) _taskTemplates.value = list.orEmpty()
        }
    }

    override fun deleteTaskTemplate(serverId: String, id: Int) {
        viewModelScope.launch {
            val result = runCatching {
                val client = deviceById(serverId)?.let { repository.client(it) }
                    ?: error(appContext.getString(R.string.task_template_error_no_device))
                client.workTaskTemplateDelete(id)
            }
            val toast = result.fold(
                { appContext.getString(R.string.task_template_deleted) },
                { e ->
                    if (e is CancellationException) throw e
                    (e as? ApiError)?.displayMessage() ?: e.message
                        ?: appContext.getString(R.string.task_template_delete_failed)
                },
            )
            Toast.makeText(appContext, toast, Toast.LENGTH_SHORT).show()
            // Reload only when the form still shows THIS device's list — a reload
            // for a switched-away device would win the seq race and resurrect its
            // chips inside the new device's composer.
            if (serverId == templatesServerId) loadTaskTemplates(serverId)
        }
    }

    /** 任务卡长按「存为模板」: a snapshot of ONLY the composer's V1 prefill keys
     *  upserts by name onto the task's OWN device (server trims + validates;
     *  same name refreshes in place). Task-specific config fields (base_branch,
     *  mode_id, config_values…) are dropped — V1 restores none of them and a
     *  stale branch name must never ride a template into a future restore.
     *  Toast feedback; the composer's chips reload on next form open. */
    fun saveTaskTemplate(entry: TaskBoardEntry) {
        val task = entry.task
        val name = task.title.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            val result = runCatching {
                val client = deviceById(entry.serverId)?.let { repository.client(it) }
                    ?: error(appContext.getString(R.string.task_template_error_no_device))
                val snapshot = task.rawConfig
                    ?.filterKeys { it in TEMPLATE_CONFIG_KEYS }
                    ?.let { keys -> buildJsonObject { keys.forEach { (k, v) -> put(k, v) } } }
                    ?: JsonObject(emptyMap())
                client.workTaskTemplateSave(
                    name = name,
                    title = name,
                    config = snapshot,
                )
            }
            val toast = result.fold(
                { saved -> appContext.getString(R.string.task_template_saved, saved.name) },
                { e ->
                    if (e is CancellationException) throw e
                    (e as? ApiError)?.displayMessage() ?: e.message
                        ?: appContext.getString(R.string.task_template_save_failed)
                },
            )
            Toast.makeText(appContext, toast, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        /** The three config keys the composer's V1 prefill actually reads. */
        val TEMPLATE_CONFIG_KEYS = setOf("display_text", "prompt_blocks", "agent_type")
    }

    private var formTargetSeq = 0

    override fun loadTarget(serverId: String) {
        val seq = ++formTargetSeq
        viewModelScope.launch {
            _formTargets.value = _formTargets.value.copy(
                folders = emptyList(), agents = emptyList(), foldersLoading = true, error = null,
                loadedForServerId = serverId,
            )
            val loaded = try {
                targetLoader.load(serverId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Never a fresh DispatchTargets(): that would blank the device
                // chips (profiles must survive a failed target load — the
                // DispatchTargets loader contract, ui重构 §6.3).
                app.codeg.android.core.data.DispatchTargets(
                    profiles = _formTargets.value.profiles,
                    error = "无法连接目标设备",
                )
            }
            if (seq != formTargetSeq) return@launch
            _formTargets.value = _formTargets.value.copy(
                folders = loaded.folders,
                agents = loaded.agents,
                foldersLoading = loaded.foldersLoading,
                error = loaded.error,
                agentsError = loaded.agentsError,
                foldersError = loaded.foldersError,
                loadedForServerId = serverId,
            )
        }
    }

    private var formBranchSeq = 0

    override fun loadBranches(serverId: String, folder: app.codeg.android.core.model.FolderDetail) {
        // newTaskMode never shows the branch row; this stays implemented for the
        // interface but must never be called in that mode.
        val seq = ++formBranchSeq
        val key = "$serverId:${folder.path}"
        _formUi.value = _formUi.value.copy(branchesLoading = true, branchesError = false, branchFolderKey = key)
        viewModelScope.launch {
            val profile = deviceById(serverId)
            val client = profile?.let { repository.client(it) }
            val list = client?.let { c ->
                runCatching { c.gitListAllBranches(folder.path) }
                    .onFailure { e -> android.util.Log.w("TaskHubViewModel", "git_list_all_branches failed: ${e.message}") }
                    .getOrNull()
            }
            if (seq != formBranchSeq) return@launch
            _formUi.value = if (list == null) {
                _formUi.value.copy(branchesLoading = false, branchesError = true, branches = emptyList())
            } else {
                _formUi.value.copy(
                    branchesLoading = false,
                    branchesError = false,
                    branches = (list.local + list.remote).distinct(),
                )
            }
        }
    }

    override fun setTaskAutoStartDefault(value: Boolean) {
        viewModelScope.launch { appSettingsStore.setTaskAutoStartDefault(value) }
    }

    override suspend fun probeAgentOptions(
        serverId: String,
        agentType: app.codeg.android.core.model.AgentType,
        workingDir: String?,
        configValues: Map<String, String>,
    ): app.codeg.android.core.model.AgentOptionsSnapshot? =
        agentOptionsProber.probe(serverId, agentType, workingDir, configValues)

    override suspend fun modelProviderEntries(
        serverId: String,
        agentType: app.codeg.android.core.model.AgentType,
    ): List<app.codeg.android.core.model.ModelProviderInfo> =
        agentOptionsProber.modelProviderEntries(serverId, agentType)

    override fun dispatch(
        submit: app.codeg.android.feature.dispatch.DispatchFormSubmit,
        onDone: (app.codeg.android.core.datastore.DispatchRef) -> Unit,
    ) {
        if (_formUi.value.dispatching) return
        _formUi.value = _formUi.value.copy(dispatching = true, error = null)
        viewModelScope.launch {
            try {
                val outcome = dispatchService.send(
                    app.codeg.android.core.data.DispatchService.SendRequest(
                        ideaId = submit.ideaId,
                        serverId = submit.serverId,
                        folderId = submit.folderId,
                        folderName = submit.folderName,
                        title = submit.title,
                        description = submit.description,
                        agentTypeWire = submit.agentType?.wire,
                        modeId = submit.modeId,
                        configValues = submit.configValues,
                        baseBranch = null,
                        scheduleLocalMillis = submit.scheduleLocalMillis,
                        autoStart = submit.autoStart,
                        sessionMode = submit.sessionMode,
                        addToBoard = submit.addToBoard,
                        attachments = submit.attachments,
                    ),
                )
                when (outcome) {
                    is app.codeg.android.core.data.DispatchOutcome.Confirmed -> {
                        _formUi.value = _formUi.value.copy(dispatching = false)
                        refresh()
                        onDone(outcome.ref)
                    }
                    is app.codeg.android.core.data.DispatchOutcome.Unknown -> {
                        _formUi.value = _formUi.value.copy(
                            dispatching = false,
                            error = "创建结果未知，请到收件箱「派发记录」确认，勿直接重发",
                        )
                        // The screen renders the UNKNOWN receipt dialog from this
                        // ref (R1A: onDone must fire here too — the ledger ref is
                        // the only evidence of the ambiguous request).
                        onDone(outcome.ref)
                    }
                    is app.codeg.android.core.data.DispatchOutcome.HardFailed -> {
                        // No onDone: the error is surfaced in the form itself.
                        _formUi.value = _formUi.value.copy(dispatching = false, error = outcome.message)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _formUi.value = _formUi.value.copy(dispatching = false, error = e.message ?: "派发失败")
            }
        }
    }

    // endregion

    /** Expand a task: events + changed files load once; diff loads on demand. */
    fun loadDetail(serverId: String, taskId: Int, withDiff: Boolean = false) {
        val epoch = detailEpoch
        viewModelScope.launch {
            try {
                val client = deviceById(serverId)?.let { repository.client(it) }
                if (client == null) {
                    // Silent-return left an expanded card with a blank body and
                    // no way to retry — surface the failure on the card instead.
                    _ui.update { mutateDetail(it, serverId, taskId) { it.copy(loadError = "设备不可用，无法加载详情") } }
                    return@launch
                }
                val existing = _ui.value.details[serverId]?.get(taskId)
                if (withDiff && existing?.diffLoading != true && existing?.diff == null && existing?.diffError == null) {
                    _ui.update { setDiffLoading(it, serverId, taskId, true) }
                    val diffText = runCatching { client.workTaskDiff(taskId) }
                    // Same guard as the events branch: an action ran while the
                    // diff was in flight and dropped this detail — writing the
                    // stale diff back would resurrect the dropped snapshot.
                    if (epoch != detailEpoch) return@launch
                    diffText
                        .onSuccess { text -> _ui.update { setDiff(it, serverId, taskId, text, null) } }
                        .onFailure { e -> _ui.update { setDiff(it, serverId, taskId, null, e.message ?: "Diff 加载失败") } }
                }
                if (existing == null || existing.loadError != null) {
                    val events = runCatching { client.workTaskEvents(taskId) }.getOrNull()
                    val files = runCatching { client.workTaskChangedFiles(taskId) }.getOrNull()
                    // A null here means THAT load failed, not "no data" (the
                    // decoders return emptyList for empty) — cache the failure so
                    // the card can show it with a retry instead of a blank body.
                    // An action ran while this round was in flight: the dropped
                    // detail must stay dropped (the next expand refetches) —
                    // repopulating would show a pre-action snapshot forever.
                    if (epoch != detailEpoch) return@launch
                    if (events == null || files == null) {
                        _ui.update { mutateDetail(it, serverId, taskId) { it.copy(loadError = "详情加载失败，请重试") } }
                        return@launch
                    }
                    _ui.update { state ->
                        state.copy(
                            details = state.details +
                                (serverId to (state.details[serverId].orEmpty() +
                                    (taskId to (state.details[serverId]?.get(taskId) ?: TaskDetail()).copy(
                                        events = events,
                                        changedFiles = files,
                                        loadError = null,
                                    )))),
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // detail loads are best-effort; the board stays usable
            }
        }
    }

    private fun setDiffLoading(state: UiState, serverId: String, taskId: Int, loading: Boolean): UiState =
        mutateDetail(state, serverId, taskId) { it.copy(diffLoading = loading) }

    private fun setDiff(state: UiState, serverId: String, taskId: Int, text: String?, error: String?): UiState =
        mutateDetail(state, serverId, taskId) { it.copy(diff = text, diffError = error, diffLoading = false) }

    private inline fun mutateDetail(
        state: UiState,
        serverId: String,
        taskId: Int,
        transform: (TaskDetail) -> TaskDetail,
    ): UiState {
        val current = state.details[serverId]?.get(taskId) ?: TaskDetail()
        return state.copy(
            details = state.details +
                (serverId to (state.details[serverId].orEmpty() + (taskId to transform(current)))),
        )
    }

    /** Clears both the error and the non-error outcome note (merge queued…). */
    fun clearActionError() { _ui.update { it.copy(actionError = null, actionNote = null) } }
}

private inline fun MutableStateFlow<TaskHubViewModel.UiState>.update(transform: (TaskHubViewModel.UiState) -> TaskHubViewModel.UiState) {
    value = transform(value)
}

/** 批v161.1 Bug B: ceiling for one user action round (the mutation call only,
 *  not the post-action refreshes). Past it the busy slot is force-released and
 *  the wait becomes a visible timeout error instead of a silent global freeze. */
internal const val ACTION_TIMEOUT_MS = 45_000L

/** Column label used in [boardMoveNote] copy (matches the board's zh UI). */
internal fun segmentMoveLabel(seg: TaskSegment): String = when (seg) {
    TaskSegment.TODO -> "待办"
    TaskSegment.RUNNING -> "进行中"
    TaskSegment.ATTENTION -> "等你处理"
    TaskSegment.DONE -> "已完成"
}

/**
 * 批v161.1 Bug A: diff the previous slice against the fresh one and describe
 * the most recent externally-driven move, so a card that jumps across pager
 * pages (取消后落进已完成、被别的端归档/删除) never vanishes without a cue.
 * Returns null when nothing moved, the caller acted (busy — their own round
 * already follows the card via actedLandedSegment), or only brand-new cards
 * appeared. Deletions/archives-away count as a move ("已从看板移除").
 */
internal fun boardMoveNote(old: List<TaskBoardEntry>, new: List<TaskBoardEntry>, busy: Boolean): String? {
    if (busy || old.isEmpty()) return null
    val oldById = old.associateBy { it.task.id }
    val newIds = new.mapTo(HashSet()) { it.task.id }
    var note: String? = null
    for (entry in new) {
        val prev = oldById[entry.task.id] ?: continue
        val from = taskSegmentOf(prev.task)
        val to = taskSegmentOf(entry.task)
        if (from != to && from != null && to != null) {
            note = "「${entry.task.title}」已从「${segmentMoveLabel(from)}」移到「${segmentMoveLabel(to)}」"
        }
    }
    for (prev in old) {
        if (prev.task.id !in newIds) {
            note = "「${prev.task.title}」已从看板移除"
        }
    }
    return note
}

/** M1 全部启动 outcome line, file-level for a unit test (the sweep needs servers).
 *  [totalTargets] is informational — failures are listed by device·folder name. */
internal fun startAllResultNote(
    claimed: Int,
    okDevices: Int,
    totalTargets: Int,
    failed: List<String>,
): String {
    if (claimed == 0 && failed.isEmpty()) return "没有可启动的任务（已认领 0 个）"
    val deviceWord = if (okDevices == 1) "1 台设备" else "$okDevices 台设备"
    val base = "已启动 $claimed 个任务（$deviceWord）"
    return if (failed.isEmpty()) base
    else base + "；失败：" + failed.joinToString("、")
}
