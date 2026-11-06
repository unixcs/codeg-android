package app.codeg.android.core.notify

import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.data.TaskRow
import app.codeg.android.core.data.TaskTransition
import app.codeg.android.core.data.taskBaseline
import app.codeg.android.core.data.taskTransitions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** 系统级通知体系: a device that answered every poll just stopped answering. */
data class DeviceDownEvent(
    val serverId: String,
    val serverName: String,
)

/**
 * App-scoped watcher for task-board outcomes (系统级通知体系). Mirrors
 * [app.codeg.android.core.data.InteractiveRequestWatcher]'s shape: a 30s poll
 * of every aggregate-subscribed device (`visible && includeInAll` — the same
 * device set the task board's 「全部」 mode shows), diffed against the previous
 * round by the pure [taskTransitions] function. Two hot streams come out:
 * task transitions (done / failed arrivals) and device-down events (a device
 * that had answered before went silent — reported once per outage, recovery is
 * silent). No bus of record: the poll diff IS the event source, so a killed
 * process simply rebuilds its baseline on the first round back.
 *
 * Transport notes: `client(profile)` returns null for a token-less profile and
 * the shared OkHttp engine caps every POST at 30s (connect/read/write), so a
 * hung device degrades to a null round within one interval — the same
 * runCatching(null) pattern the interactive watcher uses for `petSessions`.
 *
 * Lifecycle is reference-counted ([start]/[stop]); the Application holds the
 * process-lifetime reference (notifications must work in the background).
 */
@Singleton
class TaskResultWatcher @Inject constructor(
    private val repository: ServerRepository,
) {
    private val supervisor = SupervisorJob()
    private val scope = kotlinx.coroutines.CoroutineScope(supervisor + Dispatchers.Default)

    private val _taskTransitions = MutableSharedFlow<TaskTransition>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val taskTransitions: SharedFlow<TaskTransition> = _taskTransitions.asSharedFlow()

    private val _deviceDown = MutableSharedFlow<DeviceDownEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val deviceDown: SharedFlow<DeviceDownEvent> = _deviceDown.asSharedFlow()

    /** Last round's "serverId:taskId" → status. Kept across stop/start so a
     *  watcher restart never replays stale transitions as fresh events. */
    @Volatile
    private var baseline: Map<String, String> = emptyMap()

    /** Devices with at least one SUCCESSFUL round since this process started —
     *  "上一轮成功" so a device that was offline at cold start is not announced. */
    private val knownOk = ConcurrentHashMap.newKeySet<String>()

    /** Outages already announced; cleared silently on recovery. */
    private val downReported = ConcurrentHashMap.newKeySet<String>()

    private var pollJob: Job? = null
    private val refCount = AtomicInteger(0)

    /** Idempotent: N holders share one running watcher. */
    fun start() {
        if (refCount.incrementAndGet() > 1) return
        pollJob = scope.launch { pollLoop() }
    }

    fun stop() {
        val n = refCount.decrementAndGet()
        if (n > 0) return
        if (n < 0) { refCount.set(0); return }
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            try {
                pollOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // One bad round (DataStore read, transport) must not kill the loop.
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun pollOnce() {
        val profiles = repository.profiles.first()
            .filter { it.visible && it.includeInAll }
        // Nothing aggregated → nothing to watch, and nothing may be misreported.
        if (profiles.isEmpty()) return
        val liveIds = profiles.map { it.id }.toSet()
        // Devices removed from the aggregate must not keep stale outage state.
        knownOk.retainAll(liveIds)
        downReported.retainAll(liveIds)

        val rows = mutableListOf<TaskRow>()
        val okNow = mutableListOf<String>()
        for (profile in profiles) {
            val tasks = runCatching {
                repository.client(profile)?.workTaskList()
            }.getOrNull()
            // Null = transport failure / old server / missing token.
            if (tasks == null) {
                if (profile.id in knownOk && downReported.add(profile.id)) {
                    _deviceDown.tryEmit(DeviceDownEvent(profile.id, profile.name))
                }
                continue
            }
            okNow += profile.id
            downReported.remove(profile.id)
            tasks.forEach { task -> rows += TaskRow(profile.id, profile.name, task) }
        }
        knownOk.addAll(okNow)

        val previous = baseline
        baseline = taskBaseline(rows)
        taskTransitions(previous, rows).forEach { _taskTransitions.tryEmit(it) }
    }

    companion object {
        /** Same cadence as [app.codeg.android.core.data.InteractiveRequestWatcher]
         *  — the two polls ride the same 30s heartbeat. */
        const val POLL_INTERVAL_MS = 30_000L
    }
}
