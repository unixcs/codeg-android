package app.codeg.android.feature.floatingball

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the floating-ball gesture classifier (batch v126). Every
 * threshold and branch of the state machine is pinned here — the classifier is
 * the only place gesture semantics live, and the web port must not drift.
 *
 * Timeline convention: t0=1000 keeps every timestamp positive so the
 * double-tap window arithmetic can't lean on a zero-initialized clock.
 */
class GestureClassifierTest {

    private fun classifier() = GestureClassifier()

    private fun tap(c: GestureClassifier, t: Long, x: Float = 100f, y: Float = 100f): GestureResult {
        c.onDown(t, x, y)
        return c.onUp(t + 80, x, y)
    }

    // ---- taps ----

    @Test
    fun `first ever tap is a candidate not a double`() {
        // lastTapUpAt initializes to 0; a naive `t - last < window` alone could
        // only misfire within 300ms of clock zero, but the pending-tap guard
        // below is what makes this branch airtight regardless of the clock.
        val c = classifier()
        val r = tap(c, 1000)
        assertTrue(r is GestureResult.TapCandidate)
        assertEquals(1, (r as GestureResult.TapCandidate).generation)
    }

    @Test
    fun `second tap inside the window is a double tap`() {
        val c = classifier()
        tap(c, 1000)
        val r = tap(c, 1200)
        assertTrue(r is GestureResult.DoubleTap)
        assertNull(c.pendingTapGeneration)
    }

    @Test
    fun `second tap outside the window is a fresh candidate`() {
        val c = classifier()
        tap(c, 1000)
        val r = tap(c, 1400) // 400ms later > 300ms window
        assertTrue(r is GestureResult.TapCandidate)
    }

    @Test
    fun `an intervening gesture kills the double-tap chain`() {
        val c = classifier()
        tap(c, 1000) // up at 1080, lastTapUpAt = 1080
        // Fast swipe (starts moving < 250ms after down) between the two taps.
        c.onDown(1100, 100f, 100f)
        c.onMove(1200, 40f, 100f)
        c.onUp(1250, 30f, 100f)
        // Tap again 270ms after the FIRST tap's up — inside the raw window, but
        // the swipe already cleared pendingTapGeneration, so the chain is dead:
        // this must be a fresh candidate, never a double tap.
        val r = tap(c, 1270)
        assertTrue("got $r", r is GestureResult.TapCandidate)
    }

    @Test
    fun `long press at threshold fires on release`() {
        val c = classifier()
        c.onDown(1000, 100f, 100f)
        val r = c.onUp(1600, 100f, 100f) // 600ms hold, no move
        assertTrue(r is GestureResult.LongPress)
    }

    @Test
    fun `long press wins over the double-tap window`() {
        val c = classifier()
        tap(c, 1000)
        c.onDown(1100, 100f, 100f)
        val r = c.onUp(1750, 100f, 100f) // held 650ms
        assertTrue(r is GestureResult.LongPress)
    }

    @Test
    fun `consumePendingTap fires once`() {
        val c = classifier()
        tap(c, 1000)
        assertTrue(c.consumePendingTap())
        assertFalse(c.consumePendingTap())
    }

    @Test
    fun `pending tap survives the next down but dies on a fast move`() {
        val c = classifier()
        tap(c, 1000)
        c.onDown(1050, 100f, 100f)
        assertEquals(1, c.pendingTapGeneration) // second tap may still become a double
        c.onMove(1100, 60f, 100f) // moved 40px within 50ms → fast start
        assertNull(c.pendingTapGeneration)
    }

    // ---- fast swipes (single segment) ----

    @Test
    fun `fast swipe left names left`() {
        val c = classifier()
        c.onDown(1000, 300f, 100f)
        c.onMove(1100, 250f, 100f) // cross movePx within tapMs → gesture mode
        c.onMove(1150, 150f, 100f)
        val r = c.onUp(1200, 120f, 100f)
        assertTrue("got $r", r is GestureResult.Named && r.gesture == "left")
    }

    @Test
    fun `fast swipe straight up names up`() {
        val c = classifier()
        c.onDown(1000, 100f, 300f)
        c.onMove(1100, 100f, 240f)
        val r = c.onUp(1150, 100f, 120f)
        assertTrue("got $r", r is GestureResult.Named && r.gesture == "up")
    }

    @Test
    fun `fast diagonal up-right names upRight`() {
        val c = classifier()
        c.onDown(1000, 100f, 300f)
        c.onMove(1100, 130f, 270f)
        val r = c.onUp(1150, 160f, 240f)
        assertTrue("got $r", r is GestureResult.Named && r.gesture == "upRight")
    }

    @Test
    fun `plain down swipe is deliberately unbound`() {
        val c = classifier()
        c.onDown(1000, 100f, 100f)
        c.onMove(1100, 100f, 150f)
        val r = c.onUp(1150, 100f, 250f)
        assertTrue(r is GestureResult.None)
    }

    // ---- folds (two-segment compounds) ----

    @Test
    fun `up then down folds to upDown even back at origin`() {
        // The web original gated folding behind net displacement ≥ MOVE_PX, which
        // silently killed every fold that returns to its start (net ≈ 0). The
        // classifier folds BEFORE the gate — pinned here so the fix can't regress.
        val c = classifier()
        c.onDown(1000, 200f, 300f)
        c.onMove(1100, 200f, 230f) // U
        c.onMove(1200, 200f, 160f)
        c.onMove(1300, 200f, 220f) // D on the way back down
        c.onMove(1400, 200f, 295f)
        val r = c.onUp(1450, 200f, 298f) // ~back at the origin
        assertTrue("got $r", r is GestureResult.Named && r.gesture == "upDown")
    }

    @Test
    fun `up then left folds to upThenLeft`() {
        val c = classifier()
        c.onDown(1000, 200f, 300f)
        c.onMove(1100, 200f, 220f) // U
        c.onMove(1200, 160f, 200f) // drifting left
        val r = c.onUp(1250, 120f, 190f)
        assertTrue("got $r", r is GestureResult.Named && r.gesture == "upThenLeft")
    }

    @Test
    fun `up-right then down folds to upRightThenDown`() {
        val c = classifier()
        c.onDown(1000, 200f, 300f)
        c.onMove(1100, 240f, 260f) // UR (both axes 40px)
        c.onMove(1200, 245f, 310f) // second leg near-vertical (x drift < 30px → D)
        val r = c.onUp(1250, 250f, 330f)
        assertTrue("got $r", r is GestureResult.Named && r.gesture == "upRightThenDown")
    }

    @Test
    fun `left then right folds to leftThenRight`() {
        val c = classifier()
        c.onDown(1000, 200f, 200f)
        c.onMove(1100, 150f, 200f) // L
        c.onMove(1200, 260f, 200f) // R
        val r = c.onUp(1250, 300f, 200f)
        assertTrue("got $r", r is GestureResult.Named && r.gesture == "leftThenRight")
    }

    @Test
    fun `startup jitter replaces the segment instead of stacking`() {
        // A swipe meant as up-left that first registers U, then drifts into UL,
        // must end as ONE UL segment — not the pair U,UL (which folds to
        // upThenLeft, a different gesture than the user drew).
        val c = classifier()
        c.onDown(1000, 200f, 300f)
        c.onMove(1100, 200f, 235f) // U (65px up)
        c.onMove(1150, 165f, 200f) // cumulative from the U anchor: 35px left, 35px up → UL
        val r = c.onUp(1200, 150f, 190f)
        assertTrue("got $r", r is GestureResult.Named && r.gesture == "upLeft")
    }

    // ---- drag fork ----

    @Test
    fun `slow move enters drag mode and ends with DragEnd`() {
        val c = classifier()
        c.onDown(1000, 100f, 100f)
        assertTrue(c.onMove(1400, 105f, 100f) is GestureResult.None) // still < movePx
        val enter = c.onMove(1500, 120f, 100f) // moved 20px but 500ms after down
        assertTrue(enter is GestureResult.EnterDrag)
        assertTrue(c.isDragging)
        assertTrue(c.onMove(1600, 60f, 60f) is GestureResult.Dragging)
        assertTrue(c.onUp(1700, 50f, 50f) is GestureResult.DragEnd)
        assertFalse(c.isDragging)
    }

    @Test
    fun `drag does not classify a gesture on release`() {
        val c = classifier()
        c.onDown(1000, 100f, 100f)
        c.onMove(1500, 120f, 100f) // EnterDrag
        c.onMove(1600, 220f, 100f) // dragged 100px right
        val r = c.onUp(1700, 300f, 100f)
        assertTrue(r is GestureResult.DragEnd)
    }

    // ---- cancellation ----

    @Test
    fun `cancel resets the touch without firing`() {
        val c = classifier()
        c.onDown(1000, 100f, 100f)
        c.onMove(1100, 50f, 100f) // gesture in progress
        c.cancel() // e.g. a second finger landed
        // The composable breaks out without calling onUp; state must be clean for
        // the next touch.
        val r = tap(c, 2000)
        assertTrue(r is GestureResult.TapCandidate)
        assertEquals(2, (r as GestureResult.TapCandidate).generation)
    }

    @Test
    fun `cancel during drag leaves no dragging state behind`() {
        val c = classifier()
        c.onDown(1000, 100f, 100f)
        c.onMove(1500, 120f, 100f)
        assertTrue(c.isDragging)
        c.cancel()
        assertFalse(c.isDragging)
    }

    // ---- segment classification thresholds ----

    @Test
    fun `orthogonal segments need a strict one-point-six dominance`() {
        val c = classifier()
        // 30 vs 18.7: 30 > 29.92 → R.
        assertEquals("R", c.classifySegment(30f, -18.7f))
        // 30 vs 19: needs 30 > 30.4 → below the strict bar → still accumulating.
        assertNull(c.classifySegment(30f, -19f))
        // 20 vs 45: both below 30 → no diagonal; 45 > 32 → U.
        assertEquals("U", c.classifySegment(20f, -45f))
    }

    @Test
    fun `diagonals take both axes past the threshold with no dominance ratio`() {
        val c = classifier()
        assertEquals("UR", c.classifySegment(35f, -100f))
        assertEquals("DL", c.classifySegment(-40f, 33f))
        assertNull(c.classifySegment(25f, 25f)) // neither axis at 30 yet
    }

    @Test
    fun `small displacements accumulate as null`() {
        val c = classifier()
        assertNull(c.classifySegment(5f, 5f))
        assertNull(c.classifySegment(0f, 20f))
    }
}
