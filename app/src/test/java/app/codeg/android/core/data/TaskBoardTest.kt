package app.codeg.android.core.data

import app.codeg.android.core.model.ConversationStatus
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.WorkTaskInfo
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskBoardTest {

    private fun task(
        id: Int,
        status: String,
        serverId: String = "s1",
        title: String = "T$id",
        scheduledAt: String? = null,
        updatedAt: String? = null,
        deferred: Boolean = false,
    ) = TaskBoardEntry(
        serverId = serverId,
        serverName = "srv-$serverId",
        task = WorkTaskInfo(
            id = id, title = title, status = status,
            scheduledAt = scheduledAt, updatedAt = updatedAt, deferred = deferred,
        ),
    )

    /** Web-parity mapping (author's board-columns.ts), ui重构 阶段3. */
    @Test
    fun `segment mapping follows the web board`() {
        assertEquals(TaskSegment.TODO, taskSegmentOf(task(1, "todo").task))
        assertEquals(TaskSegment.TODO, taskSegmentOf(task(1, "queued").task))
        assertEquals(TaskSegment.RUNNING, taskSegmentOf(task(1, "preparing").task))
        assertEquals(TaskSegment.RUNNING, taskSegmentOf(task(1, "running").task))
        assertEquals(TaskSegment.ATTENTION, taskSegmentOf(task(1, "awaiting_input").task))
        assertEquals(TaskSegment.ATTENTION, taskSegmentOf(task(1, "review").task))
        assertEquals(TaskSegment.ATTENTION, taskSegmentOf(task(1, "merging").task))
        assertEquals(TaskSegment.ATTENTION, taskSegmentOf(task(1, "failed").task))
        assertEquals(TaskSegment.DONE, taskSegmentOf(task(1, "done").task))
        assertEquals(TaskSegment.DONE, taskSegmentOf(task(1, "canceled").task))
        assertEquals(TaskSegment.DONE, taskSegmentOf(task(1, "cancelled").task))
    }

    @Test
    fun `deferred tasks are filed under done like the web board`() {
        assertEquals(TaskSegment.DONE, taskSegmentOf(task(1, "review", deferred = true).task))
        assertEquals(TaskSegment.DONE, taskSegmentOf(task(1, "failed", deferred = true).task))
    }

    @Test
    fun `unknown status never lands in a segment`() {
        // A future server version adding a state must surface in the neutral
        // unknown section — never silently in 已完成.
        assertNull(taskSegmentOf(task(1, "frobnicating").task))
        assertNull(taskSegmentOf(task(1, "").task))
        assertNull(taskSegmentOf(task(1, "archived").task))
        val board = buildBoard(listOf(task(1, "frobnicating")))
        assertEquals(0, board.segments[TaskSegment.DONE]!!.size)
        assertEquals(1, board.unknown.size)
    }

    @Test
    fun `status string is preserved verbatim`() {
        val entry = task(1, "some_brand_new_state")
        assertEquals("some_brand_new_state", entry.task.status)
    }

    @Test
    fun `archived rows hide unless shown`() {
        val archivedRow = TaskBoardEntry(
            serverId = "s1", serverName = "srv",
            task = WorkTaskInfo(id = 7, title = "T7", status = "done", archivedAt = "2026-10-01T00:00:00Z"),
        )
        assertEquals(0, buildBoard(listOf(archivedRow)).segments.values.sumOf { it.size })
        val shown = buildBoard(listOf(archivedRow), showArchived = true)
        assertEquals(1, shown.segments[TaskSegment.DONE]!!.size)
    }

    @Test
    fun `canceled rows hide only when the filter says so`() {
        val rows = listOf(task(1, "canceled"), task(2, "cancelled"))
        assertEquals(2, buildBoard(rows, showCanceled = true).segments[TaskSegment.DONE]!!.size)
        assertEquals(0, buildBoard(rows, showCanceled = false).segments[TaskSegment.DONE]!!.size)
    }

    @Test
    fun `counts reflect filters but not search (search applied by caller)`() {
        val rows = listOf(task(1, "todo"), task(2, "running"), task(3, "review"))
        val board = buildBoard(rows, showCanceled = false)
        assertEquals(1, board.counts[TaskSegment.TODO])
        assertEquals(1, board.counts[TaskSegment.RUNNING])
        assertEquals(1, board.counts[TaskSegment.ATTENTION])
        assertEquals(0, board.counts[TaskSegment.DONE])
    }

    @Test
    fun `scheduled tasks sort before unscheduled within a segment`() {
        val board = buildBoard(
            listOf(
                task(1, "todo", updatedAt = "2026-11-01T10:00:00Z"),
                task(2, "todo", scheduledAt = "2026-11-02T08:00:00Z"),
            ),
        )
        assertEquals(2, board.segments[TaskSegment.TODO]!!.first().task.id)
    }

    @Test
    fun `same segment aggregates across servers`() {
        val board = buildBoard(listOf(task(1, "running", serverId = "a"), task(2, "running", serverId = "b")))
        assertEquals(listOf("a", "b"), board.segments[TaskSegment.RUNNING]!!.map { it.serverId })
    }

    /** ui重构 阶段1: wild statuses stay display-known, raw preserved. */
    @Test
    fun `wild statuses are archived-like or neutral and never runnable-looking`() {
        assertTrue(task(1, "archived").task.isArchivedLike)
        assertTrue(
            TaskBoardEntry("s", "srv", WorkTaskInfo(2, "T", "done", archivedAt = "2026-10-01T00:00:00Z"))
                .task.isArchivedLike,
        )
        assertEquals(TaskStatusTone.NEUTRAL, taskStatusUi(task(3, "mystery").task).tone)
        assertEquals("mystery", taskStatusUi(task(3, "mystery").task).raw)
        assertEquals(TaskStatusTone.RUN, taskStatusUi(task(4, "running").task).tone)
        assertEquals(TaskStatusTone.WARN, taskStatusUi(task(5, "review").task).tone)
        assertEquals(TaskStatusTone.DANGER, taskStatusUi(task(6, "failed").task).tone)
    }

    // ---- 批v160 W5: kanban accent projection + column stepping + archive-all ----

    /** The card/header accent per status, web statusAccent parity. */
    @Test
    fun `accent tone follows the web statusAccent matrix`() {
        assertEquals(TaskAccentTone.QUEUED, taskAccentTone(task(1, "todo").task))
        assertEquals(TaskAccentTone.QUEUED, taskAccentTone(task(2, "queued").task))
        assertEquals(TaskAccentTone.LIVE, taskAccentTone(task(3, "preparing").task))
        assertEquals(TaskAccentTone.LIVE, taskAccentTone(task(4, "running").task))
        assertEquals(TaskAccentTone.ATTENTION, taskAccentTone(task(5, "awaiting_input").task))
        assertEquals(TaskAccentTone.ATTENTION, taskAccentTone(task(6, "review").task))
        assertEquals(TaskAccentTone.ATTENTION, taskAccentTone(task(7, "merging").task))
        assertEquals(TaskAccentTone.FAILED, taskAccentTone(task(8, "failed").task))
        assertEquals(TaskAccentTone.DONE, taskAccentTone(task(9, "done").task))
        assertEquals(TaskAccentTone.CANCELED, taskAccentTone(task(10, "canceled").task))
        assertEquals(TaskAccentTone.QUEUED, taskAccentTone(task(11, "mystery").task))
    }

    /** Shelved/archived outrank the underlying status (muted wins). */
    @Test
    fun `archived and deferred rows project their own accent`() {
        assertEquals(TaskAccentTone.ARCHIVED, taskAccentTone(task(1, "done", deferred = false).copyTask(archivedAt = "2026-11-01T00:00:00Z").task))
        assertEquals(TaskAccentTone.SHELVED, taskAccentTone(task(2, "review", deferred = true).task))
        assertEquals(TaskAccentTone.SHELVED, taskAccentTone(task(3, "failed", deferred = true).task))
    }

    /** Column stepping clamps at both ends — never wraps around (W5 拍板). */
    @Test
    fun `segment step clamps to the four columns`() {
        assertEquals(1, segmentStepIndex(0, 1))
        assertEquals(3, segmentStepIndex(2, 1))
        assertEquals(3, segmentStepIndex(3, 1))
        assertEquals(3, segmentStepIndex(3, 5))
        assertEquals(0, segmentStepIndex(0, -1))
        assertEquals(0, segmentStepIndex(2, -9))
        assertEquals(2, segmentStepIndex(3, -1))
    }

    /** 全部归档 sweeps the DONE column only, and skips already-archived rows. */
    @Test
    fun `archive-all candidates are done rows not yet archived`() {
        val rows = listOf(
            task(1, "done"),
            task(2, "done", deferred = true),
            task(3, "failed"),
            task(4, "canceled"),
            task(5, "running"),
        )
        assertEquals(listOf(1, 2), archiveAllCandidates(rows).map { it.task.id })
        assertEquals(
            emptyList<TaskBoardEntry>(),
            archiveAllCandidates(listOf(task(6, "done").copyTask(archivedAt = "2026-11-01T00:00:00Z"))),
        )
    }

    /** Timeline heads need a label for a bare status string (payload.to). */
    @Test
    fun `status label res maps known statuses and nulls the wild`() {
        assertEquals(app.codeg.android.R.string.task_status_running, taskStatusLabelRes("running"))
        assertEquals(app.codeg.android.R.string.task_status_review, taskStatusLabelRes("review"))
        assertEquals(app.codeg.android.R.string.task_status_canceled, taskStatusLabelRes("cancelled"))
        assertNull(taskStatusLabelRes("mystery"))
        assertNull(taskStatusLabelRes(null))
    }

    // ---- 批v162 P1: the board holds tasks AND tracked conversations ----

    private fun conv(
        id: Int,
        status: String,
        messageCount: Int = 4,
        updatedAt: String = "2026-11-06T10:00:00Z",
    ) = ConversationSummary(
        id = id,
        folderId = 7,
        title = "C$id",
        agentType = app.codeg.android.core.model.AgentType.CLAUDE_CODE,
        status = ConversationStatus.fromWire(status),
        messageCount = messageCount,
        createdAt = Instant.parse("2026-11-06T09:00:00Z"),
        updatedAt = Instant.parse(updatedAt),
    )

    private fun boardConv(
        id: Int,
        status: String,
        messageCount: Int = 4,
        updatedAt: String = "2026-11-06T10:00:00Z",
        live: Boolean = true,
    ) = BoardConversationEntry(
        serverId = "s1",
        serverName = "srv-s1",
        conversation = conv(id, status, messageCount, updatedAt),
        live = live,
        archived = false,
    )

    /**
     * 批v165: the column is `live || started` for in_progress rows — the old
     * messageCount rule filed every generating native session under 待办
     * (the server never maintains message_count for native sessions). Started
     * = updatedAt strictly past createdAt (+ε), i.e. the server bumped it on a
     * prompt claim at least once.
     */
    @Test
    fun `conversation segment maps status and the started rule`() {
        // Never prompted: updatedAt == createdAt (same server clock read), no
        // live connection → the one true 待办.
        assertEquals(
            TaskSegment.TODO,
            conversationSegmentOf(boardConv(1, "in_progress", live = false, updatedAt = "2026-11-06T09:00:00Z")),
        )
        // Prompted but the live probe can't see it (delegation child / idle):
        // updatedAt moved past createdAt → running, NOT 待办. The v165 bug.
        assertEquals(
            TaskSegment.RUNNING,
            conversationSegmentOf(boardConv(2, "in_progress", live = false, messageCount = 0)),
        )
        // Probe sees the connection → running regardless of timestamps.
        assertEquals(
            TaskSegment.RUNNING,
            conversationSegmentOf(boardConv(3, "in_progress", live = true, updatedAt = "2026-11-06T09:00:00Z")),
        )
        assertEquals(TaskSegment.ATTENTION, conversationSegmentOf(boardConv(4, "pending_review")))
        assertEquals(TaskSegment.DONE, conversationSegmentOf(boardConv(5, "completed")))
        assertEquals(TaskSegment.DONE, conversationSegmentOf(boardConv(6, "cancelled")))
        // Unknown wire values must never vanish: they stay in the running column.
        assertEquals(TaskSegment.RUNNING, conversationSegmentOf(boardConv(7, "mystery")))
    }

    /** One clock: tasks (scheduled first) and sessions interleave by epoch millis. */
    @Test
    fun `merge orders tasks and conversations by one clock`() {
        val rows = mergeBoardRows(
            tasks = listOf(
                task(1, "running", updatedAt = "2026-11-06T09:00:00Z"),
                task(2, "todo", scheduledAt = "2026-11-06T12:00:00Z"),
            ),
            conversations = listOf(
                boardConv(9, "in_progress", updatedAt = "2026-11-06T11:00:00Z"),
            ),
        )
        // A future scheduled task floats above everything, then the session, then the task.
        assertEquals(listOf("s1:t2", "s1:c9", "s1:t1"), rows.map { it.key })
    }

    /** Kind is part of the LazyColumn key: task 7 and conversation 7 never collide. */
    @Test
    fun `board row keys are kind scoped`() {
        val rows = mergeBoardRows(
            tasks = listOf(task(7, "running", updatedAt = "2026-11-06T09:00:00Z")),
            conversations = listOf(boardConv(7, "in_progress", updatedAt = "2026-11-06T10:00:00Z")),
        )
        assertEquals(2, rows.map { it.key }.distinct().size)
    }

    /** No timestamps at all: 0 keeps the row visible at the bottom instead of crashing. */
    @Test
    fun `task sort falls back and tolerates missing timestamps`() {
        assertEquals(0L, taskSortMs(task(1, "todo").task))
        assertEquals(
            Instant.parse("2026-11-06T10:00:00Z").toEpochMilli(),
            taskSortMs(task(1, "todo", updatedAt = "2026-11-06T10:00:00Z").task),
        )
        // Offset form (server sometimes emits it) parses too.
        assertEquals(
            Instant.parse("2026-11-06T10:00:00Z").toEpochMilli(),
            taskSortMs(task(1, "todo", updatedAt = "2026-11-06T10:00:00+00:00").task),
        )
    }

    /** WorkTaskInfo has no copy for entries — tiny local helper for the two tests above. */
    private fun TaskBoardEntry.copyTask(archivedAt: String?): TaskBoardEntry =
        TaskBoardEntry(
            serverId = serverId,
            serverName = serverName,
            task = WorkTaskInfo(
                id = task.id, title = task.title, status = task.status,
                scheduledAt = task.scheduledAt, updatedAt = task.updatedAt,
                deferred = task.deferred, archivedAt = archivedAt,
            ),
        )
}
