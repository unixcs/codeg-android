package app.codeg.android.core.datastore

import app.codeg.android.core.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for per-device agent visibility (batch v123): the JSON serde and
 * the post-fallback filter. DataStore round-trips are intentionally not covered
 * (single-key-JSON pattern, no logic of its own — same convention as ReadMarks).
 */
class DeviceAgentPrefsTest {

    @Test
    fun `serde round-trips per-server hidden sets`() {
        val prefs = DeviceAgentPrefs(
            servers = mapOf(
                "profile-a" to DeviceAgentPrefs.ServerHidden(listOf("zcode", "grok")),
                "profile-b" to DeviceAgentPrefs.ServerHidden(emptyList()),
            ),
        )
        val raw = DeviceAgentPrefsSerdeHarness.encode(prefs)
        val decoded = DeviceAgentPrefsSerdeHarness.decode(raw)
        assertEquals(prefs, decoded)
    }

    @Test
    fun `decode of garbage or null yields empty prefs`() {
        assertEquals(DeviceAgentPrefs(), DeviceAgentPrefsSerdeHarness.decode(null))
        assertEquals(DeviceAgentPrefs(), DeviceAgentPrefsSerdeHarness.decode("not json"))
    }

    @Test
    fun `unknown keys in the payload are ignored`() {
        val decoded = DeviceAgentPrefsSerdeHarness.decode("""{"servers":{"p1":{"hidden":["zcode"]}},"extra":1}""")
        assertEquals(setOf("zcode"), decoded.servers["p1"]?.hidden?.toSet())
    }

    @Test
    fun `filter removes hidden agents after the fallback`() {
        val candidates = listOf(AgentTypeWireHarness.CLAUDE, AgentTypeWireHarness.ZCODE, AgentTypeWireHarness.GROK)
        val filtered = filterHiddenAgents(candidates, setOf("zcode"))
        assertEquals(listOf(AgentTypeWireHarness.CLAUDE, AgentTypeWireHarness.GROK), filtered)
    }

    @Test
    fun `filter with empty hidden set is identity`() {
        val candidates = listOf(AgentTypeWireHarness.CLAUDE, AgentTypeWireHarness.ZCODE)
        assertEquals(candidates, filterHiddenAgents(candidates, emptySet()))
    }

    @Test
    fun `filter may legitimately return empty (everything hidden)`() {
        val candidates = listOf(AgentTypeWireHarness.ZCODE)
        assertTrue(filterHiddenAgents(candidates, setOf("zcode")).isEmpty())
    }

    @Test
    fun `hidden set never matches unknown wire values`() {
        val filtered = filterHiddenAgents(listOf(AgentTypeWireHarness.CLAUDE), setOf("nonsense"))
        assertFalse(filtered.isEmpty())
    }
}

/** Serde access without spinning the DataStore-backed singleton. */
private object DeviceAgentPrefsSerdeHarness {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun encode(prefs: DeviceAgentPrefs): String = json.encodeToString(DeviceAgentPrefs.serializer(), prefs)

    fun decode(raw: String?): DeviceAgentPrefs =
        raw?.let { runCatching { json.decodeFromString(DeviceAgentPrefs.serializer(), it) }.getOrNull() }
            ?: DeviceAgentPrefs()
}

/** Wire-name fixtures mirroring AgentType.wire without touching the enum (keeps this test pure). */
private object AgentTypeWireHarness {
    val CLAUDE: AgentType = AgentType.CLAUDE_CODE
    val ZCODE: AgentType = AgentType.ZCODE
    val GROK: AgentType = AgentType.GROK
}
