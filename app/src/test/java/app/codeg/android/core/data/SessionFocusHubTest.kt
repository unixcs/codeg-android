package app.codeg.android.core.data

import app.codeg.android.core.datastore.SessionRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
