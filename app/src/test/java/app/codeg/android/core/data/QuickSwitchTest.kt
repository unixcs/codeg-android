package app.codeg.android.core.data

import app.codeg.android.core.datastore.SessionRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Depth quick-switch rings (batch v136). The ball's three "recent session"
 * actions became depth-N cycles: depth 1 jumps to the single most recent
 * session, depth 2 alternates the two most recent, depth 3 walks the three most
 * recent in order, and so on. The ring is a SNAPSHOT — recency marks move on
 * every open, so re-ranking per fire would make depth 3 ping-pong the two
 * newest and never reach the third.
 */
class QuickSwitchTest {

    private fun marks(vararg entries: Pair<String, Long>): Map<String, Long> =
        entries.toMap()

    private val all = setOf("a", "b")

    @Test
    fun `pool is newest-first and truncated to depth`() {
        val m = marks("a:1" to 100L, "a:2" to 400L, "a:3" to 300L, "a:4" to 200L)
        assertEquals(SessionRef("a", 2), nextQuickSwitchStep(m, all, 1, null, null)!!.ref)
        val d2 = nextQuickSwitchStep(m, all, 2, null, null)!!
        assertEquals(2, d2.ring.refs.size)
        assertEquals(listOf(SessionRef("a", 2), SessionRef("a", 3)), d2.ring.refs)
        assertEquals(listOf(SessionRef("a", 2), SessionRef("a", 3), SessionRef("a", 4)), nextQuickSwitchStep(m, all, 3, null, null)!!.ring.refs)
    }

    @Test
    fun `invalid and unknown-device keys are filtered out`() {
        val m = marks(
            "a:1" to 100L,
            "zz:2" to 900L, // device no longer connected
            "notamark" to 800L,
            "a:nope" to 700L,
            ":5" to 600L,
            "a:" to 500L,
        )
        val step = nextQuickSwitchStep(m, setOf("a"), 3, null, null)!!
        assertEquals(listOf(SessionRef("a", 1)), step.ring.refs)
        assertEquals(SessionRef("a", 1), step.ref)
    }

    @Test
    fun `cross-device conversations with the same id are distinct`() {
        val m = marks("a:5" to 300L, "b:5" to 200L)
        val step = nextQuickSwitchStep(m, all, 2, null, null)!!
        assertEquals(listOf(SessionRef("a", 5), SessionRef("b", 5)), step.ring.refs)
        assertEquals(SessionRef("a", 5), step.ref)
    }

    @Test
    fun `depth below one and an empty pool yield null`() {
        val m = marks("a:1" to 100L)
        assertNull(nextQuickSwitchStep(m, all, 0, null, null))
        assertNull(nextQuickSwitchStep(emptyMap(), all, 3, null, null))
        assertNull(nextQuickSwitchStep(m, setOf("zz"), 3, null, null))
    }

    @Test
    fun `depth one stays on the most recent session`() {
        val m = marks("a:1" to 300L, "a:2" to 200L)
        val first = nextQuickSwitchStep(m, all, 1, null, null)!!
        assertEquals(SessionRef("a", 1), first.ref)
        // Firing again while sitting on it cannot go anywhere else.
        assertEquals(SessionRef("a", 1), nextQuickSwitchStep(m, all, 1, SessionRef("a", 1), first.ring)!!.ref)
    }

    @Test
    fun `depth three walks the snapshot ring in order despite recency reranking`() {
        // Fire 1 — newest first.
        val fire1 = nextQuickSwitchStep(
            marks("a:1" to 400L, "a:2" to 300L, "a:3" to 200L, "a:4" to 100L),
            all, 3, null, null,
        )!!
        assertEquals(SessionRef("a", 1), fire1.ref)

        // Opening a:1 bumps its mark; a live rank would reorder to [1,2,3] with the
        // SAME shape, but next must still be the ring's second slot.
        val fire2 = nextQuickSwitchStep(
            marks("a:1" to 500L, "a:2" to 300L, "a:3" to 200L, "a:4" to 100L),
            all, 3, fire1.ref, fire1.ring,
        )!!
        assertEquals(SessionRef("a", 2), fire2.ref)

        // Opening a:2 makes IT newest; without the snapshot ring the next pick
        // would be a:1 again (rank 2 of [2,1,3]) and a:3 would be unreachable.
        val fire3 = nextQuickSwitchStep(
            marks("a:1" to 500L, "a:2" to 600L, "a:3" to 200L, "a:4" to 100L),
            all, 3, fire2.ref, fire2.ring,
        )!!
        assertEquals(SessionRef("a", 3), fire3.ref)

        // Full wrap.
        val fire4 = nextQuickSwitchStep(
            marks("a:1" to 500L, "a:2" to 600L, "a:3" to 700L, "a:4" to 100L),
            all, 3, fire3.ref, fire3.ring,
        )!!
        assertEquals(SessionRef("a", 1), fire4.ref)
    }

    @Test
    fun `manual jump to the ring's next slot skips it instead of repeating`() {
        // Ring [1,2,3], last fire landed on 1; the user manually opened 2.
        val ring = QuickSwitchRing(3, listOf(SessionRef("a", 1), SessionRef("a", 2), SessionRef("a", 3)), 0)
        val step = nextQuickSwitchStep(
            marks("a:1" to 400L, "a:2" to 300L, "a:3" to 200L),
            all, 3, SessionRef("a", 2), ring,
        )!!
        assertEquals(SessionRef("a", 3), step.ref)
    }

    @Test
    fun `ring rebuilds when a member leaves the pool`() {
        val ring = QuickSwitchRing(2, listOf(SessionRef("a", 1), SessionRef("a", 2)), 0)
        // a:2 was deleted / its mark dropped; only a:1 and a:3 remain.
        val step = nextQuickSwitchStep(
            marks("a:1" to 400L, "a:3" to 200L),
            all, 2, SessionRef("a", 1), ring,
        )!!
        assertEquals(listOf(SessionRef("a", 1), SessionRef("a", 3)), step.ring.refs)
        assertEquals(SessionRef("a", 3), step.ref)
    }

    @Test
    fun `ring rebuilds when the current conversation is outside it`() {
        val ring = QuickSwitchRing(2, listOf(SessionRef("a", 1), SessionRef("a", 2)), 0)
        // User navigated to a:3 by hand (not in the depth-2 ring).
        val step = nextQuickSwitchStep(
            marks("a:1" to 400L, "a:2" to 300L, "a:3" to 200L),
            all, 2, SessionRef("a", 3), ring,
        )!!
        assertEquals(SessionRef("a", 1), step.ref)
        assertEquals(0, step.ring.cursor)
    }

    @Test
    fun `ring rebuilds when depth changes`() {
        val ring = QuickSwitchRing(2, listOf(SessionRef("a", 1), SessionRef("a", 2)), 1)
        val step = nextQuickSwitchStep(
            marks("a:1" to 400L, "a:2" to 300L, "a:3" to 200L),
            all, 3, null, ring,
        )!!
        assertEquals(3, step.ring.depth)
        assertEquals(SessionRef("a", 1), step.ref)
    }

    @Test
    fun `device switch drops the carried ring`() {
        val ring = QuickSwitchRing(2, listOf(SessionRef("a", 1), SessionRef("a", 2)), 0)
        // Now only device b is connected; the carried refs can't survive.
        val step = nextQuickSwitchStep(
            marks("a:1" to 400L, "b:9" to 100L),
            setOf("b"), 2, null, ring,
        )!!
        assertEquals(listOf(SessionRef("b", 9)), step.ring.refs)
        assertEquals(SessionRef("b", 9), step.ref)
    }

    @Test
    fun `a ring snapshotted before more sessions existed grows to the new pool`() {
        // Depth 2 fired while only one session was open: the ring is [1].
        val fire1 = nextQuickSwitchStep(marks("a:1" to 100L), all, 2, null, null)!!
        assertEquals(listOf(SessionRef("a", 1)), fire1.ring.refs)

        // A second session appears. Keeping the one-member ring would degrade depth 2
        // into the old fixed "most recent" shortcut forever, so the ring must rebuild
        // to the two newest.
        val fire2 = nextQuickSwitchStep(
            marks("a:1" to 100L, "a:2" to 200L),
            all, 2, null, fire1.ring,
        )!!
        assertEquals(listOf(SessionRef("a", 2), SessionRef("a", 1)), fire2.ring.refs)
        assertEquals(SessionRef("a", 2), fire2.ref)

        // …and from there it advances inside the grown ring normally.
        val fire3 = nextQuickSwitchStep(
            marks("a:1" to 100L, "a:2" to 200L),
            all, 2, fire2.ref, fire2.ring,
        )!!
        assertEquals(SessionRef("a", 1), fire3.ref)
    }
}
