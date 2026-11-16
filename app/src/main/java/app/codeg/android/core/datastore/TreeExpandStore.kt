package app.codeg.android.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 批v180-C: the file tree's persisted expand/collapse memory. Keys are
 * "serverId|root|path" — the same on-disk directory under a different device or
 * a different browse root keeps independent state, and a stale key (dir deleted,
 * device removed) simply matches nothing, so there is no pruning pass.
 *
 * Same default-DataStore pattern as the aggregate filter stores: inject the
 * app-wide Preferences DataStore, own one key.
 */
@Singleton
class TreeExpandStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val expanded: Flow<Set<String>> =
        dataStore.data.map { prefs -> prefs[KEY] ?: emptySet() }

    /** Flip one node's expansion under one (device, root) context. */
    suspend fun setExpanded(key: String, expanded: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[KEY] ?: emptySet()
            prefs[KEY] = if (expanded) current + key else current - key
        }
    }

    companion object {
        private val KEY = stringPreferencesKey("files_tree_expanded")
    }
}
