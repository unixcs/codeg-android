package app.codeg.android.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Per-device last browsed root: `{ servers: { "<profileId>": "/path" } }`. */
@Serializable
data class LastBrowsePrefs(val servers: Map<String, String> = emptyMap())

/**
 * 批v181: remembers where the user last browsed on each device, so "全部文件"
 * reopens there instead of always landing on $HOME. Per-device JSON prefs with
 * the same shape and rationale as [CommonDirsStore] — the decode must not ride
 * the app-wide settings flow. No fallback defaults: null = never browsed here.
 */
@Singleton
class LastBrowseStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun lastFor(profileId: String?): Flow<String?> = dataStore.data.map { prefs ->
        if (profileId == null) null else decode(prefs[KEY]).servers[profileId]
    }

    /** One-shot read for the "all files" entry point. */
    suspend fun lastNow(profileId: String?): String? =
        if (profileId == null) null else decode(dataStore.data.firstOrNull()?.get(KEY)).servers[profileId]

    suspend fun setLast(profileId: String, path: String) {
        dataStore.edit { prefs ->
            val servers = decode(prefs[KEY]).servers.toMutableMap()
            servers[profileId] = path
            prefs[KEY] = json.encodeToString(LastBrowsePrefs.serializer(), LastBrowsePrefs(servers))
        }
    }

    private fun decode(raw: String?): LastBrowsePrefs =
        raw?.let { runCatching { json.decodeFromString(LastBrowsePrefs.serializer(), it) }.getOrNull() }
            ?: LastBrowsePrefs()

    private companion object {
        val KEY = stringPreferencesKey("last_browse_root")
    }
}
