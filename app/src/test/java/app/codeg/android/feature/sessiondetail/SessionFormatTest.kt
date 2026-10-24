package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.SessionConfigKind
import app.codeg.android.core.model.SessionConfigOption
import app.codeg.android.core.model.SessionConfigSelectGroup
import app.codeg.android.core.model.SessionConfigSelectOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionFormatTest {

    @Test
    fun elapsedSplitsHoursMinutesSeconds() {
        val parts = elapsedParts((15 * 60 + 36) * 1000L)
        assertEquals(0L, parts.hours)
        assertEquals(15L, parts.minutes)
        assertEquals(36L, parts.seconds)
    }

    @Test
    fun elapsedKeepsSubHourRemainderSeparate() {
        val parts = elapsedParts(2 * 3600_000L + 5 * 60_000L + 9_000L)
        assertEquals(2L, parts.hours)
        assertEquals(5L, parts.minutes)
        assertEquals(9L, parts.seconds)
    }

    @Test
    fun elapsedClampsNegativeSpansToZero() {
        val parts = elapsedParts(-5_000L)
        assertEquals(0L, parts.hours)
        assertEquals(0L, parts.minutes)
        assertEquals(0L, parts.seconds)
    }

    @Test
    fun tokensUseCompactUnits() {
        assertEquals("670.6K", formatTokens(670_600))
        assertEquals("2.7M", formatTokens(2_700_000))
        assertEquals("87M", formatTokens(87_000_000))
        assertEquals("999", formatTokens(999))
        assertEquals("0", formatTokens(0))
    }

    @Test
    fun tokensDropTheUselessTrailingZero() {
        assertEquals("1M", formatTokens(1_000_000))
        assertEquals("87M", formatTokens(87_000_000))
        assertEquals("2K", formatTokens(2_000))
        // Real fractions keep their decimal.
        assertEquals("1.5M", formatTokens(1_500_000))
        assertEquals("670.6K", formatTokens(670_600))
    }

    @Test
    fun percentKeepsOneDecimalAndClamps() {
        assertEquals("64.0%", formatPercent(64.0))
        assertEquals("97.0%", formatPercent(97.0))
        assertEquals("100.0%", formatPercent(120.0))
        assertEquals("0.0%", formatPercent(-1.0))
    }

    // ===== completedAtLabel / turnDurationLabel =====

    @Test
    fun completedAtUses24hClockInTheGivenZone() {
        val at = java.time.Instant.parse("2026-10-24T06:46:00Z")
        assertEquals("14:46", completedAtLabel(at, java.time.ZoneId.of("Asia/Shanghai")))
        assertEquals("06:46", completedAtLabel(at, java.time.ZoneId.of("UTC")))
    }

    @Test
    fun durationUnderAMinuteShowsSecondsOnly() {
        assertEquals("0s", turnDurationLabel(0))
        assertEquals("42s", turnDurationLabel(42_000))
        assertEquals("59s", turnDurationLabel(59_900))
    }

    @Test
    fun durationUnderAnHourShowsMinutesAndSeconds() {
        assertEquals("1m00s", turnDurationLabel(60_000))
        assertEquals("2m13s", turnDurationLabel(133_000))
        assertEquals("15m36s", turnDurationLabel((15 * 60 + 36) * 1000L))
    }

    @Test
    fun durationBeyondAnHourDropsSeconds() {
        assertEquals("1h04m", turnDurationLabel(64 * 60_000L))
        assertEquals("2h00m", turnDurationLabel(2 * 3600_000L))
        assertEquals("1h00m", turnDurationLabel(3600_000L + 59_000L))
    }

    // ===== currentModelLabel =====

    private fun modelOption(
        currentValue: String? = "glm-5.3",
        id: String = "model",
        category: String? = "model",
        type: String = "select",
        options: List<SessionConfigSelectOption> = listOf(
            SessionConfigSelectOption(value = "glm-5.3", name = "GLM-5.3"),
            SessionConfigSelectOption(value = "glm-5-flash", name = "GLM-5 Flash"),
        ),
        groups: List<SessionConfigSelectGroup> = emptyList(),
    ) = SessionConfigOption(
        id = id,
        name = "Model",
        category = category,
        kind = SessionConfigKind(type = type, currentValue = currentValue, options = options, groups = groups),
    )

    @Test
    fun modelLabelResolvesTheDisplayNameFromTheOptionList() {
        assertEquals("GLM-5.3", currentModelLabel(listOf(modelOption())))
    }

    @Test
    fun modelLabelMatchesByIdWhenCategoryIsAbsent() {
        val byId = modelOption(category = null)
        assertEquals("GLM-5.3", currentModelLabel(listOf(byId)))
        val byCategory = modelOption(id = "reasoning_effort", category = "model")
        assertEquals("GLM-5.3", currentModelLabel(listOf(byCategory)))
    }

    @Test
    fun modelLabelResolvesThroughGroups() {
        val grouped = modelOption(
            options = emptyList(),
            groups = listOf(
                SessionConfigSelectGroup(
                    group = "zai",
                    name = "Z.ai",
                    options = listOf(SessionConfigSelectOption(value = "glm-5.3", name = "GLM-5.3")),
                ),
            ),
        )
        assertEquals("GLM-5.3", currentModelLabel(listOf(grouped)))
    }

    @Test
    fun modelLabelFallsBackToTheRawValueWhenNoEntryMatches() {
        assertEquals("raw-model-id", currentModelLabel(listOf(modelOption(currentValue = "raw-model-id"))))
    }

    @Test
    fun modelLabelIsNullWithoutAModelSelector() {
        val effort = SessionConfigOption(
            id = "reasoning_effort",
            kind = SessionConfigKind(currentValue = "high", options = listOf(SessionConfigSelectOption(value = "high", name = "High"))),
        )
        assertNull(currentModelLabel(listOf(effort)))
        assertNull(currentModelLabel(emptyList()))
    }

    @Test
    fun modelLabelIsNullForNonSelectOrEmptyValue() {
        assertNull(currentModelLabel(listOf(modelOption(type = "boolean"))))
        assertNull(currentModelLabel(listOf(modelOption(currentValue = null))))
        assertNull(currentModelLabel(listOf(modelOption(currentValue = "  "))))
    }
}
