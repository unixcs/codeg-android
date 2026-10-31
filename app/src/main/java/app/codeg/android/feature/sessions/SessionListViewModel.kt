package app.codeg.android.feature.sessions

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.R
import app.codeg.android.core.data.SessionProjectRepository
import app.codeg.android.core.data.DeviceLoad
import app.codeg.android.core.data.DeviceLoadStatus
import app.codeg.android.core.data.AGG_DEVICE_TIMEOUT_MS
import app.codeg.android.core.data.AGG_SKELETON_DELAY_MS
import app.codeg.android.core.data.SINGLE_DEVICE_TIMEOUT_MS
import app.codeg.android.core.data.aggregateDeviceSignature
import app.codeg.android.core.data.failedNames
import app.codeg.android.core.data.loadingNames
import app.codeg.android.core.data.okCount
import app.codeg.android.core.data.participating
import app.codeg.android.core.data.retrying
import app.codeg.android.core.data.retryableIds
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.AppSettingsStore
import app.codeg.android.core.datastore.ChatListDisplayStore
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.displayMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import javax.inject.Inject

/**
 * Drives the Chats (session list) screen: loads folders + conversations for the
 * selected server and keeps the full list in memory so grouping / pin toggles
 * stay instant. Faithful port of the iOS `SessionListViewModel` — the grouped
 * display (Pinned / per-folder / Other) is derived by [SessionGrouping].
 *
 * Rebinds automatically when the selected server changes (or is edited in place
 * to a new endpoint): a monotonic [fetchGeneration] guards against a slow fetch
 * clobbering a newer one.
 */
@HiltViewModel
class SessionListViewModel @Inject constructor(
    private val repository: ServerRepository,
    private val projectRepo: SessionProjectRepository,
    private val appSettingsStore: AppSettingsStore,
    @ApplicationContext private val appContext: Context,
    /** Per-server hidden/collapsed section prefs, read+written by the Chats screen. */
    val chatListDisplayStore: ChatListDisplayStore,
    /** Floating-ball session cycling (batch v132) — the Chats list registers here. */
    val focusHub: app.codeg.android.core.data.SessionFocusHub,
) : ViewModel() {

    private val _ui = MutableStateFlow(SessionListUiState())
    val ui: StateFlow<SessionListUiState> = _ui.asStateFlow()

    /**
     * The grouped, fully-sorted sections the Chats list renders (Pinned / per-folder
     * / Other). Derived from [_ui] but recomputed ONLY when the folders/conversations
     * actually change — a refresh-spinner or error toggle keeps the same list
     * references, so [distinctUntilChanged] short-circuits and nothing re-sorts. The
     * O(n·log n) grouping runs on [Dispatchers.Default], never on the main thread or
     * during composition, which is what keeps a very large session list smooth.
     */
    val sections: StateFlow<List<SessionSection>> =
        _ui
            .map { GroupingInput(it.folders, it.conversations) }
            .distinctUntilChanged()
            .map { buildSessionSections(it.folders, it.conversations) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Inputs of the aggregate branch's one collector. */
    private data class AggregateInput(
        val profile: ServerProfile?,
        val all: Boolean,
        val tick: Int,
        val profiles: List<ServerProfile>,
    )

    /** The only [_ui] fields the grouping depends on, so spinner/error toggles don't re-sort. */
    private data class GroupingInput(
        val folders: List<FolderDetail>,
        val conversations: List<ConversationSummary>,
    )

    private var client: CodegClient? = null
    private var loadedEndpoint: String? = null
    private var loadedProfileId: String? = null
    private var fetchGeneration = 0

    /** Set while aggregate is on and a device-list invalidation (delete/rename
     *  from anywhere) only reached the aggregate tick — the single-device list
     *  is stale until the next [onProfile]. Eats the R2-6 skip guard (R3-2a). */
    private var singleStale = false

    /** Last aggregate-branch inputs. The floating ball can switch devices while
     *  the aggregate list is on screen; that only changes the selection, not the
     *  fan-out, so it must not re-fetch (v134 — same rule as the activity feed).
     *  The device SET is different: adding, deleting, hiding or renaming a device
     *  changes what the fan-out should cover, so its signature is compared too —
     *  otherwise the 全部 list keeps ghost rows for a device that is gone. */
    private var lastAllMode: Boolean? = null
    private var lastAggTick: Int? = null
    private var lastDeviceSig: String? = null

    init {
        // Single mode machine (batch v132 review #1): the aggregate ("全部") branch
        // and the single-device branch share ONE collector so they can never write
        // each other's state — the pre-v132 two-collector shape (onProfile +
        // conversationsChanged) would fight a third aggregate collector.
        viewModelScope.launch {
            combine(
                repository.selectedProfile,
                repository.chatsAll,
                repository.chatsRefreshTick,
                repository.profiles,
            ) { profile, all, tick, profiles -> AggregateInput(profile, all, tick, profiles) }
                // Every DataStore write re-emits selectedProfile/profiles (draft
                // autosave, read marks, a select() from a handoff). Those emissions
                // carry IDENTICAL inputs — dropping them here keeps collectLatest from
                // cancelling a healthy in-flight fetch, which used to strand the busy
                // flags (and silently swallow a pull-to-refresh).
                .distinctUntilChanged()
                .collectLatest { input ->
                    val (profile, all, tick) = input
                    // The device SET/signature includes includeInAll (batch v137):
                    // skipping or restoring a device must re-fan-out immediately.
                    val deviceSig = input.profiles.aggregateDeviceSignature()
                    val modeChanged = lastAllMode != all
                    val tickChanged = tick != lastAggTick
                    val devicesChanged = deviceSig != lastDeviceSig
                    lastAllMode = all
                    lastAggTick = tick
                    lastDeviceSig = deviceSig
                    if (all) {
                        // A profile-only change (ball gesture) leaves the aggregate
                        // fan-out identical — refresh only on mode entry, an
                        // invalidation tick, a device-set change, or the first load.
                        if (modeChanged || tickChanged || devicesChanged || !_agg.value.hasLoaded) {
                            launchAggregate()
                        }
                    } else {
                        // Leaving the aggregate invalidates its in-flight round.
                        aggregateJob?.cancel()
                        // R2-6: aggregate→device flip with an UNCHANGED profile is a
                        // mode transition, not new data — skip the full refetch (the
                        // list is already on screen and hasLoaded). The skip holds
                        // only when NOTHING went stale meanwhile (R3-2a) and the
                        // endpoint still matches, so device edits rebind (R3-2b).
                        if (profile != null && !singleStale &&
                            profile.id == loadedProfileId &&
                            "${profile.id}|${profile.baseUrl}" == loadedEndpoint &&
                            _ui.value.hasLoaded
                        ) {
                            // collectLatest already cancelled any in-flight fetch;
                            // clear the flags or isBusy stays true forever and every
                            // later refresh/retry is refused.
                            _ui.update { it.copy(isLoading = false, isRefreshing = false) }
                            return@collectLatest
                        }
                        onProfile(profile)
                    }
                }
        }
        // Renames/pins/deletes from anywhere (detail screen, row menus) converge:
        // single-device refetches in place; the aggregate list re-fetches via the
        // chats tick so both branches share one invalidation channel.
        viewModelScope.launch {
            repository.conversationsChanged.collect {
                if (repository.chatsAll.value) {
                    singleStale = true
                    repository.requestChatsRefresh()
                } else if (client != null && !_ui.value.isBusy) {
                    fetch(isInitial = false)
                }
            }
        }
    }

    private suspend fun onProfile(profile: ServerProfile?) {
        loadedProfileId = profile?.id
        singleStale = false
        if (profile == null) {
            client = null
            loadedEndpoint = null
            _ui.value = SessionListUiState()
            return
        }
        val endpoint = "${profile.id}|${profile.baseUrl}"
        val changed = endpoint != loadedEndpoint
        loadedEndpoint = endpoint
        val resolved = repository.client(profile)
        client = resolved
        if (resolved == null) {
            _ui.update { it.copy(isLoading = false, error = appContext.getString(R.string.sessions_missing_token)) }
            return
        }
        fetch(isInitial = true, force = changed)
    }

    /** Pull-to-refresh / toolbar refresh — keeps the current list on screen. */
    fun refresh() {
        if (repository.chatsAll.value) {
            if (_agg.value.isBusy) return
            repository.requestChatsRefresh()
        } else {
            if (_ui.value.isBusy) return
            viewModelScope.launch { fetch(isInitial = false) }
        }
    }

    // ---- Aggregate ("全部") branch — its own state space (review #1) ----

    private val _agg = MutableStateFlow(AggregateUiState())
    val aggregateUi: StateFlow<AggregateUiState> = _agg.asStateFlow()

    private var aggGeneration = 0

    /**
     * The in-flight aggregate round. Deliberately NOT a child of the input
     * collector: `collectLatest` must not cancel a healthy round just because the
     * selection changed (tapping a 全部 row selects its owning device while staying
     * in All mode). Only a newer round, or leaving the aggregate, cancels it.
     */
    private var aggregateJob: Job? = null

    private fun launchAggregate(retryOnly: Boolean = false) {
        aggregateJob?.cancel()
        aggregateJob = viewModelScope.launch {
            try {
                fetchAggregate(retryOnly)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A DataStore read failure must not take the app down with the
                // retry/refresh coroutine (batch v137 review #3).
                _agg.update { it.copy(isLoading = false, isRefreshing = false) }
            }
        }
    }

    /**
     * Grouped aggregate display. The project filter is applied HERE (not in the
     * fetch) so switching projects never refetches — [projectRepo.activeMemberKeys]
     * is gated on [ServerRepository.chatsAll] so a stale filter can never leak
     * into another surface (review #3). Grouping runs on [Dispatchers.Default].
     */
    val aggregateSections: StateFlow<List<AggregateSection>> =
        combine(
            _agg,
            repository.chatsAll,
            projectRepo.activeMemberKeys,
            appSettingsStore.sessionReadMarks,
        ) { agg, all, memberKeys, marks ->
            buildAggregateSessionSections(agg.servers, memberKeys.takeIf { all }, marks)
        }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** One progressive fan-out over every participating device (batch v137: 3s
     *  per-device deadline, results committed the moment they settle instead of
     *  `awaitAll`). Event-driven via [ServerRepository.chatsRefreshTick] like the
     *  single-device branch, not polled.
     *
     *  [retryOnly] re-asks just the failed devices — the banner's retry keeps the
     *  good rows on screen. */
    private suspend fun fetchAggregate(retryOnly: Boolean = false) {
        aggGeneration += 1
        val token = aggGeneration
        val allProfiles = repository.profiles.first().orEmpty()
        val allServers = allProfiles.filter { it.visible && it.includeInAll }
        // `profiles.first()` suspends; a newer round may have started and finished
        // meanwhile. Seeding after that would resurrect the older round's flags.
        if (token != aggGeneration) return
        val retryIds = if (retryOnly) {
            // Everything that did not settle OK — a device still LOADING when the
            // user hits retry would otherwise be cancelled with the old round and
            // never re-asked (Chats has no poll to recover it).
            _agg.value.deviceStates.retryableIds()
        } else {
            emptySet()
        }
        val servers = if (retryOnly) allServers.filter { it.id in retryIds } else allServers

        _agg.update { state ->
            // Devices that left the aggregate (skipped/hidden/deleted) drop their
            // rows; a failed one keeps its last known rows (see the commit below).
            val allowed = allServers.map { it.id }.toSet()
            val seeded = if (retryOnly) {
                state.deviceStates.participating(allowed).retrying(retryIds)
            } else {
                servers.map { DeviceLoad(it.id, it.name, DeviceLoadStatus.LOADING) }
            }
            state.copy(
                deviceStates = seeded,
                servers = state.servers.filter { (p, _) -> p.id in allowed },
                allSkipped = allProfiles.any { it.visible } && allServers.isEmpty(),
                isLoading = !state.hasLoaded,
                isRefreshing = state.hasLoaded,
                error = null,
            )
        }

        // Skeleton fallback: no device answered within the delay → stop the
        // full-screen spinner and let the list frame (with placeholders) show.
        val skeleton = viewModelScope.launch {
            delay(AGG_SKELETON_DELAY_MS)
            if (token == aggGeneration) {
                _agg.update {
                    if (!it.hasLoaded) {
                        // The round is still running: keep the busy flag set so
                        // pull-to-refresh/retry cannot start a competing round and
                        // abandon devices still inside their 3s window.
                        it.copy(isLoading = false, hasLoaded = true, isRefreshing = true)
                    } else {
                        it
                    }
                }
            }
        }

        try {
            coroutineScope {
                servers.forEach { server ->
                    launch {
                        val convs: List<ConversationSummary>? = try {
                            repository.client(server)?.let { c ->
                                withTimeoutOrNull(AGG_DEVICE_TIMEOUT_MS) {
                                    c.listConversations(sortBy = "updated").take(AGG_FETCH_LIMIT)
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                            null
                        }
                        if (token != aggGeneration) return@launch
                        if (convs != null) {
                            // Only a complete list may prune — take(200) output must
                            // never read as "deleted" for rows past the cutoff (review #5).
                            // Best-effort cleanup: a bad members blob must not cancel
                            // this device's sibling fetches (batch v137 review #3).
                            try {
                                projectRepo.pruneServerIfComplete(
                                    server.id,
                                    convs.map { it.id.toString() }.toSet(),
                                    truncated = convs.size >= AGG_FETCH_LIMIT,
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                                // ignore — the feed itself is still valid
                            }
                        }
                        _agg.update { state ->
                            val byId = state.servers.associateBy { it.first.id }.toMutableMap()
                            if (convs != null) byId[server.id] = server to convs
                            state.copy(
                                // Re-pair with the CURRENT profile so a rename lands
                                // in the group header without waiting for that device
                                // to answer again (batch v137 review #3).
                                servers = allServers.mapNotNull { p ->
                                    byId[p.id]?.second?.let { p to it }
                                },
                                deviceStates = state.deviceStates.map { load ->
                                    if (load.serverId != server.id) {
                                        load
                                    } else if (convs == null) {
                                        load.copy(
                                            status = DeviceLoadStatus.FAILED,
                                            error = appContext.getString(R.string.device_unreachable_named, server.name),
                                        )
                                    } else {
                                        load.copy(status = DeviceLoadStatus.OK, error = null)
                                    }
                                },
                                hasLoaded = true,
                                isLoading = false,
                            )
                        }
                    }
                }
            }
        } finally {
            // A cancelled round (newer round / leaving the aggregate) never reaches
            // the line after coroutineScope — the skeleton must not outlive it.
            skeleton.cancel()
        }
        if (token != aggGeneration) return
        _agg.update { state ->
            val ok = state.deviceStates.okCount()
            val failedNames = state.deviceStates.failedNames()
            state.copy(
                isLoading = false,
                isRefreshing = false,
                hasLoaded = true,
                error = if (ok == 0 && failedNames.isNotEmpty()) {
                    appContext.getString(
                        R.string.activity_devices_unreachable,
                        failedNames.joinToString(", "),
                    )
                } else {
                    null
                },
            )
        }
    }

    /** Banner retry: refetch only the failed devices, keeping good rows visible. */
    fun retryFailedDevices() {
        if (!repository.chatsAll.value) {
            refresh()
            return
        }
        if (_agg.value.deviceStates.none { it.status != DeviceLoadStatus.OK }) {
            refresh()
            return
        }
        // No "already busy" gate: a retry must be able to rescue devices still
        // inside their window, and launchAggregate supersedes the old round.
        launchAggregate(retryOnly = true)
    }

    /** Optimistic pin on an aggregate row, persisted to the OWNING device —
     *  conversation ids are server-local autoincrements and collide (review #4). */
    fun setPinnedAggregate(row: AggregateRow, pinned: Boolean) {
        viewModelScope.launch {
            val c = clientFor(row.serverId) ?: return@launch
            val originalPinnedAt = row.conversation.pinnedAt
            _agg.update { it.updateServer(row.serverId) { convs -> convs.map {
                if (it.id == row.conversation.id) it.copy(pinnedAt = if (pinned) Instant.now() else null) else it
            } } }
            try {
                c.setPinned(row.conversation.id, pinned)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Revert just THIS row (R1-5): restoring a captured whole-list
                // snapshot would clobber a concurrent fetch that landed meanwhile.
                _agg.update { it.updateServer(row.serverId) { convs -> convs.map {
                    if (it.id == row.conversation.id) it.copy(pinnedAt = originalPinnedAt) else it
                } } }
                _agg.update { it.copy(error = e.displayMessage()) }
            }
        }
    }

    /** Optimistic delete on an aggregate row + project-membership cleanup. */
    fun deleteAggregate(row: AggregateRow) {
        viewModelScope.launch {
            val c = clientFor(row.serverId) ?: return@launch
            _agg.update { it.updateServer(row.serverId) { convs -> convs.filterNot { it.id == row.conversation.id } } }
            try {
                c.deleteConversation(row.conversation.id)
                projectRepo.removeRef(row.serverId, row.conversation.id)
                repository.notifyConversationsChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Re-insert just this row (R1-5) — order is irrelevant, the section
                // builders re-sort; the next fetch is the source of truth anyway.
                _agg.update { it.updateServer(row.serverId) { convs ->
                    if (convs.any { it.id == row.conversation.id }) convs else convs + row.conversation
                } }
                _agg.update { it.copy(error = e.displayMessage()) }
            }
        }
    }

    /** Rename an aggregate row on its OWNING device (ids are server-local, review #4),
     *  then converge: patch the row in place and notify — the notification is what
     *  drives singleStale so the single-device list refetches on the next visit. */
    fun renameAggregate(row: AggregateRow, title: String) {
        viewModelScope.launch {
            val c = clientFor(row.serverId)
            if (c == null) {
                _agg.update { it.copy(error = appContext.getString(R.string.sessions_missing_token)) }
                return@launch
            }
            try {
                c.renameConversation(row.conversation.id, title)
                _agg.update { it.updateServer(row.serverId) { convs -> convs.map {
                    if (it.id == row.conversation.id) it.copy(title = title) else it
                } } }
                repository.notifyConversationsChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _agg.update { it.copy(error = e.displayMessage()) }
            }
        }
    }

    fun dismissAggregateError() = _agg.update { it.copy(error = null) }

    /** Whether the Chats tab currently shows the cross-device aggregate list. */
    val chatsAll: StateFlow<Boolean> = repository.chatsAll

    // ---- Project passthroughs for the sheets (hoisted, one instance per screen) ----

    val sessionProjects: StateFlow<List<app.codeg.android.core.datastore.SessionProject>> =
        projectRepo.projects

    val projectMembers: StateFlow<Map<String, List<app.codeg.android.core.datastore.SessionRef>>> =
        projectRepo.members

    val activeProjectId: StateFlow<String?> = projectRepo.activeProjectId

    fun toggleProjectMembership(projectId: String, ref: app.codeg.android.core.datastore.SessionRef, member: Boolean) {
        viewModelScope.launch { projectRepo.setMembership(projectId, ref, member) }
    }

    fun createProject(name: String, onCreated: (app.codeg.android.core.datastore.SessionProject) -> Unit = {}) {
        viewModelScope.launch { onCreated(projectRepo.create(name)) }
    }

    private suspend fun clientFor(serverId: String): CodegClient? {
        val profile = repository.profiles.first().firstOrNull { it.id == serverId } ?: return null
        return repository.client(profile)
    }

    private fun AggregateUiState.updateServer(
        serverId: String,
        transform: (List<ConversationSummary>) -> List<ConversationSummary>,
    ): AggregateUiState = copy(
        servers = servers.map { (p, convs) -> if (p.id == serverId) p to transform(convs) else p to convs },
    )

    private suspend fun fetch(isInitial: Boolean, force: Boolean = false) {
        val active = client ?: return
        fetchGeneration += 1
        val token = fetchGeneration
        if (force) {
            _ui.update { it.copy(folders = emptyList(), conversations = emptyList(), hasLoaded = false) }
        }
        _ui.update {
            if (isInitial) it.copy(isLoading = true, error = null)
            else it.copy(isRefreshing = true, error = null)
        }
        try {
            // Single-device deadline (batch v137): a powered-off device used to spin
            // for the full 30s OkHttp timeout with no way out. 4s, then a retryable
            // error — the previous list (if any) stays on screen.
            val result = withTimeoutOrNull(SINGLE_DEVICE_TIMEOUT_MS) {
                coroutineScope {
                    val folders = async { active.listFolders() }
                    val conversations = async { active.listConversations() }
                    folders.await() to conversations.await()
                }
            }
            if (token != fetchGeneration) return
            if (result == null) {
                val name = repository.profiles.first().firstOrNull { it.id == loadedProfileId }?.name.orEmpty()
                _ui.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        hasLoaded = true,
                        error = appContext.getString(R.string.device_unreachable_named, name),
                    )
                }
                return
            }
            _ui.update {
                it.copy(
                    folders = result.first,
                    conversations = result.second,
                    hasLoaded = true,
                    isLoading = false,
                    isRefreshing = false,
                    error = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (token != fetchGeneration) return
            _ui.update { it.copy(isLoading = false, isRefreshing = false, error = e.displayMessage()) }
        }
    }

    /**
     * Optimistically pin/unpin, then persist. On failure the local change reverts
     * and an error is surfaced. The optimistic `pinnedAt` is a local stand-in for
     * ordering; the next refresh replaces it with the server's value.
     */
    fun setPinned(conversation: ConversationSummary, pinned: Boolean) {
        val active = client ?: return
        val previous = _ui.value.conversations
        _ui.update { state ->
            state.copy(
                conversations = state.conversations.map {
                    if (it.id == conversation.id) it.copy(pinnedAt = if (pinned) Instant.now() else null) else it
                },
            )
        }
        viewModelScope.launch {
            try {
                active.setPinned(conversation.id, pinned)
            } catch (e: Exception) {
                _ui.update { it.copy(conversations = previous, error = e.displayMessage()) }
            }
        }
    }

    fun dismissError() = _ui.update { it.copy(error = null) }

    /**
     * Delete a conversation from the Chats list. The row leaves the list
     * immediately and the server is told in the background — the list already
     * collects [ServerRepository.conversationsChanged] to converge. On failure
     * the row is restored and the error surfaced.
     */
    fun delete(conversation: ConversationSummary) {
        val active = client ?: return
        val previous = _ui.value.conversations
        _ui.update { state ->
            state.copy(conversations = state.conversations.filterNot { it.id == conversation.id })
        }
        viewModelScope.launch {
            try {
                active.deleteConversation(conversation.id)
                loadedProfileId?.let { projectRepo.removeRef(it, conversation.id) }
                repository.notifyConversationsChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(conversations = previous, error = e.displayMessage()) }
            }
        }
    }

    /**
     * Rename a conversation from the Chats list. Awaits the server, patches the row's
     * title in place (trimmedTitle is derived), and notifies so the aggregate list (if
     * another device's view is showing) converges too. On failure the error surfaces;
     * nothing was changed locally beforehand.
     */
    fun rename(conversation: ConversationSummary, title: String) {
        val active = client
        if (active == null) {
            _ui.update { it.copy(error = appContext.getString(R.string.sessions_missing_token)) }
            return
        }
        viewModelScope.launch {
            try {
                active.renameConversation(conversation.id, title)
                _ui.update { state -> state.copy(conversations = state.conversations.map {
                    if (it.id == conversation.id) it.copy(title = title) else it
                }) }
                repository.notifyConversationsChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.displayMessage()) }
            }
        }
    }
}

/** Raw list state; the grouped display is derived by [SessionGrouping]. */
data class SessionListUiState(
    val folders: List<FolderDetail> = emptyList(),
    val conversations: List<ConversationSummary> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    val error: String? = null,
) {
    val isBusy: Boolean get() = isLoading || isRefreshing

    /**
     * Empty ⟺ no conversations: every conversation lands in some section (Pinned, a
     * folder group, or Other), so folders-but-no-sessions correctly shows the empty
     * state instead of a wall of empty folder headers (matches iOS). Reads raw [_ui]
     * fields, so the decision stays atomic with the data and never flickers against
     * the separately-derived [SessionListViewModel.sections] flow.
     */
    val isEmpty: Boolean get() = conversations.isEmpty()
}

/** Aggregate ("全部") list state; the grouped display derives via [aggregateSections]. */
data class AggregateUiState(
    val servers: List<Pair<ServerProfile, List<ConversationSummary>>> = emptyList(),
    /** Per-device progress for this round (batch v137) — drives names + retry. */
    val deviceStates: List<DeviceLoad> = emptyList(),
    /** Every visible device is opted out of All ("skip in All") — the empty
     *  aggregate must say so instead of claiming there are no sessions. */
    val allSkipped: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    val error: String? = null,
) {
    val isBusy: Boolean get() = isLoading || isRefreshing

    /** Devices that could not be fetched this round. */
    val failedDevices: Int get() = deviceStates.count { it.status == DeviceLoadStatus.FAILED }

    /** Names for the failure banner ("win 未响应"), in feed order. */
    val failedDeviceNames: List<String> get() = deviceStates.failedNames()

    /** Devices still connecting — the empty state must wait for these, otherwise
     *  the 600ms skeleton phase reads as "no sessions anywhere" (batch v137). */
    val pendingDeviceNames: List<String> get() = deviceStates.loadingNames()

    /** Unfiltered row total — separates "no sessions anywhere" from "project has
     *  no matching sessions" for the empty states (review #10). */
    val totalRows: Int get() = servers.sumOf { it.second.size }
}

private const val AGG_FETCH_LIMIT = 200

/** One folder and the (non-pinned) conversations shown under its group. */
data class FolderGroup(
    val folder: FolderDetail,
    val conversations: List<ConversationSummary>,
)

/**
 * Pure grouping/filtering of a session list, ported 1:1 from the iOS
 * `SessionListViewModel` derived-data accessors. Pinned conversations appear
 * only in the Pinned group; each folder group lists its non-pinned
 * conversations newest-first.
 */
object SessionGrouping {

    fun sortedFolders(folders: List<FolderDetail>): List<FolderDetail> =
        folders.sortedWith(
            compareBy({ it.sortOrder }, { it.name.lowercase() }),
        )

    /** `folderId -> name` for the dim folder tag shown on cross-folder rows. */
    fun folderNames(folders: List<FolderDetail>): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        for (folder in folders) out.putIfAbsent(folder.id, folder.name)
        return out
    }

    /** Pinned conversations across all folders, most-recently-pinned first. */
    fun pinned(conversations: List<ConversationSummary>, search: String = ""): List<ConversationSummary> =
        matching(conversations.filter { it.isPinned }, search)
            .sortedByDescending { it.pinnedAt ?: Instant.MIN }

    /**
     * One group per folder (in [sortedFolders] order) holding that folder's
     * non-pinned conversations, newest-first. With no search every folder is
     * included; when searching, folders with no match are dropped.
     */
    fun folderGroups(
        folders: List<FolderDetail>,
        conversations: List<ConversationSummary>,
        search: String = "",
    ): List<FolderGroup> {
        val searching = search.trim().isNotEmpty()
        val unpinnedByFolder = conversations.filter { !it.isPinned }.groupBy { it.folderId }
        return sortedFolders(folders).mapNotNull { folder ->
            val convs = matching(unpinnedByFolder[folder.id] ?: emptyList(), search)
                .sortedByDescending { it.updatedAt }
            if (searching && convs.isEmpty()) null else FolderGroup(folder, convs)
        }
    }

    /** Non-pinned conversations whose folder isn't in the folder list (orphans). */
    fun ungrouped(
        folders: List<FolderDetail>,
        conversations: List<ConversationSummary>,
        search: String = "",
    ): List<ConversationSummary> {
        val known = folders.map { it.id }.toSet()
        return matching(
            conversations.filter { !it.isPinned && it.folderId !in known },
            search,
        ).sortedByDescending { it.updatedAt }
    }

    private fun matching(convs: List<ConversationSummary>, search: String): List<ConversationSummary> {
        val query = search.trim()
        if (query.isEmpty()) return convs
        return convs.filter { conv ->
            (conv.trimmedTitle?.contains(query, ignoreCase = true) == true) ||
                (conv.model?.contains(query, ignoreCase = true) == true)
        }
    }
}
