package app.codeg.android.core.network

import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.AcpForkBody
import app.codeg.android.core.model.ConnectBody
import app.codeg.android.core.model.ConversationStatus
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.core.model.ForkResult
import app.codeg.android.core.model.QuestionAnswer
import app.codeg.android.core.model.SessionSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks down codeg's split casing: requests camelCase, responses snake_case. */
class CodegJsonTest {

    @Test
    fun `request bodies encode camelCase and omit nulls`() {
        val json = CodegJson.request.encodeToString(
            ConnectBody(agentType = AgentType.CLAUDE_CODE, workingDir = "/work"),
        )
        assertTrue(json.contains("\"agentType\":\"claude_code\""))
        assertTrue(json.contains("\"workingDir\":\"/work\""))
        // null optionals are omitted (Swift `encodeIfPresent` parity).
        assertFalse(json.contains("sessionId"))
        assertFalse(json.contains("preferredModeId"))
    }

    @Test
    fun `non-null defaults are still encoded`() {
        // declined defaults to false but must be sent (encodeDefaults = true).
        val json = CodegJson.request.encodeToString(QuestionAnswer())
        assertTrue(json.contains("\"declined\":false"))
    }

    @Test
    fun `folder response decodes snake_case keys`() {
        val folder = CodegJson.response.decodeFromString<FolderDetail>(
            """{"id":1,"name":"web","path":"/srv/web","git_branch":"main",
               "default_agent_type":"codex","sort_order":3,"color":"#fff","is_chat":true}""",
        )
        assertEquals("main", folder.gitBranch)
        assertEquals(AgentType.CODEX, folder.defaultAgentType)
        assertEquals(3, folder.sortOrder)
        assertTrue(folder.isChat)
    }

    @Test
    fun `conversation summary decodes enums and counts`() {
        val c = CodegJson.response.decodeFromString<ConversationSummary>(
            """{"id":7,"folder_id":2,"title":"Fix auth","agent_type":"gemini",
               "status":"in_progress","message_count":5,
               "created_at":"2024-01-02T03:04:05Z","updated_at":"2024-01-02T03:04:06Z"}""",
        )
        assertEquals(2, c.folderId)
        assertEquals(AgentType.GEMINI, c.agentType)
        assertEquals(ConversationStatus.IN_PROGRESS, c.status)
        assertEquals(5, c.messageCount)
        assertEquals("Fix auth", c.trimmedTitle)
    }

    @Test
    fun `unknown enum values fall back instead of throwing`() {
        val c = CodegJson.response.decodeFromString<ConversationSummary>(
            """{"id":1,"folder_id":1,"agent_type":"brand_new_agent","status":"weird_status",
               "message_count":0,"created_at":"2024-01-02T03:04:05Z","updated_at":"2024-01-02T03:04:05Z"}""",
        )
        assertEquals(AgentType.UNKNOWN, c.agentType)
        assertEquals(ConversationStatus.OTHER, c.status)
    }

    @Test
    fun `bare JSON integer decodes (create_conversation)`() {
        assertEquals(42, CodegJson.response.decodeFromString(Int.serializer(), "42"))
    }

    @Test
    fun `acp fork body encodes camelCase and omits an absent anchor`() {
        val json = CodegJson.request.encodeToString(
            AcpForkBody(connectionId = "conn-1", conversationId = 7, folderId = 3),
        )
        assertTrue(json.contains("\"connectionId\":\"conn-1\""))
        assertTrue(json.contains("\"conversationId\":7"))
        assertTrue(json.contains("\"folderId\":3"))
        assertFalse(json.contains("forkFromTurnId"))
    }

    @Test
    fun `acp fork body sends an explicit fork point`() {
        val json = CodegJson.request.encodeToString(AcpForkBody("conn-1", forkFromTurnId = "turn-4"))
        assertTrue(json.contains("\"forkFromTurnId\":\"turn-4\""))
    }

    @Test
    fun `fork result decodes camelCase wire keys`() {
        // `acp_fork` is the one response that is camelCase; the client decodes it
        // with a plain Json instance (no SnakeCase naming strategy).
        val result = Json { ignoreUnknownKeys = true }.decodeFromString<ForkResult>(
            """{"forkedSessionId":"S2","originalSessionId":"S1","siblingConversationId":124}""",
        )
        assertEquals("S2", result.forkedSessionId)
        assertEquals("S1", result.originalSessionId)
        assertEquals(124, result.siblingConversationId)
    }

    @Test
    fun `session snapshot decodes the fork capability in snake_case`() {
        val snapshot = CodegJson.response.decodeFromString<SessionSnapshot>(
            """{"selectors_ready":true,"fork_supported":true,"connection_id":"conn-1"}""",
        )
        assertTrue(snapshot.forkSupported)
        assertEquals("conn-1", snapshot.connectionId)
    }
}
