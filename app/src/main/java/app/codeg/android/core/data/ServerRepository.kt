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
        val profile = serverStore.profiles.first().firstOrNull { it.id == id } ?: return
        serverStore.upsert(profile.copy(visible = visible))
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

    /** Refresh tick for the aggregate Chats list (mode bumps + conversationsChanged). */
    private val _chatsRefreshTick = MutableStateFlow(0)
    val chatsRefreshTick: StateFlow<Int> = _chatsRefreshTick.asStateFlow()

    fun requestChatsRefresh() { _chatsRefreshTick.value += 1 }

    /** The token for a profile, read off the IO dispatcher (Keystore decrypt). */
    suspend fun token(id: String): String? =
        withContext(dispatchers.io) { secretStore.get(id) }

    /** Save (insert/replace) a profile and its token. */
    suspend fun save(profile: ServerProfile, token: String) {
        withContext(dispatchers.io) { secretStore.put(profile.id, token) }
        serverStore.upsert(profile)
    }

    suspend fun delete(id: String) {
        // Secret first (mirrors save): if the second step fails, a profile whose token
        // is gone is visible and recoverable; an orphaned token blob never is.
        withContext(dispatchers.io) { secretStore.remove(id) }
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
