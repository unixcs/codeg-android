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

/** Per-server new-task folder memory: `{ servers: { "<profileId>": { lastUsed/pinned } } }`. */
@Serializable
data class DraftFolderPrefs(val servers: Map<String, ServerFolders> = emptyMap()) {
    @Serializable
    data class ServerFolders(
        /** Folder of the last draft the user actually sent (or explicitly picked). */
        val lastUsedFolderId: Int? = null,
        /** Folder pinned in Settings — beats [lastUsedFolderId] when set. */
        val pinnedFolderId: Int? = null,
    )
}

/**
 * Remembers which folder new-task drafts default to, per server (batch v134).
 * [lastUsedFolderId] is written whenever a draft is sent or the picker changes;
 * [pinnedFolderId] is the Settings override. The consumer (SessionDetailViewModel)
 * resolves pinned → last-used and validates the id against the picker-visible
 * top-level set, so a stale id (folder closed since) falls through silently.
 *
 * Deliberately NOT part of [AppSettings]: the JSON decode must not ride the
 * app-wide settings flow (same reasoning as sessionReadMarks).
 */
@Singleton
class DraftFolderPrefsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Folder memory for [profileId] (null when none). */
    fun foldersFor(profileId: String?): Flow<DraftFolderPrefs.ServerFolders?> = dataStore.data.map { prefs ->
        foldersIn(prefs[KEY], profileId)
    }

    /** One-shot read for the draft-loading path (already inside a coroutine). */
    suspend fun foldersNow(profileId: String?): DraftFolderPrefs.ServerFolders? =
        foldersIn(dataStore.data.firstOrNull()?.get(KEY), profileId)

    suspend fun setLastUsed(profileId: String?, folderId: Int) {
        if (profileId == null) return
        encodeWith(profileId) { it.copy(lastUsedFolderId = folderId) }
    }

    suspend fun setPinned(profileId: String?, folderId: Int?) {
        if (profileId == null) return
        encodeWith(profileId) { it.copy(pinnedFolderId = folderId) }
    }

    internal fun decode(raw: String?): DraftFolderPrefs =
        raw?.let { runCatching { json.decodeFromString(DraftFolderPrefs.serializer(), it) }.getOrNull() }
            ?: DraftFolderPrefs()

    private fun foldersIn(raw: String?, profileId: String?): DraftFolderPrefs.ServerFolders? =
        if (profileId == null) null else decode(raw).servers[profileId]

    private suspend fun encodeWith(
        profileId: String,
        patch: (DraftFolderPrefs.ServerFolders) -> DraftFolderPrefs.ServerFolders,
    ) {
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY])
            val servers = current.servers.toMutableMap()
            servers[profileId] = patch(servers[profileId] ?: DraftFolderPrefs.ServerFolders())
            prefs[KEY] = json.encodeToString(DraftFolderPrefs.serializer(), DraftFolderPrefs(servers))
        }
    }

    private companion object {
        val KEY = stringPreferencesKey("draft_folder_prefs")
    }
}
