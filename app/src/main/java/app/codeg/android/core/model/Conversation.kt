package app.codeg.android.core.model

import app.codeg.android.core.model.wire.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * A conversation/session persisted on a server (Rust `DbConversationSummary`).
 * Mutable-feeling optimistic fields on iOS (`title`, `status`, `pinnedAt`) are
 * `val`s here; the UI layer holds optimistic copies in its state.
 */
@Serializable
data class ConversationSummary(
    val id: Int,
    val folderId: Int,
    val title: String? = null,
    val agentType: AgentType,
    val status: ConversationStatus,
    val model: String? = null,
    val gitBranch: String? = null,
    val externalId: String? = null,
    val messageCount: Int = 0,
    /** Wire `kind`: "regular" | "chat" | "loop" | "delegate" (Rust
     *  `ConversationKind`). Null on a hypothetical old server that omits it.
     *  批v165: `delegate` rows are delegation sub-sessions — the server's pet
     *  snapshot deliberately EXCLUDES them while they work, so live-probe
     *  consumers must not treat "not probed" as "not running" for them. */
    val kind: String? = null,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant,
    @Serializable(with = InstantSerializer::class)
    val updatedAt: Instant,
    /** Server `pinned_at`, or null when not pinned. Pinning does NOT bump
     * `updated_at` — the "Pinned" group sorts by this instead. */
    @Serializable(with = InstantSerializer::class)
    val pinnedAt: Instant? = null,
) {
    /** Non-empty, trimmed user title, or null for an unnamed session. Render
     * verbatim (user data); fall back to a localized "Untitled session" only
     * when this is null. */
    val trimmedTitle: String?
        get() = title?.takeIf { it.isNotBlank() }

    val isPinned: Boolean get() = pinnedAt != null
}

/** Aggregate token/timing stats for a session (Rust `SessionStats`). */
@Serializable
data class SessionStats(
    val totalUsage: TurnUsage? = null,
    val totalTokens: Int? = null,
    val totalDurationMs: Int = 0,
    val contextWindowUsedTokens: Int? = null,
    val contextWindowMaxTokens: Int? = null,
    val contextWindowUsagePercent: Double? = null,
)

/**
 * Full session detail incl. message history (Rust `DbConversationDetail`).
 * Decode-only. [turns] uses the hand-written `ContentBlock` serializer for its
 * polymorphic blocks.
 *
 * fleet M1 turn window: when the request carried `tailTurns`/`fromIndex` (and
 * the server is 0.41.3+), [turns] holds only the tail slice from turnsOffset on, while
 * summary/stats still describe the FULL transcript, and the four window fields
 * are set together. All null (or any one missing) = a legacy full response —
 * `turns` is the whole transcript and windowed merging must stay disabled.
 */
@Serializable
data class ConversationDetail(
    val summary: ConversationSummary,
    val turns: List<MessageTurn> = emptyList(),
    val sessionStats: SessionStats? = null,
    val inFlightUserTurnId: String? = null,
    /** Global index of [turns][.turns]' first element (window responses only). */
    val turnsOffset: Int? = null,
    /** Length of the full transcript (window responses only). */
    val turnsTotal: Int? = null,
    /** Assistant turns above the window — send-time baseline conversion. */
    val assistantTurnsBeforeOffset: Int? = null,
    /** User turns above the window (absent from servers predating it). */
    val userTurnsBeforeOffset: Int? = null,
    /**
     * Structural fingerprint of `full[0..turnsOffset)` (16-hex string) — the
     * loaded window's seam. A history page's [ConversationTurnsPage.prefixHashBeforeIndex]
     * must equal this before the page may join the window.
     */
    val prefixHash: String? = null,
)

/**
 * One page of older history (`get_folder_conversation_turns`, fleet M1):
 * `full[turnsOffset .. turnsOffset + turns.size)`, ending just before the
 * requested beforeIndex. Light path — no summary, no stats, no live
 * correlation. Splice contract (web `requestOlderPage`): the page joins the
 * loaded window only when [prefixHashBeforeIndex] equals the window's
 * [ConversationDetail.prefixHash]; after a splice, [prefixHash] becomes the
 * window's new seam fingerprint.
 */
@Serializable
data class ConversationTurnsPage(
    val turns: List<MessageTurn> = emptyList(),
    val turnsOffset: Int,
    val turnsTotal: Int,
    val assistantTurnsBeforeOffset: Int = 0,
    val userTurnsBeforeOffset: Int = 0,
    val prefixHash: String,
    val prefixHashBeforeIndex: String,
)

/** Returned by `acp_find_connection_for_conversation` when a live ACP
 * connection already owns a conversation. */
@Serializable
data class ConversationConnectionInfo(
    val connectionId: String,
    val eventSeq: Long = 0,
)

/** `health` endpoint response — used to validate a server profile. */
@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
)
