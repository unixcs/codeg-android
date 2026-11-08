package app.codeg.android.core.designsystem.component

import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.codeg.android.core.designsystem.theme.CodegTheme
import kotlin.math.roundToInt

/** The swipe anchor set: rest, dragged-to-the-left (fires the left [SwipeSpec]),
 *  dragged-to-the-right (fires the right binding). */
enum class SwipeAnchor { REST, LEFT, RIGHT }

/** Resolved swipe action — what the reveal pane paints and what firing runs.
 *  批v171 N7: lifted from the sessions package so the inbox cards can reuse the
 *  same Gmail/Outlook skeleton; business mapping (action id → spec) stays with
 *  the caller. */
data class SwipeSpec(
    val label: String,
    val icon: ImageVector,
    val background: Color,
    val fire: () -> Unit,
)

/** Reveal width / trigger distance: the row must travel this far before the
 *  pane is fully exposed; the release threshold is half of it. */
private val SwipeActionWidth = 96.dp

/**
 * 批v168 (session rows) / 批v171 N7 (generalized): wraps a row in the
 * Gmail/Outlook swipe skeleton with the iOS Mail reveal skin — dragging past
 * the half-way threshold and releasing settles the content onto the action
 * anchor, which fires the bound action and springs the row back. Directions
 * without a resolvable spec are physically locked (their anchor doesn't
 * exist), and with both off this is a plain Box — zero overhead on rows that
 * opted out. Vertical scrolling is untouched (horizontal-only drag), and the
 * reveal pane rides start/end alignment, flipping with the layout direction.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeActionBox(
    leftSpec: SwipeSpec?,
    rightSpec: SwipeSpec?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 14.dp,
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

    Box(modifier.clip(RoundedCornerShape(cornerRadius))) {
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
