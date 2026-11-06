package app.codeg.android.core.data

import app.codeg.android.core.model.WorkTaskInfo

/**
 * One polled task row flattened with its owning device — [WorkTaskInfo] is
 * server-local (ids collide across devices), so the device identity rides
 * alongside for the diff and the notifications.
 */
data class TaskRow(
    val serverId: String,
    val serverName: String,
    val task: WorkTaskInfo,
)

/**
 * 系统级通知体系: one task crossed from a non-terminal status into a finished
 * one between two poll rounds (批v161.3 R2: from is now "anything not in
 * [TERMINAL_STATUSES]", so todo→done announces too). Emitted once per
 * (device, task) per round — the notifier maps it to a task_results
 * notification.
 */
data class TaskTransition(
    val serverId: String,
    val serverName: String,
    val taskId: Int,
    val title: String,
    val from: String,
    val to: String,
)

/** Statuses a task can still LEAVE — everything else is terminal, so only a
 *  `from` outside this set is news (批v161.3 R2: the old whitelist of active
 *  statuses missed todo→done and would have missed every future non-terminal
 *  state the server adds; a blacklist of terminal states covers them all). */
private val TERMINAL_STATUSES = setOf("done", "failed", "cancelled", "canceled")

/** Arrivals worth a notification. `cancelled`/`canceled`/`archived` are
 *  deliberate user actions — never announced. */
private val ARRIVAL_STATUSES = setOf("done", "failed")

/**
 * Diff one poll round against the previous baseline (pure, unit-tested). The
 * baseline maps "serverId:taskId" → last seen status; the returned transitions
 * are the tasks that moved from a NON-TERMINAL status ([TERMINAL_STATUSES])
 * into an [ARRIVAL_STATUSES] member — todo→done included (批v161.3 R2).
 *
 * Semantics pinned by [app.codeg.android.core.data.TaskTransitionsTest]:
 * - an unseen key (first round / brand-new task / freshly added device) is
 *   BASELINE ONLY — no event, so cold start and device adds never storm;
 * - any terminal → terminal move (done→done, failed→done, cancelled→…) is
 *   quiet, and `archived` is never an arrival;
 * - a task missing from `current` simply drops out of the baseline the caller
 *   rebuilds — its reappearance later is a first sighting again, never an event.
 */
fun taskTransitions(
    previous: Map<String, String>,
    current: List<TaskRow>,
): List<TaskTransition> {
    val out = mutableListOf<TaskTransition>()
    for (row in current) {
        val from = previous["${row.serverId}:${row.task.id}"] ?: continue
        val to = row.task.status
        if (from !in TERMINAL_STATUSES && to in ARRIVAL_STATUSES) {
            out += TaskTransition(
                serverId = row.serverId,
                serverName = row.serverName,
                taskId = row.task.id,
                title = row.task.title,
                from = from,
                to = to,
            )
        }
    }
    return out
}

/** The next diff baseline for [current] (callers persist this between rounds). */
fun taskBaseline(current: List<TaskRow>): Map<String, String> =
    current.associate { "${it.serverId}:${it.task.id}" to it.task.status }
