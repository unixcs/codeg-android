package app.codeg.android.feature.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.R
import app.codeg.android.core.data.SessionFocusHub
import app.codeg.android.core.data.SessionProjectRepository
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.AppSettingsStore
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.datastore.SessionRef
import app.codeg.android.core.datastore.isSessionUnread
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.displayMessage
import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
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
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/**
 * The Activity tab: a 25-second poll loop deriving the "Running" set (live
 * conversations) and "Last 24 Hours" set. Port of the iOS `ActivityModel`.
 *
 * One polling entry drives both display modes — the plain per-device feed and the
 * "All devices" aggregate ([ServerRepository.activityAll]). `combine(selectedProfile,
 * activityAll, refreshTick)` restarts the loop whenever any of the three changes, so
 * the two modes can never fight over the collector (a plain `onProfile` collector plus
 * a separate aggregate collector would reset each other). Rows carry the owning
 * server's id/name so the aggregate feed can label and hand-off correctly (folderId
 * is only unique within one server, so folder names are resolved per device).
 *
 * Unread marks (batch v121): the device-local read marks combine into [sections] so
 * each Last-24h row carries its unread flag precomputed. `distinctUntilChanged` keeps
 * the shared DataStore's unrelated writes (e.g. draft saves) from re-running the
 * section build.
 */
@HiltViewModel
class ActivityViewModel @Inject constructor(
    private val repository: ServerRepository,
    settingsStore: AppSettingsStore,
    private val projectRepo: SessionProjectRepository,
    /** Floating-ball session cycling (batch v132) — the aggregate list registers here. */
    val focusHub: SessionFocusHub,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _ui = MutableStateFlow(ActivityUiState())
    val ui: StateFlow<ActivityUiState> = _ui.asStateFlow()

    /** App foreground signal (batch v130) — gates the 25s poll while backgrounded. */
    private val foreground = (appContext as app.codeg.android.CodegApplication).appForeground

    /**
     * The Running / Last-24h sections the feed renders, sorted and with the folder tag
     * pre-resolved — computed on [Dispatchers.Default] and recomputed only when the
     * conversations/folder names change (not on the 25s poll's spinner toggle). Keeps
     * the filter+sort off the main thread and out of composition, like the Chats list.
     *
     * Batch v132: in aggregate mode the active project's membership narrows the rows
     * HERE (never in the fetch), so switching projects is instant and the filter is
     * gated on [ServerRepository.activityAll] — a single-device feed can never be
     * silently filtered by a leftover project selection (review #3).
     */
    val sections: StateFlow<List<ActivitySection>> =
        combine(
            _ui.map { it.conversations }.distinctUntilChanged(),
            settingsStore.sessionReadMarks,
            repository.activityAll,
            projectRepo.activeMemberKeys,
        ) { conversations, readMarks, all, memberKeys ->
            val narrowed = memberKeys
                .takeIf { all }
                ?.let { keys -> conversations.filter { row -> row.refKey in keys } }
                ?: conversations
            buildActivitySections(narrowed, readMarks)
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var pollJob: Job? = null

    /** Folder names per server — cache survives aggregate↔single switches so each
     *  25s poll doesn't re-list folders on every device (folderId is server-local). */
    private val folderCache = HashMap<String, Map<Int, String>>()

    init {
        viewModelScope.launch {
            combine(
                repository.selectedProfile,
                repository.activityAll,
                repository.activityRefreshTick,
                ::Triple,
            ).collectLatest { (profile, all, tick) ->
                // First emission for a *mode* change resets the feed; a tick-only bump
                // (pull-to-refresh while already in this mode) refreshes in place so
                // the loaded list stays visible and the spinner runs its course.
                val isReset = profile?.id != lastProfileId || all != lastAll
                lastProfileId = profile?.id
                lastAll = all
                onMode(profile, all, reset = isReset)
            }
        }
    }

    private var lastProfileId: String? = null
    private var lastAll: Boolean = false

    /** One-shot manual-fetch flag consumed by the next poll iteration (batch v130). */
    @Volatile private var manualOnce = false

    private suspend fun onMode(profile: ServerProfile?, all: Boolean, reset: Boolean) {
        pollJob?.cancel()
        if (profile == null) {
            _ui.value = ActivityUiState()
            return
        }
        if (reset) {
            _ui.value = ActivityUiState(loading = true)
        }
        pollJob = viewModelScope.launch {
            // Foreground gate (batch v130): viewModelScope survives backgrounding, so this
            // loop used to wake the radio against every device for the app's whole
            // background life. Pause while backgrounded; on resume the bumped tick makes
            // the mode-compare refetch immediately.
            while (true) {
                // Foreground gate (batch v130): on resume the loop resumes and fetches
                // immediately, which is the post-background refetch.
                if (!foreground.value) foreground.first { it }
                val manual = manualOnce
                manualOnce = false
                fetch(profile, all, manual = manual)
                delay(25_000)
            }
        }
    }

    fun refresh() {
        if (_ui.value.refreshing) return
        restartPoll(manual = true)
    }

    /** Kill the current poll loop and start a fresh one for the current mode. A
     *  cancelAndJoin + one-shot fetch (the old refresh/delete) left the recurring
     *  25s poll dead until the next mode switch — the feed silently went stale. */
    private fun restartPoll(manual: Boolean) {
        manualOnce = manual
        viewModelScope.launch {
            pollJob?.cancelAndJoin()
            repository.selectedProfile.first()?.let { profile ->
                onMode(profile, repository.activityAll.value, reset = false)
            }
        }
    }

    /**
     * Delete a conversation from the feed. Resolves the owning device from [serverId]
     * (the aggregate feed carries rows from every device), removes the row locally
     * first — the 25s poll and [app.codeg.android.core.data.ServerRepository.notifyConversationsChanged]
     * (only collected by the Chats tab) won't refresh this feed — then persists; a
     * failure restores the row and surfaces the error.
     */
    fun delete(row: ActivityRow) {
        viewModelScope.launch(Dispatchers.Main) {
            val profile = repository.profiles.first().orEmpty().firstOrNull { it.id == row.serverId }
            val client = profile?.let { repository.client(it) }
            if (client == null) {
                _ui.update { it.copy(error = appContext.getString(R.string.sessions_missing_token)) }
                return@launch
            }
            val previous = _ui.value.conversations
            // Scope the optimistic removal to the OWNING device: ids are server-local
            // autoincrements and the same numeric id exists on other devices
            // (pre-existing bug found in batch v132 review #4).
            _ui.update { state ->
                state.copy(
                    conversations = state.conversations.filterNot {
                        it.conversation.id == row.conversation.id && it.serverId == row.serverId
                    },
                )
            }
            try {
                client.deleteConversation(row.conversation.id)
                projectRepo.removeRef(row.serverId, row.conversation.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(conversations = previous, error = e.displayMessage()) }
                restartPoll(manual = false)
                return@launch
            }
            // Re-bucket Running/Recent immediately with a fresh (restarted) poll.
            restartPoll(manual = true)
        }
    }

    /** Rename from the activity feed (batch v133). Awaits the owning device, patches
     *  the row in place, then restarts the poll so the sections re-sort by the new
     *  ordering data — rename has no optimistic local state to revert. */
    fun rename(row: ActivityRow, title: String) {
        viewModelScope.launch(Dispatchers.Main) {
            val profile = repository.profiles.first().orEmpty().firstOrNull { it.id == row.serverId }
            val client = profile?.let { repository.client(it) }
            if (client == null) {
                _ui.update { it.copy(error = appContext.getString(R.string.sessions_missing_token)) }
                return@launch
            }
            try {
                client.renameConversation(row.conversation.id, title)
                _ui.update { state ->
                    state.copy(
                        conversations = state.conversations.map {
                            if (it.conversation.id == row.conversation.id && it.serverId == row.serverId) {
                                it.copy(conversation = it.conversation.copy(title = title))
                            } else {
                                it
                            }
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.displayMessage()) }
                return@launch
            }
            restartPoll(manual = false)
        }
    }

    // ---- Project passthroughs for the hoisted AddToProjectSheet (batch v132) ----

    val sessionProjects = projectRepo.projects
    val projectMembers = projectRepo.members
    val activeProjectId = projectRepo.activeProjectId

    fun toggleProjectMembership(projectId: String, ref: SessionRef, member: Boolean) {
        viewModelScope.launch { projectRepo.setMembership(projectId, ref, member) }
    }

    fun createProject(name: String, onCreated: (app.codeg.android.core.datastore.SessionProject) -> Unit = {}) {
        viewModelScope.launch { onCreated(projectRepo.create(name)) }
    }

    /** Refresh one device's feed (also resolves its folder names, once). */
    private suspend fun fetchOne(
        c: CodegClient,
        serverId: String,
    ): List<ActivityRow> {
        val conversations = c.listConversations(sortBy = "updated").take(200)
        val folders = folderCache.getOrPut(serverId) {
            try {
                c.listFolders().associate { f -> f.id to f.name }
            } catch (e: CancellationException) {
                // A withTimeoutOrNull(10s) cancel must propagate — swallowing it here
                // completed "normally" and the deadline never triggered.
                throw e
            } catch (_: Exception) {
                emptyMap()
            }
        }
        return conversations.map { conv -> ActivityRow(conv, serverId, "", folders[conv.folderId]) }
    }

    private suspend fun fetch(profile: ServerProfile, all: Boolean, manual: Boolean = false) {
        if (manual) _ui.update { it.copy(refreshing = true) }
        try {
            if (!all) {
                val c = repository.client(profile)
                if (c == null) {
                    _ui.update {
                        it.copy(loading = false, refreshing = false, hasLoaded = true, error = "No token for ${profile.name}")
                    }
                    return
                }
                val rows = fetchOne(c, profile.id).map { it.copy(serverName = profile.name) }
                _ui.update {
                    it.copy(
                        conversations = rows,
                        loading = false, refreshing = false, hasLoaded = true, error = null,
                        lastRefreshed = Instant.now(),
                    )
                }
            } else {
                // Aggregate mode shows the day-to-day subset only: hidden devices
                // contribute nothing (their data stays intact; the Servers screen
                // can bring them back).
                val servers = repository.profiles.first().orEmpty().filter { it.visible }
                // Per-device scopes so folderId collisions across servers can't cross-
                // contaminate; each device resolves its own folder names. A device that
                // fails (offline / no token) is *counted*, never laundered into an
                // empty feed — dell powering off must not read as "no activity".
                var failed = 0
                var reachable = 0
                val rows: List<ActivityRow> = coroutineScope {
                    servers.map { server ->
                        async {
                            val client = repository.client(server)
                            if (client == null) {
                                failed += 1
                                return@async emptyList<ActivityRow>()
                            }
                            reachable += 1
                            try {
                                // Per-device deadline (batch v130): a device that accepts
                                // TCP but trickles bytes held awaitAll for the full 30s
                                // OkHttp timeout per request, stalling the whole feed.
                                withTimeoutOrNull(10_000) {
                                    fetchOne(client, server.id).map { it.copy(serverName = server.name) }
                                } ?: run { failed += 1; emptyList() }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                                failed += 1
                                emptyList()
                            }
                        }
                    }.awaitAll().flatten()
                }
                _ui.update {
                    it.copy(
                        conversations = rows,
                        failedDevices = failed,
                        reachableDevices = reachable,
                        loading = false, refreshing = false, hasLoaded = true,
                        error = (if (reachable == 0 && failed > 0) "All devices unreachable" else null),
                        lastRefreshed = Instant.now(),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _ui.update { it.copy(loading = false, refreshing = false, error = if (it.hasLoaded) it.error else e.displayMessage()) }
        }
    }
}

data class ActivityUiState(
    val conversations: List<ActivityRow> = emptyList(),
    /** Aggregate mode only: devices that could not be fetched this round. */
    val failedDevices: Int = 0,
    val reachableDevices: Int = 0,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    val error: String? = null,
    val lastRefreshed: Instant? = null,
)

/** One feed row: the conversation plus the device it came from. `folderName` is
 *  pre-resolved per device in the aggregate feed (folderId is server-local);
 *  `unread` is precomputed from the device-local read marks (Last-24h rows only). */
data class ActivityRow(
    val conversation: ConversationSummary,
    val serverId: String,
    val serverName: String,
    val folderName: String? = null,
    val unread: Boolean = false,
) {
    /** Cross-device identity (batch v132) — conversation ids are server-local. */
    val refKey: String get() = "$serverId:${conversation.id}"
}

/** What an [ActivitySection] represents — drives its header icon / label / tint. */
enum class ActivityKind { RUNNING, RECENT }

/** One rendered group in the Activity feed (Running / Last 24 Hours), pre-sorted. */
data class ActivitySection(
    val id: String,
    val kind: ActivityKind,
    val rows: List<ActivityRow>,
) {
    val count: Int get() = rows.size
}

/**
 * Split the conversation list into the Running (live, newest-first) and Last-24h
 * (non-live, updated within 24h, newest-first) sections, dropping an empty one. Pure;
 * the view model runs it on [Dispatchers.Default]. Rows carry their resolved folder
 * and server labels so nothing needs a map lookup at compose time. [readMarks] keys
 * are `"<serverId>:<conversationId>"` → last opened-at (epochMilli, device-local).
 */
fun buildActivitySections(
    conversations: List<ActivityRow>,
    readMarks: Map<String, Long> = emptyMap(),
): List<ActivitySection> {
    val running = conversations.filter { it.conversation.status.isLive }
        .sortedByDescending { it.conversation.updatedAt }
    val cutoff = Instant.now().minus(Duration.ofHours(24))
    val recent = conversations.filter { !it.conversation.status.isLive && it.conversation.updatedAt.isAfter(cutoff) }
        .sortedByDescending { it.conversation.updatedAt }
        .map { row ->
            val mark = readMarks["${row.serverId}:${row.conversation.id}"]
            row.copy(
                unread = isSessionUnread(
                    statusLive = false,
                    updatedAt = row.conversation.updatedAt.toEpochMilli(),
                    readMark = mark,
                ),
            )
        }

    val out = ArrayList<ActivitySection>(2)
    if (running.isNotEmpty()) out += ActivitySection("running", ActivityKind.RUNNING, running)
    if (recent.isNotEmpty()) out += ActivitySection("recent", ActivityKind.RECENT, recent)
    return out
}
