package app.codeg.android.feature.sessions

import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.datastore.SessionProject
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ConversationStatus
import app.codeg.android.core.model.ConversationSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Pure builders of the Chats aggregate ("全部") mode + the floating-ball project
 * cycle (batch v132). The same shape of tests as SessionGrouping/Activity sections.
 */
class AggregateSessionsTest {

    private val t0 = Instant.parse("2026-10-29T00:00:00Z")
    private fun at(min: Long) = t0.plusSeconds(min * 60)

    private fun conv(
        id: Int,
        atMin: Long,
        pinnedMin: Long? = null,
    ) = ConversationSummary(
        id = id,
        folderId = 0,
        title = "s$id",
        agentType = AgentType.CODEX,
        status = ConversationStatus.COMPLETED,
        createdAt = t0,
        updatedAt = at(atMin),
        pinnedAt = pinnedMin?.let(::at),
    )

    private val deviceA = ServerProfile("a", "A", "http://a")
    private val deviceB = ServerProfile("b", "B", "http://b")

    // ---- buildAggregateSessionSections ----

    @Test
    fun `pinned bucket spans devices and sorts by pinnedAt, device sections sort by updatedAt`() {
        val sections = buildAggregateSessionSections(
            listOf(
                deviceA to listOf(conv(1, 5), conv(2, 30, pinnedMin = 10), conv(3, 20)),
                deviceB to listOf(conv(4, 25), conv(5, 40, pinnedMin = 60)),
            ),
        )

        assertEquals(3, sections.size)
        val pinned = sections[0]
        assertEquals(AggregateKind.PINNED, pinned.kind)
        assertEquals(listOf(5, 2), pinned.rows.map { it.conversation.id }) // pinnedAt 60 > 10
        assertEquals(AggregateKind.DEVICE, sections[1].kind)
        assertEquals("a", sections[1].serverId)
        assertEquals(listOf(3, 1), sections[1].rows.map { it.conversation.id }) // 20 > 5, pin excluded
        assertEquals("b", sections[2].serverId)
        assertEquals(listOf(4), sections[2].rows.map { it.conversation.id })
    }

    @Test
    fun `empty device sections are dropped`() {
        val sections = buildAggregateSessionSections(
            listOf(
                deviceA to listOf(conv(1, 5)),
                deviceB to emptyList(),
            ),
        )
        assertEquals(listOf("a"), sections.map { it.serverId })
    }

    @Test
    fun `memberKeys filters before bucketing so the pinned bucket obeys the project too`() {
        // Member set: a:2 (pinned), b:4. a:2 must survive into the pinned bucket.
        val sections = buildAggregateSessionSections(
            listOf(
                deviceA to listOf(conv(1, 5), conv(2, 30, pinnedMin = 10)),
                deviceB to listOf(conv(4, 25)),
            ),
            memberKeys = setOf("a:2", "b:4"),
        )
        assertEquals(listOf("agg-pinned", "agg-b"), sections.map { it.id })
        assertEquals(listOf(2), sections[0].rows.map { it.conversation.id })
        assertEquals(listOf(4), sections[1].rows.map { it.conversation.id })
    }

    @Test
    fun `project filter can empty the whole list`() {
        val sections = buildAggregateSessionSections(
            listOf(deviceA to listOf(conv(1, 5))),
            memberKeys = setOf("a:99"),
        )
        assertTrue(sections.isEmpty())
    }

    @Test
    fun `unread derives from read marks and never flags live rows`() {
        val sections = buildAggregateSessionSections(
            listOf(
                deviceA to listOf(
                    ConversationSummary(
                        id = 1, folderId = 0, title = "live", agentType = AgentType.CODEX,
                        status = ConversationStatus.IN_PROGRESS, createdAt = t0, updatedAt = at(1),
                    ),
                    conv(2, 5),
                ),
            ),
            readMarks = mapOf("a:2" to at(10).toEpochMilli()),
        )
        val rows = sections.single().rows
        assertEquals(false, rows[0].unread) // live → pulse, not unread
        assertEquals(false, rows[1].unread) // read at minute 10 > updated minute 5
    }

    // ---- nextProjectFilter ----

    private fun project(id: String, order: Int) = SessionProject(id, id, 0, order)

    @Test
    fun `project cycle walks forward and wraps to all`() {
        val projects = listOf(project("p1", 1), project("p2", 2), project("p3", 3))
        assertNull("no projects → stay unfiltered", nextProjectFilter(null, emptyList()))
        assertEquals("null → first project", "p1", nextProjectFilter(null, projects))
        assertEquals("p2", nextProjectFilter("p1", projects))
        assertEquals("p3", nextProjectFilter("p2", projects))
        assertNull("last project wraps to 全部", nextProjectFilter("p3", projects))
    }

    @Test
    fun `stale active id restarts the cycle at the first project`() {
        val projects = listOf(project("p1", 1), project("p2", 2))
        assertEquals("p1", nextProjectFilter("deleted-id", projects))
    }
}
