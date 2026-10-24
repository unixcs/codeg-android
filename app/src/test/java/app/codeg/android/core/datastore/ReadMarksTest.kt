package app.codeg.android.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the device-local session read marks (batch v121): the LRU prune
 * and the unread predicate. DataStore round-trips are intentionally not covered — the
 * store follows the existing single-key-JSON pattern and holds no logic of its own.
 */
class ReadMarksTest {

    @Test
    fun `prune keeps everything under the cap`() {
        val marks = mapOf("a:1" to 100L, "a:2" to 200L)
        assertEquals(marks, pruneReadMarks(marks))
    }

    @Test
    fun `prune drops the oldest beyond the cap`() {
        val marks = (1..(READ_MARKS_CAP + 50)).associate { "s:$it" to it.toLong() }
        val pruned = pruneReadMarks(marks)
        assertEquals(READ_MARKS_CAP, pruned.size)
        // The 50 oldest (smallest timestamps) are gone; the newest all survive.
        assertFalse(pruned.containsKey("s:1"))
        assertFalse(pruned.containsKey("s:50"))
        assertEquals(READ_MARKS_CAP.toLong() + 50, pruned["s:${READ_MARKS_CAP + 50}"])
    }

    @Test
    fun `prune re-opening a session refreshes its slot`() {
        // 501 distinct entries → prune fires; then the oldest is re-opened (newest
        // timestamp) and must survive the next prune while s:2 (now oldest) drops.
        val marks = (1..(READ_MARKS_CAP + 1)).associate { "s:$it" to it.toLong() } +
            mapOf("s:1" to Long.MAX_VALUE / 2)
        val pruned = pruneReadMarks(marks)
        assertTrue(pruned.containsKey("s:1"))
        assertFalse(pruned.containsKey("s:2")) // now the oldest, pruned
        assertEquals(READ_MARKS_CAP, pruned.size)
    }

    @Test
    fun `never-opened session is unread`() {
        assertTrue(isSessionUnread(statusLive = false, updatedAt = 1_000L, readMark = null))
    }

    @Test
    fun `changed after open is unread again`() {
        assertTrue(isSessionUnread(statusLive = false, updatedAt = 2_000L, readMark = 1_000L))
    }

    @Test
    fun `opened after last change is read`() {
        assertFalse(isSessionUnread(statusLive = false, updatedAt = 1_000L, readMark = 2_000L))
    }

    @Test
    fun `live rows are never unread-flagged`() {
        assertFalse(isSessionUnread(statusLive = true, updatedAt = 2_000L, readMark = 1_000L))
        assertFalse(isSessionUnread(statusLive = true, updatedAt = 1_000L, readMark = null))
    }
}
