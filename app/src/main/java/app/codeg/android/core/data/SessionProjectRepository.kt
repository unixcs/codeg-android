package app.codeg.android.core.data

import app.codeg.android.core.datastore.SessionProject
import app.codeg.android.core.datastore.SessionProjectStore
import app.codeg.android.core.datastore.SessionRef
import app.codeg.android.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UI-facing holder for the cross-device session projects: the project list and
 * membership (persisted via [SessionProjectStore]) plus the currently active
 * filter (in-memory, like [ServerRepository.activityAll]). Lives in core/data
 * so feature ViewModels and MainShell share one instance — Hilt ViewModels
 * cannot inject each other.
 *
 * Invariant (batch v132 review #3): `activeProjectId != null` only while the
 * showing surface is in an aggregate ("全部") mode; MainShell clears it whenever
 * the UI leaves that context so a single-device list is never silently filtered.
 */
@Singleton
class SessionProjectRepository @Inject constructor(
    private val store: SessionProjectStore,
    @ApplicationScope private val scope: CoroutineScope,
) {
    val projects: StateFlow<List<SessionProject>> =
        store.projects
            .map { list -> list.sortedBy { it.sortOrder } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val members: StateFlow<Map<String, List<SessionRef>>> =
        store.members
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val _activeProjectId = MutableStateFlow<String?>(null)
    val activeProjectId: StateFlow<String?> = _activeProjectId.asStateFlow()

    /** Member keys ("serverId:conversationId") of the active project, or null
     *  when no filter is active — the exact shape the section builders want. */
    val activeMemberKeys: Flow<Set<String>?> =
        combine(_activeProjectId, members) { id, mem ->
            id?.let { pid -> mem[pid]?.map { it.key }?.toSet() }
        }

    fun setActive(id: String?) {
        _activeProjectId.value = id?.takeIf { pid -> projects.value.any { it.id == pid } }
    }

    /**
     * Activate an id straight out of [create] WITHOUT re-validating against the
     * [projects] StateFlow: that snapshot is fed on the ApplicationScope and lags
     * the DataStore write by ≥1 dispatcher hop, so `setActive` on a just-created
     * id deterministically drops it (R3-1, proven by test). Callers must only pass
     * ids returned by [create].
     */
    fun activateCreated(id: String) {
        _activeProjectId.value = id
    }

    fun projectById(id: String?): SessionProject? = projects.value.firstOrNull { it.id == id }

    /** Project ids a session belongs to (drives the add-to-project checkmarks). */
    fun projectIdsOf(refKey: String): Set<String> =
        members.value.filterValues { refs -> refs.any { it.key == refKey } }.keys

    fun memberCount(projectId: String): Int = members.value[projectId]?.size ?: 0

    suspend fun create(name: String): SessionProject {
        val project = SessionProject(
            id = java.util.UUID.randomUUID().toString(),
            name = name.trim(),
            createdAt = System.currentTimeMillis(),
            sortOrder = (projects.value.maxOfOrNull { it.sortOrder } ?: 0) + 1,
        )
        store.create(project)
        return project
    }

    suspend fun rename(id: String, name: String) = store.rename(id, name.trim())

    suspend fun remove(id: String) {
        if (_activeProjectId.value == id) _activeProjectId.value = null
        store.removeProject(id)
    }

    suspend fun setMembership(projectId: String, ref: SessionRef, member: Boolean) =
        store.setMembership(projectId, ref, member)

    /** Session deleted on its device — drop the ref everywhere (review #8). */
    suspend fun removeRef(serverId: String, conversationId: Int) =
        store.removeRef(serverId, conversationId)

    /** Device deleted — drop all its refs (review #8). */
    suspend fun removeRefsForServer(serverId: String) = store.removeRefsForServer(serverId)

    /**
     * Prune stale refs for one server. Call ONLY with a complete (untruncated)
     * conversation list — `take(200)` output must not prune (review #5).
     */
    suspend fun pruneServerIfComplete(serverId: String, liveKeys: Set<String>, truncated: Boolean) {
        if (!truncated) store.pruneServer(serverId, liveKeys)
    }
}
