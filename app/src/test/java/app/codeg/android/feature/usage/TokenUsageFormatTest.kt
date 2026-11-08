package app.codeg.android.feature.usage

import app.codeg.android.core.model.TokenUsageHeatCell
import app.codeg.android.core.model.TokenUsageTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 批v169 Token 仪表盘纯函数面：格式化/命中率/夜猫子占比/环比/周期过滤器。
 * 不落 Android 类，JVM 直跑。
 */
class TokenUsageFormatTest {

    // ---------- formatTokenCount ----------

    @Test
    fun `formatTokenCount tiers`() {
        assertEquals("999", formatTokenCount(999))
        assertEquals("1.0K", formatTokenCount(1_000))
        assertEquals("61.0M", formatTokenCount(61_000_000))
        assertEquals("1.46B", formatTokenCount(1_460_000_000))
        assertEquals("13.80B", formatTokenCount(13_800_300_000))
        assertEquals("1.00T", formatTokenCount(1_000_000_000_000))
        assertEquals("9.22T", formatTokenCount(9_220_000_000_000))
    }

    @Test
    fun `formatTokenCount boundary prefers B over M`() {
        // 恰好 1e9 落 B 档，不再显示 1000.0M
        assertEquals("1.00B", formatTokenCount(1_000_000_000))
    }

    // ---------- cacheHitRate ----------

    @Test
    fun `cacheHitRate zero total is zero`() {
        assertEquals(0f, cacheHitRate(TokenUsageTotals()), 1e-6f)
    }

    @Test
    fun `cacheHitRate web口径 denominator is all four classes`() {
        val t = TokenUsageTotals(
            inputTokens = 10, outputTokens = 10,
            cacheCreationTokens = 10, cacheReadTokens = 70, totalTokens = 100,
        )
        assertEquals(0.7f, cacheHitRate(t), 1e-6f)
    }

    @Test
    fun `cacheHitRate clamps to one`() {
        val t = TokenUsageTotals(cacheReadTokens = 100, totalTokens = 100)
        assertEquals(1f, cacheHitRate(t), 1e-6f)
    }

    // ---------- nightOwlFraction ----------

    @Test
    fun `nightOwlFraction counts 22-05 window`() {
        val cells = buildList {
            add(TokenUsageHeatCell(weekday = 0, hour = 23, totalTokens = 45))
            add(TokenUsageHeatCell(weekday = 1, hour = 2, totalTokens = 5))
            add(TokenUsageHeatCell(weekday = 1, hour = 12, totalTokens = 50))
        }
        assertEquals(0.5f, nightOwlFraction(cells), 1e-6f)
    }

    @Test
    fun `nightOwlFraction empty or all-daytime is zero`() {
        assertEquals(0f, nightOwlFraction(emptyList()), 1e-6f)
        assertEquals(0f, nightOwlFraction(listOf(TokenUsageHeatCell(0, 10, 100))), 1e-6f)
    }

    @Test
    fun `nightOwlFraction hour 6 is daytime`() {
        // 06:00 属白天（夜窗=22..23,0..5）
        assertEquals(0f, nightOwlFraction(listOf(TokenUsageHeatCell(0, 6, 100))), 1e-6f)
    }

    // ---------- deltaPercent ----------

    @Test
    fun `deltaPercent null when previous missing or zero`() {
        assertNull(deltaPercent(100, 0))
        assertNull(deltaPercent(100, -5))
    }

    @Test
    fun `deltaPercent up down and flat`() {
        assertEquals(0.25f, deltaPercent(125, 100)!!, 1e-4f)
        assertEquals(-0.5f, deltaPercent(50, 100)!!, 1e-4f)
        assertEquals(0f, deltaPercent(100, 100)!!, 1e-6f)
    }

    // ---------- formatDuration / peakHour ----------

    @Test
    fun `formatDuration hours and minutes`() {
        assertEquals("100h 24m", formatDuration(100 * 3_600_000L + 24 * 60_000L))
        assertEquals("5m", formatDuration(5 * 60_000L))
        assertEquals("0m", formatDuration(0))
    }

    @Test
    fun `peakHour highest token hour and null on empty`() {
        assertEquals(4, peakHour(listOf(TokenUsageHeatCell(0, 3, 10), TokenUsageHeatCell(1, 4, 99))))
        assertNull(peakHour(emptyList()))
        assertNull(peakHour(listOf(TokenUsageHeatCell(0, 8, 0))))
        // Dirty server cell (hour=99) must not surface as "Peak 99:00".
        assertNull(peakHour(listOf(TokenUsageHeatCell(0, 99, 50))))
    }

    // ---------- usageFilter ----------

    @Test
    fun `usageFilter TODAY starts at local midnight and asks previous`() {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val f = usageFilter(UsagePeriod.TODAY, now = java.time.LocalDate.of(2026, 11, 9), zone = zone)
        assertEquals("2026-11-08T16:00:00Z", f.start) // 当地零点 = UTC 前一天 16:00
        assertEquals("day", f.bucket)
        assertEquals(480, f.tzOffsetMinutes)
        assertEquals(true, f.comparePrevious)
    }

    @Test
    fun `usageFilter QUARTER backs 89 days`() {
        val zone = java.time.ZoneId.of("UTC")
        val f = usageFilter(UsagePeriod.QUARTER, now = java.time.LocalDate.of(2026, 11, 9), zone = zone)
        assertEquals("2026-08-12T00:00:00Z", f.start)
    }

    @Test
    fun `usageFilter WEEK and MONTH windows`() {
        val zone = java.time.ZoneId.of("UTC")
        assertEquals(
            "2026-11-03T00:00:00Z",
            usageFilter(UsagePeriod.WEEK, now = java.time.LocalDate.of(2026, 11, 9), zone = zone).start,
        )
        assertEquals(
            "2026-10-11T00:00:00Z",
            usageFilter(UsagePeriod.MONTH, now = java.time.LocalDate.of(2026, 11, 9), zone = zone).start,
        )
    }
}
