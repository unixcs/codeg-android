package app.codeg.android.feature.floatingball

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.designsystem.theme.CodegTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Margin the ball keeps from the screen edges (drag clamps inside it). */
private val DOCK_MARGIN = 12.dp
private val PANEL_WIDTH = 230.dp
/** A "no timeout" stand-in for withTimeoutOrNull — one hour is forever here. */
private const val INDEFINITE_MS = 3_600_000L

/**
 * The in-app floating ball overlay (batch v126). Rides above the whole MainShell
 * (bottom bar and full-screen routes included) as a sibling of the Scaffold:
 * touches on the ball never leak into the tab-swipe / nested-scroll layers below.
 *
 * Performance contract (the "silky" requirement): gesture classification is a
 * synchronous pure calculation inside the pointer callbacks; the ball's position
 * is a state read ONLY inside the offset lambda (placement-phase — a drag frame
 * re-places the ball without recomposing it); press feedback goes through a
 * graphicsLayer lambda; DataStore is written once per drag release.
 *
 * @param bindings gesture key → action id (null = unbound), already sanitized.
 * @param posRatio persisted position as screen fractions; null until loaded.
 * @param onAction called with the bound action id ("panel" is handled internally).
 */
@Composable
fun FloatingBallHost(
    enabled: Boolean,
    sizeDp: Dp,
    bindings: Map<String, String?>,
    posRatio: Pair<Float, Float>?,
    onMoved: (fx: Float, fy: Float) -> Unit,
    onAction: (String) -> Unit,
) {
    if (!enabled) return
    val density = LocalDensity.current
    val colors = CodegTheme.colors
    val classifier = remember { GestureClassifier() }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var pos by remember { mutableStateOf<IntOffset?>(null) }
    var pressed by remember { mutableStateOf(false) }
    var dragArmed by remember { mutableStateOf(false) }
    var pendingTapGen by remember { mutableStateOf<Int?>(null) }
    var showPanel by remember { mutableStateOf(false) }

    // Restore the persisted position once the container size is known. The ratio
    // form survives resolution / device changes; clamping keeps it on screen even
    // if the persisted pair came from a different aspect ratio.
    LaunchedEffect(posRatio, containerSize) {
        if (pos == null && containerSize != IntSize.Zero && posRatio != null) {
            val (fx, fy) = posRatio
            pos = IntOffset(
                (fx * containerSize.width).roundToInt().coerceIn(0, (containerSize.width - 1).coerceAtLeast(0)),
                (fy * containerSize.height).roundToInt().coerceIn(0, (containerSize.height - 1).coerceAtLeast(0)),
            )
        }
    }

    // Single-tap confirmation timer: fires only if no second tap / gesture /
    // drag superseded the candidate (generation guard, see classifier kdoc).
    LaunchedEffect(pendingTapGen) {
        val gen = pendingTapGen ?: return@LaunchedEffect
        delay(classifier.doubleTapMs)
        if (classifier.pendingTapGeneration == gen && classifier.consumePendingTap()) {
            pendingTapGen = null
            fireGesture(bindings, "tap", onAction) { showPanel = !showPanel }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it },
    ) {
        val ballPx = with(density) { sizeDp.roundToPx() }
        val marginPx = with(density) { DOCK_MARGIN.roundToPx() }
        val wPx = with(density) { maxWidth.roundToPx() }
        val hPx = with(density) { maxHeight.roundToPx() }
        val defaultPos = IntOffset(wPx - ballPx - marginPx, (hPx * 0.82f).roundToInt())

        fun clamp(offset: IntOffset): IntOffset = IntOffset(
            offset.x.coerceIn(0, (wPx - ballPx).coerceAtLeast(0)),
            offset.y.coerceIn(0, (hPx - ballPx).coerceAtLeast(0)),
        )

        Box(
            Modifier
                // Placement-phase state read: a drag frame re-places without recompose.
                .offset { pos ?: defaultPos }
                // The ball often docks on a screen edge; without this the system
                // back-gesture band steals horizontal swipes started on the ball.
                .systemGestureExclusion()
                .graphicsLayer {
                    // dragArmed = held ~600ms: the ball swells to say "drag me now".
                    val scale = when {
                        dragArmed -> 1.16f
                        pressed -> 1.08f
                        else -> 1f
                    }
                    shadowElevation = when {
                        dragArmed -> 16f
                        pressed -> 14f
                        else -> 7f
                    }
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(colors.accent, colors.accentDim)), CircleShape)
                .border(1.5.dp, colors.onAccent.copy(alpha = 0.35f), CircleShape)
                .pointerInputBall(
                    classifier = classifier,
                    getPos = { pos ?: defaultPos },
                    setPos = { pos = clamp(it) },
                    setPressed = { pressed = it },
                    setArmed = { dragArmed = it },
                    onPendingTap = { pendingTapGen = it },
                    clearPendingTap = { pendingTapGen = null },
                    onDragEnd = { final ->
                        pos = final
                        if (wPx > 0 && hPx > 0) {
                            onMoved(final.x.toFloat() / wPx, final.y.toFloat() / hPx)
                        }
                    },
                    onGesture = { gesture -> fireGesture(bindings, gesture, onAction) { showPanel = !showPanel } },
                    constrain = ::clamp,
                )
                .size(sizeDp),
            contentAlignment = Alignment.Center,
        ) {
            Text("◆", color = colors.onAccent, fontSize = (sizeDp.value * 0.4f).sp)
        }

        if (showPanel) {
            // Scrim eats the outside tap and closes; the panel itself is offset
            // near the ball like the web version's anchored panel.
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(onClick = { showPanel = false }),
            )
            val panelPx = with(density) { PANEL_WIDTH.roundToPx() }
            val liftPx = with(density) { 90.dp.roundToPx() }
            val ballPos = pos ?: defaultPos
            val panelX = (ballPos.x - panelPx + ballPx).coerceAtLeast(marginPx)
            val panelY = (ballPos.y - liftPx).coerceAtLeast(marginPx)
            Surface(
                modifier = Modifier
                    .offset { IntOffset(panelX, panelY) }
                    .width(PANEL_WIDTH)
                    .heightIn(max = 420.dp),
                shape = RoundedCornerShape(14.dp),
                color = colors.bgElevated.copy(alpha = 0.97f),
                border = BorderStroke(1.dp, colors.surfaceStroke),
                shadowElevation = 10.dp,
            ) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(R.string.ball_panel_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.accent,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                    FloatingBallCatalog.ACTIONS.forEach { (id, labelRes) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (id != "panel") {
                                        showPanel = false
                                        onAction(id)
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                        ) {
                            Text(stringResource(labelRes), color = colors.textPrimary, fontSize = 14.sp)
                        }
                        HorizontalDivider(color = colors.hairline)
                    }
                    Text(
                        stringResource(R.string.common_close),
                        color = colors.textTertiary,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showPanel = false }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                    )
                }
            }
        }
    }
}

/** gesture → bound action; "panel" toggles the in-host panel. */
private fun fireGesture(
    bindings: Map<String, String?>,
    gesture: String,
    onAction: (String) -> Unit,
    openPanel: () -> Unit,
) {
    when (val action = bindings[gesture]) {
        null, "" -> Unit
        "panel" -> openPanel()
        else -> onAction(action)
    }
}

private fun Offset.toIntOffset() = IntOffset(x.roundToInt(), y.roundToInt())

/**
 * The ball's raw pointer handler. Hand-rolled instead of detectDragGestures
 * because the fast-swipe vs hold-drag fork needs the down timestamp, and the
 * classifier wants every intermediate sample.
 *
 * Frame discipline (the v126.1 follow-the-finger fix): every sample is
 * translated into the CONTAINER frame using the ball's position FROZEN at
 * gesture start. `main.position` is local to the ball node, which itself moves
 * while dragging — computing the drag delta from local positions feeds the
 * ball's own motion back into the delta (the ball chases its own tail, halving
 * and oscillating the follow). The frozen frame breaks that loop.
 *
 * Drag model (owner-specified): hold ~600ms → the drag arms (ball swells;
 * classifier.armed) → ANY movement then drags, release parks the ball where
 * the finger left it (clamped to the screen minus margin — no edge snapping).
 * The pre-existing slow-move path (stillness past the fast-swipe window, then
 * a deliberate move) also enters drag. Hold-600ms-and-release-without-moving
 * still fires long-press (ball settings).
 */
private fun Modifier.pointerInputBall(
    classifier: GestureClassifier,
    getPos: () -> IntOffset,
    setPos: (IntOffset) -> Unit,
    setPressed: (Boolean) -> Unit,
    setArmed: (Boolean) -> Unit,
    onPendingTap: (Int?) -> Unit,
    clearPendingTap: () -> Unit,
    onDragEnd: (IntOffset) -> Unit,
    onGesture: (String) -> Unit,
    constrain: (IntOffset) -> IntOffset,
): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val origin = getPos()
            fun Offset.toContainer() = Offset(x + origin.x, y + origin.y)
            var dragBase: IntOffset? = null
            var grabContainer = Offset.Zero
            var lastT = down.uptimeMillis
            classifier.onDown(down.uptimeMillis, down.position.x + origin.x, down.position.y + origin.y)
            setPressed(true)
            setArmed(false)
            try {
                while (true) {
                    // While nothing is armed/dragging/classified, cap the wait at
                    // the hold time REMAINING since the down (not since the last
                    // event — a wiggling finger must not keep pushing the deadline
                    // out). Expiry arms hold-to-drag.
                    val timeoutMs =
                        if (classifier.armed || classifier.isDragging || classifier.gestureStarted) INDEFINITE_MS
                        else (down.uptimeMillis + classifier.longPressMs - lastT).coerceAtLeast(1L)
                    val event = withTimeoutOrNull(timeoutMs) { awaitPointerEvent() }
                    if (event == null) {
                        classifier.arm()
                        if (classifier.armed) setArmed(true)
                        continue
                    }
                    // A second finger while the primary is down is ambiguous — abort
                    // the whole gesture without firing (stricter than the web's ignore).
                    if (event.changes.any { it.id != down.id && it.pressed }) {
                        classifier.cancel()
                        break
                    }
                    val main = event.changes.firstOrNull { it.id == down.id } ?: break
                    lastT = main.uptimeMillis
                    if (!main.pressed) {
                        // Compose has no Cancel event type: a system-cancelled
                        // gesture arrives as all-pointers-up with the change already
                        // consumed (how detectTapGestures spots it). A consumed
                        // release aborts without firing; a clean one finishes.
                        if (main.isConsumed) {
                            classifier.cancel()
                        } else {
                            val c = main.position.toContainer()
                            when (val res = classifier.onUp(main.uptimeMillis, c.x, c.y)) {
                                is GestureResult.TapCandidate -> onPendingTap(res.generation)
                                is GestureResult.DoubleTap -> {
                                    clearPendingTap()
                                    onGesture("doubleTap")
                                }
                                is GestureResult.LongPress -> onGesture("longPress")
                                is GestureResult.Named -> onGesture(res.gesture)
                                is GestureResult.DragEnd -> {
                                    // Park where the finger released — constrained to
                                    // the screen (no snap-to-edge; v126.1).
                                    val final = dragBase?.let { constrain(it + (c - grabContainer).toIntOffset()) }
                                        ?: getPos()
                                    onDragEnd(final)
                                }
                                else -> Unit
                            }
                        }
                        break
                    }
                    if (main.positionChanged()) {
                        val c = main.position.toContainer()
                        when (classifier.onMove(main.uptimeMillis, c.x, c.y)) {
                            GestureResult.EnterDrag -> {
                                dragBase = getPos()
                                grabContainer = c
                                setArmed(false) // armed → actually dragging now
                            }
                            GestureResult.Dragging -> {
                                val base = dragBase
                                if (base == null) {
                                    dragBase = getPos()
                                    grabContainer = c
                                } else {
                                    setPos(base + (c - grabContainer).toIntOffset())
                                }
                            }
                            else -> Unit
                        }
                        // A move that turned this touch into a gesture/drag kills the
                        // pending single tap — mirror that into the timer state.
                        if (classifier.pendingTapGeneration == null) clearPendingTap()
                    }
                }
            } finally {
                setPressed(false)
                setArmed(false)
                classifier.cancel()
            }
        }
    },
)
