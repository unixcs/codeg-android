package app.codeg.android.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Batch v133 regression: the layered selector resolution must keep the agent-level
 * MODE alive for conversations that only ever picked a model — the old all-or-nothing
 * takeover dropped it, and every reconnect fell back to the server's default (YOLO →
 * Build, the owner-reported bug).
 */
class ResolveSelectorPrefsTest {

    @Test
    fun `conversation empty falls through to agent prefs entirely`() {
        val resolved = resolveSelectorPrefs(
            conv = SelectorPrefs(),
            agent = SelectorPrefs(modeId = "yolo", configValues = mapOf("model" to "gpt-6")),
        )
        assertEquals("yolo", resolved.modeId)
        assertEquals(mapOf("model" to "gpt-6"), resolved.configValues)
    }

    @Test
    fun `conversation with only a model keeps the agent-level mode`() {
        val resolved = resolveSelectorPrefs(
            conv = SelectorPrefs(configValues = mapOf("model" to "deepseek-v4")),
            agent = SelectorPrefs(modeId = "yolo", configValues = mapOf("model" to "gpt-6", "effort" to "high")),
        )
        // THE fix: mode survives the conversation-level config pick.
        assertEquals("yolo", resolved.modeId)
        // Per-key merge: conversation wins the keys it picked, agent fills the rest.
        assertEquals(mapOf("model" to "deepseek-v4", "effort" to "high"), resolved.configValues)
    }

    @Test
    fun `conversation mode wins over agent default`() {
        val resolved = resolveSelectorPrefs(
            conv = SelectorPrefs(modeId = "build"),
            agent = SelectorPrefs(modeId = "yolo"),
        )
        assertEquals("build", resolved.modeId)
    }

    @Test
    fun `null conversation is agent prefs unchanged`() {
        val resolved = resolveSelectorPrefs(
            conv = null,
            agent = SelectorPrefs(modeId = "yolo"),
        )
        assertEquals("yolo", resolved.modeId)
        assertEquals(null, resolved.configValues)
    }

    @Test
    fun `empty merged config collapses to null so acp_connect omits it`() {
        val resolved = resolveSelectorPrefs(
            conv = SelectorPrefs(configValues = emptyMap()),
            agent = SelectorPrefs(),
        )
        assertEquals(null, resolved.modeId)
        assertEquals(null, resolved.configValues)
    }

    @Test
    fun `agent-level mode survives when conversation picked neither`() {
        val resolved = resolveSelectorPrefs(
            conv = null,
            agent = SelectorPrefs(modeId = "full-access", configValues = mapOf("model" to "x")),
        )
        assertEquals("full-access", resolved.modeId)
        assertEquals(mapOf("model" to "x"), resolved.configValues)
    }
}
