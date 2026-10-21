package app.codeg.android.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Per-server display preferences for the Chats list: which folder groups are
 *  hidden from the home list, and which section headers are collapsed. */
@Serializable
data class ChatListDisplay(
    val hiddenSections: Set<String> = emptySet(),
    val collapsedSections: Set<String> = emptySet(),
)

/**
 * Local display preferences for the session list, keyed by server id. Purely a
 * client-side view filter — the server has no "hidden folder" concept (only its
 * own chat-kind folders, which never reach this list), so hiding is done here.
 * Section ids are the stable section keys (`folder-12`, `other`; the pinned
 * section is never hidden), so folder renames don't break the filter.
 *
 * Collapsed section state is persisted here too, fixing the "every launch
 * re-expands everything" behavior of the previous in-memory-only fold set.
 */
@Singleton
class ChatListDisplayStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun display(serverId: String): Flow<ChatListDisplay> =
        dataStore.data.map { prefs -> decode(prefs[KEY])[serverId] ?: ChatListDisplay() }

    suspend fun displayNow(serverId: String): ChatListDisplay =
        decode(dataStore.data.first()[KEY])[serverId] ?: ChatListDisplay()

    suspend fun setHidden(serverId: String, hidden: Set<String>) =
        update(serverId) { it.copy(hiddenSections = hidden) }

    suspend fun setCollapsed(serverId: String, collapsed: Set<String>) =
        update(serverId) { it.copy(collapsedSections = collapsed) }

    private suspend fun update(serverId: String, mutate: (ChatListDisplay) -> ChatListDisplay) {
        dataStore.edit { prefs ->
            val map = decode(prefs[KEY]).toMutableMap()
            map[serverId] = mutate(map[serverId] ?: ChatListDisplay())
            prefs[KEY] = json.encodeToString(SERIALIZER, map)
        }
    }

    private fun decode(raw: String?): Map<String, ChatListDisplay> =
        raw?.let { runCatching { json.decodeFromString(SERIALIZER, it) }.getOrNull() } ?: emptyMap()

    private companion object {
        val KEY = stringPreferencesKey("chat_list_display")
        val SERIALIZER = MapSerializer(String.serializer(), ChatListDisplay.serializer())
    }
}
