package app.codeg.android.core.data

import app.codeg.android.core.model.WorkTaskInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统级通知体系: the poll-round diff that feeds the task_results notifications.
 * Pure function — these tests pin the event semantics the notifier relies on:
 * first rounds build a baseline silently, any non-terminal → done/failed move
 * is news (批v161.3 R2: the from side is a terminal-state BLACKLIST, so
 * todo→done announces too), and deliberate exits (cancel/archive) never
 * announce.
 */
class TaskTransitionsTest {

    private fun row(serverId: String, id: Int, status: String, name: String = "dev") =
        TaskRow(serverId, name, WorkTaskInfo(id = id, title = "task $id", status = status))

    @Test
    fun `first round only builds the baseline - no events`() {
        val current = listOf(
            row("s1", 1, "running"),
            row("s1", 2, "done"),
            row("s1", 3, "queued"),
        )
        assertTrue(taskTransitions(emptyMap(), current).isEmpty())
        // The caller persists this map as the next round's baseline.
        assertEquals(
            mapOf("s1:1" to "running", "s1:2" to "done", "s1:3" to "queued"),
            taskBaseline(current),
        )
    }

    @Test
    fun `running to done triggers`() {
        val events = taskTransitions(
            previous = mapOf("s1:1" to "running"),
            current = listOf(row("s1", 1, "done")),
        )
        assertEquals(1, events.size)
        assertEquals("running", events[0].from)
        assertEquals("done", events[0].to)
        assertEquals("s1", events[0].serverId)
        assertEquals(1, events[0].taskId)
        assertEquals("task 1", events[0].title)
        assertEquals("dev", events[0].serverName)
    }

    @Test
    fun `running to failed triggers`() {
        val events = taskTransitions(
            previous = mapOf("s1:1" to "running"),
            current = listOf(row("s1", 1, "failed")),
        )
        assertEquals(1, events.size)
        assertEquals("failed", events[0].to)
    }

    @Test
    fun `queued to done triggers`() {
        val events = taskTransitions(
            previous = mapOf("s1:1" to "queued"),
            current = listOf(row("s1", 1, "done")),
        )
        assertEquals(1, events.size)
    }

    @Test
    fun `todo to done also triggers - non-terminal blacklist semantics`() {
        // 批v161.3 R2: the from side is now "not in the terminal set", so the
        // pre-flight todo column IS an exit — a task launched straight to done
        // must announce (the old active-status whitelist silently ate it).
        val events = taskTransitions(
            previous = mapOf("s1:1" to "todo"),
            current = listOf(row("s1", 1, "done")),
        )
        assertEquals("todo→done should notify", 1, events.size)
        // Same rule for the failure arrival.
        val failed = taskTransitions(
            previous = mapOf("s1:2" to "todo"),
            current = listOf(row("s1", 2, "failed")),
        )
        assertEquals(1, failed.size)
    }

    @Test
    fun `every non-terminal from-state announces on a done arrival`() {
        // The full non-terminal ladder. Not a whitelist anymore (批v161.3 R2):
        // any status the server adds outside the terminal set is covered
        // automatically — this list just pins the statuses that exist today.
        listOf("todo", "queued", "preparing", "running", "awaiting_input", "review", "merging")
            .forEach { from ->
                val events = taskTransitions(
                    previous = mapOf("s1:1" to from),
                    current = listOf(row("s1", 1, "done")),
                )
                assertEquals("from $from should notify", 1, events.size)
            }
    }

    @Test
    fun `terminal from-states never trigger - either cancelled spelling`() {
        // done→done and failed→done are re-arrivals of a finished task, and a
        // cancelled task reaching done is a server-side oddity, not news.
        listOf("done", "failed", "cancelled", "canceled").forEach { from ->
            val events = taskTransitions(
                previous = mapOf("s1:1" to from),
                current = listOf(row("s1", 1, "done")),
            )
            assertTrue("from=$from must be silent", events.isEmpty())
        }
    }

    @Test
    fun `done to done does not trigger`() {
        val events = taskTransitions(
            previous = mapOf("s1:1" to "done"),
            current = listOf(row("s1", 1, "done")),
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `cancelled targets never trigger - either spelling`() {
        listOf("cancelled", "canceled").forEach { to ->
            val events = taskTransitions(
                previous = mapOf("s1:1" to "running"),
                current = listOf(row("s1", 1, to)),
            )
            assertTrue("to=$to must be silent", events.isEmpty())
        }
    }

    @Test
    fun `archived target never triggers`() {
        val events = taskTransitions(
            previous = mapOf("s1:1" to "review"),
            current = listOf(row("s1", 1, "archived")),
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `unseen task in a later round is baseline only`() {
        // A brand-new task (created between rounds) with no previous status
        // cannot have a "transition" — including one born straight into done.
        val events = taskTransitions(
            previous = mapOf("s1:1" to "running"),
            current = listOf(
                row("s1", 1, "running"),
                row("s1", 2, "done"),
            ),
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `multiple devices are mixed by key - each device's tasks stay separate`() {
        val events = taskTransitions(
            previous = mapOf(
                "s1:1" to "running",
                "s2:1" to "done",
                "s2:2" to "queued",
            ),
            current = listOf(
                row("s1", 1, "done", name = "devA"),
                row("s2", 1, "done", name = "devB"),
                row("s2", 2, "failed", name = "devB"),
                row("s2", 3, "running", name = "devB"), // unseen → baseline only
            ),
        )
        // s1:1 running→done; s2:1 done→done quiet; s2:2 queued→failed.
        assertEquals(2, events.size)
        assertEquals("s1", events[0].serverId)
        assertEquals("devA", events[0].serverName)
        assertEquals("done", events[0].to)
        assertEquals("s2", events[1].serverId)
        assertEquals(2, events[1].taskId)
        assertEquals("failed", events[1].to)
    }

    @Test
    fun `a task missing this round drops out and reappears silently`() {
        val baseline = taskBaseline(listOf(row("s1", 1, "running")))
        // Round 2: the fetch came back without task 1 (flaky page) — nothing to
        // emit and the rebuilt baseline no longer contains it.
        assertTrue(taskTransitions(baseline, emptyList()).isEmpty())
        // Round 3: task 1 is back, now done — but the watcher never saw the
        // transition, so it is a first sighting again, not an event.
        assertTrue(taskTransitions(emptyMap(), listOf(row("s1", 1, "done"))).isEmpty())
    }
}
