package app.codeg.android.core.data

import app.codeg.android.core.model.AgentOptionsSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-level cache of the per-agent options catalog (the `describeAgentOptions` probe
 * spawns a real agent CLI server-side and takes seconds). Batch v133 kept this on the
 * session-detail ViewModel, so every "new task" navigation started from a cold cache
 * and the options sheet spun through the full probe again; a singleton lets
 * consecutive drafts and detail pushes share one probe per (server, agent, workspace).
 *
 * stale-while-revalidate contract: [get] returns the last good snapshot instantly;
 * callers revalidate with `describeCatalog(force = true)` and the fresh result
 * overwrites the entry ONLY on success — a failed probe never destroys the stale
 * copy. Keys include the serverId: workspace paths like /mnt/Projects/929 collide
 * across devices, and a probe result is only valid for the server that produced it.
 * Entries are LRU-capped — worktree paths grow without bound.
 */
@Singleton
class AgentCatalogStore @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val entries = LinkedHashMap<String, AgentOptionsSnapshot>(16, 0.75f, true)
    private val inFlight = mutableMapOf<String, Deferred<AgentOptionsSnapshot?>>()

    /** Last good snapshot for [key]; also refreshes its LRU position. */
    fun get(key: String): AgentOptionsSnapshot? = synchronized(lock) { entries[key] }

    /** Single-flight probe per [key]. The probe runs on the store's own scope, so it
     *  outlives the ViewModel that requested it — a VM dying mid-probe doesn't waste
     *  the server's expensive CLI spawn, and the result still warms the cache. */
    fun getOrProbe(key: String, probe: suspend () -> AgentOptionsSnapshot?): Deferred<AgentOptionsSnapshot?> =
        synchronized(lock) {
            inFlight[key]?.let { return it }
            val deferred = scope.async { runCatching { probe() }.getOrNull() }
            inFlight[key] = deferred
            deferred.invokeOnCompletion {
                val result = runCatching { deferred.getCompleted() }.getOrNull()
                synchronized(lock) {
                    inFlight.remove(key)
                    if (result != null) {
                        entries[key] = result
                        while (entries.size > MAX_ENTRIES) {
                            entries.remove(entries.keys.iterator().next())
                        }
                    }
                }
            }
            deferred
        }

    private companion object {
        const val MAX_ENTRIES = 32
    }
}
