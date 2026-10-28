package app.codeg.android.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A user-defined cross-device grouping of sessions ("项目"). Purely an app-local
 * organizational view: membership references live on the owning devices as
 * (serverId, conversationId) pairs — the same identity convention as the read
 * marks — so a project can span any number of devices and agents without a
 * central server. Named `SessionProject` because the `projects` feature package
 * already denotes workspace folders.
 */
@Serializable
data class SessionProject(
    val id: String,
    val name: String,
    val createdAt: Long,
    val sortOrder: Int,
)

/**
 * One session's membership reference. [conversationId] is server-local (an
 * autoincrement Int), so the pair is the only stable cross-device identity.
 */
@Serializable
data class SessionRef(
    val serverId: String,
    val conversationId: Int,
) {
    /** Key matching the `session_read_marks` / handoff-reference convention. */
    val key: String get() = "$serverId:$conversationId"
}

/**
 * Persists the [SessionProject] list and the per-project [SessionRef] membership
 * in the shared Preferences DataStore (two JSON keys). Follows [ServerStore]:
 * decode failures degrade to empty rather than throwing on every read. Every
 * mutator rewrites inside a single [DataStore.edit] so concurrent callers can't
 * interleave a read-modify-write.
 */
@Singleton
class SessionProjectStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val projects: Flow<List<SessionProject>> =
        dataStore.data.map { decodeProjects(it[PROJECTS]) }

    val members: Flow<Map<String, List<SessionRef>>> =
        dataStore.data.map { decodeMembers(it[MEMBERS]) }

    suspend fun create(project: SessionProject) {
        dataStore.edit { prefs ->
            val list = decodeProjects(prefs[PROJECTS]).toMutableList()
            list += project
            prefs[PROJECTS] = encodeProjects(list)
        }
    }

    suspend fun rename(id: String, name: String) {
        dataStore.edit { prefs ->
            val list = decodeProjects(prefs[PROJECTS]).map {
                if (it.id == id) it.copy(name = name) else it
            }
            prefs[PROJECTS] = encodeProjects(list)
        }
    }

    /** Removes a project and every membership entry pointing at it. */
    suspend fun removeProject(id: String) {
        dataStore.edit { prefs ->
            prefs[PROJECTS] = encodeProjects(decodeProjects(prefs[PROJECTS]).filterNot { it.id == id })
            prefs[MEMBERS] = encodeMembers(decodeMembers(prefs[MEMBERS]).filterKeys { it != id })
        }
    }

    suspend fun setMembership(projectId: String, ref: SessionRef, member: Boolean) {
        dataStore.edit { prefs ->
            val map = decodeMembers(prefs[MEMBERS]).toMutableMap()
            val refs = (map[projectId] ?: emptyList()).filterNot { it.key == ref.key }
            map[projectId] = if (member) refs + ref else refs
            prefs[MEMBERS] = encodeMembers(map)
        }
    }

    /** Session deleted on a device: drop the ref from every project. */
    suspend fun removeRef(serverId: String, conversationId: Int) {
        removeRefKey(SessionRef(serverId, conversationId).key)
    }

    /** Device deleted (or wiped): drop all refs of that server from all projects. */
    suspend fun removeRefsForServer(serverId: String) {
        removeRefKey { it.startsWith("$serverId:") }
    }

    /**
     * Drop this server's refs that are absent from [liveKeys] (complete,
     * untruncated conversation list — callers MUST pass `size < fetch limit`).
     * [liveKeys] carries BARE conversation-id strings ("12", no server prefix —
     * the exact shape of `convs.map { it.id.toString() }` from the fetch).
     */
    suspend fun pruneServer(serverId: String, liveKeys: Set<String>) {
        val prefix = "$serverId:"
        removeRefKey { it.startsWith(prefix) && it.substringAfter(':') !in liveKeys }
    }

    private suspend fun removeRefKey(key: String) = removeRefKey { it == key }

    private suspend fun removeRefKey(drop: (String) -> Boolean) {
        dataStore.edit { prefs ->
            val map = decodeMembers(prefs[MEMBERS])
                .mapValues { (_, refs) -> refs.filterNot { drop(it.key) } }
                .filterValues { it.isNotEmpty() }
            prefs[MEMBERS] = encodeMembers(map)
        }
    }

    private fun encodeProjects(list: List<SessionProject>): String =
        json.encodeToString(ListSerializer(SessionProject.serializer()), list)

    private fun encodeMembers(map: Map<String, List<SessionRef>>): String =
        json.encodeToString(
            MapSerializer(String.serializer(), ListSerializer(SessionRef.serializer())),
            map,
        )

    private fun decodeProjects(raw: String?): List<SessionProject> =
        raw?.let {
            runCatching { json.decodeFromString(ListSerializer(SessionProject.serializer()), it) }.getOrNull()
        } ?: emptyList()

    private fun decodeMembers(raw: String?): Map<String, List<SessionRef>> =
        raw?.let {
            runCatching {
                json.decodeFromString(
                    MapSerializer(String.serializer(), ListSerializer(SessionRef.serializer())),
                    it,
                )
            }.getOrNull()
        } ?: emptyMap()

    private companion object {
        val PROJECTS = stringPreferencesKey("session_projects")
        val MEMBERS = stringPreferencesKey("session_project_members")
    }
}
