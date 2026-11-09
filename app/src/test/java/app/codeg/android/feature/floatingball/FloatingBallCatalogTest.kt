package app.codeg.android.feature.floatingball

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catalog invariants for the floating ball (batch v126; v129 dropped the
 * longPress gesture — the 1s hold now moves the ball): the 24-gesture /
 * 31-action tables (v150 added the four quick-center actions; v151 the five
 * quick-shell ones; v160 the ↓/↙/↘ family and cycleSessionCustom) and the
 * binding sanitize semantics (web loadBindings port), plus the font ladder the
 * ball's font actions step through.
 */
class FloatingBallCatalogTest {

    @Test
    fun `twenty-four unique gestures`() {
        assertEquals(24, FloatingBallCatalog.GESTURES.size)
        assertEquals(FloatingBallCatalog.GESTURES.size, FloatingBallCatalog.GESTURES.toSet().size)
    }

    @Test
    fun `batch v160 adds the down family unbound plus cycleSessionCustom`() {
        // The owner's high-frequency set: ↓ ↙ ↘ singles and ↓↑ ↙↑ ↙→ ↘↑ ↘←
        // folds. The classifier now NAMES them; the misfire guard (a scroll-like
        // slip near the screen edge) moved into DEFAULT_BINDINGS — all eight ship
        // null so nothing fires until the user binds one in settings.
        val added = setOf(
            "down", "downLeft", "downRight",
            "downThenUp", "downLeftThenUp", "downLeftThenRight", "downRightThenUp", "downRightThenLeft",
        )
        assertTrue(FloatingBallCatalog.GESTURES.toSet().containsAll(added))
        assertTrue("missing action cycleSessionCustom", "cycleSessionCustom" in FloatingBallCatalog.ACTION_IDS)
        for (gesture in added) {
            assertNull("gesture $gesture must ship unbound", FloatingBallCatalog.DEFAULT_BINDINGS[gesture])
        }
    }

    @Test
    fun `action ids are unique and labels exist`() {
        val ids = FloatingBallCatalog.ACTIONS.map { it.first }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.isNotEmpty())
        assertTrue(FloatingBallCatalog.ACTIONS.all { it.second != 0 })
    }

    @Test
    fun `batch v132 session-project actions are bindable`() {
        for (id in listOf("cycleProject", "cycleSession", "openHighlighted")) {
            assertTrue("missing action $id", id in FloatingBallCatalog.ACTION_IDS)
        }
    }

    @Test
    fun `batch v136 keeps the three recent-session action ids and their defaults`() {
        // The labels changed to "quick switch · depth N", but the ids are the saved
        // pref values — renaming one would silently reset every existing binding.
        for (id in listOf("recentSession1", "recentSession2", "recentSession3")) {
            assertTrue("missing action $id", id in FloatingBallCatalog.ACTION_IDS)
        }
        // v150 appended the four quick-center actions; v151 the five quick-shell
        // ones (tabInbox/tabTasks/quickNewSession/toggleAllDevices/refreshActivity);
        // v160 added cycleSessionCustom; v171 the two swipe-binding cycles
        // (cycleSwipeLeft/cycleSwipeRight); v172 quickSave. The count guard stays
        // so an accidental catalog change is caught in CI.
        assertEquals(34, FloatingBallCatalog.ACTIONS.size)
        assertEquals("recentSession1", FloatingBallCatalog.DEFAULT_BINDINGS["upThenRight"])
        assertEquals("recentSession2", FloatingBallCatalog.DEFAULT_BINDINGS["upRightThenDown"])
        assertEquals("recentSession3", FloatingBallCatalog.DEFAULT_BINDINGS["upLeftThenRight"])
    }

    @Test
    fun `batch v172 adds quickSave without touching defaults`() {
        assertTrue("missing action quickSave", "quickSave" in FloatingBallCatalog.ACTION_IDS)
        // v150 policy: catalog growth never rebinds an existing gesture.
        assertTrue(FloatingBallCatalog.DEFAULT_BINDINGS.values.none { it == "quickSave" })
    }

    @Test
    fun `default bindings cover exactly the gesture set`() {
        assertEquals(FloatingBallCatalog.GESTURES.toSet(), FloatingBallCatalog.DEFAULT_BINDINGS.keys)
    }

    @Test
    fun `batch v151 adds the five quick-shell actions without touching defaults`() {
        for (id in listOf("tabInbox", "tabTasks", "quickNewSession", "toggleAllDevices", "refreshActivity")) {
            assertTrue("missing action $id", id in FloatingBallCatalog.ACTION_IDS)
        }
        // v150 policy: catalog growth never rebinds an existing gesture.
        assertTrue(
            FloatingBallCatalog.DEFAULT_BINDINGS.values.none {
                it in setOf("tabInbox", "tabTasks", "quickNewSession", "toggleAllDevices", "refreshActivity")
            },
        )
    }

    @Test
    fun `default binding values are null or known actions`() {
        for ((gesture, action) in FloatingBallCatalog.DEFAULT_BINDINGS) {
            assertTrue(
                "gesture $gesture bound to unknown action $action",
                action == null || action in FloatingBallCatalog.ACTION_IDS,
            )
        }
    }

    @Test
    fun `unbound-by-default gestures are exactly the down family plus upRightThenLeft`() {
        val unbound = FloatingBallCatalog.DEFAULT_BINDINGS.filterValues { it == null }.keys
        assertEquals(
            setOf(
                "upRightThenLeft",
                "down", "downLeft", "downRight",
                "downThenUp", "downLeftThenUp", "downLeftThenRight", "downRightThenUp", "downRightThenLeft",
            ),
            unbound,
        )
    }

    @Test
    fun `sanitize with nothing saved returns the defaults`() {
        assertEquals(FloatingBallCatalog.DEFAULT_BINDINGS, FloatingBallCatalog.sanitize(emptyMap()))
    }

    @Test
    fun `sanitize keeps a known override`() {
        val out = FloatingBallCatalog.sanitize(mapOf("up" to "newSession"))
        assertEquals("newSession", out["up"])
    }

    @Test
    fun `sanitize keeps an explicit unbind as null`() {
        val out = FloatingBallCatalog.sanitize(mapOf("left" to null))
        assertTrue(out.containsKey("left"))
        assertEquals(null, out["left"])
    }

    @Test
    fun `sanitize drops unknown gestures`() {
        val out = FloatingBallCatalog.sanitize(mapOf("diagonalSpin" to "newSession"))
        assertEquals(FloatingBallCatalog.DEFAULT_BINDINGS, out)
    }

    @Test
    fun `sanitize falls an unknown action id back to that gesture default`() {
        val out = FloatingBallCatalog.sanitize(mapOf("up" to "selfDestruct"))
        assertEquals(FloatingBallCatalog.DEFAULT_BINDINGS["up"], out["up"])
    }

    @Test
    fun `sanitize always emits the full gesture-key table`() {
        val out = FloatingBallCatalog.sanitize(mapOf("up" to "tabChats"))
        assertEquals(FloatingBallCatalog.GESTURES.toSet(), out.keys)
    }

    // ---- size ladder ----

    @Test
    fun `ball size ladder is ordered and unknown ids fall to medium`() {
        val small = floatBallSizeDp("small").value
        val medium = floatBallSizeDp("medium").value
        val large = floatBallSizeDp("large").value
        assertTrue(small < medium && medium < large)
        assertEquals(medium, floatBallSizeDp("gigantic").value)
    }

    // ---- font ladder ----

    @Test
    fun `font ladder steps and clamps at both ends`() {
        val c = FloatingBallCatalog
        assertEquals(1.15f, stepFontScale(1.0f, +1))
        assertEquals(0.85f, stepFontScale(1.0f, -1))
        assertEquals(0.85f, stepFontScale(0.85f, -1)) // floor
        assertEquals(1.3f, stepFontScale(1.3f, +1)) // ceiling
        assertEquals(0.85f, stepFontScale(1.3f, -3)) // multi-step floor
    }

    @Test
    fun `an off-ladder current snaps from the one-point-zero rung`() {
        // A corrupt pref (say 0.93f) must still land on a real rung, not persist
        // halfway — and 1.0 is the anchor the ladder recovers to.
        assertEquals(1.15f, stepFontScale(0.93f, +1))
        assertEquals(0.85f, stepFontScale(0.93f, -1))
    }

    @Test
    fun `four ladder steps are strictly ascending`() {
        val steps = FONT_STEPS.toList()
        assertEquals(4, steps.size)
        for (i in 0 until steps.size - 1) {
            assertTrue(steps[i] < steps[i + 1])
        }
    }
}
