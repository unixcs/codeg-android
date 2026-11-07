package app.codeg.android.core.data

import app.codeg.android.core.model.ConversationStatus
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.WorkTaskInfo

/**
 * Server status → display projection (ui重构 阶段3, mirrors the author's web
 * board `board-columns.ts`):
 *
 *   待办     todo, queued
 *   进行中   preparing, running
 *   等你处理 awaiting_input, review, merging, failed
 *   已完成   done, canceled/cancelled; deferred rows are filed here too
 *
 * This is a *derived, best-effort* display grouping — the server's raw
 * [WorkTaskInfo.status] stays on the row verbatim and is never rewritten.
 * Unknown statuses never land in 已完成 (PRD §8.2); they surface in their own
 * neutral section, and archived rows hide behind the archived filter.
 */
enum class TaskSegment(val labelRes: Int) {
    TODO(app.codeg.android.R.string.task_seg_todo),
    RUNNING(app.codeg.android.R.string.task_seg_running),
    ATTENTION(app.codeg.android.R.string.task_seg_attention),
    DONE(app.codeg.android.R.string.task_seg_done),
}

/** The board's display order — matches the four-way segmented control. */
val SEGMENT_ORDER = listOf(
    TaskSegment.TODO,
    TaskSegment.RUNNING,
    TaskSegment.ATTENTION,
    TaskSegment.DONE,
)

/**
 * Statuses the server's `set_deferred` accepts (work_task_service.rs
 * DEFERRABLE_STATUSES; web's DEFERRABLE_STATUSES agrees). A stale deferred_at
 * on any OTHER status must not shelve the row — a deferred-flagged running
 * task belongs in 进行中 with its cancel action, not in 已完成.
 */
val DEFERRABLE_STATUSES = setOf("review", "failed")

/** Web-parity `isDeferred`: the flag only counts on a deferrable status. */
fun isEffectivelyDeferred(task: WorkTaskInfo): Boolean =
    task.deferred && task.status in DEFERRABLE_STATUSES

/**
 * Map one task to its display segment, or null when it belongs to the unknown
 * section (unrecognized status, or a stray status="archived" row being shown).
 */
fun taskSegmentOf(task: WorkTaskInfo): TaskSegment? = when {
    isEffectivelyDeferred(task) -> TaskSegment.DONE // 上游规则：搁置的 review/failed 也归 Done
    else -> when (task.status) {
        "todo", "queued" -> TaskSegment.TODO
        "preparing", "running" -> TaskSegment.RUNNING
        "awaiting_input", "review", "merging", "failed" -> TaskSegment.ATTENTION
        "done", "canceled", "cancelled" -> TaskSegment.DONE
        else -> null
    }
}

/** One board entry: a task plus the server it came from (ids are per-server). */
data class TaskBoardEntry(
    val serverId: String,
    val serverName: String,
    val task: WorkTaskInfo,
)

/**
 * P1: one TRACKED conversation on the board. Conversations are not tasks on the
 * server — membership is app-local ([app.codeg.android.core.datastore.BoardTrackStore])
 * — but once a conversation is a member it must flow through the same four
 * columns, so it gets its own entry type instead of being faked into a
 * [WorkTaskInfo] (fabricated ids/statuses would leak into every task action).
 */
data class BoardConversationEntry(
    val serverId: String,
    val serverName: String,
    val conversation: ConversationSummary,
    /** True when the device reports a live ACP connection for this session. */
    val live: Boolean,
    /** App-local archive flag — hides the row unless the archived filter is on. */
    val archived: Boolean,
)

/** Grace window for the "has this session ever been prompted" heuristic: the
 *  server stamps `created_at` and `updated_at` with the same clock read at
 *  create, so equality means untouched; anything a prompt did moves `updated_at`
 *  a full clock read later. Two seconds absorbs sub-second storage rounding. */
private const val STARTED_EPSILON_SECONDS = 2L

/**
 * Conversation status → the same four columns the tasks use. 批v165: three
 * signals, because the row's status alone cannot separate 待办 from running.
 * Every row is BORN `in_progress` (create_inner) and `message_count` is never
 * maintained for native sessions (only imports and delegation children ever get
 * a write), so the old `in_progress && messageCount == 0 → 待办` rule filed
 * every generating native session under 待办 — the v165 board bug.
 *
 * What actually distinguishes "never started":
 * - [BoardConversationEntry.live] — the device's live connection table. Exact
 *   but INCOMPLETE: the pet payload deliberately hides delegation sub-agents
 *   while they work and skips idle connections, so a generating sub-session can
 *   report live=false. Never the sole gate.
 * - `updatedAt > createdAt (+ε)` — the server stamps both with one clock read
 *   at create and bumps `updated_at` on every prompt claim (manager prompt-start
 *   "WRITE FIRST") and every status transition. Strictly greater == prompted at
 *   least once, however the session was launched. (A manual rename also bumps
 *   it — the one benign false "started": a renamed-but-never-prompted session
 *   sits in 运行中 instead of 待办.)
 *
 * TurnComplete moves a finished turn to `pending_review` (→ 待处理), so
 * `in_progress` + started means genuinely mid-turn or a turn stranded by a
 * server restart — 运行中 is the least-wrong column for both.
 */
fun conversationSegmentOf(entry: BoardConversationEntry): TaskSegment {
    val conversation = entry.conversation
    return when {
        conversation.status == ConversationStatus.PENDING_REVIEW -> TaskSegment.ATTENTION
        conversation.status == ConversationStatus.COMPLETED -> TaskSegment.DONE
        conversation.status == ConversationStatus.CANCELLED -> TaskSegment.DONE
        conversation.status == ConversationStatus.IN_PROGRESS -> when {
            entry.live -> TaskSegment.RUNNING
            conversation.updatedAt >
                conversation.createdAt.plusSeconds(STARTED_EPSILON_SECONDS) -> TaskSegment.RUNNING
            // A member that never sent a prompt has not started anything, even
            // though the server row is BORN `in_progress` — §3.1's first row.
            else -> TaskSegment.TODO
        }
        // Unknown wire values must never vanish: they stay in the running column.
        else -> TaskSegment.RUNNING
    }
}

/**
 * 批v162 P1: one row of a column. Tasks and tracked conversations share a
 * column but are NOT the same entity — a sealed row keeps the card renderer's
 * `when` exhaustive, so a conversation can never fall through into a task
 * action (合并/开始/重试) that makes no sense for it.
 */
sealed interface BoardRow {
    /** Stable LazyColumn key; the kind is part of it so task 7 and conversation 7 never collide. */
    val key: String

    /** Epoch millis, the single clock both entities are ordered by. */
    val sortMs: Long
}

data class TaskBoardRow(val entry: TaskBoardEntry) : BoardRow {
    override val key: String get() = "${entry.serverId}:t${entry.task.id}"
    override val sortMs: Long get() = taskSortMs(entry.task)
}

data class ConversationBoardRow(val entry: BoardConversationEntry) : BoardRow {
    override val key: String get() = "${entry.serverId}:c${entry.conversation.id}"
    override val sortMs: Long get() = entry.conversation.updatedAt.toEpochMilli()
}

/**
 * 批v162 P1: merge a column's tasks and conversations under ONE clock (§3.3).
 * A scheduled task still floats to the top for free — its `scheduled_at` is in
 * the future, so its sort key is the largest. The sort is stable, so equal keys
 * keep tasks before conversations, the order the board has always shown.
 */
fun mergeBoardRows(
    tasks: List<TaskBoardEntry>,
    conversations: List<BoardConversationEntry>,
): List<BoardRow> = (tasks.map(::TaskBoardRow) + conversations.map(::ConversationBoardRow))
    .sortedByDescending { it.sortMs }

/** Board projection result: per-segment lists plus the neutral unknown section. */
data class TaskBoardData(
    val segments: Map<TaskSegment, List<TaskBoardEntry>> = emptyMap(),
    val unknown: List<TaskBoardEntry> = emptyList(),
    /** Segment sizes BEFORE the search query is applied, for the segment bar. */
    val counts: Map<TaskSegment, Int> = emptyMap(),
    /** P1: tracked conversations, projected into the same segments. */
    val conversations: Map<TaskSegment, List<BoardConversationEntry>> = emptyMap(),
)

/**
 * Group tasks into segments. Web-parity filtering: archived rows
 * ([WorkTaskInfo.isArchivedLike]) stay hidden unless [showArchived]; canceled
 * rows hide unless [showCanceled]. Unrecognized statuses go to [TaskBoardData.unknown].
 */
fun buildBoard(
    entries: List<TaskBoardEntry>,
    showCanceled: Boolean = true,
    showArchived: Boolean = false,
    conversations: List<BoardConversationEntry> = emptyList(),
): TaskBoardData {
    val visible = entries.filter { entry ->
        val task = entry.task
        if (task.isArchivedLike && !showArchived) return@filter false
        if ((task.status == "canceled" || task.status == "cancelled") && !showCanceled) {
            return@filter false
        }
        true
    }
    val known = mutableListOf<TaskBoardEntry>()
    val unknown = mutableListOf<TaskBoardEntry>()
    visible.forEach { entry ->
        if (taskSegmentOf(entry.task) == null) unknown += entry else known += entry
    }
    val grouped = known.groupBy { taskSegmentOf(it.task)!! }
    val segments = SEGMENT_ORDER.associateWith { column ->
        grouped[column].orEmpty().sortedWith(compareByDescending { entry: TaskBoardEntry -> taskSortKey(entry) })
    }
    // A locally archived conversation sits in DONE and only shows with the
    // archived filter on — the same rule tasks follow ([isArchivedLike]).
    val convVisible = conversations.filter { showArchived || !it.archived }
    val convGrouped = convVisible.groupBy {
        if (it.archived) TaskSegment.DONE else conversationSegmentOf(it)
    }
    val conversationsBySegment = SEGMENT_ORDER.associateWith { column ->
        convGrouped[column].orEmpty().sortedByDescending { it.conversation.updatedAt }
    }
    return TaskBoardData(
        segments = segments,
        unknown = unknown.sortedWith(compareByDescending { entry: TaskBoardEntry -> taskSortKey(entry) }),
        counts = SEGMENT_ORDER.associateWith {
            (segments[it]?.size ?: 0) + (conversationsBySegment[it]?.size ?: 0)
        },
        conversations = conversationsBySegment,
    )
}

/**
 * Sort key inside a segment: scheduled first (soonest wins), then most recently
 * updated. Wire timestamps are RFC3339 UTC strings, which sort lexicographically.
 */
private fun taskSortKey(entry: TaskBoardEntry): String = taskSortMs(entry.task).toString()

/**
 * The same ordering rule as a number, for the cross-entity merge: scheduled
 * first, then most recently updated, then created.
 */
fun taskSortMs(task: WorkTaskInfo): Long =
    timestampMs(task.scheduledAt)
        ?: timestampMs(task.updatedAt)
        ?: timestampMs(task.createdAt)
        ?: 0L

/**
 * Normalize an RFC3339 timestamp to an epoch-millis string so sorting is order-
 * safe regardless of the wire offset (`+08:00` vs `Z`); unparsable → null.
 */
private fun timestampKey(iso: String?): String? = timestampMs(iso)?.toString()

private fun timestampMs(iso: String?): Long? = iso?.let { raw ->
    runCatching { java.time.Instant.parse(raw).toEpochMilli() }
        // Offset form (…+08:00) throws in Instant.parse below API 34 — without
        // this fallback those rows sort as "no time" (bottom of every column).
        .recoverCatching { java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
        .getOrNull()
}

// region 批v160 W5: web-parity `statusAccent` — the row/column colour language.

/**
 * Web-parity `statusAccent` (task-card.tsx 169-189), as a tone the UI maps to a
 * Compose colour — the board column's marker bar and a card's accent speak one
 * language: `failed` reads red rather than the attention column's amber, and
 * `canceled` stays neutral instead of inheriting Done's green.
 */
enum class TaskAccentTone { ARCHIVED, SHELVED, QUEUED, LIVE, ATTENTION, FAILED, DONE, CANCELED }

fun taskAccentTone(task: WorkTaskInfo): TaskAccentTone = when {
    task.isArchivedLike -> TaskAccentTone.ARCHIVED
    isEffectivelyDeferred(task) -> TaskAccentTone.SHELVED
    else -> when (task.status) {
        "todo", "queued" -> TaskAccentTone.QUEUED
        "preparing", "running" -> TaskAccentTone.LIVE
        "awaiting_input", "review", "merging" -> TaskAccentTone.ATTENTION
        "failed" -> TaskAccentTone.FAILED
        "done" -> TaskAccentTone.DONE
        "canceled", "cancelled" -> TaskAccentTone.CANCELED
        else -> TaskAccentTone.QUEUED // unknown → the neutral muted like todo
    }
}

/** Each column's marker colour = the accent of its canonical member status. */
fun segmentAccentTone(segment: TaskSegment): TaskAccentTone = when (segment) {
    TaskSegment.TODO -> TaskAccentTone.QUEUED
    TaskSegment.RUNNING -> TaskAccentTone.LIVE
    TaskSegment.ATTENTION -> TaskAccentTone.ATTENTION
    TaskSegment.DONE -> TaskAccentTone.DONE
}

/**
 * 批v160 W5: the kanban's horizontal swipe steps between the four columns and
 * STOPS at the ends — swiping past 已完成 or before 待办 does nothing (no
 * wraparound; a board that loops would fling the user across three columns).
 */
fun segmentStepIndex(current: Int, step: Int): Int =
    (current + step).coerceIn(0, SEGMENT_ORDER.lastIndex)

/**
 * The rows the DONE column's 全部归档 acts on: status=="done" only. The server's
 * set_archived accepts done/failed/canceled, but the column also holds canceled
 * and shelved review rows whose owners did not ask for them to be shelved away.
 */
fun archiveAllCandidates(entries: List<TaskBoardEntry>): List<TaskBoardEntry> =
    entries.filter { it.task.status == "done" && !it.task.isArchivedLike }

// endregion

// region Status → label/tone projection (single source of truth for tags).

enum class TaskStatusTone { NEUTRAL, RUN, WARN, INFO, DANGER, DONE }

data class TaskStatusUi(val labelRes: Int, val tone: TaskStatusTone, val raw: String? = null)

/**
 * Display label + tone for a raw status. `raw != null` means the label string
 * itself is the server's raw status (unknown state) — render it neutrally and
 * never offer status-dependent actions.
 */
fun taskStatusUi(task: WorkTaskInfo): TaskStatusUi {
    val (label, tone) = when (task.status) {
        "todo" -> R_task_todo to TaskStatusTone.NEUTRAL
        "queued" -> R_task_queued to TaskStatusTone.NEUTRAL
        "preparing" -> R_task_preparing to TaskStatusTone.RUN
        "running" -> R_task_running to TaskStatusTone.RUN
        "awaiting_input" -> R_task_awaiting to TaskStatusTone.WARN
        "review" -> R_task_review to TaskStatusTone.WARN
        "merging" -> R_task_merging to TaskStatusTone.INFO
        "done" -> R_task_done to TaskStatusTone.DONE
        "failed" -> R_task_failed to TaskStatusTone.DANGER
        "canceled", "cancelled" -> R_task_canceled to TaskStatusTone.NEUTRAL
        "archived" -> R_task_archived to TaskStatusTone.NEUTRAL
        else -> return TaskStatusUi(0, TaskStatusTone.NEUTRAL, raw = task.status)
    }
    return TaskStatusUi(label, tone)
}

/**
 * 批v160 W5: label for an ARBITRARY status string — the detail timeline's
 * status_changed heads project `payload.to`, which arrives without a
 * [WorkTaskInfo] to feed [taskStatusUi]. Same mapping; null for unknown
 * (the raw string renders as-is).
 */
fun taskStatusLabelRes(status: String?): Int? = when (status) {
    "todo" -> R_task_todo
    "queued" -> R_task_queued
    "preparing" -> R_task_preparing
    "running" -> R_task_running
    "awaiting_input" -> R_task_awaiting
    "review" -> R_task_review
    "merging" -> R_task_merging
    "done" -> R_task_done
    "failed" -> R_task_failed
    "canceled", "cancelled" -> R_task_canceled
    "archived" -> R_task_archived
    else -> null
}

// Int indirection keeps this file free of a UI import (R constants resolve fine).
private val R_task_todo = app.codeg.android.R.string.task_status_todo
private val R_task_queued = app.codeg.android.R.string.task_status_queued
private val R_task_preparing = app.codeg.android.R.string.task_status_preparing
private val R_task_running = app.codeg.android.R.string.task_status_running
private val R_task_awaiting = app.codeg.android.R.string.task_status_awaiting_input
private val R_task_review = app.codeg.android.R.string.task_status_review
private val R_task_merging = app.codeg.android.R.string.task_status_merging
private val R_task_done = app.codeg.android.R.string.task_status_done
private val R_task_failed = app.codeg.android.R.string.task_status_failed
private val R_task_canceled = app.codeg.android.R.string.task_status_canceled
private val R_task_archived = app.codeg.android.R.string.task_status_archived

// endregion
