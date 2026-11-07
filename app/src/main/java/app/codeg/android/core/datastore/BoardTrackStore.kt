package app.codeg.android.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * P1 任务板追踪：一条被用户「加入任务板」的会话。会话和任务在服务端是两个
 * 独立实体——`create_conversation` 只写一行，没有 worktree，也不进看板——
 * 所以「这条会话算任务板成员」这件事只能由 App 本地记住。
 *
 * 为什么不在服务端加字段：主人明确要求不动部署中的 Codeg Server，避免上游
 * 同步时冲突；代价是网页端看不到这些成员、换机不带走（规划 §4.5 已写明）。
 *
 * [conversationId] 是服务端自增 id，只在 [serverId] 内唯一，所以两者合起来
 * 才是身份（与 SessionRef / 已读标记同一约定）。
 */
@Serializable
data class TrackedConversation(
    val serverId: String,
    val conversationId: Int,
    val folderId: Int,
    val addedAt: Long,
    /** 非 null = 用户在看板上归档了这条会话（本地标记，不改服务端状态）。 */
    val archivedAt: Long? = null,
) {
    val key: String get() = "$serverId:$conversationId"
}

/**
 * 任务板成员账本（Preferences DataStore，单 JSON key）。解码失败退化为空表，
 * 与 [SessionProjectStore] 同一条降级策略：读坏了不能把整页看板拖死。
 *
 * 「连续两轮全量成功都没看到才剪枝」不在这里实现——那是拉取层（VM）的判断，
 * 只有它知道这一轮是不是完整成功。
 */
@Singleton
class BoardTrackStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val tracked: Flow<List<TrackedConversation>> =
        dataStore.data.map { decode(it[TRACKED]) }

    /** 一次性读取（VM 拉取层用）——不订阅上游，DataStore 抖动不卡协程。
     *  批v165 R2 (P2): JSON 解码失败返回 null（≠ 空表）——拉取层据此跳过本轮，
     *  而不是把「读不了」当成「没有成员」把看板切片清空。 */
    suspend fun trackedNow(): List<TrackedConversation>? = dataStore.data.first().let { decodeOrNull(it[TRACKED]) }

    /** 加入看板；已存在则保留原 addedAt（幂等，重复点不重置时间），但**清掉归档
     *  标记**——重新加入一条归档过的会话，卡片必须回到列里；否则点了没反应，
     *  看起来像按钮坏了。 */
    suspend fun track(serverId: String, conversationId: Int, folderId: Int): Boolean {
        // 批v165 R5: a decode failure skips the edit (R2 rule) — report it so
        // the caller can surface it instead of a silent no-op. An idempotent
        // re-add (already tracked, not archived) legitimately writes nothing
        // and still reports true.
        var decodeFailed = false
        dataStore.edit { prefs ->
            // 批v165 R2 (P2): all four writers are read-modify-write over the
            // whole list — a decode failure used to read as empty and REWRITE
            // the ledger as empty (silent wipe). Skip the edit instead.
            val list = decodeOrNull(prefs[TRACKED]) ?: run { decodeFailed = true; return@edit }
            val index = list.indexOfFirst {
                it.serverId == serverId && it.conversationId == conversationId
            }
            if (index >= 0) {
                if (list[index].archivedAt != null) {
                    prefs[TRACKED] = encode(
                        list.toMutableList().also { it[index] = it[index].copy(archivedAt = null) },
                    )
                }
                return@edit
            }
            prefs[TRACKED] = encode(
                list + TrackedConversation(
                    serverId = serverId,
                    conversationId = conversationId,
                    folderId = folderId,
                    addedAt = System.currentTimeMillis(),
                ),
            )
        }
        return !decodeFailed
    }

    /** 移出看板（手动清理，以及剪枝用）。 */
    suspend fun untrack(serverId: String, conversationId: Int): Boolean {
        // 批v165 R5: decode failure is reported, not silent (see [track]).
        var decodeFailed = false
        dataStore.edit { prefs ->
            val list = decodeOrNull(prefs[TRACKED]) ?: run { decodeFailed = true; return@edit }
            prefs[TRACKED] = encode(
                list.filterNot {
                    it.serverId == serverId && it.conversationId == conversationId
                },
            )
        }
        return !decodeFailed
    }

    /** 看板上的「归档 / 取消归档」——本地标记，不改服务端会话状态。 */
    suspend fun setArchived(serverId: String, conversationId: Int, archived: Boolean) {
        dataStore.edit { prefs ->
            val now = System.currentTimeMillis()
            val list = decodeOrNull(prefs[TRACKED]) ?: return@edit
            prefs[TRACKED] = encode(
                list.map {
                    if (it.serverId == serverId && it.conversationId == conversationId) {
                        it.copy(archivedAt = if (archived) (it.archivedAt ?: now) else null)
                    } else {
                        it
                    }
                },
            )
        }
    }

    /** 批量剪枝：只删调用方已确认「连续两轮全量成功都没出现」的 key。 */
    suspend fun prune(keys: Set<String>) {
        if (keys.isEmpty()) return
        dataStore.edit { prefs ->
            // 批v165 R2 (P2): a corrupt ledger must not be "pruned" into an
            // empty one — bail and leave the bytes alone.
            val list = decodeOrNull(prefs[TRACKED]) ?: return@edit
            prefs[TRACKED] = encode(list.filterNot { it.key in keys })
        }
    }

    private fun decodeOrNull(raw: String?): List<TrackedConversation>? {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(SERIAL, raw) }.getOrNull()
    }

    private fun decode(raw: String?): List<TrackedConversation> =
        decodeOrNull(raw) ?: emptyList()

    private fun encode(list: List<TrackedConversation>): String = json.encodeToString(SERIAL, list)

    private companion object {
        val TRACKED = stringPreferencesKey("board_tracked_conversations")
        val SERIAL = ListSerializer(TrackedConversation.serializer())
    }
}
