package app.codeg.android.feature.sessions

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.R
import app.codeg.android.core.data.SessionProjectRepository
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
            ) { profile, all, _ -> profile to all }
                .collectLatest { (profile, all) ->
                    if (all) {
                        fetchAggregate()
                    } else {
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

    /** One fan-out over every visible device (10s per-device deadline, failures
     *  counted — the Activity feed's reviewed pattern). Not polled: event-driven
     *  via [ServerRepository.chatsRefreshTick] like the single-device branch. */
    private suspend fun fetchAggregate() {
        aggGeneration += 1
        val token = aggGeneration
        _agg.update { it.copy(isLoading = !it.hasLoaded, isRefreshing = it.hasLoaded, error = null) }
        val servers = repository.profiles.first().orEmpty().filter { it.visible }
        var failed = 0
        var succeeded = 0
        val fetched = coroutineScope {
            servers.map { server ->
                async {
                    val c = repository.client(server)
                        ?: run { failed += 1; return@async server to emptyList<ConversationSummary>() }
                    try {
                        val convs = withTimeoutOrNull(10_000) {
                            c.listConversations(sortBy = "updated").take(AGG_FETCH_LIMIT)
                        }
                        if (convs == null) {
                            failed += 1
                            server to emptyList()
                        } else {
                            // Only a complete list may prune — take(200) output must
                            // never read as "deleted" for rows past the cutoff (review #5).
                            projectRepo.pruneServerIfComplete(
                                server.id,
                                convs.map { it.id.toString() }.toSet(),
                                truncated = convs.size >= AGG_FETCH_LIMIT,
                            )
                            succeeded += 1
                            server to convs
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                        failed += 1
                        server to emptyList()
                    }
                }
            }.awaitAll()
        }
        if (token != aggGeneration) return
        _agg.update {
            it.copy(
                servers = fetched,
                failedDevices = failed,
                // "Reachable" means the fetch RETURNED — a token-holding but offline
                // device is a failure, not a silently-empty device (R2-2).
                reachableDevices = succeeded,
                hasLoaded = true,
                isLoading = false,
                isRefreshing = false,
                error = if (succeeded == 0 && failed > 0) {
                    appContext.getString(R.string.sessions_all_unreachable)
                } else {
                    null
                },
            )
        }
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
            val result = coroutineScope {
                val folders = async { active.listFolders() }
                val conversations = async { active.listConversations() }
                folders.await() to conversations.await()
            }
            if (token != fetchGeneration) return
            _ui.update {
                it.copy(
                    folders = result.first,
                    conversations = result.second,
                    hasLoaded = true,
                    isLoading = false,
                    isRefreshing = false,
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
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    val error: String? = null,
    val failedDevices: Int = 0,
    val reachableDevices: Int = 0,
) {
    val isBusy: Boolean get() = isLoading || isRefreshing

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
