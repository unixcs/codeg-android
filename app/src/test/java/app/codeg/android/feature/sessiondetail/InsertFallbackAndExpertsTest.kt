package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ExpertInstallStatus
import app.codeg.android.core.model.ExpertLinkState
import app.codeg.android.core.model.ExpertListItem
import app.codeg.android.core.model.ExpertMetadata
import app.codeg.android.core.network.ApiError
import app.codeg.android.core.network.filterLinkedExperts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Filtering rules for the compose "+" Expert Skills sheet (batch v122). */
class FilterLinkedExpertsTest {

    private fun expert(id: String) = ExpertListItem(metadata = ExpertMetadata(id = id))
    private fun status(expertId: String, agent: AgentType, state: ExpertLinkState) =
        ExpertInstallStatus(expertId = expertId, agentType = agent, state = state)

    private val catalog = listOf(expert("a"), expert("b"), expert("c"))

    @Test
    fun `keeps only experts linked to the requested agent`() {
        val filtered = filterLinkedExperts(
            catalog,
            listOf(
                status("a", AgentType.CLAUDE_CODE, ExpertLinkState.LINKED_TO_CODEG),
                status("b", AgentType.CLAUDE_CODE, ExpertLinkState.NOT_LINKED),
                // linked elsewhere / broken must not count either.
                status("c", AgentType.CLAUDE_CODE, ExpertLinkState.LINKED_ELSEWHERE),
                status("b", AgentType.CODEX, ExpertLinkState.LINKED_TO_CODEG),
            ),
            AgentType.CLAUDE_CODE,
        )
        assertEquals(listOf("a"), filtered.map { it.id })
    }

    @Test
    fun `no status rows for the agent fails open to the full catalog`() {
        // An unmanaged agent (e.g. pi on a custom dir) has zero rows server-side.
        val filtered = filterLinkedExperts(
            catalog,
            listOf(status("a", AgentType.CODEX, ExpertLinkState.LINKED_TO_CODEG)),
            AgentType.GEMINI,
        )
        assertEquals(catalog, filtered)
    }

    @Test
    fun `rows exist but none linked returns empty - the user unlinked everything`() {
        val filtered = filterLinkedExperts(
            catalog,
            listOf(
                status("a", AgentType.CLAUDE_CODE, ExpertLinkState.NOT_LINKED),
                status("b", AgentType.CLAUDE_CODE, ExpertLinkState.LINKED_ELSEWHERE),
            ),
            AgentType.CLAUDE_CODE,
        )
        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `zero own rows means unmanaged - other agents links fail open, not unlink-all`() {
        // The server emits a row for EVERY (expert, supported-agent) pair, so an
        // agent with no rows at all is one its skill store doesn't manage — the
        // fail-open case, NOT "the user unlinked everything" (that's the test
        // above, where the agent's OWN rows exist and none is linked). Other
        // agents' linked rows must not be read as this agent's unlink choice.
        val filtered = filterLinkedExperts(
            catalog,
            listOf(
                status("a", AgentType.CODEX, ExpertLinkState.LINKED_TO_CODEG),
                status("b", AgentType.GROK, ExpertLinkState.LINKED_TO_CODEG),
            ),
            AgentType.CLAUDE_CODE,
        )
        assertEquals(catalog, filtered)
    }
}

/** The insert fallback's recoverable-error matcher (batch v122). */
class IsNoActiveTurnErrorTest {

    private fun serverError(message: String) = ApiError.Server(
        status = 400,
        code = "invalid_input",
        serverMessage = message,
    )

    @Test
    fun `matches the server NoActiveTurn display text`() {
        // AcpError::NoActiveTurn Display: "no active turn to send feedback to",
        // carried verbatim through the HTTP error envelope.
        assertTrue(isNoActiveTurnError(serverError("no active turn to send feedback to")))
    }

    @Test
    fun `does not match FeedbackDisabled or unrelated text`() {
        // "live feedback is disabled" must keep the row queued with a notice,
        // NOT trigger the flush fallback.
        assertFalse(isNoActiveTurnError(serverError("live feedback is disabled")))
        assertFalse(
            isNoActiveTurnError(serverError("API endpoint 'x' is not available in web mode")),
        )
    }

    @Test
    fun `does not match transport errors`() {
        assertFalse(isNoActiveTurnError(ApiError.Transport("Network error")))
    }
}
