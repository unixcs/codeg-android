package app.codeg.android.feature.floatingball

import kotlin.math.hypot

/**
 * Pure-JVM floating-ball gesture classifier (batch v126), ported from the web
 * codeg floating ball (`codeg-floating-ball.user.js`), itself ported from MindFS
 * quickSwitch.ts. No Compose / Android dependency — the ball composable feeds it
 * pointer events in container coordinates and turns the returned [GestureResult]s
 * into binding fires; every threshold and branch here is unit-tested.
 *
 * Deviations from the web original (both adversarial-review fixes, not regressions):
 * 1. On up, a two-segment path is folded BEFORE the net-displacement gate — the web
 *    gates `foldGesture` behind `total >= MOVE_PX`, which silently kills every
 *    "returns to origin" fold (a clean ↑↓ ends where it started, so its net
 *    displacement is ~0). Here the gate only applies to the single-segment branch.
 * 2. The double-tap timer does not live here. The classifier tracks the pending-tap
 *    generation; the composable layer runs the delay and only fires if the
 *    generation still matches (the web's setTimeout fires into whatever gesture
 *    happens to be running 300ms later).
 */
sealed interface GestureResult {
    /** Nothing concluded yet (event absorbed). */
    object None : GestureResult

    /** The finger stayed down past the fast-swipe window before moving — drag-to-move. */
    object EnterDrag : GestureResult

    /** Drag continuation. */
    object Dragging : GestureResult

    /** Drag released — caller snaps to an edge and persists the position. */
    object DragEnd : GestureResult

    /** A tap that may still become a double-tap; start the window timer on [generation]. */
    data class TapCandidate(val generation: Int) : GestureResult

    data class DoubleTap(val generation: Int) : GestureResult

    /** Released after [GestureClassifier.longPressMs] without moving (release-time semantics). */
    data class LongPress(val generation: Int) : GestureResult

    /** A recognized swipe gesture; [gesture] is a key of [FloatingBallCatalog.GESTURES]. */
    data class Named(val gesture: String, val generation: Int) : GestureResult
}

/**
 * Two-phase path recognizer. State machine per touch:
 * down → (moves) → segments accumulate against the last turning-point anchor →
 * up folds the path. Fast swipes (moving within [tapMs] of the down) classify as
 * gestures; slow presses that only then start moving switch to drag-the-ball mode.
 * Create once per ball and reuse — [cancel] resets between gestures; nothing is
 * allocated per move.
 */
class GestureClassifier(
    val tapMs: Long = 250L,
    val doubleTapMs: Long = 300L,
    val longPressMs: Long = 600L,
    private val movePx: Float = 8f,
    private val swipePx: Float = 30f,
) {
    /** Bumped on every [onDown] — the tap-timer generation guard. */
    var generation = 0
        private set

    /**
     * Non-null while a released tap is still inside the double-tap window. NOT
     * cleared by the next [onDown] (that would kill double-tap), but cleared the
     * moment that touch turns into a move / drag or completes as a double-tap.
     */
    var pendingTapGeneration: Int? = null
        private set

    var isDragging = false
        private set

    private var t0 = 0L
    private var x0 = 0f
    private var y0 = 0f
    private var moved = false
    private val path = ArrayList<String>(4)
    private var anchorX = 0f
    private var anchorY = 0f
    private var lastTapUpAt = 0L

    fun onDown(t: Long, x: Float, y: Float): GestureResult {
        generation++
        t0 = t
        x0 = x
        y0 = y
        moved = false
        isDragging = false
        path.clear()
        anchorX = x
        anchorY = y
        return GestureResult.None
    }

    fun onMove(t: Long, x: Float, y: Float): GestureResult {
        if (isDragging) return GestureResult.Dragging
        val total = hypot(x - x0, y - y0)
        if (!moved && total >= movePx) {
            if (t - t0 < tapMs) {
                // Fast start → this touch is a gesture; any pending single tap dies.
                moved = true
                pendingTapGeneration = null
            } else {
                // Held still past the fast window, then moved → drag the ball.
                isDragging = true
                pendingTapGeneration = null
                return GestureResult.EnterDrag
            }
        }
        if (!moved) return GestureResult.None

        val seg = classifySegment(x - anchorX, y - anchorY) ?: return GestureResult.None
        val lastSeg = path.lastOrNull()
        if (seg != lastSeg) {
            val isFold = lastSeg != null && isOpposite(lastSeg, seg)
            when {
                // A fold pair (or the first segment) always opens/pushes a segment.
                isFold || path.isEmpty() -> path.add(seg)
                // Startup jitter: a single non-fold tweak replaces the segment (U → UL),
                // but a pair that would fold (UL → D) MUST push or the second leg is eaten.
                path.size == 1 && foldGesture(path[0], seg) == null -> path[0] = seg
                else -> path.add(seg)
            }
        }
        // Re-anchor at the current fingertip either way — the next segment's
        // displacement is measured from here, never from the touch origin (a fold's
        // reverse leg would otherwise cancel the net displacement and misclassify).
        anchorX = x
        anchorY = y
        return GestureResult.None
    }

    fun onUp(t: Long, x: Float, y: Float): GestureResult {
        val gen = generation
        if (isDragging) {
            isDragging = false
            return GestureResult.DragEnd
        }
        val dur = t - t0
        if (!moved) {
            val result: GestureResult = when {
                dur >= longPressMs -> GestureResult.LongPress(gen)
                // Double-tap requires a PREVIOUS tap still pending (a candidate the
                // timer hasn't consumed). lastTapUpAt alone would misfire on the
                // very first tap ever (t - 0 < window), and an intervening gesture
                // already cleared pendingTapGeneration — so no false chains.
                t - lastTapUpAt < doubleTapMs && pendingTapGeneration != null -> {
                    lastTapUpAt = 0L
                    pendingTapGeneration = null
                    GestureResult.DoubleTap(gen)
                }
                else -> {
                    lastTapUpAt = t
                    pendingTapGeneration = gen
                    GestureResult.TapCandidate(gen)
                }
            }
            resetGesture()
            return result
        }
        // Fold first, before any net-displacement gate — see class kdoc deviation #1.
        val folded = if (path.size >= 2) foldGesture(path[path.size - 2], path[path.size - 1]) else null
        if (folded != null) {
            resetGesture()
            return GestureResult.Named(folded, gen)
        }
        val total = hypot(x - x0, y - y0)
        if (path.size == 1 && total >= movePx) {
            val single = when (path[0]) {
                "L" -> "left"
                "R" -> "right"
                "U" -> "up"
                "UL" -> "upLeft"
                "UR" -> "upRight"
                else -> null // D / DL / DR are deliberately unbound (misfire-prone).
            }
            if (single != null) {
                resetGesture()
                return GestureResult.Named(single, gen)
            }
        }
        resetGesture()
        return GestureResult.None
    }

    /** Abort the in-flight gesture without firing anything (cancel event, extra
     *  pointer, gesture-scope cancellation). The double-tap window survives. */
    fun cancel() {
        moved = false
        isDragging = false
        path.clear()
    }

    /** Claim the pending tap (the window timer expired). False if it was already
     *  superseded by a double-tap / a new gesture / another drag. */
    fun consumePendingTap(): Boolean {
        val pending = pendingTapGeneration
        if (pending == null) return false
        pendingTapGeneration = null
        return true
    }

    private fun resetGesture() {
        moved = false
        isDragging = false
        path.clear()
    }

    /** Segment direction from the displacement since the last anchor. Diagonals
     *  need both axes past the threshold (no dominance ratio); orthogonal legs need
     *  a strict 1.6x dominance; below threshold returns null to keep accumulating. */
    internal fun classifySegment(dx: Float, dy: Float): String? {
        val adx = kotlin.math.abs(dx)
        val ady = kotlin.math.abs(dy)
        if (adx >= swipePx && ady >= swipePx) {
            return when {
                dy < 0 && dx < 0 -> "UL"
                dy < 0 -> "UR"
                dx < 0 -> "DL"
                else -> "DR"
            }
        }
        if (adx >= swipePx && adx > ady * 1.6f) return if (dx < 0) "L" else "R"
        if (ady >= swipePx && ady > adx * 1.6f) return if (dy < 0) "U" else "D"
        return null
    }
}

/** Opposite-direction map for fold detection. */
private val OPP = mapOf(
    "L" to "R", "R" to "L", "U" to "D", "D" to "U",
    "UL" to "DR", "DR" to "UL", "UR" to "DL", "DL" to "UR",
)

internal fun isOpposite(a: String, b: String): Boolean = OPP[a] == b

/** Two-segment fold pairs → named gestures (the 9 compound bindings). */
internal fun foldGesture(a: String, b: String): String? = when {
    a == "L" && b == "R" -> "leftThenRight"
    a == "R" && b == "L" -> "rightThenLeft"
    a == "U" && b == "D" -> "upDown"
    a == "U" && b == "L" -> "upThenLeft"
    a == "U" && b == "R" -> "upThenRight"
    a == "UL" && b == "R" -> "upLeftThenRight"
    a == "UR" && b == "L" -> "upRightThenLeft"
    a == "UR" && b == "D" -> "upRightThenDown"
    a == "UL" && b == "D" -> "upLeftThenDown"
    else -> null
}
