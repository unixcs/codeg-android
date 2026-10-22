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

/** Max characters persisted per draft. Longer text still lives in memory for the
 *  session — it just doesn't round-trip a process death (keeps the single prefs
 *  file small and the edit cheap). */
private const val MAX_DRAFT_CHARS = 100_000

@Serializable
data class DraftEntry(
    val text: String = "",
    val updatedAt: Long = 0,
)

/**
 * Per-conversation message drafts, persisted to DataStore so text survives leaving
 * the screen *and* a process kill (the Telegram model: keyed by conversation, saved
 * continuously, cleared on send). Keys are `"<serverId>:<conversationId>"` for an
 * existing conversation and `"<serverId>:new:<folderId>"` for the pre-send new-task
 * screen (the conversation id doesn't exist until the first send lands).
 */
@Singleton
class DraftStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun draft(key: String): Flow<DraftEntry> =
        dataStore.data.map { prefs -> decode(prefs[KEY])[key] ?: DraftEntry() }

    suspend fun draftNow(key: String): DraftEntry =
        decode(dataStore.data.first()[KEY])[key] ?: DraftEntry()

    suspend fun setText(key: String, text: String) {
        dataStore.edit { prefs ->
            val map = decode(prefs[KEY]).toMutableMap()
            if (text.isEmpty()) {
                map.remove(key)
            } else {
                map[key] = DraftEntry(text.take(MAX_DRAFT_CHARS), System.currentTimeMillis())
            }
            prefs[KEY] = json.encodeToString(SERIALIZER, map)
        }
    }

    private fun decode(raw: String?): Map<String, DraftEntry> =
        raw?.let { runCatching { json.decodeFromString(SERIALIZER, it) }.getOrNull() } ?: emptyMap()

    companion object {
        private val KEY = stringPreferencesKey("conversation_drafts")
        private val SERIALIZER = MapSerializer(String.serializer(), DraftEntry.serializer())

        /** Key for an existing conversation. */
        fun conversationKey(serverId: String?, conversationId: Int): String =
            "${serverId ?: "?"}:$conversationId"

        /** Key for the new-task composer (conversation not created yet). */
        fun newTaskKey(serverId: String?, folderId: Int): String =
            "${serverId ?: "?"}:new:$folderId"
    }
}
