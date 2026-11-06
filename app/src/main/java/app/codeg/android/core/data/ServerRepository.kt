package app.codeg.android.core.data

import app.codeg.android.core.common.DispatcherProvider
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.datastore.ServerStore
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.CodegClientFactory
import app.codeg.android.core.network.EventStream
import app.codeg.android.core.security.SecretStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single entry point the UI layer uses for everything server-scoped: the saved
 * profiles + selection (from [ServerStore]), their tokens (from [SecretStore]),
 * and building API clients for them (via [CodegClientFactory]). Keeps the
 * token-handling and client construction in one place so ViewModels stay thin.
 */
@Singleton
class ServerRepository @Inject constructor(
    private val serverStore: ServerStore,
    private val secretStore: SecretStore,
    private val factory: CodegClientFactory,
    private val dispatchers: DispatcherProvider,
) {
    val profiles: Flow<List<ServerProfile>> = serverStore.profiles

    /** The day-to-day subset: hidden devices are excluded from the device bar,
     *  swipe ring, quick switches and the aggregate activity feed. The Servers
     *  management screen keeps reading [profiles] (everything, with the toggle). */
    val visibleProfiles: Flow<List<ServerProfile>> =
        serverStore.profiles.map { list -> list.filter { it.visible } }

    /** Flip a device's visibility without touching its token or selection. */
    suspend fun setVisible(id: String, visible: Boolean) {
        serverStore.update(id) { it.copy(visible = visible) }
    }

    /** Opt a device out of (or back into) the "All devices" aggregate feeds. The
     *  device stays in the bar and can still be opened on its own (batch v137). */
    suspend fun setIncludeInAll(id: String, include: Boolean) {
        serverStore.update(id) { it.copy(includeInAll = include) }
    }

    val selectedId: Flow<String?> = serverStore.selectedId

    /** Broadcast that a conversation was renamed/pinned/status-changed/deleted, so
     *  list-type screens can refetch. The faithful equivalent of iOS's
     *  `NotificationCenter` `conversationsDidChange`. */
    private val _conversationsChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val conversationsChanged: SharedFlow<Unit> = _conversationsChanged.asSharedFlow()

    fun notifyConversationsChanged() { _conversationsChanged.tryEmit(Unit) }

    /** Broadcast that the folder set changed (e.g. a worktree was registered/opened
     *  during a branch switch), so project lists can refetch. iOS's `foldersDidChange`. */
    private val _foldersChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val foldersChanged: SharedFlow<Unit> = _foldersChanged.asSharedFlow()

    fun notifyFoldersChanged() { _foldersChanged.tryEmit(Unit) }

    val selectedProfile: Flow<ServerProfile?> =
        combine(profiles, selectedId) { list, id -> list.firstOrNull { it.id == id } }

    // Activity "All devices" aggregate mode. Lives here (a @Singleton both feature
    // ViewModels already inject) because Hilt ViewModels cannot inject each other.
    // Not persisted: a killed process comes back on single-device mode, which is the
    // predictable default.
    private val _activityAll = MutableStateFlow(false)
    val activityAll: StateFlow<Boolean> = _activityAll.asStateFlow()

    fun setActivityAll(all: Boolean) { _activityAll.value = all }

    /** Pull-to-refresh tick for the "All devices" feed (increment = refetch now). */
    private val _activityRefreshTick = MutableStateFlow(0)
    val activityRefreshTick: StateFlow<Int> = _activityRefreshTick.asStateFlow()

    fun requestActivityRefresh() { _activityRefreshTick.value += 1 }

    // Chats-tab aggregate ("全部") mode — the Chats twin of [activityAll]. Same
    // lifecycle rules: in-memory only, a cold start lands on single-device mode.
    private val _chatsAll = MutableStateFlow(false)
    val chatsAll: StateFlow<Boolean> = _chatsAll.asStateFlow()

    fun setChatsAll(all: Boolean) { _chatsAll.value = all }

    // Search-tab aggregate ("全部") mode (批v151) — the third twin. Same rules:
    // in-memory only, fan-out lives in SearchViewModel, cold start is single-device.
    private val _searchAll = MutableStateFlow(false)
    val searchAll: StateFlow<Boolean> = _searchAll.asStateFlow()

    fun setSearchAll(all: Boolean) { _searchAll.value = all }

    // Task-board aggregate ("全部") mode (批v152) — the fourth twin. Same rules:
    // in-memory only, a cold start lands on the single-device board unless the
    // entry point (bottom bar / ball / workbench card) sets All first.
    private val _tasksAll = MutableStateFlow(false)
    val tasksAll: StateFlow<Boolean> = _tasksAll.asStateFlow()

    fun setTasksAll(all: Boolean) { _tasksAll.value = all }

    /** One-shot 段位 request (批v152): the workbench stat cards and the 需要处理
     *  preview ask the board to open on a specific segment. The board CONSUMES it
     *  (reads then clears) so a later tab switch doesn't re-apply a stale jump.
     *  批v153: each request carries its issue time — a tap the user never followed
     *  into the board must not hijack a much later plain visit. */
    private val _taskSegmentRequest = MutableStateFlow<Pair<TaskSegment, Long>?>(null)

    fun requestTaskSegment(segment: TaskSegment) {
        _taskSegmentRequest.value = segment to android.os.SystemClock.elapsedRealtime()
    }

    // compareAndSet loop instead of a plain read+write: the board and a stat-card
    // tap can race across threads, and a stale segment must not be re-applied.
    fun consumeTaskSegment(): TaskSegment? {
        while (true) {
            val current = _taskSegmentRequest.value ?: return null
            if (_taskSegmentRequest.compareAndSet(current, null)) {
                // 批v153: freshness window — a stat-card tap and the board entry it
                // causes are near-instant; anything older is an abandoned request.
                return if (android.os.SystemClock.elapsedRealtime() - current.second <= 10_000) {
                    current.first
                } else {
                    null
                }
            }
        }
    }

    // 批v163（舰队 M2，Q3）：工作台琥珀区「自动化失败」行 → 直达任务板的
    // 自动化段。Same one-shot discipline as the task-segment request: consumed
    // on entry, freshness-windowed so an abandoned tap never hijacks later.
    private val _automationTabRequest = MutableStateFlow<Long?>(null)

    fun requestAutomationTab() {
        _automationTabRequest.value = android.os.SystemClock.elapsedRealtime()
    }

    fun consumeAutomationTabRequest(): Boolean {
        while (true) {
            val current = _automationTabRequest.value ?: return false
            if (_automationTabRequest.compareAndSet(current, null)) {
                return android.os.SystemClock.elapsedRealtime() - current <= 10_000
            }
        }
    }

    /** Refresh tick for the aggregate Chats list (mode bumps + conversationsChanged). */
    private val _chatsRefreshTick = MutableStateFlow(0)
    val chatsRefreshTick: StateFlow<Int> = _chatsRefreshTick.asStateFlow()

    fun requestChatsRefresh() { _chatsRefreshTick.value += 1 }

    /** 批v161.3 R4: plaintext-token cache served by [token] — every
     *  client()/eventStream() build used to pay a hardware-backed Keystore
     *  decrypt, and the watchers build several per 30s round. The only writers
     *  of the secret store are [save]/[delete] below, and both update the
     *  cache in the same breath, so a re-saved or deleted profile never serves
     *  a stale token. */
    private val tokenCache = ConcurrentHashMap<String, String>()

    /** The token for a profile, or null if it has none. */
    suspend fun token(id: String): String? {
        tokenCache[id]?.let { return it }
        val token = withContext(dispatchers.io) { secretStore.get(id) } ?: return null
        tokenCache[id] = token
        return token
    }

    /** Save (insert/replace) a profile and its token. */
    suspend fun save(profile: ServerProfile, token: String) {
        withContext(dispatchers.io) { secretStore.put(profile.id, token) }
        // 批v161.3 R4: the plaintext is right here — seed the cache with the
        // value just written instead of invalidating into a fresh decrypt.
        tokenCache[profile.id] = token
        serverStore.upsert(profile)
    }

    suspend fun delete(id: String) {
        // Secret first (mirrors save): if the second step fails, a profile whose token
        // is gone is visible and recoverable; an orphaned token blob never is.
        withContext(dispatchers.io) { secretStore.remove(id) }
        tokenCache.remove(id) // 批v161.3 R4: a deleted profile must not keep serving its token.
        serverStore.delete(id)
    }

    suspend fun select(id: String?) = serverStore.select(id)

    /** An API client for a saved profile, or null if its token is missing. */
    suspend fun client(profile: ServerProfile): CodegClient? {
        val token = token(profile.id) ?: return null
        return factory.client(profile.baseUrl, token)
    }

    /** An event stream for a saved profile, or null if its token is missing. */
    suspend fun eventStream(profile: ServerProfile): EventStream? {
        val token = token(profile.id) ?: return null
        return factory.eventStream(profile.baseUrl, token)
    }

    /** A transient client for an unsaved server (e.g. "Test Connection"). */
    fun transientClient(baseUrl: String, token: String): CodegClient =
        factory.client(baseUrl, token)
}
