package app.codeg.android.feature.sessiondetail.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks down the Fork chip's enable/disable rules (Codex-only, text-anchored). */
class ForkAvailabilityTest {

    @Test
    fun `unsupported wins over every per-turn condition`() {
        val gate = ForkAvailability.resolve(
            supported = false,
            busy = true,
            forkTurnId = "turn-4",
            isThreadTail = true,
        )
        assertEquals(ForkGate.Blocked(ForkBlockReason.UNSUPPORTED), gate)
    }

    @Test
    fun `busy blocks a otherwise resolvable anchor`() {
        val gate = ForkAvailability.resolve(
            supported = true,
            busy = true,
            forkTurnId = "turn-4",
            isThreadTail = true,
        )
        assertEquals(ForkGate.Blocked(ForkBlockReason.BUSY), gate)
    }

    @Test
    fun `a normal historical reply sends its turn id`() {
        val gate = ForkAvailability.resolve(
            supported = true,
            busy = false,
            forkTurnId = "turn-4",
            isThreadTail = false,
        )
        assertEquals(ForkGate.Ready("turn-4"), gate)
    }

    @Test
    fun `a textless non-tail reply is unresolved rather than a wrong tail fork`() {
        val gate = ForkAvailability.resolve(
            supported = true,
            busy = false,
            forkTurnId = null,
            isThreadTail = false,
        )
        assertEquals(ForkGate.Blocked(ForkBlockReason.UNRESOLVED), gate)
    }

    @Test
    fun `a textless tail reply forks at the tail without an anchor`() {
        val gate = ForkAvailability.resolve(
            supported = true,
            busy = false,
            forkTurnId = null,
            isThreadTail = true,
        )
        assertEquals(ForkGate.Ready(null), gate)
    }

    @Test
    fun `a live id is blocked even at the tail because the server has not confirmed it`() {
        val gate = ForkAvailability.resolve(
            supported = true,
            busy = false,
            forkTurnId = "live-abc",
            isThreadTail = true,
        )
        assertEquals(ForkGate.Blocked(ForkBlockReason.UNRESOLVED), gate)
    }

    @Test
    fun `a live id before the tail cannot name a branch point`() {
        val gate = ForkAvailability.resolve(
            supported = true,
            busy = false,
            forkTurnId = "live-abc",
            isThreadTail = false,
        )
        assertEquals(ForkGate.Blocked(ForkBlockReason.UNRESOLVED), gate)
    }

    @Test
    fun `live turn ids are recognised by the client-side prefix`() {
        assertTrue(ForkAvailability.isLiveTurnId("live-123"))
        assertFalse(ForkAvailability.isLiveTurnId("turn-3"))
    }
}
