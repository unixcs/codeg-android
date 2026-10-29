package app.codeg.android.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.codeg.android.core.model.AgentType
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-conversation override layer over the per-agent [SelectorPrefsStore].
 *
 * Why: [SelectorPrefsStore] is keyed by agent GLOBALLY, so a model (config) pick inside
 * one conversation would follow the user into every other conversation of the same
 * agent — on Android, where connections are ephemeral, each reconnect replays the
 * last-chosen model via `acp_connect` preferred values and the server faithfully applies
 * it. This store pins config picks to `<serverId>:<conversationId>` so a conversation
 * sticks to its own model. MODE is deliberately NOT symmetric (batch v133): a confirmed
 * mode pick is promoted to the agent-level default by SessionDetailViewModel.applyMode
 * (mode is a sticky global preference), while config options stay conversation-locked.
 * [resolveSelectorPrefs] layers the two: conversation overrides per field, agent prefs
 * remain the fallback for everything the conversation layer never touched.
 */
@Singleton
class ConversationSelectorStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The conversation's own picks for [agent] (empty if it never diverged). */
    suspend fun prefs(serverId: String?, conversationId: Int, agent: AgentType): SelectorPrefs =
        all()[key(serverId, conversationId)]?.get(agent.wire) ?: SelectorPrefs()

    /** Remember one config option's value for THIS conversation. */
    suspend fun saveConfig(serverId: String?, conversationId: Int, agent: AgentType, configId: String, valueId: String) =
        update(serverId, conversationId, agent) { it.copy(configValues = (it.configValues ?: emptyMap()) + (configId to valueId)) }

    /** Remember the conversation's mode pick. */
    suspend fun saveMode(serverId: String?, conversationId: Int, agent: AgentType, modeId: String) =
        update(serverId, conversationId, agent) { it.copy(modeId = modeId) }

    private fun key(serverId: String?, conversationId: Int) = "${serverId ?: "?"}:$conversationId"

    private suspend fun all(): Map<String, Map<String, SelectorPrefs>> = decode(dataStore.data.first()[KEY])

    private suspend fun update(
        serverId: String?,
        conversationId: Int,
        agent: AgentType,
        mutate: (SelectorPrefs) -> SelectorPrefs,
    ) {
        dataStore.edit { prefs ->
            val outer = decode(prefs[KEY]).toMutableMap()
            val inner = outer[key(serverId, conversationId)].orEmpty().toMutableMap()
            inner[agent.wire] = mutate(inner[agent.wire] ?: SelectorPrefs())
            outer[key(serverId, conversationId)] = inner
            prefs[KEY] = json.encodeToString(SERIALIZER, outer)
        }
    }

    private fun decode(raw: String?): Map<String, Map<String, SelectorPrefs>> =
        raw?.let { runCatching { json.decodeFromString(SERIALIZER, it) }.getOrNull() } ?: emptyMap()

    private companion object {
        val KEY = stringPreferencesKey("conversation_selector_prefs")
        val SERIALIZER = MapSerializer(
            String.serializer(),
            MapSerializer(String.serializer(), SelectorPrefs.serializer()),
        )
    }
}
