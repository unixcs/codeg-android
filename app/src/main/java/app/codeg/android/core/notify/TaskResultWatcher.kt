package app.codeg.android.core.notify

import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.data.TaskRow
import app.codeg.android.core.data.TaskTransition
import app.codeg.android.core.data.taskBaseline
import app.codeg.android.core.data.taskTransitions
import app.codeg.android.core.model.WorkTaskInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Device-down judgment (批v161.3 R1): ONE dropped round is not an outage — a
 * wifi hiccup or a slow server restart used to announce itself instantly. A
 * device must now fail TWO CONSECUTIVE rounds (armed in [downCandidates] on
 * the first, announced on the second, un-armed by any success) before the
 * silent alert fires.
 *
 * Transport notes: `client(profile)` returns null for a token-less profile and
 * the shared OkHttp engine caps every POST at 30s (connect/read/write), so a
 * hung device degrades to a null round within one interval.
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
        // 批v161.3 R2: widened alongside the other notification buffers — 16
        // drop-oldest slots were thin for a burst round (many tasks finishing
        // during one 30s window plus a cold-start diff).
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val taskTransitions: SharedFlow<TaskTransition> = _taskTransitions.asSharedFlow()

    private val _deviceDown = MutableSharedFlow<DeviceDownEvent>(
        extraBufferCapacity = 64,
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

    /** 批v161.3 R1: devices that failed the LAST round while in [knownOk] —
     *  armed but not yet announced. A second consecutive failed round converts
     *  the candidate into a [downReported] announcement; any success un-arms. */
    private val downCandidates = ConcurrentHashMap.newKeySet<String>()

    /** Outages already announced; cleared silently on recovery. */
    private val downReported = ConcurrentHashMap.newKeySet<String>()

    private var pollJob: Job? = null
    private val refCount = AtomicInteger(0)

    /** 批v176: serializes [pollOnce] — the 30s loop and a [poke]-triggered
     *  round can otherwise interleave mid-body and diff two rounds against the
     *  SAME baseline (double-emitting one transition) or corrupt the
     *  outage bookkeeping. Concurrency is mutual exclusion, not reordering. */
    private val pollMutex = Mutex()

    /** 批v176: the debounced poke job — active means a round is already due. */
    private var pokeJob: Job? = null

    /**
     * 批v176: WS push (`task://changed` on the firehose) → an immediate poll
     * instead of waiting up to [POLL_INTERVAL_MS] for the next tick. Debounced:
     * a burst of pushes (every task status flip fires one) collapses into one
     * round per debounce window; [pollOnce] diffs against the baseline, so
     * redundant rounds emit nothing. Safe to call from any thread / while the
     * loop is stopped (a poke before start just does one standalone round).
     */
    @Synchronized
    fun poke() {
        if (pokeJob?.isActive == true) return
        pokeJob = scope.launch {
            kotlinx.coroutines.delay(POKE_DEBOUNCE_MS)
            try {
                pollOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Same tolerance as the loop: one bad round must not kill the poke path.
            }
        }
    }

    /** Idempotent: N holders share one running watcher. The @Synchronized
     *  closes the check-then-act race (批v161.3 R2): two start()s (or a
     *  start/stop pair) interleaving on the refcount could launch two loops or
     *  drop the only reference while a loop was mid-launch. */
    @Synchronized
    fun start() {
        if (refCount.incrementAndGet() > 1) return
        pollJob = scope.launch { pollLoop() }
    }

    @Synchronized
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

    /** 批v176: the loop and [poke] both funnel here; the mutex keeps their
     *  rounds from interleaving (see the field kdoc). */
    private suspend fun pollOnce() = pollMutex.withLock { pollOnceLocked() }

    private suspend fun pollOnceLocked() {
        val profiles = repository.profiles.first()
            .filter { it.visible && it.includeInAll }
        // Nothing aggregated → nothing to watch, and nothing may be misreported.
        if (profiles.isEmpty()) return
        val liveIds = profiles.map { it.id }.toSet()
        // Devices removed from the aggregate must not keep stale outage state.
        knownOk.retainAll(liveIds)
        downCandidates.retainAll(liveIds)
        downReported.retainAll(liveIds)

        // 批v161.3 R2: the per-device requests run CONCURRENTLY (same
        // coroutineScope+launch shape as TaskHubViewModel.fetchDevices) — the
        // serial sweep let the slowest device stretch the round toward the 30s
        // transport ceiling and delayed every notification behind it. Each
        // worker records its own outcome; per-device CancellationException
        // propagates through coroutineScope and out of pollOnce.
        val successes = ConcurrentHashMap<String, List<WorkTaskInfo>>()
        coroutineScope {
            profiles.forEach { profile ->
                launch {
                    // Null = transport failure / old server / missing token.
                    // try/catch, not runCatching (批v161.3 R2): runCatching
                    // swallows the coroutine's CancellationException and turned
                    // a watcher stop into a "device failed" round — a candidate
                    // step toward a false offline alert.
                    val tasks = try {
                        repository.client(profile)?.workTaskList()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (tasks != null) successes[profile.id] = tasks
                }
            }
        }

        val rows = mutableListOf<TaskRow>()
        for (profile in profiles) {
            val tasks = successes[profile.id]
            if (tasks == null) {
                // Two-consecutive-failed gate (批v161.3 R1): the first failed
                // round arms a candidate silently; the second announces once.
                if (profile.id in knownOk) {
                    if (!downCandidates.add(profile.id) && downReported.add(profile.id)) {
                        downCandidates.remove(profile.id)
                        _deviceDown.tryEmit(DeviceDownEvent(profile.id, profile.name))
                    }
                }
                continue
            }
            downCandidates.remove(profile.id)
            downReported.remove(profile.id)
            tasks.forEach { task -> rows += TaskRow(profile.id, profile.name, task) }
        }
        knownOk.addAll(successes.keys)

        val previous = baseline
        baseline = taskBaseline(rows)
        taskTransitions(previous, rows).forEach { _taskTransitions.tryEmit(it) }
    }

    companion object {
        /** Same cadence as [app.codeg.android.core.data.InteractiveRequestWatcher]
         *  — the two polls ride the same 30s heartbeat. */
        const val POLL_INTERVAL_MS = 30_000L

        /** 批v176: poke debounce — pushes inside this window collapse into one
         *  extra round; anything slower falls through to the regular tick. */
        const val POKE_DEBOUNCE_MS = 1_000L
    }
}
