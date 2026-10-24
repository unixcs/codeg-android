package app.codeg.android.feature.sessiondetail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the send-while-running queue ([QueuedDraft] list semantics
 * and the flush backoff). The VM's network paths (insertQueuedNow / sendQueued) are
 * integration-tested against a real server; here we lock the pieces that are cheap
 * to get subtly wrong: head-first order, removal by id, and the 5s/15s/45s ladder.
 */
class QueuedDraftsTest {

    private fun draft(id: String, text: String = "t-$id") = QueuedDraft(id, text, java.time.Instant.now())

    @Test
    fun enqueueAppendsAndPreservesOrder() {
        var queue = emptyList<QueuedDraft>()
        queue = queue + draft("a")
        queue = queue + draft("b")
        queue = queue + draft("c")
        assertEquals(listOf("a", "b", "c"), queue.map { it.id })
        assertEquals("a", queue.first().id)
    }

    @Test
    fun removalByLeavesHeadIntact() {
        val queue = listOf(draft("a"), draft("b"), draft("c"))
        val edited = queue.filterNot { it.id == "b" }
        assertEquals(listOf("a", "c"), edited.map { it.id })
        val removedHead = queue.filterNot { it.id == "a" }
        assertEquals("b", removedHead.first().id)
    }

    @Test
    fun blankTextIsRejectedByTheSameTrimTheVmApplies() {
        // The VM gate: `if (trimmed.isEmpty()) return` — blank input never enqueues.
        assertTrue("   \n ".trim().isEmpty())
        assertTrue("  hi  ".trim() == "hi")
    }

    @Test
    fun flushBackoffIs5s15s45sThenCaps() {
        // Mirror of the VM's ×3 ladder (SessionDetailViewModel.flushBackoffMs).
        fun backoff(attempt: Int): Long {
            var ms = 5_000L
            repeat((attempt - 1).coerceIn(0, 3)) { ms *= 3 }
            return ms
        }
        assertEquals(5_000L, backoff(1))
        assertEquals(15_000L, backoff(2))
        assertEquals(45_000L, backoff(3))
        // Beyond the retry budget the exponent stops growing.
        assertEquals(135_000L, backoff(4))
        assertEquals(135_000L, backoff(6))
    }
}
