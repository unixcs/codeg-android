package app.codeg.android.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure serde tests for the new-task folder memory (batch v134). DataStore
 * round-trips are intentionally not covered (single-key-JSON pattern, no logic
 * of its own — same convention as DeviceAgentPrefs).
 */
class DraftFolderPrefsTest {

    @Test
    fun `serde round-trips per-server folder memory`() {
        val prefs = DraftFolderPrefs(
            servers = mapOf(
                "profile-a" to DraftFolderPrefs.ServerFolders(lastUsedFolderId = 929, pinnedFolderId = 3),
                "profile-b" to DraftFolderPrefs.ServerFolders(lastUsedFolderId = 7),
            ),
        )
        val decoded = DraftFolderPrefsSerdeHarness.decode(DraftFolderPrefsSerdeHarness.encode(prefs))
        assertEquals(prefs, decoded)
    }

    @Test
    fun `decode of garbage or null yields empty prefs`() {
        assertEquals(DraftFolderPrefs(), DraftFolderPrefsSerdeHarness.decode(null))
        assertEquals(DraftFolderPrefs(), DraftFolderPrefsSerdeHarness.decode("not json"))
    }

    @Test
    fun `unknown keys in the payload are ignored`() {
        val decoded = DraftFolderPrefsSerdeHarness.decode("""{"servers":{"p1":{"lastUsedFolderId":42}},"extra":1}""")
        assertEquals(42, decoded.servers["p1"]?.lastUsedFolderId)
        assertNull(decoded.servers["p1"]?.pinnedFolderId)
    }

    @Test
    fun `pinned beats last-used by construction of the default chain`() {
        val f = DraftFolderPrefs.ServerFolders(lastUsedFolderId = 1, pinnedFolderId = 2)
        assertEquals(2, f.pinnedFolderId ?: f.lastUsedFolderId)
        assertEquals(1, DraftFolderPrefs.ServerFolders(lastUsedFolderId = 1).let { it.pinnedFolderId ?: it.lastUsedFolderId })
        assertNull(DraftFolderPrefs.ServerFolders().let { it.pinnedFolderId ?: it.lastUsedFolderId })
    }
}

/** Serde access without spinning the DataStore-backed singleton. */
private object DraftFolderPrefsSerdeHarness {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun encode(prefs: DraftFolderPrefs): String = json.encodeToString(DraftFolderPrefs.serializer(), prefs)

    fun decode(raw: String?): DraftFolderPrefs =
        raw?.let { runCatching { json.decodeFromString(DraftFolderPrefs.serializer(), it) }.getOrNull() }
            ?: DraftFolderPrefs()
}
