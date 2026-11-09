package app.codeg.android.core.notify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批v161.3 R5: pins for the two pure helpers the wave-2 fixes made
 * load-bearing — the per-(server, conversation) deep-link request code (R3)
 * and the one-line shade title (R2). TITLE_MAX is private in the notifier;
 * 64 is pinned here as the shade-row contract — if it changes on purpose,
 * update the arithmetic below with it.
 */
class TaskResultNotifierTest {

    @Test
    fun `turn request code stays in the 1e9 band`() {
        for (server in listOf("srv-a", "srv-b", "x", "", "长名字设备-12345")) {
            for (conversation in intArrayOf(1, 42, 101, 100_000)) {
                val code = TaskResultNotifier.turnRequestCode(server, conversation)
                assertTrue("band low: $code", code >= 1_000_000_000)
                assertTrue("band high: $code", code < 1_000_000_000 + (1 shl 30))
            }
        }
    }

    @Test
    fun `turn request code is stable and pair-distinct`() {
        assertEquals(
            TaskResultNotifier.turnRequestCode("srv-a", 7),
            TaskResultNotifier.turnRequestCode("srv-a", 7),
        )
        // Same conversation id on another device must land on another code
        // (server-local ids are the whole reason the server id joins the
        // hash). Fixed inputs whose hashes provably differ by non-multiples
        // of the 2^30 mask period.
        assertNotEquals(
            TaskResultNotifier.turnRequestCode("srv-a", 7),
            TaskResultNotifier.turnRequestCode("srv-b", 7),
        )
        assertNotEquals(
            TaskResultNotifier.turnRequestCode("srv-a", 7),
            TaskResultNotifier.turnRequestCode("srv-a", 8),
        )
    }

    @Test
    fun `short title passes through with newlines flattened`() {
        assertEquals("a b", TaskResultNotifier.ellipsis("a\nb"))
        assertEquals("一 二", TaskResultNotifier.ellipsis("一\n二"))
    }

    @Test
    fun `long title truncates at TITLE_MAX code points`() {
        val out = TaskResultNotifier.ellipsis("字".repeat(74))
        assertEquals("字".repeat(64) + "…", out)
    }

    @Test
    fun `surrogate pairs are not split at the cut`() {
        // 👍 is one code point, two UTF-16 units — the pre-R2 length-based cut
        // could slice it in half into a stray replacement char.
        val out = TaskResultNotifier.ellipsis("👍".repeat(67))
        assertTrue("cut kept the pair whole", out.startsWith("👍"))
        assertEquals((64 + 1).toLong(), out.codePointCount(0, out.length).toLong())
    }

    // 批v173 C7: the firehose run_settled decoder — pure, so the wire contract
    // is pinned without a socket. The JSON must survive the full parse path
    // (parseFrame → Channel payload → parseRunSettled), hence the real Json.
    private val json = Json { ignoreUnknownKeys = true }

    private fun payload(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject(block)

    @Test
    fun `run settled payload decodes to the run`() {
        val frame = json.parseToJsonElement(
            """{"channel":"automation://changed","payload":{"kind":"run_settled","automation_id":9,"run_id":12,"status":"succeeded"}}""",
        )
        val obj = frame as JsonObject
        val inner = obj["payload"] as JsonObject
        val settled = TaskResultNotifier.parseRunSettled(inner)
        assertEquals(RunSettled(9, 12, "succeeded"), settled)
    }

    @Test
    fun `run settled builder path and status pass-through`() {
        val inner = payload {
            put("kind", "run_settled")
            put("automation_id", 3)
            put("run_id", 4)
            put("status", "failed")
        }
        assertEquals(RunSettled(3, 4, "failed"), TaskResultNotifier.parseRunSettled(inner))
        assertTrue("failed is notifyable", "failed" in TaskResultNotifier.NOTIFYABLE_RUN_STATUSES)
        assertTrue("succeeded is notifyable", "succeeded" in TaskResultNotifier.NOTIFYABLE_RUN_STATUSES)
        assertFalse("cancelled stays silent", "cancelled" in TaskResultNotifier.NOTIFYABLE_RUN_STATUSES)
    }

    @Test
    fun `malformed or foreign payloads decode to null`() {
        // Wrong kind on the same channel.
        assertNull(
            TaskResultNotifier.parseRunSettled(
                payload { put("kind", "automation_changed"); put("automation_id", 1); put("run_id", 2); put("status", "succeeded") },
            ),
        )
        // Missing fields each fail independently.
        assertNull(TaskResultNotifier.parseRunSettled(payload { put("kind", "run_settled") }))
        assertNull(
            TaskResultNotifier.parseRunSettled(
                payload { put("kind", "run_settled"); put("automation_id", 1) },
            ),
        )
        assertNull(
            TaskResultNotifier.parseRunSettled(
                payload { put("kind", "run_settled"); put("automation_id", 1); put("run_id", 2) },
            ),
        )
        // Non-numeric ids.
        assertNull(
            TaskResultNotifier.parseRunSettled(
                payload { put("kind", "run_settled"); put("automation_id", "x"); put("run_id", 2); put("status", "succeeded") },
            ),
        )
    }

    @Test
    fun `automation notification id is stable and family-distinct`() {
        assertEquals(
            TaskResultNotifier.automationNotificationId("srv-a", 7),
            TaskResultNotifier.automationNotificationId("srv-a", 7),
        )
        assertNotEquals(
            TaskResultNotifier.automationNotificationId("srv-a", 7),
            TaskResultNotifier.automationNotificationId("srv-a", 8),
        )
        // Same run id on another device — server-local ids join the hash.
        assertNotEquals(
            TaskResultNotifier.automationNotificationId("srv-a", 7),
            TaskResultNotifier.automationNotificationId("srv-b", 7),
        )
    }
}
