package app.codeg.android.feature.taskhub

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 批v180-E: floating-ball intents into the task board's local UI state. The
 * ball fires from MainShell, which cannot reach TaskHubScreen's rememberSaveable
 * state, so the actions raise one-shot flags here that the board consumes via
 * continuous collects — same cross-screen pattern as FloatingBallSettingsBus.
 * Pure UI intents: no device-scope mirroring, nothing persisted (the
 * requestAutomationTab deep link in ServerRepository stays the automation
 * FAILURE row's path).
 */
object TaskHubIntents {
    val overview = MutableStateFlow(false)
    val automations = MutableStateFlow(false)

    fun openOverview() {
        overview.value = true
    }

    fun openAutomations() {
        automations.value = true
    }
}
