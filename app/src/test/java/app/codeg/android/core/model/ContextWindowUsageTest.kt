package app.codeg.android.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextWindowUsageTest {

    @Test
    fun `server fields win over model inference`() {
        val usage = resolveContextWindowUsage(
            stats = SessionStats(
                contextWindowUsedTokens = 20_000,
                contextWindowMaxTokens = 100_000,
                contextWindowUsagePercent = 19.0,
            ),
            model = "DeepSeek-V4.1-Flash",
        )
        assertEquals(20_000, usage.usedTokens)
        assertEquals(100_000, usage.maxTokens as Int?)
        assertEquals(19.0, usage.percent!!, 0.001)
    }

    @Test
    fun `missing percent is derived from used and inferred max`() {
        val usage = resolveContextWindowUsage(
            stats = SessionStats(contextWindowUsedTokens = 64_000),
            model = "Hy3",
        )
        assertEquals(64_000, usage.usedTokens)
        assertEquals(256_000, usage.maxTokens)
        assertEquals(25.0, usage.percent!!, 0.001)
    }

    @Test
    fun `unknown model exposes used without fabricating a percent`() {
        val usage = resolveContextWindowUsage(
            stats = SessionStats(contextWindowUsedTokens = 74_800),
            model = "totally-unknown-model",
        )
        assertEquals(74_800, usage.usedTokens)
        assertNull(usage.maxTokens)
        assertNull(usage.percent)
    }

    @Test
    fun `negative server values do not fabricate occupancy`() {
        val usage = resolveContextWindowUsage(
            stats = SessionStats(
                contextWindowUsedTokens = -1,
                contextWindowMaxTokens = 100_000,
                contextWindowUsagePercent = -10.0,
            ),
            model = "DeepSeek-V4.1-Flash",
        )
        assertNull(usage.usedTokens)
        assertEquals(100_000, usage.maxTokens)
        assertNull(usage.percent)
    }

    @Test
    fun `no stats resolves nothing`() {
        val usage = resolveContextWindowUsage(stats = null, model = "DeepSeek-V4.1-Flash")
        assertNull(usage.usedTokens)
        assertEquals(128_000, usage.maxTokens as Int?)
        assertNull(usage.percent)
    }
}
