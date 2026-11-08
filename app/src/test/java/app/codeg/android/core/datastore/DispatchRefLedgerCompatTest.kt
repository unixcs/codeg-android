package app.codeg.android.core.datastore

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批v166: the anti-double-create ledger gained `modeId` + `configValues` (the
 * pinned permission mode and model/effort picks a re-dispatch must re-send).
 * Old rows on disk predate both keys — decoding them must yield the defaults,
 * never a thrown ledger (a decode failure would silently DROP the only trace
 * of an unresolved dispatch and invite a double create).
 */
class DispatchRefLedgerCompatTest {

    // Same configuration the stores use (IdeaStore/DispatchStore).
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `legacy row without the new fields decodes to defaults`() {
        val legacy = """
            {"requestId":"r1","serverId":"tx","folderId":9,"title":"TODO 清理",
             "state":"UNKNOWN","createdAt":1730000000000}
        """.trimIndent()
        val ref = json.decodeFromString(DispatchRef.serializer(), legacy)
        assertEquals("r1", ref.requestId)
        assertNull(ref.modeId)
        assertEquals(emptyMap<String, String>(), ref.configValues)
    }

    @Test
    fun `pinned mode and picks round-trip`() {
        val ref = DispatchRef(
            requestId = "r2",
            serverId = "tx",
            folderId = 9,
            title = "gpt对标视频",
            state = DispatchState.UNKNOWN,
            modeId = "Build",
            configValues = mapOf("model" to "group/deepseek-v4.1-flash", "reasoning_effort" to "max"),
        )
        val encoded = json.encodeToString(DispatchRef.serializer(), ref)
        val decoded = json.decodeFromString(DispatchRef.serializer(), encoded)
        assertEquals("Build", decoded.modeId)
        assertEquals(mapOf("model" to "group/deepseek-v4.1-flash", "reasoning_effort" to "max"), decoded.configValues)
    }

    @Test
    fun `unknown keys are ignored and future fields stay forward compatible`() {
        val forward = """
            {"requestId":"r3","serverId":"dell","folderId":1,"title":"t",
             "modeId":"plan","configValues":{"model":"m"},"someFutureField":true}
        """.trimIndent()
        val ref = json.decodeFromString(DispatchRef.serializer(), forward)
        assertEquals("plan", ref.modeId)
        assertEquals(mapOf("model" to "m"), ref.configValues)
    }

    @Test
    fun `defaults keep a pre-field row dispatchable as a task`() {
        // A pre-v166 row (idea-era semantics) must keep its ledger meaning:
        // autoStart true, task mode, no session. The new fields change nothing.
        val legacy = """
            {"requestId":"r4","serverId":"nas","folderId":2,"title":"x",
             "state":"PENDING","createdAt":1,"autoStart":true}
        """.trimIndent()
        val ref = json.decodeFromString(DispatchRef.serializer(), legacy)
        assertTrue(ref.autoStart)
        assertEquals(false, ref.sessionMode)
        assertNull(ref.modeId)
    }
}
