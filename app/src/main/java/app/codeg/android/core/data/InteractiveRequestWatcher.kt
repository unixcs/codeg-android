package app.codeg.android.core.data

import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.model.AcpEvent
import app.codeg.android.core.model.EventEnvelope
import app.codeg.android.core.network.EventStream
import app.codeg.android.core.network.PetSessionRow
import app.codeg.android.core.network.StreamFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Which surface an interaction needs. */
enum class InteractionKind { PERMISSION, QUESTION, PLAN }

/**
 * One pending interaction (permission / question / plan approval) the watcher
 * saw on any device — the row behind the workbench 「等待你确认」 section (批v160)
 * and the W8 system notification. [title] is best-effort content; the UI falls
 * back to the kind label when blank.
 */
data class PendingInteraction(
    val serverId: String,
    val serverName: String,
    val conversationId: Int,
    val connectionId: String,
    val kind: InteractionKind,
    /** permission request_id / question_id / plan approval_id. */
    val requestId: String,
    val title: String,
    val optionCount: Int,
    /** Epoch ms the interaction was FIRST seen — drives the waiting-duration line. */
    val since: Long,
) {
    /** Dedupe key: the same request re-delivered (snapshot / replay) is one row. */
    val key: String get() = "$serverId|$connectionId|$kind|$requestId"
}

/**
 * 系统级通知体系: a turn just finished on some conversation the watcher is
 * attached to — the realtime "execution finished" signal behind the task-results
 * notification. [conversationTitle] is best-effort: the watcher's own 30s poll
 * already carries per-connection titles (zero extra requests); null → the
 * notifier falls back to a generic string. [stopReason] rides the existing
 * `turn_complete` event verbatim (never null from the wire decoder; kept
 * nullable because it is informational only).
 */
data class TurnCompleteEvent(
    val serverId: String,
    val conversationId: Int,
    val conversationTitle: String? = null,
    val stopReason: String?,
)

/**
 * App-scoped watcher for "an agent is blocked waiting for the user" (批v160 需要处理
 * 汇聚 + W8 notifications). There is no global event firehose in the server, so the
 * watcher builds one out of the two primitives that exist:
 *
 * 1. Every 30s, poll each visible profile's `pet_list_active_sessions` and diff the
 *    live connection table against the current attach set. New connections get
 *    `eventStream.attach` — whose SNAPSHOT restores the pending permission/question/
 *    plan three-state even if the request fired while nobody was watching. Gone
 *    connections are detached and their registrations dropped.
 * 2. Per-profile frame collectors consume the attached streams: interactive request
 *    events register a [PendingInteraction]; their `*_resolved` twins remove it;
 *    `turn_complete` clears everything on that connection (the same rule the session
 *    detail screen applies to its own cards).
 *
 * Lifecycle is reference-counted ([start]/[stop]) so the shell (foreground) and the
 * W8 keep-alive service (background) can hold it independently. Registered requests
 * survive a stop — the next poll's diff cleans up anything that resolved in between.
 */
@Singleton
class InteractiveRequestWatcher @Inject constructor(
    private val repository: ServerRepository,
) {
    private val supervisor = SupervisorJob()
    private val scope = kotlinx.coroutines.CoroutineScope(supervisor + Dispatchers.Default)

    private val _requests = MutableStateFlow<List<PendingInteraction>>(emptyList())
    val requests: StateFlow<List<PendingInteraction>> = _requests.asStateFlow()

    /** 系统级通知体系: hot stream of turn completions. Replay-free (a notifier
     *  only cares about turns finishing from now on) with a small drop-oldest
     *  buffer so a slow consumer cannot block the frame collectors. 批v161.3
     *  R2: 16 → 64 alongside the other notification buffers — a burst of
     *  finishing turns plus a cold-start snapshot used to thin the margin. */
    private val _turnComplete = MutableSharedFlow<TurnCompleteEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val turnComplete: SharedFlow<TurnCompleteEvent> = _turnComplete.asSharedFlow()

    /** Per-profile runtime: the cold stream, its collector job and the poll table. */
    private class ProfileRuntime(
        val stream: EventStream,
        /** connectionId → conversationId, fed by the poll and the snapshots. */
        val connections: ConcurrentHashMap<String, Int> = ConcurrentHashMap(),
        /** connectionId → last title the poll saw (PetSessionRow.title) — the
         *  zero-cost source for [TurnCompleteEvent.conversationTitle]. */
        val titles: ConcurrentHashMap<String, String> = ConcurrentHashMap(),
    ) {
        var job: Job? = null
    }

    private val runtimes = ConcurrentHashMap<String, ProfileRuntime>()
    private var pollJob: Job? = null
    private val refCount = AtomicInteger(0)

    /** Idempotent: N holders (shell + keep-alive service) share one running watcher. */
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
        runtimes.keys.toList().forEach(::retireProfile)
    }

    /** One subscription PER CONNECTION (批v160 review): the server's detach kills
     *  exactly one subscription — a per-profile id would orphan every sibling
     *  connection the moment one of them is detached. */
    private fun subscriptionId(serverId: String, connectionId: String) =
        "watcher-$serverId-$connectionId"

    private suspend fun pollLoop() {
        while (scope.isActive) {
            try {
                pollOnce()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // One bad round (DataStore read, transport) must not kill the loop.
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun pollOnce() {
        val profiles = repository.profiles.first().filter { it.visible }
        val liveIds = profiles.map { it.id }.toSet()
        // A deleted (or hidden) device must not keep feeding its registrations.
        runtimes.keys.filter { it !in liveIds }.forEach(::retireProfile)

        for (profile in profiles) {
            val rows = runCatching {
                repository.client(profile)?.petSessions()
            }.getOrNull()
            // Null = transport failure or old server: keep the current attach set
            // (and registrations) untouched — a flaky poll must not blink the list.
            if (rows == null) continue

            val runtime = runtimeFor(profile) ?: continue // no token → skip
            val desired = rows.associate { it.connectionId to it.conversationId }
            // 系统级通知体系: remember what each connection is called while the
            // poll is in hand — turn-complete notifications reuse these titles.
            rows.forEach { row -> runtime.titles[row.connectionId] = row.title }
            val current = runtime.connections.keys.toSet()
            (desired.keys - current).forEach { conn ->
                runtime.stream.attach(subscriptionId(profile.id, conn), conn)
            }
            (current - desired.keys).forEach { conn ->
                runtime.stream.detach(subscriptionId(profile.id, conn))
                removeConnection(profile.id, conn)
                runtime.connections.remove(conn)
                runtime.titles.remove(conn)
            }
            runtime.connections.putAll(desired)
        }
    }

    /** (Re)create the stream collector for [profile]; null when it has no token. */
    private suspend fun runtimeFor(profile: ServerProfile): ProfileRuntime? {
        runtimes[profile.id]?.takeIf { it.job?.isActive == true }?.let { return it }
        runtimes[profile.id]?.let { existing ->
            existing.job?.cancel()
            runtimes.remove(profile.id)
        }
        val stream = repository.eventStream(profile) ?: return null
        val runtime = ProfileRuntime(stream)
        runtime.job = scope.launch {
            try {
                stream.frames().collect { frame -> handleFrame(profile, runtime, frame) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Retired or restarted: fall through to the cleanup below.
            } catch (_: Exception) {
                // Socket failure: the next poll rebuilds the runtime.
            }
            // A dead collector must not pin the runtime: the next poll rebuilds it.
            runtimes.remove(profile.id, runtime)
        }
        runtimes[profile.id] = runtime
        return runtime
    }

    private fun retireProfile(serverId: String) {
        runtimes.remove(serverId)?.job?.cancel()
        _requests.update { list -> list.filterNot { it.serverId == serverId } }
    }

    private fun removeConnection(serverId: String, connectionId: String) {
        _requests.update { list ->
            list.filterNot { it.serverId == serverId && it.connectionId == connectionId }
        }
    }

    private fun handleFrame(profile: ServerProfile, runtime: ProfileRuntime, frame: StreamFrame) {
        when (frame) {
            is StreamFrame.Snapshot -> {
                val s = frame.snapshot
                val connId = s.connectionId ?: return
                s.conversationId?.let { runtime.connections[connId] = it }
                val conv = s.conversationId ?: runtime.connections[connId] ?: return
                s.pendingPermission?.let { p ->
                    register(
                        profile, connId, conv, InteractionKind.PERMISSION, p.requestId,
                        permissionTitle(p.toolCall), p.options.size,
                    )
                }
                s.pendingQuestion?.let { q ->
                    register(
                        profile, connId, conv, InteractionKind.QUESTION, q.questionId,
                        q.questions.firstOrNull()?.question?.takeIf { it.isNotBlank() },
                        q.questions.sumOf { it.options.size },
                    )
                }
                s.pendingPlanApproval?.let { p ->
                    register(profile, connId, conv, InteractionKind.PLAN, p.approvalId, null, 0)
                }
            }
            is StreamFrame.Replay -> frame.events.forEach { onEvent(profile, runtime, it) }
            is StreamFrame.Event -> onEvent(profile, runtime, frame.envelope)
            // Re-attach after a (re)connect so the server re-sends the snapshot —
            // attach messages sent before the handshake are queued either way, this
            // covers connections discovered after the socket came up.
            StreamFrame.Ready -> runtime.connections.keys.forEach { conn ->
                runtime.stream.attach(subscriptionId(profile.id, conn), conn)
            }
            // Transient socket drops: the poll re-attaches within one interval; the
            // registrations stay (the list must not blink on every wifi hiccup).
            else -> {}
        }
    }

    private fun onEvent(profile: ServerProfile, runtime: ProfileRuntime, envelope: EventEnvelope) {
        val conv = runtime.connections[envelope.connectionId] ?: return
        when (val e = envelope.event) {
            is AcpEvent.PermissionRequest -> register(
                profile, envelope.connectionId, conv, InteractionKind.PERMISSION,
                e.requestId, permissionTitle(e.toolCall), e.options.size,
            )
            is AcpEvent.QuestionRequest -> register(
                profile, envelope.connectionId, conv, InteractionKind.QUESTION,
                e.questionId,
                e.questions.firstOrNull()?.question?.takeIf { it.isNotBlank() },
                e.questions.sumOf { it.options.size },
            )
            is AcpEvent.PlanApprovalRequest -> register(
                profile, envelope.connectionId, conv, InteractionKind.PLAN, e.approvalId, null, 0,
            )
            is AcpEvent.PermissionResolved ->
                remove(profile.id, envelope.connectionId, InteractionKind.PERMISSION, e.requestId)
            is AcpEvent.QuestionResolved ->
                remove(profile.id, envelope.connectionId, InteractionKind.QUESTION, e.questionId)
            is AcpEvent.PlanApprovalResolved ->
                remove(profile.id, envelope.connectionId, InteractionKind.PLAN, e.approvalId)
            // Turn over → nothing can still be waiting on this connection (the
            // detail screen clears its own cards with the same rule). The same
            // frame is the app-wide "execution finished" signal (系统级通知体系):
            // emit it with the conversation the connection was bound to, plus
            // whatever title the last poll had seen for it.
            is AcpEvent.TurnComplete -> {
                val title = runtime.titles.remove(envelope.connectionId)
                _turnComplete.tryEmit(
                    TurnCompleteEvent(
                        serverId = profile.id,
                        conversationId = conv,
                        conversationTitle = title?.takeIf { it.isNotBlank() },
                        stopReason = e.stopReason,
                    ),
                )
                removeConnection(profile.id, envelope.connectionId)
            }
            else -> {}
        }
    }

    private fun register(
        profile: ServerProfile,
        connectionId: String,
        conversationId: Int,
        kind: InteractionKind,
        requestId: String,
        title: String?,
        optionCount: Int,
    ) {
        if (requestId.isBlank()) return
        _requests.update { list ->
            val key = PendingInteraction(
                profile.id, profile.name, conversationId, connectionId, kind, requestId, "", 0, 0,
            ).key
            val since = list.firstOrNull { it.key == key }?.since ?: System.currentTimeMillis()
            val row = PendingInteraction(
                serverId = profile.id,
                serverName = profile.name,
                conversationId = conversationId,
                connectionId = connectionId,
                kind = kind,
                requestId = requestId,
                title = title.orEmpty(),
                optionCount = optionCount,
                since = since,
            )
            list.filterNot { it.key == key } + row
        }
    }

    private fun remove(serverId: String, connectionId: String, kind: InteractionKind, requestId: String) {
        _requests.update { list ->
            list.filterNot {
                it.serverId == serverId && it.connectionId == connectionId &&
                    it.kind == kind && it.requestId == requestId
            }
        }
    }

    /** Best-effort title off the freeform `tool_call` object — the same field the
     *  permission card's [ParsedPermission] reads (`title` / tool name). */
    private fun permissionTitle(toolCall: kotlinx.serialization.json.JsonElement?): String? {
        val obj = toolCall as? JsonObject ?: return null
        return listOf("title", "toolName", "tool_name", "name")
            .firstNotNullOfOrNull { key ->
                (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            }
    }

    companion object {
        /** Watch cadence: the snapshot-on-attach carries the pending state, so the
         *  poll only needs to catch NEW connections — 30s is plenty (and light). */
        const val POLL_INTERVAL_MS = 30_000L
    }
}
