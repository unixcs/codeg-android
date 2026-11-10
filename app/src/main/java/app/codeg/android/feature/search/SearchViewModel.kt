package app.codeg.android.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.AGG_DEVICE_TIMEOUT_MS
import app.codeg.android.core.data.DeviceLoad
import app.codeg.android.core.data.DeviceLoadStatus
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.data.SessionProjectRepository
import app.codeg.android.core.data.aggregateDeviceSignature
import app.codeg.android.core.datastore.RecentSearchesStore
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.datastore.SessionProject
import app.codeg.android.core.datastore.SessionRef
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.network.ApiError
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.displayMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import javax.inject.Inject

/** Per-device response cap inside an aggregate search round (批v151). The merge
 *  answers "what matches across devices, most recent first" — one hot device
 *  must not bury every other device's hits under 500 rows of history. */
private const val AGG_FETCH_LIMIT = 200

/** One device's slice inside an aggregate search round (批v151). */
data class SearchDeviceResults(
    val serverId: String,
    val serverName: String,
    val conversations: List<ConversationSummary>,
)

/** One merged aggregate row: the conversation plus the device it came from. */
data class SearchHit(
    val conversation: ConversationSummary,
    val serverId: String,
    val serverName: String,
)

/** The merged list's deterministic order (ties break by serverId then id so two
 *  devices' identical timestamps can't swap rows between frames). Extracted so
 *  the row-restore paths keep the same order as a fresh merge (批v174 P3). */
private val HIT_ORDER = compareByDescending<SearchHit> { it.conversation.updatedAt }
    .thenBy { it.serverId }
    .thenBy { it.conversation.id }

/**
 * Merge per-device search slices into one recency-sorted list (批v151). Pure —
 * unit-tested in MergeSearchResultsTest. Ties (identical updatedAt, ids are
 * server-local) break by serverId then conversation id so the order is
 * deterministic across recompositions; otherwise two devices' identical
 * timestamps could swap rows between frames and jitter the list. Pinned rows do
 * NOT float here: the aggregate answers "what matches, most recent first", and
 * pinning is a single-device view concern (row actions are disabled in All
 * mode — a server-local id must never be mutated from the merged list).
 */
fun mergeSearchResults(perDevice: List<SearchDeviceResults>): List<SearchHit> =
    perDevice.flatMap { d -> d.conversations.map { SearchHit(it, d.serverId, d.serverName) } }
        .sortedWith(HIT_ORDER)

/** Global conversation search: debounced server query + persisted recent terms. Port of iOS `SearchView`. */
@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: ServerRepository,
    private val recentStore: RecentSearchesStore,
    // 批v174 P3: the 添加到项目 sheet's store (same single-instance passthrough
    // shape as the Chats VM).
    private val projectRepo: SessionProjectRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _ui = MutableStateFlow(SearchUiState())
    val ui: StateFlow<SearchUiState> = _ui.asStateFlow()

    val recent: StateFlow<List<String>> =
        recentStore.searches.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- 批v174 P3: project passthroughs for the 添加到项目 sheet (hoisted,
    //  one instance per screen — same shape as the Chats VM's). ----

    val sessionProjects: StateFlow<List<SessionProject>> = projectRepo.projects

    val projectMembers: StateFlow<Map<String, List<SessionRef>>> = projectRepo.members

    fun toggleProjectMembership(projectId: String, ref: SessionRef, member: Boolean) {
        viewModelScope.launch { projectRepo.setMembership(projectId, ref, member) }
    }

    fun createProject(name: String, onCreated: (SessionProject) -> Unit = {}) {
        viewModelScope.launch { onCreated(projectRepo.create(name)) }
    }

    private var client: CodegClient? = null

    /** The profile [client] was built for. Kept so a search can re-resolve after a
     *  token edit — token changes do NOT emit on the profile flow. */
    private var currentProfile: ServerProfile? = null

    /** `(id, baseUrl)` of the device the current results belong to. Comparing the
     *  endpoint, not just the id, matters: editing a server's URL keeps its id. */
    private var serverKey: Pair<String?, String?>? = null

    /**
     * Epoch of the newest accepted search. `collectLatest` cancels the previous
     * request when a new query arrives, but cancellation only lands at a
     * suspension point: a request that already got its response can still write
     * its (now stale) rows into [_ui]. Every runSearch therefore carries the epoch
     * it started with and drops its writes once a newer search — a debounce
     * emission, a Retry tap, or a device switch — has taken over.
     */
    private var searchEpoch = 0L

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
                .collectLatest { profile ->
                // Resolve BEFORE committing any state: repository.client() suspends on
                // the token read, and a cancellation mid-way (collectLatest) must not
                // leave a half-switched client or a wiped list behind.
                val resolved = profile?.let { resolveClient(it) }
                val key = profile?.let { it.id to it.baseUrl }
                // Re-emissions are routine (the repository combines two cold flows), so
                // only a REAL endpoint change may invalidate results. Ids alone are not
                // enough — editing a device's URL keeps the id, and the old server's
                // rows must not outlive the swap (pin/rename would hit the new server).
                val switched = key != serverKey
                val recovered = !switched && resolved != null && _ui.value.noDevice
                serverKey = key
                currentProfile = profile
                client = resolved
                // 批v151: in All mode the fan-out owns the results (device-independent);
                // a selected-device change must neither wipe them nor restart the
                // search — just re-track the client for when All mode ends.
                if (switched && !_ui.value.allMode) {
                    // Results are server-local (ids collide across devices) and this VM
                    // outlives device switches — stale rows must not survive a device
                    // change, or rename/pin/delete would hit the WRONG device's session.
                    _ui.update {
                        it.copy(
                            results = emptyList(),
                            // Folder ids are server-local too: stale labels would tag
                            // the new device's rows with the old device's names.
                            folderNames = emptyMap(),
                            serverId = profile?.id,
                            searching = false,
                            error = null,
                            noDevice = resolved == null,
                        )
                    }
                    // Answer the visible query on the device now on screen. The epoch is
                    // minted AFTER the client swap (never before a suspension), so an old
                    // device's in-flight response can no longer land after the clear.
                    startSearch(_query.value, ++searchEpoch)
                } else if (recovered && !_ui.value.allMode) {
                    // A token was added for the same device: the profile flow carries no
                    // token edits, so nothing else would clear the flag.
                    _ui.update { it.copy(noDevice = false) }
                    startSearch(_query.value, ++searchEpoch)
                }
                // Fetch folder names AFTER starting the search: runSearch sets
                // `searching` synchronously, so the empty state can't flash during
                // this round trip. All mode renders serverName instead (folder ids
                // are server-local; the per-device fan-out doesn't pay for labels).
                val names = if (resolved == null || _ui.value.allMode) emptyMap() else resolveFolderNames(resolved)
                if (!_ui.value.allMode) _ui.update { it.copy(folderNames = names) }
            }
        }
        // 批v151 All-mode toggle: entering leaves the current rows on screen until the
        // fan-out replaces them (no wipe → no flash); leaving clears the aggregate
        // rows AND the single-device slices (R1: inside All they may belong to a
        // device left minutes ago) and re-runs single-device search on the query.
        // 重启条件还带聚合范围签名（审查修正）：停在 All 时"跳过全部/改名"要立即
        // 重新扇出，同 Chats/Activity 聚合的 deviceSig 规则；单模式下签名变化不
        // 重跑（单设备搜索不读 includeInAll）。
        viewModelScope.launch {
            var lastSig: String? = null
            var lastAll: Boolean? = null
            combine(
                repository.searchAll,
                repository.visibleProfiles.map { it.aggregateDeviceSignature() }.distinctUntilChanged(),
            ) { all, sig -> all to sig }
                .retryWhen { _, attempt ->
                        // 批v165 R1 (P0): a DataStore read failure upstream must resubscribe — a
                        // bare collector dying here is the v130 crash class (process kill from a
                        // screen entry) AND "deaf for life" if it never re-arms.
                        kotlinx.coroutines.delay(if (attempt < 3) 1_000 else 5_000)
                        true
                }
                .collectLatest { (all, sig) ->
                    val sigChanged = lastSig != null && sig != lastSig
                    val modeChanged = lastAll != null && all != lastAll
                    lastSig = sig
                    lastAll = all
                    _ui.update {
                        // Leaving All also drops the aggregate slices — they must never
                        // resurface if All is re-entered before a fresh round lands.
                        // 批v153: a real mode flip also drops the single-device slices —
                        // inside All the switched-branch wipe is skipped (allMode guard),
                        // so they can still belong to a device left minutes ago;
                        // pin/rename/delete would fire a server-local id at the WRONG
                        // device before the fresh round below lands. serverId rides along
                        // so the delete dialog's remember key re-arms on the change.
                        // 批v153 R4（墨菲审查）：the single-device wipe MUST gate on
                        // modeChanged — a sig-only emission while in single mode (device
                        // bar's includeInAll long-press, device rename) would otherwise
                        // wipe visible results with no round replacing them: the re-run
                        // clause below needs !sigChanged || all || modeChanged.
                        if (all) it.copy(allMode = true)
                        else if (modeChanged) it.copy(
                            allMode = false,
                            allResults = emptyList(),
                            deviceStates = emptyList(),
                            results = emptyList(),
                            folderNames = emptyMap(),
                            serverId = currentProfile?.id,
                        )
                        else it.copy(allMode = false, allResults = emptyList(), deviceStates = emptyList())
                    }
                    // 批v153: a mode flip must always answer the visible query — the sig
                    // clause alone skips the leave-All round when the device signature
                    // changed in the same emission (e.g. a device was deleted while in
                    // All), leaving a wiped list that no round ever replaces.
                    if (!sigChanged || all || modeChanged) runSearch(_query.value, ++searchEpoch)
                    if (!all && modeChanged) {
                        // 批v153: folder labels are server-local and were not maintained
                        // while in All (the profile collector skips them for the fan-out);
                        // re-resolve for the device now on screen. The suspension sits in
                        // collectLatest's cancellation domain — a newer emission voids it.
                        client?.let { c ->
                            val names = resolveFolderNames(c)
                            _ui.update { it.copy(folderNames = names) }
                        }
                    }
                    // 直接 await（第1轮性能审查修正）：startSearch 的 viewModelScope
                    // launch 会逃出 collectLatest 的取消域——快速连切 All/单设备会让
                    // N 设备轮次堆跑到超时；await 后新一轮 emission 结构性取消旧轮。
                }
        }
        viewModelScope.launch {
            _query.debounce(300).collectLatest { q -> runSearch(q, ++searchEpoch) }
        }
    }

    fun onQueryChange(value: String) { _query.value = value }

    fun submit() {
        val q = _query.value.trim()
        if (q.isEmpty()) return
        // 写盘失败不炸进程（第2轮审查修正）：与 runSingleSearch/runAggregateSearch
        // 对 add() 的包法一致——磁盘满时每次搜索提交都会走到这里。
        viewModelScope.launch {
            try {
                recentStore.add(q)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun useRecent(term: String) { _query.value = term }

    fun clearRecent() {
        viewModelScope.launch {
            try {
                recentStore.clear()
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /** Re-run the current query after a failure (drives the inline-error Retry button).
     *  Also recovers a missing token: [runSearch] re-resolves a null client from the
     *  selected profile, since token edits never emit on the profile flow. */
    fun retry() { viewModelScope.launch { runSearch(_query.value, ++searchEpoch) } }

    /**
     * Launch [runSearch] under [epoch] and return immediately. The caller mints the
     * epoch AFTER any suspension that could reorder it (client swap, token read) —
     * carrying an epoch minted before a suspension is how an old request used to
     * survive a newer one.
     */
    private fun startSearch(raw: String, epoch: Long) {
        viewModelScope.launch { runSearch(raw, epoch) }
    }

    /** Route by mode: the aggregate fan-out and the single-device search share the
     *  epoch/debounce machinery but own disjoint state slices. */
    private suspend fun runSearch(raw: String, epoch: Long) {
        if (_ui.value.allMode) runAggregateSearch(raw, epoch) else runSingleSearch(raw, epoch)
    }

    /** Resolve a client for [profile]; "missing token / unusable profile" is a null
     *  client, but cancellation always propagates — a superseded collectLatest action
     *  must actually stop, not run its tail after being cancelled. */
    private suspend fun resolveClient(profile: ServerProfile): CodegClient? =
        try {
            repository.client(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private suspend fun resolveFolderNames(active: CodegClient): Map<Int, String> =
        try {
            active.listFolders().associate { it.id to it.name }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }

    // ---- 批v174 P3: per-OWNING-device row actions (All-mode rows carry their
    //  serverId; the single rows are pinned to the selected device, so the dialogs
    //  hold a (serverId, conversation) pair and BOTH modes route through this
    //  trio — one code path, no mode forks at the call site). Same optimistic-
    //  then-persist shape as the Chats aggregate: resolve a client for the row's
    //  OWN device, mutate just that row (never a captured whole-list snapshot —
    //  R1-5), rethrow cancellation, surface failures as the screen error.

    /** A client for the OWNING device of a row — ids are server-local. */
    private suspend fun clientFor(serverId: String): CodegClient? {
        val profile = repository.profiles.first().firstOrNull { it.id == serverId } ?: return null
        return repository.client(profile)
    }

    /** Optimistic pin/unpin on the row's owning device. Touches both result
     *  slices (the row may be visible in either mode). */
    fun setPinnedAt(serverId: String, conversation: ConversationSummary, pinned: Boolean) {
        viewModelScope.launch {
            val c = clientFor(serverId) ?: return@launch
            val previous = conversation.pinnedAt
            val stamp = if (pinned) Instant.now() else null
            _ui.update { st ->
                st.copy(
                    results = st.results.map {
                        if (st.serverId == serverId && it.id == conversation.id) it.copy(pinnedAt = stamp) else it
                    },
                    allResults = st.allResults.map { hit ->
                        if (hit.serverId == serverId && hit.conversation.id == conversation.id)
                            hit.copy(conversation = hit.conversation.copy(pinnedAt = stamp))
                        else hit
                    },
                )
            }
            try {
                c.setPinned(conversation.id, pinned)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { st ->
                    st.copy(
                        results = st.results.map {
                            if (st.serverId == serverId && it.id == conversation.id) it.copy(pinnedAt = previous) else it
                        },
                        allResults = st.allResults.map { hit ->
                            if (hit.serverId == serverId && hit.conversation.id == conversation.id)
                                hit.copy(conversation = hit.conversation.copy(pinnedAt = previous))
                            else hit
                        },
                        error = e.displayMessage(),
                    )
                }
            }
        }
    }

    /** Optimistic delete on the owning device + the Chats-parity post-success
     *  duties: project-membership cleanup and a conversations-changed ping so
     *  the other list surfaces converge. Failure restores just this row. */
    fun deleteAt(serverId: String, conversation: ConversationSummary) {
        viewModelScope.launch {
            val c = clientFor(serverId) ?: return@launch
            val prevResult = _ui.value.results.firstOrNull { _ui.value.serverId == serverId && it.id == conversation.id }
            val prevHit = _ui.value.allResults.firstOrNull { it.serverId == serverId && it.conversation.id == conversation.id }
            _ui.update { st ->
                st.copy(
                    results = st.results.filterNot { st.serverId == serverId && it.id == conversation.id },
                    allResults = st.allResults.filterNot { it.serverId == serverId && it.conversation.id == conversation.id },
                )
            }
            try {
                c.deleteConversation(conversation.id)
                projectRepo.removeRef(serverId, conversation.id)
                repository.notifyConversationsChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { st ->
                    st.copy(
                        // The single slice may belong to a DIFFERENT device by now (the
                        // switch wipes results, but the error can land between) — never
                        // re-insert a stale server-local id into the new device's list.
                        results = if (prevResult != null && st.serverId == serverId && st.results.none { it.id == conversation.id }) st.results + prevResult else st.results,
                        allResults = if (prevHit != null && st.allResults.none { it.serverId == serverId && it.conversation.id == conversation.id })
                            (st.allResults + prevHit).sortedWith(HIT_ORDER)
                        else st.allResults,
                        error = e.displayMessage(),
                    )
                }
            }
        }
    }

    /** Rename on the owning device; the row patch lands after the server acks
     *  (same as the single-device [rename]). */
    fun renameAt(serverId: String, conversation: ConversationSummary, title: String) {
        viewModelScope.launch {
            val c = clientFor(serverId) ?: return@launch
            try {
                c.renameConversation(conversation.id, title)
                _ui.update { st ->
                    st.copy(
                        results = st.results.map {
                            if (st.serverId == serverId && it.id == conversation.id) it.copy(title = title) else it
                        },
                        allResults = st.allResults.map { hit ->
                            if (hit.serverId == serverId && hit.conversation.id == conversation.id)
                                hit.copy(conversation = hit.conversation.copy(title = title))
                            else hit
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.displayMessage()) }
            }
        }
    }

    private suspend fun runSingleSearch(raw: String, epoch: Long) {
        val q = raw.trim()
        if (q.isEmpty()) {
            if (epoch == searchEpoch) {
                _ui.update { it.copy(results = emptyList(), searching = false, query = "", error = null, noDevice = false) }
            }
            return
        }
        if (epoch != searchEpoch) return
        // 批v153 R4（R2 修复的镜像）：client 为空时 resolveClient 是悬挂点——
        // searching 声明必须先于它，否则重解析的数帧里屏上停留上一轮的
        // "无匹配"空态（R2 只给 runAggregateSearch 前移了这行）。
        _ui.update { it.copy(searching = true, query = q, error = null, noDevice = false) }
        val c = client ?: currentProfile?.let { profile ->
            // Commit the re-resolved client only while this search is still current: a
            // device switch during the token read must not be overwritten by the
            // (now stale) device's client, or every later request would hit the wrong
            // server while the screen shows the new one.
            resolveClient(profile)?.also { fresh -> if (epoch == searchEpoch) client = fresh }
        }
        if (c == null) {
            // No device/token to query: say so instead of rendering the "nothing
            // matched" empty state, which blames the query for a missing server.
            if (epoch == searchEpoch) {
                _ui.update { it.copy(results = emptyList(), searching = false, query = q, error = null, noDevice = true) }
            }
            return
        }
        val results = try {
            c.listConversations(search = q, sortBy = "updated")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The client captured its bearer at construction. A rotated/revoked token
            // 401s forever until it is rebuilt, and token edits never emit on the
            // profile flow — drop the cached client so Retry re-resolves it.
            if (e is ApiError.Unauthorized && epoch == searchEpoch) client = null
            if (epoch == searchEpoch) _ui.update { it.copy(searching = false, error = e.displayMessage()) }
            return
        }
        if (epoch != searchEpoch) return // superseded while the request was in flight
        _ui.update { it.copy(results = results, searching = false) }
        // Persisting the term is best-effort: a recents write must never turn a
        // successful search into an error page.
        try {
            recentStore.add(q)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // ignored
        }
    }

    /**
     * All-mode search (批v151): fan the query out across the aggregate scope
     * (visible devices joined into「全部」, i.e. includeInAll), one bounded request
     * per device, then merge by recency. Devices commit independently — a slow
     * device only delays its own slice, never the list (same policy as the
     * Activity aggregate feeds). Epoch-gated like the single search: a newer
     * query/mode/device event orphans in-flight slices.
     */
    private suspend fun runAggregateSearch(raw: String, epoch: Long) {
        val q = raw.trim()
        if (q.isEmpty()) {
            if (epoch == searchEpoch) {
                _ui.update {
                    it.copy(results = emptyList(), allResults = emptyList(), deviceStates = emptyList(), searching = false, query = "", error = null, noDevice = false)
                }
            }
            return
        }
        if (epoch != searchEpoch) return
        // 批v153: claim the round BEFORE the first suspension — the screen's empty
        // gate keys on `searching`, and visibleProfiles.first() can suspend on a
        // DataStore read; entering All would otherwise flash the "all devices
        // skipped" empty state for the frames before the flag landed.
        _ui.update { it.copy(searching = true, query = q, error = null, noDevice = false) }
        val scope = try {
            repository.visibleProfiles.first().filter { it.includeInAll }
        } catch (e: CancellationException) {
            // 结构化取消照常（第1轮并发审查修正）：runCatching 会把取消吞成
            // "空范围"，让被取代的轮次继续跑尾巴。
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        // 悬挂点之后重验 epoch：first() 可取消，过期轮次不得再写后续状态。
        if (epoch != searchEpoch) return
        if (scope.isEmpty()) {
            if (epoch == searchEpoch) {
                _ui.update { it.copy(allResults = emptyList(), deviceStates = emptyList(), searching = false) }
            }
            return
        }
        if (epoch != searchEpoch) return
        _ui.update {
            it.copy(deviceStates = scope.map { p -> DeviceLoad(p.id, p.name, DeviceLoadStatus.LOADING) })
        }
        val perDevice = coroutineScope {
            scope.map { profile ->
                async {
                    val outcome: Result<List<ConversationSummary>> = try {
                        Result.success(
                            withTimeoutOrNull(AGG_DEVICE_TIMEOUT_MS) {
                                repository.client(profile)
                                    ?.listConversations(search = q, sortBy = "updated")
                                    ?.take(AGG_FETCH_LIMIT)
                                    ?: throw IllegalStateException("client unavailable")
                            } ?: throw java.util.concurrent.TimeoutException("device timeout"),
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                    // Commit the slice's device state the moment it lands so the
                    // failure banner reflects reality while the round is still running.
                    if (epoch == searchEpoch) {
                        _ui.update { st ->
                            st.copy(deviceStates = st.deviceStates.map { dl ->
                                if (dl.serverId != profile.id) dl
                                else outcome.fold(
                                    onSuccess = { DeviceLoad(profile.id, profile.name, DeviceLoadStatus.OK) },
                                    onFailure = { e -> DeviceLoad(profile.id, profile.name, DeviceLoadStatus.FAILED, e.displayMessage()) },
                                )
                            })
                        }
                    }
                    profile to outcome
                }
            }.awaitAll()
        }
        if (epoch != searchEpoch) return // superseded while the round was in flight
        val merged = mergeSearchResults(
            perDevice.mapNotNull { (profile, outcome) ->
                outcome.getOrNull()?.let {
                    // 单响应内去重（第1轮墨菲验收加固）：服务端返回重复会话 id 会
                    // 直接撞 LazyColumn 的 key 崩溃，渲染前拦掉。
                    SearchDeviceResults(profile.id, profile.name, it.distinctBy { c -> c.id })
                }
            },
        )
        _ui.update { it.copy(allResults = merged, searching = false) }
        // 与单模式同规：只有真实拿到过响应的轮次才记入搜索历史——全军覆没的
        // 词不记（再次输入或 retry 自然补记）。
        if (perDevice.any { it.second.isSuccess }) {
            try {
                recentStore.add(q)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // ignored
            }
        }
    }
}

data class SearchUiState(
    val query: String = "",
    val results: List<ConversationSummary> = emptyList(),
    val folderNames: Map<Int, String> = emptyMap(),
    val searching: Boolean = false,
    val error: String? = null,
    /** No device (or no token for it) to search: the screen shows "no server
     *  selected" rather than a "nothing matched" empty state. */
    val noDevice: Boolean = false,
    /** Owning device of [results]; screens key pending dialogs on it so a floating-ball
     *  device switch closes them before their stale server-local ids can misfire. */
    val serverId: String? = null,
    // ---- 批v151 All-mode (aggregate fan-out) ----
    /** Merged recency-sorted hits across the aggregate scope. */
    val allResults: List<SearchHit> = emptyList(),
    /** Per-device round state — the failure banner reads FAILED entries. */
    val deviceStates: List<DeviceLoad> = emptyList(),
    /** Mirror of ServerRepository.searchAll at the last mode transition. */
    val allMode: Boolean = false,
)
