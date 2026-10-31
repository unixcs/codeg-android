package app.codeg.android.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the list of [ServerProfile]s and which one is selected, in a
 * DataStore (the profiles list is JSON-encoded under one key). The selected id
 * survives restarts, mirroring the iOS `codeg.lastSelectedServerID`.
 */
@Singleton
class ServerStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // DataStore is ONE preferences file shared by every store in the app (settings,
    // drafts, read marks, recent searches, …), so `dataStore.data` re-emits on any
    // write anywhere. Without the equality gate, storing an unrelated key looks like
    // a profile change to every collector — the search screen wiped its results on
    // the "recent searches" write that followed each successful query.
    val profiles: Flow<List<ServerProfile>> =
        dataStore.data.map { decode(it[PROFILES]) }.distinctUntilChanged()

    val selectedId: Flow<String?> =
        dataStore.data.map { it[SELECTED] }.distinctUntilChanged()

    /** Insert or replace a profile (matched by id). First profile auto-selects. */
    suspend fun upsert(profile: ServerProfile) {
        dataStore.edit { prefs ->
            val list = decode(prefs[PROFILES]).toMutableList()
            val idx = list.indexOfFirst { it.id == profile.id }
            if (idx >= 0) list[idx] = profile else list.add(profile)
            prefs[PROFILES] = json.encodeToString(list)
            if (prefs[SELECTED] == null) prefs[SELECTED] = profile.id
        }
    }

    /** Remove a profile; if it was selected, fall back to the first remaining
     *  VISIBLE one (a hidden device must not become the app's focused device just
     *  because the previous one was deleted — the day-to-day surfaces don't list it). */
    suspend fun delete(id: String) {
        dataStore.edit { prefs ->
            val list = decode(prefs[PROFILES]).filterNot { it.id == id }
            prefs[PROFILES] = json.encodeToString(list)
            if (prefs[SELECTED] == id) {
                val next = list.firstOrNull { it.visible }?.id
                if (next != null) prefs[SELECTED] = next else prefs.remove(SELECTED)
            }
        }
    }

    suspend fun select(id: String?) {
        dataStore.edit { prefs ->
            if (id == null) prefs.remove(SELECTED) else prefs[SELECTED] = id
        }
    }

    private fun decode(raw: String?): List<ServerProfile> =
        raw?.let { runCatching { json.decodeFromString<List<ServerProfile>>(it) }.getOrNull() }
            ?: emptyList()

    private companion object {
        val PROFILES = stringPreferencesKey("server_profiles")
        val SELECTED = stringPreferencesKey("selected_server_id")
    }
}
