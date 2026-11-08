package app.codeg.android.feature.usage

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The filter builder is the client's ONLY involvement in time semantics: it
 * sends local midnight as ISO-8601 plus the UTC offset — the server buckets
 * in the viewer's timezone, the client never converts a timestamp itself.
 */
class TokenUsageFilterTest {

    private val zone = ZoneId.of("Asia/Shanghai") // UTC+8, no DST — stable offset
    private val now = LocalDate.of(2026, 11, 6)

    @Test
    fun `today starts at local midnight and carries the zone offset`() {
        val f = usageFilter(UsagePeriod.TODAY, now, zone)
        assertEquals("2026-11-05T16:00:00Z", f.start) // 11-06 00:00+08:00
        assertEquals(null, f.end)
        assertEquals("day", f.bucket)
        // JS `-getTimezoneOffset()` convention: UTC+8 → +480 (server buckets
        // by this offset; it is the viewer's distance EAST of UTC).
        assertEquals(480, f.tzOffsetMinutes)
        // v169: every period asks for previous-period totals so the screen can
        // render the up/down delta badge; the server simply omits it when unknown.
        assertEquals(true, f.comparePrevious)
    }

    @Test
    fun `week is the trailing 7 days inclusive of today`() {
        val f = usageFilter(UsagePeriod.WEEK, now, zone)
        assertEquals("2026-10-30T16:00:00Z", f.start) // 10-31 00:00+08:00
    }

    @Test
    fun `utc zone reports offset zero and same-day midnight`() {
        val f = usageFilter(UsagePeriod.TODAY, now, ZoneId.of("UTC"))
        assertEquals("2026-11-06T00:00:00Z", f.start)
        assertEquals(0, f.tzOffsetMinutes)
    }

    @Test
    fun `western hemisphere reports negative offset`() {
        // Local midnight of the fixed date is 05:00Z at EST / 04:00Z at EDT —
        // only the start instant is date-pinned. The WIRE offset is the viewer's
        // current offset (JS -getTimezoneOffset() at request time), so the
        // expected value follows the real clock; the assertion pins the SIGN
        // (west of UTC reads negative) against silent negation, not the DST phase.
        val f = usageFilter(UsagePeriod.TODAY, now, ZoneId.of("America/New_York"))
        assertEquals("2026-11-06T05:00:00Z", f.start)
        val expected = ZoneId.of("America/New_York").rules
            .getOffset(java.time.Instant.now()).totalSeconds / 60
        assertEquals(expected, f.tzOffsetMinutes)
        org.junit.Assert.assertTrue("west-of-UTC offset must be negative", f.tzOffsetMinutes < 0)
    }

    @Test
    fun `token counts compact to K and M`() {
        assertEquals("990", formatTokenCount(990))
        assertEquals("1.0K", formatTokenCount(1_000))
        assertEquals("12.3K", formatTokenCount(12_300))
        assertEquals("1.2M", formatTokenCount(1_234_567))
        assertEquals("0", formatTokenCount(0))
    }
}
