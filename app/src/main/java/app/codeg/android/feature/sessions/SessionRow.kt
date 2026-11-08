package app.codeg.android.feature.sessions

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.codeg.android.R
import app.codeg.android.core.common.RelativeTime
import app.codeg.android.core.designsystem.component.AgentAvatar
import app.codeg.android.core.designsystem.component.CenteredAction
import app.codeg.android.core.designsystem.component.CenteredActionMenu
import app.codeg.android.core.designsystem.component.LivePulse
import app.codeg.android.core.designsystem.component.statusColor
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.data.isLiveFresh
import app.codeg.android.core.model.ConversationSummary
import kotlin.math.roundToInt

// 批v165 R4: were compiled per rendered row inside referenceBadge().
private val WS_RUN_RE = Regex("\\s*[\\r\\n]+\\s*")
private val MD_ESCAPE_RE = Regex("[\\\\`*_~\\[\\]()<>]")

/**
 * One conversation row in the Chats / Activity / Search lists: a single
 * borderless line. The agent's brand avatar (with a status-tinted dot) anchors
 * the left, the title fills the middle, and the trailing edge shows a live pulse
 * while running or a compact relative time otherwise. Long-press opens a Pin/
 * Unpin menu plus — when [onHandoff] is wired — a「@ 添加到会话」item that opens a
 * new session composer seeded with a `[title](codeg://session/<id>)` reference,
 * and a copy-reference fallback. The Compose analogue of the iOS row menu.
 *
 * 批v168: the row can also be swiped horizontally. Each direction carries a
 * user-bound action ([swipeLeftAction] / [swipeRightAction], resolved against
 * this row's callbacks) that fires on release past the half-way threshold —
 * the same handlers the long-press menu runs, so a swipe is only a shortcut.
 *
 * Faithful port of the iOS `SessionRow`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionRow(
    conversation: ConversationSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    folderName: String? = null,
    selected: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    onHandoff: ((reference: String) -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onAddToProject: (() -> Unit)? = null,
    /** 批v162 P1: add this session to / remove it from the task board. */
    onToggleBoard: (() -> Unit)? = null,
    /** True when the ledger already holds this session (menu says 移出看板). */
    onBoard: Boolean = false,
    onRename: (() -> Unit)? = null,
    serverName: String? = null,
    unread: Boolean = false,
    /** Floating-ball "cycle session" feedback (batch v132): glowing border + tint. */
    highlighted: Boolean = false,
    /**
     * 批v155(R2D#1): "genuinely running" gate for the pulse AND the status dot.
     * Default is the shared freshness rule — status.isLive + a heartbeat inside
     * [RUNNING_STATUS_MAX_AGE] — because the power-off corpse (status frozen
     * in_progress, no terminal write) must not pulse "running" in Chats/Search
     * any more than in the feed. The Activity feed passes its probe-corroborated
     * `row.live` explicitly.
     */
    live: Boolean = conversation.isLiveFresh(),
    /** 批v168: action fired by swiping the row's content to the LEFT (reveals
     *  the trailing edge). Resolved against this row's callbacks — a bound
     *  action whose callback is null (e.g. pin on the feed) disables that
     *  direction. Null = direction off. */
    swipeLeftAction: SessionSwipeAction? = null,
    /** 批v168: action fired by swiping the row's content to the RIGHT (reveals
     *  the leading edge). Same resolution rules as [swipeLeftAction]. */
    swipeRightAction: SessionSwipeAction? = null,
) {
    val colors = CodegTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    val hasMenu = onTogglePin != null || onHandoff != null || onDelete != null || onAddToProject != null ||
        onRename != null || onToggleBoard != null
    val clipboard = LocalClipboardManager.current
    // 批v170 克制化：悬浮球聚焦高亮走中性浅灰+细边框，accent 让给语义点。
    val highlightBg by animateColorAsState(
        targetValue = if (highlighted) colors.textPrimary.copy(alpha = 0.06f) else Color.Transparent,
        animationSpec = tween(160),
        label = "focusBg",
    )
    // The serialized session reference — the web composer's badge syntax
    // (`[title](codeg://session/<numeric id>)`; same `#id` fallback for a blank
    // title). The agent resolves the id via `get_session_info`. The label is
    // escaped exactly like the web's `escapeMarkdownText` (backslash before every
    // inline-significant ASCII punctuation, newline runs folded to spaces) so a
    // crafted title can't inject Markdown structure or break the link token.
    fun referenceBadge(): String {
        val label = conversation.trimmedTitle?.takeIf { it.isNotBlank() } ?: "#${conversation.id}"
        val safe = label.replace(WS_RUN_RE, " ").replace(MD_ESCAPE_RE) { "\\$it" }
        return "[$safe](codeg://session/${conversation.id})"
    }

    // 批v168: resolve a swipe binding against this row's capabilities. COPY is
    // offered on every surface (clipboard only, no server round-trip); the rest
    // mirror the long-press menu's per-surface wiring — a bound action whose
    // callback is null (e.g. pin on the feed) simply disables that direction.
    @Composable
    fun swipeSpec(action: SessionSwipeAction?): SwipeSpec? = when (action) {
        null -> null
        SessionSwipeAction.BOARD -> onToggleBoard?.let {
            SwipeSpec(
                label = stringResource(if (onBoard) R.string.board_conv_remove else R.string.board_conv_add),
                icon = Icons.Rounded.BookmarkAdd,
                background = colors.accent,
                fire = it,
            )
        }
        SessionSwipeAction.COPY -> SwipeSpec(
            label = stringResource(R.string.session_copy_reference),
            icon = Icons.Rounded.Link,
            background = colors.accent,
            fire = { clipboard.setText(AnnotatedString(referenceBadge())) },
        )
        SessionSwipeAction.PIN -> onTogglePin?.let {
            SwipeSpec(
                label = stringResource(if (conversation.isPinned) R.string.session_unpin else R.string.session_pin),
                icon = Icons.Rounded.PushPin,
                background = colors.accent,
                fire = it,
            )
        }
        SessionSwipeAction.HANDOFF -> onHandoff?.let { callback ->
            SwipeSpec(
                label = stringResource(R.string.session_add_to_session),
                icon = Icons.AutoMirrored.Rounded.CallMade,
                background = colors.accent,
                fire = { callback(referenceBadge()) },
            )
        }
        SessionSwipeAction.RENAME -> onRename?.let {
            SwipeSpec(
                label = stringResource(R.string.session_rename),
                icon = Icons.Rounded.Edit,
                background = colors.accent,
                fire = it,
            )
        }
        SessionSwipeAction.ADD_PROJECT -> onAddToProject?.let {
            SwipeSpec(
                label = stringResource(R.string.session_add_project),
                icon = Icons.Rounded.BookmarkAdd,
                background = colors.accent,
                fire = it,
            )
        }
        SessionSwipeAction.DELETE -> onDelete?.let {
            // The swipe only opens the confirm dialog faster — same pending-state
            // flow as the menu item, so nothing is deleted without confirmation.
            SwipeSpec(
                label = stringResource(R.string.common_delete),
                icon = Icons.Outlined.DeleteOutline,
                background = colors.danger,
                fire = it,
            )
        }
    }

    SwipeActionBox(
        leftSpec = swipeSpec(swipeLeftAction),
        rightSpec = swipeSpec(swipeRightAction),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .then(
                    if (selected) {
                        Modifier.background(colors.textPrimary.copy(alpha = 0.08f))
                    } else {
                        Modifier.background(highlightBg)
                    },
                )
                .then(
                    if (highlighted) {
                        Modifier.border(0.75.dp, colors.surfaceStroke, RoundedCornerShape(14.dp))
                    } else {
                        Modifier
                    },
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (hasMenu) {
                        { menuOpen = true }
                    } else {
                        null
                    },
                )
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box {
                AgentAvatar(conversation.agentType, size = 26.dp)
                // R2D#1: a demoted corpse (status in_progress, no heartbeat) shows
                // the neutral tertiary dot instead of the live blue — the color is
                // part of the "this is running NOW" lie the ghost fix removes.
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 2.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (!live && conversation.status.isLive) colors.textTertiary
                            else statusColor(conversation.status),
                        )
                        .border(1.5.dp, colors.bg, CircleShape),
                )
            }

            Text(
                text = conversation.trimmedTitle ?: stringResource(R.string.session_untitled),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            if (serverName != null) {
                Text(
                    text = serverName,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 72.dp),
                )
            }

            if (folderName != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.widthIn(max = 108.dp),
                ) {
                    Icon(
                        Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(11.dp),
                    )
                    Text(
                        text = folderName,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // R2D#1: pulse follows the same liveness gate as the dot — a frozen
            // corpse row in Last-24h / Chats / Search must not keep pulsing.
            if (live) {
                LivePulse()
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (unread) {
                        // Unread dot: a Last-24h row the user hasn't opened since its last
                        // change (same family as the avatar's status dot, at the row tail).
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF6BC7F2))
                                .border(1.5.dp, colors.bg, CircleShape),
                        )
                    }
                    Text(
                        text = RelativeTime.compact(conversation.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary,
                    )
                }
            }
        }
    }

    // Conditional composition is mandatory: a Dialog always owns a window, and one
    // per visible row would exhaust the window limit (DropdownMenu's Popup composed
    // nothing while closed).
    if (hasMenu && menuOpen) {
        CenteredActionMenu(
            actions = buildList {
                if (onTogglePin != null) {
                    add(
                        CenteredAction(
                            icon = Icons.Rounded.PushPin,
                            label = stringResource(
                                if (conversation.isPinned) R.string.session_unpin else R.string.session_pin,
                            ),
                            onClick = onTogglePin,
                        ),
                    )
                }
                if (onRename != null) {
                    add(
                        CenteredAction(
                            icon = Icons.Rounded.Edit,
                            label = stringResource(R.string.session_rename),
                            onClick = onRename,
                        ),
                    )
                }
                if (onAddToProject != null) {
                    add(
                        CenteredAction(
                            icon = Icons.Rounded.BookmarkAdd,
                            label = stringResource(R.string.session_add_project),
                            onClick = onAddToProject,
                        ),
                    )
                }
                if (onToggleBoard != null) {
                    add(
                        CenteredAction(
                            icon = Icons.Rounded.BookmarkAdd,
                            label = stringResource(
                                if (onBoard) R.string.board_conv_remove else R.string.board_conv_add,
                            ),
                            onClick = onToggleBoard,
                        ),
                    )
                }
                if (onHandoff != null) {
                    add(
                        CenteredAction(
                            icon = Icons.AutoMirrored.Rounded.CallMade,
                            // Same behaviour the web calls "@ 添加到会话": seed a
                            // NEW composer with `[title](codeg://session/<id>)`.
                            // It used to be labelled 接手, which promised a
                            // cross-device relay it never performed — that one
                            // lives in the session's ⋮ (handoff package).
                            label = stringResource(R.string.session_add_to_session),
                            onClick = { onHandoff(referenceBadge()) },
                        ),
                    )
                    add(
                        CenteredAction(
                            icon = Icons.Rounded.Link,
                            label = stringResource(R.string.session_copy_reference),
                            onClick = { clipboard.setText(AnnotatedString(referenceBadge())) },
                        ),
                    )
                }
                if (onDelete != null) {
                    add(
                        CenteredAction(
                            icon = Icons.Outlined.DeleteOutline,
                            label = stringResource(R.string.common_delete),
                            destructive = true,
                            onClick = onDelete,
                        ),
                    )
                }
            },
            onDismiss = { menuOpen = false },
        )
    }
}

/** The swipe anchor set: rest, dragged-to-the-left (fires [SessionSwipeAction]
 *  bound left), dragged-to-the-right (fires the right binding). */
private enum class SwipeAnchor { REST, LEFT, RIGHT }

/** Resolved swipe action — what the reveal pane paints and what firing runs. */
private data class SwipeSpec(
    val label: String,
    val icon: ImageVector,
    val background: Color,
    val fire: () -> Unit,
)

/** Reveal width / trigger distance: the row must travel this far before the
 *  pane is fully exposed; the release threshold is half of it. */
private val SwipeActionWidth = 96.dp

/**
 * 批v168: wraps a row in the Gmail/Outlook swipe skeleton with the iOS Mail
 * reveal skin — dragging past the half-way threshold and releasing settles the
 * content onto the action anchor, which fires the bound action and springs the
 * row back. Directions without a resolvable spec are physically locked (their
 * anchor doesn't exist), and with both off this is a plain Box — zero overhead
 * on rows that opted out. Vertical scrolling is untouched (horizontal-only
 * drag), and the reveal pane rides start/end alignment, flipping with the
 * layout direction (the anchor offsets stay absolute; only zh/en ship today).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwipeActionBox(
    leftSpec: SwipeSpec?,
    rightSpec: SwipeSpec?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (leftSpec == null && rightSpec == null) {
        Box(modifier) { content() }
        return
    }
    val colors = CodegTheme.colors
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val actionPx = with(density) { SwipeActionWidth.toPx() }

    // Keyed on density: the velocity threshold closure captures it, so a config
    // change must rebuild the state (the row just springs back to REST — fine).
    val state = remember(density) {
        AnchoredDraggableState(
            initialValue = SwipeAnchor.REST,
            positionalThreshold = { distance -> distance * 0.5f },
            velocityThreshold = { with(density) { 125.dp.toPx() } },
            snapAnimationSpec = tween(180),
            decayAnimationSpec = exponentialDecay(),
        )
    }
    val anchors = remember(actionPx, leftSpec != null, rightSpec != null) {
        DraggableAnchors {
            SwipeAnchor.REST at 0f
            if (leftSpec != null) SwipeAnchor.LEFT at -actionPx
            if (rightSpec != null) SwipeAnchor.RIGHT at actionPx
        }
    }
    // updateAnchors is a plain (non-suspending) call meant to run post-composition;
    // routing it through a SideEffect keeps recompositions free of state writes.
    SideEffect { state.updateAnchors(anchors) }

    // Specs are re-created every recomposition, so the effects must NOT key on
    // them: a restart would re-emit the current settledValue and fire the same
    // action twice (a board toggle would add-and-remove in one gesture). The
    // latest specs ride in through rememberUpdatedState instead.
    val latestLeft by rememberUpdatedState(leftSpec)
    val latestRight by rememberUpdatedState(rightSpec)

    // Fire on settle, then spring back. settledValue (not currentValue) is the
    // signal — it only changes when the settle animation lands on an anchor, so
    // a drag that brushes past half-way and returns never double-fires.
    LaunchedEffect(state) {
        snapshotFlow { state.settledValue }.collect { settled ->
            val spec = when (settled) {
                SwipeAnchor.LEFT -> latestLeft
                SwipeAnchor.RIGHT -> latestRight
                SwipeAnchor.REST -> null
            } ?: return@collect
            spec.fire()
            state.animateTo(SwipeAnchor.REST)
        }
    }
    // 「到位了」 cue: a tick each time the computed target crosses onto (or back
    // off) an action anchor while dragging.
    LaunchedEffect(state) {
        snapshotFlow { state.targetValue }
            .collect { target ->
                if (target != SwipeAnchor.REST) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
    }

    Box(modifier.clip(RoundedCornerShape(14.dp))) {
        val rawOffset = state.offset
        val offset = if (rawOffset.isNaN()) 0f else rawOffset
        // Reveal panes behind the content: dragging right (offset > 0) exposes
        // the leading (start) pane, dragging left the trailing (end) pane.
        if (rightSpec != null && offset > 0f) {
            RevealPane(spec = rightSpec, alignment = Alignment.CenterStart, modifier = Modifier.matchParentSize())
        }
        if (leftSpec != null && offset < 0f) {
            RevealPane(spec = leftSpec, alignment = Alignment.CenterEnd, modifier = Modifier.matchParentSize())
        }
        Box(
            // Opaque cover so the panes only show in the region the content has
            // vacated — the row normally sits straight on the screen surface.
            // The drag itself rides here: horizontal-only, so vertical list
            // scrolling passes through untouched.
            Modifier
                .offset { IntOffset(offset.roundToInt(), 0) }
                .anchoredDraggable(state, Orientation.Horizontal)
                .background(colors.bg),
        ) {
            content()
        }
    }
}

/** The iOS-Mail skin: a full-bleed tinted pane with the action's icon + label. */
@Composable
private fun RevealPane(
    spec: SwipeSpec,
    alignment: Alignment,
    modifier: Modifier = Modifier,
) {
    val tint = CodegTheme.colors.onAccent
    Box(modifier.background(spec.background), contentAlignment = alignment) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 14.dp),
        ) {
            Icon(spec.icon, contentDescription = spec.label, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(3.dp))
            Text(
                text = spec.label,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 72.dp),
            )
        }
    }
}
