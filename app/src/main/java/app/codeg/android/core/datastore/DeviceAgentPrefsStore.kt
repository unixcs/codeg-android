package app.codeg.android.core.datastore

import app.codeg.android.core.model.AgentType
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

/** Per-device, per-server hidden agents: `{ servers: { "<profileId>": { hidden: [...] } } }`. */
@Serializable
data class DeviceAgentPrefs(val servers: Map<String, ServerHidden> = emptyMap()) {
    @Serializable
    data class ServerHidden(val hidden: List<String> = emptyList())
}

/**
 * The device-local half of agent visibility (batch v123). The server's agent
 * `enabled` flag (acp_update_agent_env) already differs per server; this store
 * adds a THIS PHONE layer on top — hide an agent on one phone without touching
 * the server or the other phones. Keyed by [app.codeg.android.core.datastore.ServerProfile]
 * id (the local profile UUID), agent values are [AgentType.wire] strings.
 *
 * Deliberately NOT part of [AppSettings]: the JSON decode must not ride the
 * app-wide settings flow (same reasoning as sessionReadMarks).
 */
@Singleton
class DeviceAgentPrefsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Agents hidden for [profileId] (empty when none). */
    fun hiddenFor(profileId: String?): Flow<Set<String>> = dataStore.data.map { prefs ->
        hiddenIn(prefs[KEY], profileId)
    }

    /** One-shot read for the draft-loading path (already inside a coroutine). */
    suspend fun hiddenNow(profileId: String?): Set<String> =
        hiddenIn(dataStore.data.firstOrNull()?.get(KEY), profileId)

    suspend fun setHidden(profileId: String?, hidden: Set<String>) {
        if (profileId == null) return
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY])
            val servers = current.servers.toMutableMap()
            if (hidden.isEmpty()) servers.remove(profileId) else servers[profileId] = DeviceAgentPrefs.ServerHidden(hidden.toList())
            prefs[KEY] = json.encodeToString(DeviceAgentPrefs.serializer(), DeviceAgentPrefs(servers))
        }
    }

    /** Toggle helper for the settings row: returns the new hidden state. */
    suspend fun toggle(profileId: String?, wire: String): Boolean {
        if (profileId == null) return false
        val current = hiddenIn(dataStore.data.firstOrNull()?.get(KEY), profileId)
        val next = if (wire in current) current - wire else current + wire
        setHidden(profileId, next)
        return wire in next
    }

    internal fun decode(raw: String?): DeviceAgentPrefs =
        raw?.let { runCatching { json.decodeFromString(DeviceAgentPrefs.serializer(), it) }.getOrNull() }
            ?: DeviceAgentPrefs()

    private fun hiddenIn(raw: String?, profileId: String?): Set<String> =
        if (profileId == null) emptySet() else decode(raw).servers[profileId]?.hidden?.toSet() ?: emptySet()

    private companion object {
        val KEY = stringPreferencesKey("device_agent_prefs")
    }
}

/**
 * Pure filter applied AFTER the draft-picker's `ifEmpty` fallback (batch v123):
 * folding it into the earlier chain would let the fallback re-admit hidden
 * agents when a server reports no enabled ones. The result may be empty — an
 * empty picker is the honest rendering of "everything on this phone is hidden".
 */
fun filterHiddenAgents(candidates: List<AgentType>, hidden: Set<String>): List<AgentType> =
    candidates.filter { it.wire !in hidden }
