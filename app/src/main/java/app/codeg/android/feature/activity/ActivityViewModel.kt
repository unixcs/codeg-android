package app.codeg.android.feature.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.R
import app.codeg.android.core.data.SessionFocusHub
import app.codeg.android.core.data.SessionProjectRepository
import app.codeg.android.core.data.DeviceLoad
import app.codeg.android.core.data.DeviceLoadStatus
import app.codeg.android.core.data.AGG_DEVICE_TIMEOUT_MS
import app.codeg.android.core.data.AGG_SKELETON_DELAY_MS
import app.codeg.android.core.data.FOLDER_LOOKUP_TIMEOUT_MS
import app.codeg.android.core.data.GlobalEventWatcher
import app.codeg.android.core.data.LIVE_PROBE_TIMEOUT_MS
import app.codeg.android.core.data.RUNNING_STATUS_MAX_AGE
import app.codeg.android.core.data.SINGLE_DEVICE_TIMEOUT_MS
import app.codeg.android.core.data.aggregateDeviceSignature
import app.codeg.android.core.data.failedNames
import app.codeg.android.core.data.loadingNames
import app.codeg.android.core.data.okCount
import app.codeg.android.core.data.participating
import app.codeg.android.core.data.retrying
import app.codeg.android.core.data.retryableIds
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.ACTIVITY_FILTER_PREFS_DEFAULT
import app.codeg.android.core.datastore.ActivityFilterPrefs
import app.codeg.android.core.datastore.ADD_TO_PROJECT_PREFS_DEFAULT
import app.codeg.android.core.datastore.AddToProjectPrefs
import app.codeg.android.core.datastore.AggregateProjectFilterStore
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    // 批v179: private val — the batch-delete/filter setters below reference this
    // store from member functions (a bare constructor param is initializer-only).
    private val settingsStore: AppSettingsStore,
    private val projectRepo: SessionProjectRepository,
    private val aggregateProjectFilter: AggregateProjectFilterStore,
    /** Floating-ball session cycling (batch v132) — the aggregate list registers here. */
    val focusHub: SessionFocusHub,
    /** 批v173 C1: process-wide push layer — conversation://changed pings the feed. */
    private val globalEvents: GlobalEventWatcher,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _ui = MutableStateFlow(ActivityUiState())
    val ui: StateFlow<ActivityUiState> = _ui.asStateFlow()

    /** App foreground signal (batch v130) — gates the 25s poll while backgrounded. */
    private val foreground = (appContext as app.codeg.android.CodegApplication).appForeground

    /** 批v165 R4: tab-visibility gate. Bottom-bar navigation keeps this VM alive
     *  (saveState/restoreState), so a single visit armed the 25s all-device
     *  fan-out for the rest of the foreground session — the exact leak TaskHub
     *  and Automation already closed with this same shape. Activity was the
     *  last holdout. */
    private val _active = MutableStateFlow(false)
    fun setActive(active: Boolean) { _active.value = active }

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
     *
     * R2-B P2-1: the long-press-hidden projects fold in the same way — same shared
     * store as the Chats aggregate, same row filter, gated on [ActivitySectionsInput.all]
     * so the single-device feed stays untouched. 7 source flows exceed combine's typed
     * 5-arity: the base tuple rides a data-class input (the SessionListViewModel pattern).
     */
    private data class ActivitySectionsInput(
        val conversations: List<ActivityRow>,
        val readMarks: Map<String, Long>,
        val all: Boolean,
        val memberKeys: Set<String>?,
        val excludeKeys: Set<String>?,
    )

    val sections: StateFlow<List<ActivitySection>> =
        combine(
            combine(
                _ui.map { it.conversations }.distinctUntilChanged(),
                settingsStore.sessionReadMarks,
                repository.activityAll,
                projectRepo.activeMemberKeys,
                // 批v160 W9: the 未添加 scope's complement — every ASSIGNED ref key.
                projectRepo.activeExcludeKeys,
            ) { conversations, readMarks, all, memberKeys, excludeKeys ->
                ActivitySectionsInput(conversations, readMarks, all, memberKeys, excludeKeys)
            },
            aggregateProjectFilter.hiddenProjects,
            projectRepo.memberProjects,
            // 批v179: the RECENT window + 未读-only filter, settings-driven.
            settingsStore.settings.map { it.activityWindowHours }.distinctUntilChanged(),
            settingsStore.settings.map { it.activityUnreadOnly }.distinctUntilChanged(),
        ) { input, hiddenProjects, memberProjects, windowHours, unreadOnly ->
            buildActivitySections(
                narrowActivityRows(
                    conversations = input.conversations,
                    all = input.all,
                    memberKeys = input.memberKeys,
                    excludeKeys = input.excludeKeys,
                    hiddenProjects = hiddenProjects,
                    memberProjects = memberProjects,
                ),
                input.readMarks,
                windowHours,
                unreadOnly,
            )
        }
        .distinctUntilChanged()
        // DataStore failure upstream must not kill the sharing coroutine while
        // the feed is on screen; resubscribe with backoff, keep last-good UI.
        .retryWhen { _, attempt ->
            kotlinx.coroutines.delay(if (attempt < 3) 1_000 else 5_000)
            true
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var pollJob: Job? = null

    /** Aggregate round counter (batch v137): a late per-device result from a
     *  superseded round must never overwrite the newer round's state. */
    private var activityGeneration = 0

    /** Folder names per server endpoint — cache survives aggregate↔single switches
     *  so each 25s poll doesn't re-list folders on every device (folderId is
     *  server-local). The endpoint is part of the key: re-pointing a device must
     *  not keep resolving labels against its old server. */
    private val folderCache = HashMap<String, Map<Int, String>>()

    init {
        // 批v173 C1: a pushed conversation://changed bumps the repository's
        // activity tick — the VM's existing combine+tick arbitration does the
        // rest (dedupe, mode resets, active/foreground gating all unchanged).
        globalEvents.events
            .filter { it.channel == GlobalEventWatcher.CHANNEL_CONVERSATION }
            .onEach { repository.requestActivityRefresh() }
            .launchIn(viewModelScope)
        viewModelScope.launch {
            combine(
                repository.selectedProfile,
                repository.activityAll,
                repository.activityRefreshTick,
                repository.profiles,
            ) { profile, all, tick, profiles -> ActivityInput(profile, all, tick, profiles) }
                // Every DataStore write re-emits selectedProfile/profiles (read marks,
                // draft autosave, a select() from a handoff) with IDENTICAL inputs.
                // Dropping those keeps a redundant emission from cancelling a healthy
                // in-flight fetch (same guard the Chats tab uses).
                .distinctUntilChanged()
                // A DataStore IO failure anywhere upstream must not kill the
                // process (this VM owns the whole Activity tab incl. the task
                // board and idea inbox); resubscribe with backoff and keep the
                // last-good UI until data comes back.
                .retryWhen { _, attempt ->
                    kotlinx.coroutines.delay(if (attempt < 3) 1_000 else 5_000)
                    true
                }
                .collectLatest { input ->
                    val (profile, all, tick) = input
                    // The fan-out SET is part of the mode, not just the selection:
                    // hiding/skipping/renaming a device must re-fan-out immediately
                    // instead of waiting for the 25s poll (batch v137).
                    val deviceSig = input.profiles.aggregateDeviceSignature()
                    // First emission for a *mode* change resets the feed; a tick-only bump
                    // (pull-to-refresh while already in this mode) refreshes in place so
                    // the loaded list stays visible and the spinner runs its course.
                    // In aggregate mode the feed spans every device, so a selection
                    // change (floating ball) is not new data — resetting here used to
                    // wipe the 全部 list back to a spinner on every device switch.
                    val profileChanged = profile?.id != lastProfileId || profile?.baseUrl != lastBaseUrl
                    val isReset = all != lastAll || (!all && profileChanged)
                    val modeChanged = all != lastAll
                    val tickChanged = tick != lastTick
                    val devicesChanged = deviceSig != lastDeviceSig
                    lastProfileId = profile?.id
                    lastBaseUrl = profile?.baseUrl
                    lastAll = all
                    lastTick = tick
                    lastDeviceSig = deviceSig
                    // Same reason, one step further: don't even restart the poll. onMode
                    // cancels the running loop and immediately re-fans-out over every
                    // device, which made a device switch in 全部 cost a full refetch.
                    // A tick bump must still get through — that is pull-to-refresh.
                    // (No profile check here: in aggregate mode a selection change IS the
                    // thing we want to ignore, so including it would make this guard
                    // unreachable.)
                    if (all) {
                        // (No profile check here: in aggregate mode a selection change
                        // IS the thing we want to ignore, so including it would make
                        // this guard unreachable.)
                        if (!modeChanged && !tickChanged && !devicesChanged) return@collectLatest
                    } else if (!modeChanged && !tickChanged && !profileChanged) {
                        // Single-device view: an unrelated device being renamed,
                        // hidden or skipped changes nothing here — Chats already
                        // ignores it, and restarting this poll costs a request.
                        return@collectLatest
                    }
                    onMode(profile, all, reset = isReset)
                }
        }
    }

    private var lastProfileId: String? = null
    private var lastBaseUrl: String? = null
    private var lastTick: Int? = null
    private var lastAll: Boolean = false
    private var lastDeviceSig: String? = null

    /** One-shot manual-fetch flag consumed by the next poll iteration (batch v130). */
    @Volatile private var manualOnce = false

    private suspend fun onMode(profile: ServerProfile?, all: Boolean, reset: Boolean) {
        pollJob?.cancel()
        // A banner retry runs outside pollJob, so cancelling the poll does not stop
        // it. Invalidate every in-flight aggregate round here: its commits would
        // otherwise paint aggregate rows into the single-device feed (or into a
        // reset state) after the mode already changed.
        activityGeneration += 1
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
                if (!_active.value) _active.first { it }
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
            try {
                // Read BEFORE cancelling: a failed DataStore read after
                // cancelAndJoin would leave the poll dead until the next mode
                // change — the old loop at least kept the feed fresh.
                val profile = repository.selectedProfile.first()
                pollJob?.cancelAndJoin()
                profile?.let { onMode(it, repository.activityAll.value, reset = false) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Pull-to-refresh must not crash on a failed DataStore read;
                // the old poll loop is still alive and keeps refreshing.
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
            val profile = try {
                repository.profiles.first().orEmpty().firstOrNull { it.id == row.serverId }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val client = profile?.let { repository.client(it) }
            if (client == null) {
                _ui.update { it.copy(error = appContext.getString(R.string.sessions_missing_token)) }
                return@launch
            }
            val removed = _ui.value.conversations.filter {
                it.conversation.id == row.conversation.id && it.serverId == row.serverId
            }
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
                // Put back only the row we removed, not a snapshot of the whole feed:
                // a poll may have committed fresher rows meanwhile, and a mode switch
                // would otherwise paint other devices' rows into this device's feed.
                _ui.update { state ->
                    val missing = removed.filterNot { r ->
                        state.conversations.any {
                            it.serverId == r.serverId && it.conversation.id == r.conversation.id
                        }
                    }
                    state.copy(conversations = state.conversations + missing, error = e.displayMessage())
                }
                restartPoll(manual = false)
                return@launch
            }
            // Re-bucket Running/Recent immediately with a fresh (restarted) poll.
            restartPoll(manual = true)
        }
    }

    /**
     * 批v179 batch delete (multi-select). Same contract as [delete] per row: the
     * whole selection leaves the feed optimistically, each conversation is then
     * deleted on its OWNING device (ids are server-local, so rows group by
     * [ActivityRow.serverId] — the server exposes no batch endpoint, this is one
     * WS call per row), and only the failed rows are restored. Any failure
     * surfaces once, after the loop; the succeeded rows stay deleted.
     */
    fun deleteAll(rows: Collection<ActivityRow>) {
        if (rows.isEmpty()) return
        viewModelScope.launch(Dispatchers.Main) {
            val profiles = try {
                repository.profiles.first().orEmpty()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            val doomedKeys = rows.map { it.refKey }.toSet()
            _ui.update { state ->
                state.copy(conversations = state.conversations.filterNot { it.refKey in doomedKeys })
            }
            var lastError: String? = null
            val failed = mutableListOf<ActivityRow>()
            rows.groupBy { it.serverId }.forEach { (serverId, group) ->
                val client = profiles.firstOrNull { it.id == serverId }?.let { repository.client(it) }
                if (client == null) {
                    failed += group
                    // Same feedback as single-row [delete]: without it the rows
                    // just reappear with no hint why.
                    lastError = appContext.getString(R.string.sessions_missing_token)
                    return@forEach
                }
                group.forEach { row ->
                    try {
                        client.deleteConversation(row.conversation.id)
                        projectRepo.removeRef(serverId, row.conversation.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed += row
                        lastError = e.displayMessage()
                    }
                }
            }
            if (failed.isNotEmpty()) {
                _ui.update { state ->
                    // Restore only rows still missing (a poll may have re-fetched
                    // some meanwhile), mirroring [delete]'s targeted re-insert.
                    val missing = failed.filterNot { r ->
                        state.conversations.any {
                            it.serverId == r.serverId && it.conversation.id == r.conversation.id
                        }
                    }
                    state.copy(conversations = state.conversations + missing, error = lastError)
                }
            }
            restartPoll(manual = true)
        }
    }

    /** Rename from the activity feed (batch v133). Awaits the owning device, patches
     *  the row in place, then restarts the poll so the sections re-sort by the new
     *  ordering data — rename has no optimistic local state to revert. */
    fun rename(row: ActivityRow, title: String) {
        viewModelScope.launch(Dispatchers.Main) {
            val profile = try {
                repository.profiles.first().orEmpty().firstOrNull { it.id == row.serverId }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
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

    // 批v179: the 添加到项目 sheet's grid/order prefs, narrowed from the settings
    // store so the stateless sheet renders and persists through this VM.
    val addToProjectPrefs: StateFlow<app.codeg.android.core.datastore.AddToProjectPrefs> =
        settingsStore.settings
            .map { app.codeg.android.core.datastore.AddToProjectPrefs(it.addToProjectColumns, it.addToProjectSort) }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, app.codeg.android.core.datastore.ADD_TO_PROJECT_PREFS_DEFAULT)

    fun setAddToProjectColumns(columns: Int) {
        viewModelScope.launch { settingsStore.setAddToProjectColumns(columns) }
    }

    fun setAddToProjectSort(sort: String) {
        viewModelScope.launch { settingsStore.setAddToProjectSort(sort) }
    }

    // 批v179: the RECENT group's window + 未读-only filter, narrowed for the
    // header button/long-press filter card (label + switches read from here).
    val activityFilterPrefs: StateFlow<ActivityFilterPrefs> =
        settingsStore.settings
            .map { ActivityFilterPrefs(it.activityWindowHours, it.activityUnreadOnly) }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, ACTIVITY_FILTER_PREFS_DEFAULT)

    fun setActivityWindowHours(hours: Int) {
        viewModelScope.launch { settingsStore.setActivityWindowHours(hours) }
    }

    fun setActivityUnreadOnly(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setActivityUnreadOnly(enabled) }
    }

    fun toggleProjectMembership(projectId: String, ref: SessionRef, member: Boolean) {
        viewModelScope.launch {
            try {
                projectRepo.setMembership(projectId, ref, member)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // DataStore edit failure: membership stays unchanged; the sheet
                // reflects the store truth on next emission.
            }
        }
    }

    fun createProject(name: String, onCreated: (app.codeg.android.core.datastore.SessionProject) -> Unit = {}) {
        viewModelScope.launch {
            try {
                onCreated(projectRepo.create(name))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Create failed: onCreated simply never fires; the sheet keeps
                // its input instead of crashing.
            }
        }
    }

    /** Refresh one device's feed (also resolves its folder names, once). */
    private suspend fun fetchOne(
        c: CodegClient,
        server: ServerProfile,
    ): List<ActivityRow> {
        val conversations = c.listConversations(sortBy = "updated").take(200)
        val folders = foldersFor(c, server)
        // 批v154: corroborate the persisted `status` flag against the LIVE
        // connection table. A row stuck in `in_progress` after a server restart or
        // a missed TurnComplete write used to sit in 「正在运行」 forever; the pet
        // snapshot lists only connections that are actually prompting / waiting on
        // a permission. Null (probe failed) keeps the old status-only behavior —
        // never hide a session that may really be running.
        // 批v155 bugfix: the status-only fallback is now age-bounded
        // ([RUNNING_STATUS_MAX_AGE]). A frozen row is a turn that died mid-flight
        // (server powered off — no terminal status write) and must not re-enter
        // 运行中 every time one probe round flakes. When the probe answers, it
        // wins unchanged — the age bound only guards the degraded round.
        // 批v165 correction + blind-spot fix: updated_at does NOT advance per
        // message (server bumps it at prompt-claim and status transitions only —
        // there is no per-message write), so the age bound is a coarse guard, not
        // a liveness signal. And an ANSWERING probe still hides delegation
        // children by design while they work (pet.rs): an in_progress `delegate`
        // row the probe doesn't list is a working sub-session, not a corpse —
        // without the clause the 正在运行 section drops it every round. The trade:
        // a delegate row stranded mid-turn by an unclean server death also shows
        // until the user completes/cancels it (the desktop sidebar spins the same
        // way; no server-side reconciliation exists).
        val liveIds = withTimeoutOrNull(LIVE_PROBE_TIMEOUT_MS) { c.liveConversationIds() }
        val fallbackCutoff = Instant.now().minus(RUNNING_STATUS_MAX_AGE)
        return conversations.map { conv ->
            ActivityRow(
                conversation = conv,
                serverId = server.id,
                serverName = server.name,
                folderName = folders[conv.folderId],
                live = conv.status.isLive && when {
                    liveIds == null ->
                        conv.updatedAt.isAfter(fallbackCutoff) || conv.kind == "delegate"
                    conv.id in liveIds -> true
                    conv.kind == "delegate" -> true
                    else -> false
                },
            )
        }
    }

    /**
     * Folder names, best-effort and separately bounded: the aggregate's 3s
     * per-device deadline belongs to the feed itself. A slow `/folders` costs a
     * round of labels (uncached, so the next round retries) instead of dropping
     * the device's rows entirely.
     */
    private suspend fun foldersFor(c: CodegClient, server: ServerProfile): Map<Int, String> {
        val key = "${server.id}|${server.baseUrl}"
        folderCache[key]?.let { return it }
        val folders = try {
            withTimeoutOrNull(FOLDER_LOOKUP_TIMEOUT_MS) { c.listFolders() }
        } catch (e: CancellationException) {
            // The outer per-device deadline cancels us; swallowing it here would
            // make the round read a timed-out device as a success.
            throw e
        } catch (_: Exception) {
            null
        } ?: return emptyMap()
        return folders.associate { it.id to it.name }.also { folderCache[key] = it }
    }

    private suspend fun fetch(profile: ServerProfile, all: Boolean, manual: Boolean = false) {
        val generation = activityGeneration
        if (manual) _ui.update { it.copy(refreshing = true, error = null) }
        try {
            if (!all) {
                val c = repository.client(profile)
                if (c == null) {
                    _ui.update {
                        it.copy(loading = false, refreshing = false, hasLoaded = true, error = "No token for ${profile.name}")
                    }
                    return
                }
                // Single-device page: the user asked for THIS device, so it gets a
                // slightly wider deadline than the aggregate — but it must still end
                // in a retryable error instead of spinning for the 30s OkHttp timeout
                // (batch v137).
                val rows = withTimeoutOrNull(SINGLE_DEVICE_TIMEOUT_MS) {
                    fetchOne(c, profile)
                }
                // A mode switch invalidated this poll while the request was in
                // flight; its result must not land in the new mode's state.
                if (generation != activityGeneration) return
                if (rows == null) {
                    _ui.update {
                        it.copy(
                            loading = false, refreshing = false, hasLoaded = true,
                            error = appContext.getString(R.string.device_unreachable_named, profile.name),
                        )
                    }
                    return
                }
                _ui.update {
                    it.copy(
                        conversations = rows,
                        loading = false, refreshing = false, hasLoaded = true, error = null,
                        lastRefreshed = Instant.now(),
                    )
                }
            } else {
                fetchAllDevices()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Superseded rounds must not write into the newer round's state
            // (every other write site here is generation-guarded).
            if (generation == activityGeneration) {
                _ui.update { it.copy(loading = false, refreshing = false, error = if (it.hasLoaded) it.error else e.displayMessage()) }
            }
        }
    }

    /**
     * One progressive fan-out over the aggregate devices (batch v137). Every device
     * commits its own result the moment it settles — first success paints the list,
     * an offline device only ever flips its own row to FAILED after its 3s deadline.
     * A generation counter drops late results from a superseded round (e.g. the
     * device that timed out at 3s while the user already pulled to refresh).
     *
     * [retryOnly] refetches just the currently-failed devices and leaves the rest of
     * the list untouched — the banner's retry must not blank good data.
     */
    private suspend fun fetchAllDevices(retryOnly: Boolean = false) {
        val generation = ++activityGeneration
        val allServers = repository.profiles.first().orEmpty().filter { it.visible && it.includeInAll }
        val retryIds = if (retryOnly) {
            // Everything that did not settle OK — a device still LOADING when the
            // user hits retry would otherwise be cancelled with the old round and
            // never re-asked, leaving its rows missing until the next poll.
            _ui.value.deviceStates.retryableIds()
        } else {
            emptySet()
        }
        val servers = if (retryOnly) allServers.filter { it.id in retryIds } else allServers
        // `profiles.first()` suspends; a newer round may have started and finished
        // meanwhile. Seeding after that would resurrect the older round's flags.
        if (generation != activityGeneration) return

        _ui.update { state ->
            // Skipped devices leave the aggregate immediately; their rows must go
            // with them (unlike a mere failure, which keeps the last known rows).
            val allowed = allServers.map { it.id }.toSet()
            val seeded = if (retryOnly) {
                state.deviceStates.participating(allowed).retrying(retryIds)
            } else {
                servers.map { DeviceLoad(it.id, it.name, DeviceLoadStatus.LOADING) }
            }
            state.copy(
                deviceStates = seeded,
                conversations = state.conversations.filter { it.serverId in allowed },
                loading = !state.hasLoaded,
                refreshing = state.hasLoaded,
                // A new round re-asks the devices, so the previous round's
                // "all unreachable" verdict must not stay on screen (it would also
                // block its own retry button while the round is in flight).
                error = null,
            )
        }

        // Skeleton fallback: if not one device has answered within the delay, paint
        // the list frame with per-device placeholders instead of holding the
        // full-screen spinner hostage.
        val skeleton = viewModelScope.launch {
            delay(AGG_SKELETON_DELAY_MS)
            if (generation == activityGeneration) {
                _ui.update {
                    if (!it.hasLoaded) {
                        // The round is still running, so keep the busy flag set —
                        // otherwise pull-to-refresh/retry could start a competing
                        // round and abandon the devices still inside their window.
                        it.copy(loading = false, hasLoaded = true, refreshing = true)
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
                        val rows = try {
                            repository.client(server)?.let { client ->
                                withTimeoutOrNull(AGG_DEVICE_TIMEOUT_MS) {
                                    fetchOne(client, server)
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                            null
                        }
                        if (generation != activityGeneration) return@launch
                        val failed = rows == null
                        _ui.update { state ->
                            state.copy(
                                conversations = if (rows != null) {
                                    state.conversations.filterNot { it.serverId == server.id } + rows
                                } else {
                                    // Keep the last known rows on failure — a device that
                                    // just went dark shouldn't erase what it last showed.
                                    state.conversations
                                },
                                deviceStates = state.deviceStates.map { load ->
                                    if (load.serverId != server.id) {
                                        load
                                    } else if (failed) {
                                        load.copy(
                                            status = DeviceLoadStatus.FAILED,
                                            error = appContext.getString(R.string.device_unreachable_named, server.name),
                                        )
                                    } else {
                                        load.copy(status = DeviceLoadStatus.OK, error = null)
                                    }
                                },
                                loading = false,
                                hasLoaded = true,
                                lastRefreshed = if (!failed) Instant.now() else state.lastRefreshed,
                            )
                        }
                    }
                }
            }
        } finally {
            // A cancelled round (mode switch / newer round) never reaches the line
            // after coroutineScope — the skeleton must not outlive its round.
            skeleton.cancel()
        }
        if (generation != activityGeneration) return
        _ui.update { state ->
            val ok = state.deviceStates.okCount()
            val failedNames = state.deviceStates.failedNames()
            state.copy(
                loading = false,
                refreshing = false,
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

    /** Banner retry: re-ask only the devices that failed, keeping good rows on screen. */
    fun retryFailedDevices() {
        viewModelScope.launch {
            if (!repository.activityAll.value) {
                refresh()
                return@launch
            }
            if (_ui.value.deviceStates.none { it.status != DeviceLoadStatus.OK }) {
                refresh()
                return@launch
            }
            // Deliberately no "a round is already running" gate: retry must be able
            // to rescue devices that are still inside their window, and superseding
            // the old round is exactly what the generation counter is for.
            _ui.update { it.copy(refreshing = true) }
            try {
                fetchAllDevices(retryOnly = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // `profiles.first()` can fail on an unreadable preferences file; a
                // banner tap must not crash the app or leave the list spinning.
                _ui.update { it.copy(refreshing = false) }
            }
        }
    }
}

/** Inputs that can change the activity feed: selection, mode, refresh tick and the
 *  device set (batch v137 — the last one makes a skip/rename take effect at once). */
private data class ActivityInput(
    val profile: ServerProfile?,
    val all: Boolean,
    val tick: Int,
    val profiles: List<ServerProfile>,
)

data class ActivityUiState(
    val conversations: List<ActivityRow> = emptyList(),
    /** Aggregate mode only: per-device progress for this round (batch v137). */
    val deviceStates: List<DeviceLoad> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    val error: String? = null,
    val lastRefreshed: Instant? = null,
) {
    /** Aggregate mode only: devices that could not be fetched this round. */
    val failedDevices: Int get() = deviceStates.count { it.status == DeviceLoadStatus.FAILED }

    /** Names for the failure banner ("win 未响应"), in feed order. */
    val failedDeviceNames: List<String> get() = deviceStates.failedNames()

    /** Devices still connecting — the list must not show an empty feed while
     *  these can still bring rows (batch v137 skeleton phase). */
    val pendingDeviceNames: List<String> get() = deviceStates.loadingNames()
}

/** One feed row: the conversation plus the device it came from. `folderName` is
 *  pre-resolved per device in the aggregate feed (folderId is server-local);
 *  `unread` is precomputed from the device-local read marks (Last-24h rows only). */
data class ActivityRow(
    val conversation: ConversationSummary,
    val serverId: String,
    val serverName: String,
    val folderName: String? = null,
    val unread: Boolean = false,
    /** Genuinely running (live connection corroborated), not just `status.isLive`
     *  — see `CodegClient.liveConversationIds` (批v154). */
    val live: Boolean = conversation.status.isLive,
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
 * R2-B P2-1 narrowing, extracted for unit tests. The aggregate gate lives on
 * [all]: a single-device feed is NEVER narrowed — not by the project scope, not
 * by the long-press-hidden set (a single-device view has no chip to escape a
 * filter with). When both scopes are somehow non-null the member set owns the
 * narrowing (same contract as the Chats aggregate's builder — the repo never
 * emits both, but a stale exclude list must not stack on top).
 */
internal fun narrowActivityRows(
    conversations: List<ActivityRow>,
    all: Boolean,
    memberKeys: Set<String>?,
    excludeKeys: Set<String>?,
    hiddenProjects: Set<String>,
    memberProjects: Map<String, Set<String>>,
): List<ActivityRow> {
    if (!all) return conversations
    return conversations
        .filter { row ->
            when {
                memberKeys != null -> row.refKey in memberKeys
                excludeKeys != null -> row.refKey !in excludeKeys
                else -> true
            }
        }
        .filter { row ->
            hiddenProjects.intersect(memberProjects[row.refKey].orEmpty()).isEmpty()
        }
}

/**
 * Split the conversation list into the Running (live, newest-first) and Recent
 * (non-live, updated within [windowHours], newest-first) sections, dropping an
 * empty one. Pure; the view model runs it on [Dispatchers.Default]. 批v179: the
 * window is settings-driven (24h/48h/3d/5d/7d/0=all) and [unreadOnly] keeps only
 * rows whose finished state is unread (the blue dot) — applied after the unread
 * computation, so it never hides live rows (they aren't in RECENT anyway).
 * [readMarks] keys are `"<serverId>:<conversationId>"` → last opened-at
 * (epochMilli, device-local).
 */
fun buildActivitySections(
    conversations: List<ActivityRow>,
    readMarks: Map<String, Long> = emptyMap(),
    windowHours: Int = 24,
    unreadOnly: Boolean = false,
): List<ActivitySection> {
    val running = conversations.filter { it.live }
        .sortedByDescending { it.conversation.updatedAt }
    // 0 = 全部: no cutoff — the feed already caps at 200 conversations per device.
    val cutoff = if (windowHours > 0) Instant.now().minus(Duration.ofHours(windowHours.toLong())) else null
    val recent = conversations
        .filter { !it.live && (cutoff == null || it.conversation.updatedAt.isAfter(cutoff)) }
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
        .let { if (unreadOnly) it.filter { row -> row.unread } else it }

    val out = ArrayList<ActivitySection>(2)
    if (running.isNotEmpty()) out += ActivitySection("running", ActivityKind.RUNNING, running)
    if (recent.isNotEmpty()) out += ActivitySection("recent", ActivityKind.RECENT, recent)
    return out
}
