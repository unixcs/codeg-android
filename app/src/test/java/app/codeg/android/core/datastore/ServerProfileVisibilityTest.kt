package app.codeg.android.core.datastore

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Serialization contract for the device-visibility flag (batch v130): new profiles
 * default to visible, saved JSON from before the flag decodes as visible, and the
 * flag survives a round-trip so a hidden device stays hidden across upgrades.
 */
class ServerProfileVisibilityTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ServerProfile.serializer())

    @Test
    fun `new profile defaults to visible`() {
        assertTrue(ServerProfile("id", "name", "http://x:3080").visible)
    }

    @Test
    fun `old saved json without the flag decodes visible`() {
        val legacy = """[{"id":"a","name":"tx","baseUrl":"http://x:3080"}]"""
        val decoded = json.decodeFromString(serializer, legacy)
        assertEquals(1, decoded.size)
        assertTrue(decoded[0].visible)
    }

    @Test
    fun `hidden flag round-trips and new keys are tolerated`() {
        val encoded = json.encodeToString(
            listOf(ServerProfile("a", "tx", "http://x:3080", visible = false)),
        )
        // A future version may add more fields; this version must skip them.
        val fromFuture = """[{"id":"a","name":"tx","baseUrl":"http://x:3080","visible":false,"futureField":1}]"""
        val decoded = json.decodeFromString(serializer, fromFuture)
        assertFalse(decoded[0].visible)
        // And the round-trip re-encodes exactly the flag we saved.
        assertTrue(encoded.contains("\"visible\":false"))
    }

    @Test
    fun `copy flips visibility without touching identity`() {
        val profile = ServerProfile("a", "tx", "http://x:3080")
        val hidden = profile.copy(visible = false)
        assertEquals(profile.id, hidden.id)
        assertEquals(profile.name, hidden.name)
        assertEquals(profile.baseUrl, hidden.baseUrl)
        assertFalse(hidden.visible)
    }
}
