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
    /** Dispatch-form drafts carry a title; the composer leaves it empty. */
    val title: String = "",
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
        setTextAll(listOf(key), text)
    }

    /** Clear (or set) several keys atomically — used by the send flow, which must
     *  remove both the pre-send new-task key and the post-create conversation key in
     *  one DataStore edit so a crash between them can't resurrect the sent text. */
    suspend fun setTextAll(keys: List<String>, text: String) {
        if (keys.isEmpty()) return
        dataStore.edit { prefs ->
            val map = decode(prefs[KEY]).toMutableMap()
            for (key in keys.distinct()) {
                if (text.isEmpty()) {
                    map.remove(key)
                } else {
                    map[key] = DraftEntry(
                        text = text.take(MAX_DRAFT_CHARS),
                        updatedAt = System.currentTimeMillis(),
                    )
                }
            }
            prefs[KEY] = json.encodeToString(SERIALIZER, map)
        }
    }

    /** Dispatch-form draft: title + body in one entry (the composer only uses [text]). */
    suspend fun setDraft(key: String, title: String, text: String) {
        dataStore.edit { prefs ->
            val map = decode(prefs[KEY]).toMutableMap()
            if (title.isEmpty() && text.isEmpty()) {
                map.remove(key)
            } else {
                map[key] = DraftEntry(
                    text = text.take(MAX_DRAFT_CHARS),
                    title = title.take(MAX_DRAFT_CHARS),
                    updatedAt = System.currentTimeMillis(),
                )
            }
            prefs[KEY] = json.encodeToString(SERIALIZER, map)
        }
    }

    suspend fun clearDraft(key: String) = setDraft(key, "", "")

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

        /** Key for the shared dispatch form. [entry] separates the hosts
         *  (hub / quick / quicknew) so their half-written tasks never cross-feed,
         *  and a different project keeps its own draft. */
        fun dispatchKey(serverId: String?, entry: String, folderId: Int): String =
            "${serverId ?: "?"}:dispatch:$entry:$folderId"

        /** 批v164: idea-editor drafts. [entry] separates the two editors ("quick"
         *  for the ball's 记录想法 sheet, "inbox" for the inbox 新想法 dialog) so
         *  a half-typed idea in one never surfaces in the other. */
        fun ideaKey(entry: String): String = "idea:$entry"
    }
}
