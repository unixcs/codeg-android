package app.codeg.android.feature.sessiondetail.timeline

import app.codeg.android.core.model.AgentType

/** Why a completed reply's Fork chip is greyed out instead of hidden. */
enum class ForkBlockReason {
    /** The attached agent or the server does not implement `session/fork`. */
    UNSUPPORTED,

    /** A turn is streaming, or another fork is already in flight. */
    BUSY,

    /**
     * The server cannot name this branch point: the reply has no assistant text
     * to fingerprint, or its id is a client-side `live-*` name the server's own
     * parse has never seen — and it is not the thread tail.
     */
    UNRESOLVED,
}

/** What the footer's Fork chip should do. */
sealed interface ForkGate {
    /** [turnId] null means "fork at the thread tail". */
    data class Ready(val turnId: String?) : ForkGate

    data class Blocked(val reason: ForkBlockReason) : ForkGate
}

/** Session-wide inputs for every footer's Fork chip. */
data class ForkSessionState(
    val agent: AgentType = AgentType.CODEX,
    val supported: Boolean = false,
    val busy: Boolean = false,
)

object ForkAvailability {
    private const val LIVE_TURN_PREFIX = "live-"

    /**
     * A turn this app streamed itself is named `live-*` until the post-turn
     * reload swaps in the server transcript. The backend resolves fork points
     * against its own parse (`turn-N`), so a live id is not a usable anchor.
     */
    fun isLiveTurnId(id: String): Boolean = id.startsWith(LIVE_TURN_PREFIX)

    /** The first Android release deliberately exposes Codex only, even if a
     *  newer server advertises `session/fork` for another adapter. */
    fun supportsAgent(agent: AgentType): Boolean = agent == AgentType.CODEX

    /**
     * Decide whether this reply can be forked and, when it cannot, why.
     *
     * The server resolves Codex fork points by hashing assistant TEXT, because
     * rollout files record no item ids. A reply with no text can only be forked
     * at the tail — which is exact precisely when this reply ends the thread.
     * Anywhere earlier it would silently fork at the wrong point, so the chip
     * is greyed out instead of sending an anchor the server cannot resolve.
     *
     * A live id is blocked everywhere: it only exists in this client, so the
     * server cannot prove that reply is in the parent session's tail yet. A
     * null id at the real tail is different — the server's own parse ends
     * there, so its `None` path is exact.
     */
    fun resolve(
        agent: AgentType,
        supported: Boolean,
        busy: Boolean,
        forkTurnId: String?,
        isThreadTail: Boolean,
    ): ForkGate = when {
        !supportsAgent(agent) || !supported -> ForkGate.Blocked(ForkBlockReason.UNSUPPORTED)
        busy -> ForkGate.Blocked(ForkBlockReason.BUSY)
        isLiveTurnId(forkTurnId.orEmpty()) -> ForkGate.Blocked(ForkBlockReason.UNRESOLVED)
        isThreadTail -> ForkGate.Ready(forkTurnId)
        forkTurnId == null -> ForkGate.Blocked(ForkBlockReason.UNRESOLVED)
        else -> ForkGate.Ready(forkTurnId)
    }
}
