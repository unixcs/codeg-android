package app.codeg.android.feature.main

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Horizontal swipe navigation between the bottom tabs.
 *
 * Design constraints (from the implementation plan + review):
 * - **Directional, discrete:** a completed fling/drag past the threshold moves
 *   exactly one tab; it is NOT a pager — tab destinations keep their own
 *   view models, state, and transitions, so we only fire [onSwipe] when the
 *   gesture is decided.
 * - **Vertical wins first:** a drag is only accepted once |dx| exceeds 1.2×
 *   |dy| and a minimum distance, so vertical list scrolling is never hijacked.
 * - **Edge exclusion:** drags starting within [edgeExclusion] of the screen's
 *   left/right edge are ignored — that band belongs to the system back gesture.
 * - **RTL-aware:** under RTL, drag directions are mirrored.
 */
fun Modifier.tabSwipeNavigation(
    enabled: Boolean,
    onSwipe: (direction: SwipeDirection) -> Unit,
): Modifier = composed {
    if (!enabled) return@composed Modifier
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    // pointerInput only restarts when its keys change, so the gesture block keeps
    // running with the FIRST composition's lambda — read the latest through state.
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    val thresholdPx = with(density) { TabSwipeDefaults.distanceThreshold.toPx() }
    val edgePx = with(density) { TabSwipeDefaults.edgeExclusion.toPx() }

    pointerInput(layoutDirection) {
        var startX = 0f
        var startY = 0f
        var decided = false
        var accepted = false
        var totalX = 0f

        detectHorizontalDragGestures(
            onDragStart = { offset ->
                startX = offset.x
                startY = offset.y
                decided = false
                accepted = false
                totalX = 0f
            },
            onDragEnd = {
                if (accepted && !decided) {
                    // Released before the threshold: decide by total travel.
                    decide(totalX, layoutDirection, currentOnSwipe)
                    decided = true
                }
            },
        ) { change, _ ->
            if (decided) return@detectHorizontalDragGestures
            // Ignore drags that START in the system back-gesture band.
            val width = size.width.toFloat()
            if (startX < edgePx || startX > width - edgePx) {
                decided = true
                return@detectHorizontalDragGestures
            }
            totalX += change.positionChange().x
            val dy = change.position.y - startY
            val dx = change.position.x - startX
            if (!accepted) {
                // First decision: horizontal vs vertical. 1.2:1 favours vertical
                // scrolling so slightly diagonal list swipes are not captured.
                if (abs(dx) > thresholdPx / 2 && abs(dx) > abs(dy) * 1.2f) {
                    accepted = true
                } else if (abs(dy) > thresholdPx) {
                    decided = true // vertical scroll claimed the gesture
                }
            } else if (abs(totalX) >= thresholdPx) {
                decide(totalX, layoutDirection, currentOnSwipe)
                decided = true
            }
        }
    }
}

private fun decide(
    totalX: Float,
    layoutDirection: LayoutDirection,
    onSwipe: (SwipeDirection) -> Unit,
) {
    // A leftward drag (negative x) shows the NEXT tab; mirrored under RTL.
    val leftward = totalX < 0
    val direction = when {
        leftward == (layoutDirection == LayoutDirection.Ltr) -> SwipeDirection.NEXT
        else -> SwipeDirection.PREVIOUS
    }
    onSwipe(direction)
}

enum class SwipeDirection { NEXT, PREVIOUS }

object TabSwipeDefaults {
    /** Total horizontal travel (or fling release travel) that commits a tab change. */
    val distanceThreshold = 72.dp

    /** Band on each screen edge that belongs to the system back gesture. */
    val edgeExclusion = 24.dp
}
