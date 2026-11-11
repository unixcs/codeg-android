package app.codeg.android.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Per-device pinned directories: `{ servers: { "<profileId>": [{label, path}] } }`. */
@Serializable
data class CommonDirsPrefs(val servers: Map<String, List<ServerCommonDir>> = emptyMap())

/** One jump-menu entry in the file screen (批v176). Paths are device-absolute —
 *  tx and dell mount different trees, so the list is keyed per [ServerProfile] id. */
@Serializable
data class ServerCommonDir(val label: String, val path: String)

/**
 * The file screen's 常用目录 binding (batch v176). Per-device on purpose: /mnt on
 * tx is not /mnt on dell. Deliberately NOT part of [AppSettings] — the JSON decode
 * must not ride the app-wide settings flow (same reasoning as DeviceAgentPrefs).
 *
 * Empty stored list ⇒ the jump menu falls back to built-in defaults
 * ([DeviceCommonDirsDefaults]); the management screen seeds the defaults as
 * ordinary editable rows, after which the stored list is authoritative.
 */
@Singleton
class CommonDirsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Pinned dirs for [profileId] (empty when none — menu shows defaults). */
    fun dirsFor(profileId: String?): Flow<List<ServerCommonDir>> = dataStore.data.map { prefs ->
        if (profileId == null) emptyList() else decode(prefs[KEY]).servers[profileId].orEmpty()
    }

    /** One-shot read for the jump menu's first render (already inside a coroutine). */
    suspend fun dirsNow(profileId: String?): List<ServerCommonDir> =
        if (profileId == null) emptyList() else decode(dataStore.data.firstOrNull()?.get(KEY)).servers[profileId].orEmpty()

    suspend fun setDirs(profileId: String, dirs: List<ServerCommonDir>) {
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY])
            val servers = current.servers.toMutableMap()
            if (dirs.isEmpty()) servers.remove(profileId) else servers[profileId] = dirs
            prefs[KEY] = json.encodeToString(CommonDirsPrefs.serializer(), CommonDirsPrefs(servers))
        }
    }

    private fun decode(raw: String?): CommonDirsPrefs =
        raw?.let { runCatching { json.decodeFromString(CommonDirsPrefs.serializer(), it) }.getOrNull() }
            ?: CommonDirsPrefs()

    private companion object {
        val KEY = stringPreferencesKey("common_dirs")
    }
}

/** Built-in jump targets shown while the user hasn't bound any custom dir. */
object CommonDirsDefaults {
    const val MNT = "/mnt"
    const val PROJECTS = "/mnt/Projects"
}
