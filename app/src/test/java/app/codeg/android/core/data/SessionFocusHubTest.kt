package app.codeg.android.core.data

import app.codeg.android.core.datastore.SessionRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The floating-ball session-cycle hub (batch v132): pure step math + the
 * owner-scoped registry behavior (two list tabs compose during the lateral fade;
 * every lookup must stay inside its own owner).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionFocusHubTest {

    private fun item(server: String, id: Int) = FocusItem(
        ref = SessionRef(server, id),
        title = "s$server-$id",
        serverName = server,
    )

    // ---- advanceFocusItems (pure) ----

    @Test
    fun `step returns index after current, 0 when absent, null when empty`() {
        val list = listOf(item("a", 1), item("a", 2), item("a", 3))
        assertNull(advanceFocusItems(emptyList(), null))
        val first = advanceFocusItems(list, null)!!
        assertEquals(item("a", 1), first.first)
        assertEquals(0, first.second)
        val mid = advanceFocusItems(list, SessionRef("a", 2))!!
        assertEquals(item("a", 3), mid.first)
        assertEquals(2, mid.second)
        val wrapped = advanceFocusItems(list, SessionRef("a", 3))!!
        assertEquals(item("a", 1), wrapped.first)
        assertEquals(0, wrapped.second)
        val stale = advanceFocusItems(list, SessionRef("zzz", 99))!!
        assertEquals("stale ref restarts at 0", item("a", 1), stale.first)
    }

    // ---- SessionFocusHub ----

    @Test
    fun `cycle is owner-scoped and highlightedRef revalidates`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val hub = SessionFocusHub(scope)
        try {
            val chats = listOf(item("a", 1), item("a", 2))
            val activity = listOf(item("b", 7))
            hub.register("chats", chats)
            hub.register("activity", activity)

            val hl = hub.cycle("chats")!!
            assertEquals("chats", hl.owner)
            assertEquals(SessionRef("a", 1), hl.item.ref)
            assertEquals(1, hl.position)
            assertEquals(2, hl.total)

            // Second step advances within the same owner.
            assertEquals(SessionRef("a", 2), hub.cycle("chats")!!.item.ref)
            // The other owner's lookup never sees chats' highlight…
            assertNull(hub.highlightedRef("activity"))
            // …and a fresh cycle there starts from ITS list.
            assertEquals(SessionRef("b", 7), hub.cycle("activity")!!.item.ref)

            // When the highlighted ref vanishes from the owner's set, highlightedRef drops it.
            hub.register("chats", listOf(item("a", 1)))
            assertNull(hub.highlightedRef("chats"))
            // …and the next cycle restarts cleanly.
            assertEquals(SessionRef("a", 1), hub.cycle("chats")!!.item.ref)

            // Unregistered owner: cycle is a no-op, no highlight leaks.
            hub.unregister("activity")
            assertNull(hub.cycle("activity"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `preview auto-dismisses after 2s and the re-armed job holds till its own deadline`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val hub = SessionFocusHub(scope)
        try {
            // Single-entry list: every cycle points at the same row (position 1).
            hub.register("chats", listOf(item("a", 1)))
            hub.cycle("chats")
            assertEquals(1, hub.preview.value?.position)
            advanceTimeBy(2_001)
            assertNull("first preview dismissed", hub.preview.value)

            hub.cycle("chats") // re-arms a fresh 2s window (old job already fired)
            assertEquals(1, hub.preview.value?.position)
            advanceTimeBy(1_000)
            assertEquals("younger preview still alive", 1, hub.preview.value?.position)
            advanceTimeBy(1_001)
            assertNull(hub.preview.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `cycle after unregister walks the last-known list from the current session`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val hub = SessionFocusHub(scope)
        try {
            hub.register("chats", listOf(item("a", 1), item("a", 2), item("a", 3)))
            assertEquals("chats", hub.lastOwner)
            // The ball opened a:1, so the list surface is disposed (v136 direct entry).
            hub.unregister("chats")
            // From the detail page the ring must keep working: anchored on the
            // conversation actually on screen, not on the cleared list position.
            val stale = hub.cycle("chats", current = SessionRef("a", 1), stale = true)!!
            assertEquals(SessionRef("a", 2), stale.item.ref)
            assertEquals(2, stale.position)
            assertEquals(3, stale.total)
            // Without the stale opt-in a disposed owner is still a no-op.
            assertNull(hub.cycle("chats"))
            // The highlight survives disposal and stays owner-scoped.
            assertEquals(SessionRef("a", 2), hub.highlightedRef("chats"))
            assertNull(hub.highlightedRef("activity"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `highlightedRef validates against the last-known list after unregister`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val hub = SessionFocusHub(scope)
        try {
            hub.register("chats", listOf(item("a", 1), item("a", 2)))
            hub.cycle("chats")
            hub.unregister("chats")
            // Still resolvable: the ball's "open highlighted" fires from the detail
            // screen, i.e. after the list that produced the highlight is gone.
            assertEquals(SessionRef("a", 1), hub.highlightedRef("chats"))
            // A re-registration that no longer contains the ref drops it.
            hub.register("chats", listOf(item("a", 2)))
            assertNull(hub.highlightedRef("chats"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `lastOwner skips empty registrations and follows the newest non-empty list`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val hub = SessionFocusHub(scope)
        try {
            assertNull(hub.lastOwner)
            hub.register("chats", emptyList())
            assertNull("loading list is not a cycle target", hub.lastOwner)
            hub.register("chats", listOf(item("a", 1)))
            assertEquals("chats", hub.lastOwner)
            hub.register("activity", listOf(item("b", 7)))
            assertEquals("activity", hub.lastOwner)
            // Empty re-registration must not erase the last-known list either:
            // the chats tab reloading can't kill the ring used from a detail page.
            hub.register("chats", listOf(item("a", 2)))
            hub.register("chats", emptyList())
            assertEquals(
                SessionRef("a", 2),
                hub.cycle("chats", current = SessionRef("a", 1), stale = true)!!.item.ref,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `hasLiveList only counts non-empty registrations`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val hub = SessionFocusHub(scope)
        try {
            assertFalse(hub.hasLiveList("chats"))
            hub.register("chats", emptyList())
            assertFalse("a loading list is not a ring", hub.hasLiveList("chats"))
            hub.register("chats", listOf(item("a", 1)))
            assertTrue(hub.hasLiveList("chats"))
            assertFalse(hub.hasLiveList("activity"))
            hub.unregister("chats")
            assertFalse(hub.hasLiveList("chats"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `resolveCycleTarget prefers the visible live list and falls back to lastOwner`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val hub = SessionFocusHub(scope)
        try {
            // Nothing has ever registered: no target, the caller falls back to depth 1.
            assertNull(resolveCycleTarget("chats", isTabRoute = true, hub::hasLiveList, hub.lastOwner))

            hub.register("chats", listOf(item("a", 1)))
            // The visible list wins and is live (not stale).
            assertEquals(
                CycleTarget("chats", stale = false),
                resolveCycleTarget("chats", isTabRoute = true, hub::hasLiveList, hub.lastOwner),
            )
            // A tab that shows a list but registers no ring (Folders/Search/Settings)
            // must fall back to the last list that did, marked stale.
            assertEquals(
                CycleTarget("chats", stale = true),
                resolveCycleTarget("folders", isTabRoute = true, hub::hasLiveList, hub.lastOwner),
            )
            // From a detail/composer there is no tab at all: same fallback.
            assertEquals(
                CycleTarget("chats", stale = true),
                resolveCycleTarget(null, isTabRoute = false, hub::hasLiveList, hub.lastOwner),
            )

            // A device switch disposes the old list and composes the new one: the old
            // route no longer owns a live ring, so the newest list wins as the stale one.
            hub.unregister("chats")
            hub.register("activity", listOf(item("b", 7)))
            assertEquals(
                CycleTarget("activity", stale = false),
                resolveCycleTarget("activity", isTabRoute = true, hub::hasLiveList, hub.lastOwner),
            )
            assertEquals(
                CycleTarget("activity", stale = true),
                resolveCycleTarget("chats", isTabRoute = true, hub::hasLiveList, hub.lastOwner),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `peek computes without publishing and commit publishes the step`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val hub = SessionFocusHub(scope)
        try {
            hub.register("chats", listOf(item("a", 1), item("a", 2), item("a", 3)))
            // Ball gesture: compute first, navigate, publish only once the handoff
            // landed — a dropped fire must not move the ring and skip a session.
            val step = hub.peekCycle("chats")!!
            assertEquals(SessionRef("a", 1), step.item.ref)
            assertEquals(1, step.position)
            assertEquals(3, step.total)
            assertNull("a peek must not move the ring", hub.highlight.value)
            assertNull("a peek must not show the preview card", hub.preview.value)

            val hl = hub.commitCycle(step)
            assertEquals(SessionRef("a", 1), hl.item.ref)
            assertEquals(hl, hub.highlight.value)
            assertEquals(1, hub.preview.value?.position)

            // The committed highlight is the anchor of the NEXT peek.
            assertEquals(SessionRef("a", 2), hub.peekCycle("chats")!!.item.ref)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `validServers filters cycles and highlight resolution`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val hub = SessionFocusHub(scope)
        try {
            hub.register("chats", listOf(item("a", 1), item("b", 7)))
            // A device that vanished from the connected set is skipped.
            val step = hub.peekCycle("chats", validServers = setOf("b"))!!
            assertEquals(SessionRef("b", 7), step.item.ref)
            hub.commitCycle(step)
            // Resolution honours the same filter, live or last-known (after disposal)…
            assertEquals(SessionRef("b", 7), hub.resolveHighlightedRef(null, setOf("a", "b")))
            assertEquals(SessionRef("b", 7), hub.resolveHighlightedRef("chats", setOf("b")))
            assertNull(hub.resolveHighlightedRef(null, setOf("a")))
            hub.unregister("chats")
            assertEquals(SessionRef("b", 7), hub.resolveHighlightedRef("chats", setOf("b")))
            assertNull(hub.resolveHighlightedRef("chats", setOf("a")))
            // …and another owner may not claim it.
            assertNull(hub.resolveHighlightedRef("activity"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `empty re-registration keeps the highlight as the ring cursor`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val hub = SessionFocusHub(scope)
        try {
            hub.register("chats", listOf(item("a", 1), item("a", 2)))
            val first = hub.cycle("chats")!!
            assertEquals(SessionRef("a", 1), first.item.ref)
            // A list refetch emits an empty snapshot: "still loading", not "row gone".
            hub.register("chats", emptyList())
            assertEquals("ring cursor survives a loading list", first, hub.highlight.value)
            assertEquals(SessionRef("a", 1), hub.highlightedRef("chats"))
            // Re-registering the same rows keeps the cursor, so the next fire advances
            // instead of restarting at the top.
            hub.register("chats", listOf(item("a", 1), item("a", 2)))
            assertEquals(first, hub.highlight.value)
            assertEquals(SessionRef("a", 2), hub.peekCycle("chats")!!.item.ref)
        } finally {
            scope.cancel()
        }
    }
}
