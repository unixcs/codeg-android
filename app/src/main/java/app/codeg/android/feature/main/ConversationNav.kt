package app.codeg.android.feature.main

/**
 * Pure guard for conversation-detail pushes (batch v134): repeated triggers of the
 * SAME conversation — floating-ball gestures re-fire in the window before the
 * NavHost recomposes, recent-session ranks fire while the detail itself is on top —
 * must not stack duplicate detail entries, or Back needs one press per duplicate.
 *
 * The comparison is deliberately narrow:
 * - Only a detail destination on top (`conversation/{id}`) can dedupe; the
 *   new-task route is `conversation_new?...` and never matches the prefix.
 * - Conversation ids are server-local autoincrements and collide across devices.
 *   The top entry's OWN device rides the route as `?serverId=` and is passed in as
 *   [topDetailServerId] — never the current selection, which the floating ball can
 *   change while a detail is still on screen, and never a single shell-level stamp,
 *   which goes stale the moment a second detail is pushed over the first. Both
 *   devices known and different ⇒ different conversation, push. When either side is
 *   unknown (deep link, process restore) an id match is treated as duplicate:
 *   stacking the same screen is the bug being fixed, and a rare false dedupe costs
 *   one tap that does nothing.
 * Callers read `nav.currentBackStackEntry` directly: the back queue is in sync
 * right after `navigate()` returns, while `currentBackStackEntryAsState()` lags a
 * frame (that lag is exactly the stacking window this guard closes).
 */
fun isDuplicateDetailPush(
    topRoute: String?,
    topConversationId: Int?,
    targetConversationId: Int,
    targetServerId: String?,
    topDetailServerId: String?,
): Boolean =
    topRoute?.startsWith("conversation/") == true &&
        topConversationId == targetConversationId &&
        !(targetServerId != null && topDetailServerId != null && targetServerId != topDetailServerId)
