package app.codeg.android.core.data

import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.network.StreamFrame
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * 批v173 C1: the process-wide legacy-firehose listener. One always-on
 * `EventStream` connection per visible device; the frames the rest of the app
 * cares about (`task://changed`, `automation://changed`,
 * `conversation://changed`) are re-published on [events].
 *
 * Why not ride [InteractiveRequestWatcher]'s connections: that watcher's
 * lifecycle follows its refcount holders (shell + keep-alive service), while
 * the push layer must survive them both — C7's run-settled notifications are
 * the whole point of listening in the background. The duplicated connection
 * per device is the price of keeping the two lifecycles independent (the
 * server auto-subscribes every WS to the firehose, so the watcher simply
 * drops these frames and this watcher drops the attach-protocol frames).
 *
 * Deliberately dumb: payloads carry only ids (server's "clients refetch"
 * contract), no replay, no dedup — consumers tick their existing
 * poll/generation machinery, and the 25s/60s polls stay as the floor.
 */
@Singleton
class GlobalEventWatcher @Inject constructor(
    private val repository: ServerRepository,
) {
    data class ServerEvent(
        val serverId: String,
        val channel: String,
        val payload: JsonObject,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _events = MutableSharedFlow<ServerEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    @Volatile
    private var started = false

    /** Idempotent, process-lifetime (no stop — called once from CodegApplication). */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch { loop() }
    }

    /** The channels any in-app consumer (or C7's notifier) reacts to. */
    companion object {
        const val CHANNEL_TASK = "task://changed"
        const val CHANNEL_AUTOMATION = "automation://changed"
        const val CHANNEL_CONVERSATION = "conversation://changed"
        val WATCHED: Set<String> = setOf(CHANNEL_TASK, CHANNEL_AUTOMATION, CHANNEL_CONVERSATION)
        private const val RECONNECT_MIN_MS = 1_000L
        private const val RECONNECT_MAX_MS = 30_000L
        /** A connection that lived at least this long earns a backoff reset —
         *  a flapping server must not climb to 30s and stay there. */
        private const val BACKOFF_RESET_MS = 60_000L
        /** No token yet (device half-configured): retry slower, the profile
         *  list itself won't change when the token lands. */
        private const val NO_TOKEN_RETRY_MS = 30_000L
    }

    private suspend fun loop() {
        // Profile set drives the fan: add/remove/hide a device and every
        // runtime restarts (collectLatest cancels the previous generation).
        repository.visibleProfiles.collectLatest { profiles ->
            coroutineScope {
                profiles.forEach { profile -> launch { watchProfile(profile) } }
                awaitCancellation()
            }
        }
    }

    private suspend fun watchProfile(profile: ServerProfile) {
        var backoffMs = RECONNECT_MIN_MS
        while (currentCoroutineContext().isActive) {
            val stream = repository.eventStream(profile)
            if (stream == null) {
                delay(NO_TOKEN_RETRY_MS)
                continue
            }
            val connectedAt = android.os.SystemClock.elapsedRealtime()
            // frames() is cold and never throws: it terminates with Closed
            // (graceful or error) — the loop then reconnects with backoff.
            stream.frames().collect { frame ->
                if (frame is StreamFrame.Ready) backoffMs = RECONNECT_MIN_MS
                if (frame is StreamFrame.Channel && frame.channel in WATCHED) {
                    _events.tryEmit(ServerEvent(profile.id, frame.channel, frame.payload))
                }
            }
            val lived = android.os.SystemClock.elapsedRealtime() - connectedAt
            if (lived >= BACKOFF_RESET_MS) backoffMs = RECONNECT_MIN_MS
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(RECONNECT_MAX_MS)
        }
    }
}
