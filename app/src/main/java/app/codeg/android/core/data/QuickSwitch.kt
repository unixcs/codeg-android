package app.codeg.android.core.data

import app.codeg.android.core.datastore.SessionRef

/**
 * Floating-ball "quick switch" (batch v136): the three former fixed-rank shortcuts
 * (most recent / second / third) become *session depth* shortcuts — depth N walks
 * the N most recently opened sessions as a ring.
 *
 * Why a snapshot ring instead of "rank N of the live recency list": opening a session
 * bumps its read mark, which reorders the recency list. Stepping by rank would then
 * ping-pong (depth 2: open A → A is rank 1, so "second most recent" is B → open B → B
 * is rank 1, so "second" is A …). The ring is captured from the recency list when it
 * is (re)built and only advances its own cursor, so repeated fires really rotate
 * A → B → C → A.
 */
data class QuickSwitchRing(
    /** Depth this ring was built for (1 = the single most recent session). */
    val depth: Int,
    /** Ring members in recency order, newest first (size ≤ depth). */
    val refs: List<SessionRef>,
    /** Index of the member handed out last — the next fire starts after it. */
    val cursor: Int,
)

data class QuickSwitchStep(
    val ref: SessionRef,
    /** The ring (and cursor) to remember for this depth's next fire. */
    val ring: QuickSwitchRing,
)

/**
 * Next target for a depth-[depth] quick switch, or null when nothing is openable.
 *
 * [marks] is the device-local read-mark map (`"<serverId>:<conversationId>"` → openedAt)
 * and [validServers] filters out devices that are hidden or deleted, so a mark left by a
 * since-removed device can't be handed out. [current] is the conversation on screen (null
 * on a list/tab) and is skipped — it comes back only when it is the ring's only member
 * (a one-item ring has nowhere else to go; the caller's dedupe then no-ops).
 *
 * Ring reuse: the previous ring survives only while its members are EXACTLY the current
 * top-[depth] pool (same count, same refs) AND the user is either not in a conversation
 * or inside that ring. Anything else — a member fell out of the top-N, the pool grew
 * past the ring, the user opened an unrelated session, the depth changed — rebuilds it
 * from the pool, which is also what the very first fire does.
 */
fun nextQuickSwitchStep(
    marks: Map<String, Long>,
    validServers: Set<String>,
    depth: Int,
    current: SessionRef?,
    previous: QuickSwitchRing?,
): QuickSwitchStep? {
    if (depth < 1) return null
    val pool = marks.asSequence()
        .mapNotNull { (key, at) ->
            val server = key.substringBeforeLast(':', "")
            val id = key.substringAfterLast(':', "").toIntOrNull()
            if (server.isEmpty() || id == null || server !in validServers) null else SessionRef(server, id) to at
        }
        .sortedByDescending { it.second }
        .map { it.first }
        .take(depth)
        .toList()
    if (pool.isEmpty()) return null

    val carried = previous?.takeIf { prev ->
        prev.depth == depth &&
            prev.refs.isNotEmpty() &&
            // Same member count as the pool: a ring snapshotted while fewer sessions
            // existed must not stay a small ring forever (depth 2/3 would otherwise
            // degrade into the old fixed "most recent" shortcut). Order is NOT compared
            // — opening a member re-ranks the pool on every fire, which must not rebuild.
            prev.refs.size == pool.size &&
            prev.refs.all { it in pool } &&
            (current == null || current in prev.refs)
    }
    val ring = carried?.refs ?: pool
    val start = if (carried == null) {
        // Fresh ring: start at the newest member, unless that is the session already on
        // screen (re-opening it would do nothing visible).
        ring.indexOfFirst { it != current }.coerceAtLeast(0)
    } else {
        // Carried ring: advance past the cursor, skipping the current session (the user
        // may have jumped into a member by hand since the last fire).
        var i = (carried.cursor + 1) % ring.size
        var guard = 0
        while (guard < ring.size && ring[i] == current) {
            i = (i + 1) % ring.size
            guard += 1
        }
        i
    }
    return QuickSwitchStep(ring[start], QuickSwitchRing(depth, ring, start))
}
