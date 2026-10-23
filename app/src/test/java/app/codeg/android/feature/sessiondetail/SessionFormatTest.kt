package app.codeg.android.feature.sessiondetail

import org.junit.Assert.assertEquals
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
        assertEquals("87.0M", formatTokens(87_000_000))
        assertEquals("999", formatTokens(999))
        assertEquals("0", formatTokens(0))
    }

    @Test
    fun percentKeepsOneDecimalAndClamps() {
        assertEquals("64.0%", formatPercent(64.0))
        assertEquals("97.0%", formatPercent(97.0))
        assertEquals("100.0%", formatPercent(120.0))
        assertEquals("0.0%", formatPercent(-1.0))
    }
}
