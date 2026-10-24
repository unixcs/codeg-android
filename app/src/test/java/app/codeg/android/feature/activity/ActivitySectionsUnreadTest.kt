package app.codeg.android.feature.activity

import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ConversationStatus
import app.codeg.android.core.model.ConversationSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Section-building with the unread flag (batch v121): unread is precomputed onto
 * Last-24h rows from the device-local read marks; Running rows stay pulse-only.
 */
class ActivitySectionsUnreadTest {

    private fun conv(
        id: Int,
        status: ConversationStatus,
        updated: Instant,
    ) = ConversationSummary(
        id = id,
        folderId = 1,
        title = "c$id",
        agentType = AgentType.CLAUDE_CODE,
        status = status,
        createdAt = updated.minusSeconds(3_600),
        updatedAt = updated,
    )

    private fun row(
        id: Int,
        status: ConversationStatus,
        updated: Instant,
        serverId: String = "srv",
    ) = ActivityRow(conv(id, status, updated), serverId, "Server")

    private val now = Instant.now()

    @Test
    fun `recent row without a mark is unread`() {
        val sections = buildActivitySections(
            listOf(row(1, ConversationStatus.PENDING_REVIEW, now.minusSeconds(60))),
            readMarks = emptyMap(),
        )
        val recent = sections.first { it.id == "recent" }
        assertTrue(recent.rows.single().unread)
    }

    @Test
    fun `recent row opened after its last change is read`() {
        val updated = now.minusSeconds(60)
        val sections = buildActivitySections(
            listOf(row(1, ConversationStatus.PENDING_REVIEW, updated)),
            readMarks = mapOf("srv:1" to updated.plusSeconds(30).toEpochMilli()),
        )
        assertFalse(sections.first { it.id == "recent" }.rows.single().unread)
    }

    @Test
    fun `recent row changed after open flips back to unread`() {
        val updated = now.minusSeconds(60)
        val sections = buildActivitySections(
            listOf(row(1, ConversationStatus.PENDING_REVIEW, updated)),
            readMarks = mapOf("srv:1" to updated.minusSeconds(30).toEpochMilli()),
        )
        assertTrue(sections.first { it.id == "recent" }.rows.single().unread)
    }

    @Test
    fun `running rows are never unread and marks do not split sections`() {
        val sections = buildActivitySections(
            listOf(
                row(1, ConversationStatus.IN_PROGRESS, now),
                row(2, ConversationStatus.PENDING_REVIEW, now.minusSeconds(120), serverId = "srv2"),
            ),
            readMarks = mapOf("srv:1" to now.plusSeconds(60).toEpochMilli()),
        )
        assertEquals(listOf("running", "recent"), sections.map { it.id })
        assertTrue(sections[0].rows.none { it.unread })
        assertTrue(sections[1].rows.single().unread) // srv2 row has no mark
    }
}
