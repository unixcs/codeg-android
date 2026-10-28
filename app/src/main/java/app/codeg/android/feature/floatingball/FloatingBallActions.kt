package app.codeg.android.feature.floatingball

import androidx.annotation.StringRes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.codeg.android.R

/**
 * Static catalog shared by the ball (fire), the settings screen (bind) and the
 * tests (validate). Execution lives in MainShell's registry; this file only
 * defines ids, labels and the default binding table — no lambdas, fully testable.
 */
object FloatingBallCatalog {
    /** All 16 bindable gestures: 2 tap-class + 14 directions. The old
     *  "longPress" action was removed in v129 — a 1s hold now moves the ball
     *  (and firing nothing on a no-move release), so it is no longer bindable. */
    val GESTURES = listOf(
        "tap", "doubleTap",
        "left", "right", "up", "upLeft", "upRight",
        "upDown", "upThenLeft", "upThenRight",
        "upLeftThenRight", "upRightThenLeft", "upRightThenDown", "upLeftThenDown",
        "rightThenLeft", "leftThenRight",
    )

    val ACTIONS: List<Pair<String, Int>> = listOf(
        "panel" to R.string.ball_action_panel,
        "newSession" to R.string.ball_action_new_session,
        "tabChats" to R.string.ball_action_tab_chats,
        "tabFolders" to R.string.ball_action_tab_folders,
        "tabActivity" to R.string.ball_action_tab_activity,
        "tabSearch" to R.string.ball_action_tab_search,
        "tabSettings" to R.string.ball_action_tab_settings,
        "toggleThemeMode" to R.string.ball_action_toggle_theme,
        "nextAccent" to R.string.ball_action_next_accent,
        "fontScaleUp" to R.string.ball_action_font_up,
        "fontScaleDown" to R.string.ball_action_font_down,
        "fontScaleReset" to R.string.ball_action_font_reset,
        "recentSession1" to R.string.ball_action_recent_1,
        "recentSession2" to R.string.ball_action_recent_2,
        "recentSession3" to R.string.ball_action_recent_3,
        "switchServerNext" to R.string.ball_action_server_next,
        "switchServerPrev" to R.string.ball_action_server_prev,
        "cycleProject" to R.string.ball_action_cycle_project,
        "cycleSession" to R.string.ball_action_cycle_session,
        "openHighlighted" to R.string.ball_action_open_highlighted,
        "ballSettings" to R.string.ball_action_ball_settings,
    )

    val ACTION_IDS = ACTIONS.map { it.first }.toSet()

    /**
     * Defaults keep the web version's muscle memory (tap=panel, left=new session)
     * mapped onto app-native actions. The 1s hold is the fixed drag-to-move entry
     * (v129) and fires no action, so settings stay reachable via the tap panel.
     * Exactly one gesture is deliberately unbound (upRightThenLeft) as a misfire
     * guard.
     */
    val DEFAULT_BINDINGS: Map<String, String?> = mapOf(
        "tap" to "panel",
        "doubleTap" to "tabActivity",
        "left" to "newSession",
        "right" to "tabFolders",
        "up" to "panel",
        "upLeft" to "tabSearch",
        "upRight" to "switchServerNext",
        "upDown" to "fontScaleUp",
        "upThenLeft" to "switchServerPrev",
        "upThenRight" to "recentSession1",
        "upLeftThenRight" to "recentSession3",
        "upRightThenLeft" to null,
        "upRightThenDown" to "recentSession2",
        "upLeftThenDown" to "fontScaleDown",
        "rightThenLeft" to "fontScaleReset",
        "leftThenRight" to "nextAccent",
    )

    /** Web loadBindings semantics: defaults as the base, known gesture keys
     *  override (null = explicit unbind, valid), unknown gesture keys dropped,
     *  unknown action ids fall back to the default for that gesture. */
    fun sanitize(saved: Map<String, String?>): Map<String, String?> {
        val out = DEFAULT_BINDINGS.toMutableMap()
        for ((gesture, action) in saved) {
            if (gesture !in GESTURES) continue
            out[gesture] = if (action == null || action in ACTION_IDS) action else DEFAULT_BINDINGS[gesture]
        }
        return out
    }

    @StringRes
    fun gestureLabel(gesture: String): Int = when (gesture) {
        "tap" -> R.string.ball_gesture_tap
        "doubleTap" -> R.string.ball_gesture_double_tap
        "left" -> R.string.ball_gesture_left
        "right" -> R.string.ball_gesture_right
        "up" -> R.string.ball_gesture_up
        "upLeft" -> R.string.ball_gesture_up_left
        "upRight" -> R.string.ball_gesture_up_right
        "upDown" -> R.string.ball_gesture_up_down
        "upThenLeft" -> R.string.ball_gesture_up_then_left
        "upThenRight" -> R.string.ball_gesture_up_then_right
        "upLeftThenRight" -> R.string.ball_gesture_up_left_then_right
        "upRightThenLeft" -> R.string.ball_gesture_up_right_then_left
        "upRightThenDown" -> R.string.ball_gesture_up_right_then_down
        "upLeftThenDown" -> R.string.ball_gesture_up_left_then_down
        "rightThenLeft" -> R.string.ball_gesture_right_then_left
        "leftThenRight" -> R.string.ball_gesture_left_then_right
        else -> R.string.ball_action_none
    }
}

/** Ball size setting → physical diameter. */
fun floatBallSizeDp(sizeId: String): Dp = when (sizeId) {
    "small" -> 40.dp
    "large" -> 58.dp
    else -> 48.dp
}

/** App font-scale ladder (batch v126): four fixed steps, snap on change. */
val FONT_STEPS = floatArrayOf(0.85f, 1.0f, 1.15f, 1.3f)

/** Next ladder value from [current] moving [delta] steps (clamped at the ends). */
fun stepFontScale(current: Float, delta: Int): Float {
    var idx = FONT_STEPS.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
    if (idx < 0) idx = FONT_STEPS.indexOfFirst { kotlin.math.abs(it - 1.0f) < 0.01f }
    val next = (idx + delta).coerceIn(FONT_STEPS.indices)
    return FONT_STEPS[next]
}

/**
 * Cross-screen "open the floating-ball settings leaf" channel: the ball's action
 * fires from MainShell (which owns no Settings internals), SettingsScreen
 * collects [pending] and pushes the FLOATING_BALL leaf. Mirrors DeepLinkBus's
 * shape. The consumer resets the flag after navigating.
 */
object FloatingBallSettingsBus {
    val pending = kotlinx.coroutines.flow.MutableStateFlow(false)

    fun open() {
        pending.value = true
    }
}
