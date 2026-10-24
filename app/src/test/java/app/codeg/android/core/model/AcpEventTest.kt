package app.codeg.android.core.model

import app.codeg.android.core.network.CodegJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the hand-written, flattened `AcpEvent` / `EventEnvelope` decoders. */
class AcpEventTest {

    private val json: Json = CodegJson.response

    private fun obj(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject
    private fun event(raw: String): AcpEvent = AcpEvent.fromWire(obj(raw), json)

    @Test
    fun `content delta`() {
        assertEquals(AcpEvent.ContentDelta("hi"), event("""{"type":"content_delta","text":"hi"}"""))
    }

    @Test
    fun `tool call reads snake_case fields`() {
        val e = event(
            """{"type":"tool_call","tool_call_id":"t1","title":"Read file",
               "kind":"read","status":"in_progress","raw_input":"path"}""",
        ) as AcpEvent.ToolCall
        assertEquals("t1", e.id)
        assertEquals("Read file", e.title)
        assertEquals("read", e.kind)
        assertEquals("in_progress", e.status)
        assertEquals("path", e.rawInput)
    }

    @Test
    fun `permission request decodes nested options`() {
        val e = event(
            """{"type":"permission_request","request_id":"r1","tool_call":{"kind":"execute"},
               "options":[{"option_id":"o1","name":"Allow","kind":"allow_once"},
                          {"option_id":"o2","name":"Deny","kind":"reject_once"}]}""",
        ) as AcpEvent.PermissionRequest
        assertEquals("r1", e.requestId)
        assertEquals(2, e.options.size)
        assertEquals("o1", e.options[0].optionId)
        assertFalse(e.options[0].isReject)
        assertTrue(e.options[1].isReject)
    }

    @Test
    fun `question request decodes nested questions`() {
        val e = event(
            """{"type":"question_request","question_id":"q1",
               "questions":[{"id":"a","question":"Which?","header":"H","multi_select":false,
                             "options":[{"label":"X","description":"d"}]}]}""",
        ) as AcpEvent.QuestionRequest
        assertEquals("q1", e.questionId)
        assertEquals(1, e.questions.size)
        assertEquals("Which?", e.questions[0].question)
        assertEquals("X", e.questions[0].options[0].label)
    }

    @Test
    fun `turn complete defaults stop reason`() {
        assertEquals(AcpEvent.TurnComplete("end_turn"), event("""{"type":"turn_complete"}"""))
    }

    @Test
    fun `unknown event type is preserved, not thrown`() {
        assertEquals(AcpEvent.Unknown("bananas"), event("""{"type":"bananas","x":1}"""))
    }

    @Test
    fun `envelope is flattened with seq and connection id`() {
        val env = EventEnvelope.fromWire(
            obj("""{"seq":5,"connection_id":"conn-1","type":"content_delta","text":"x"}"""),
            json,
        )
        assertEquals(5L, env.seq)
        assertEquals("conn-1", env.connectionId)
        assertEquals(AcpEvent.ContentDelta("x"), env.event)
    }

    @Test
    fun `tool call carries the agent's opaque meta`() {
        val e = event(
            """{"type":"tool_call","tool_call_id":"t1","title":"Context compacting","kind":"other",
               "status":"in_progress","meta":{"contextCompaction":true,"tokensBefore":120000}}""",
        ) as AcpEvent.ToolCall
        assertEquals(true, e.meta?.get("contextCompaction")?.toString()?.toBoolean())
        // A payload with no meta at all decodes to null (= "unchanged" on an update).
        val bare = event("""{"type":"tool_call_update","tool_call_id":"t1","status":"completed"}""") as AcpEvent.ToolCallUpdate
        assertEquals(null, bare.meta)
    }

    @Test
    fun `plan approval request and resolved decode`() {
        val req = event(
            """{"type":"plan_approval_request","approval_id":"a1","tool_call_id":"t9",
               "plan_markdown":"# Plan\n- step"}""",
        )
        assertEquals(AcpEvent.PlanApprovalRequest("a1", "t9", "# Plan\n- step"), req)
        assertEquals(AcpEvent.PlanApprovalResolved("a1"), event("""{"type":"plan_approval_resolved","approval_id":"a1"}"""))
        // An empty/missing plan still opens the approval surface — the card shows a notice.
        val empty = event("""{"type":"plan_approval_request","approval_id":"a2"}""") as AcpEvent.PlanApprovalRequest
        assertEquals("", empty.planMarkdown)
        assertEquals("", empty.toolCallId)
    }

    @Test
    fun `snapshot carries a pending plan approval`() {
        val snap = LiveSessionSnapshot.fromWire(
            obj(
                """{"connection_id":"c1","status":"prompting",
                   "pending_plan_approval":{"approval_id":"a1","tool_call_id":"t9","plan_markdown":"body"}}""",
            ),
            json,
        )
        assertEquals("a1", snap.pendingPlanApproval?.approvalId)
        assertEquals("t9", snap.pendingPlanApproval?.toolCallId)
        assertEquals("body", snap.pendingPlanApproval?.planMarkdown)
        // Absent key ⇒ null, which the VM treats as "clear any stale card".
        assertEquals(null, LiveSessionSnapshot.fromWire(obj("""{"connection_id":"c1"}"""), json).pendingPlanApproval)
    }

    @Test
    fun `session config options event decodes the model selector`() {
        val e = event(
            """{"type":"session_config_options","config_options":[
               {"id":"model","name":"Model","category":"model",
                "kind":{"type":"select","current_value":"glm-5.3",
                        "options":[{"value":"glm-5.3","name":"GLM-5.3"}]}},
               {"id":"reasoning_effort","name":"Effort",
                "kind":{"type":"select","current_value":"high",
                        "options":[{"value":"high","name":"High"}]}}]}""",
        ) as AcpEvent.SessionConfigOptions
        assertEquals(2, e.options.size)
        assertEquals("model", e.options[0].id)
        assertEquals("glm-5.3", e.options[0].kind.currentValue)
        assertEquals("GLM-5.3", e.options[0].kind.options[0].name)
    }

    @Test
    fun `snapshot carries config options for the model label`() {
        val snap = LiveSessionSnapshot.fromWire(
            obj(
                """{"connection_id":"c1","config_options":[
                   {"id":"model","category":"model",
                    "kind":{"type":"select","current_value":"gpt-5",
                            "groups":[{"group":"openai","name":"OpenAI",
                                       "options":[{"value":"gpt-5","name":"GPT-5"}]}]}}]}""",
            ),
            json,
        )
        assertEquals(1, snap.configOptions.size)
        assertEquals("GPT-5", snap.configOptions[0].kind.groups[0].options[0].name)
        // Absent key ⇒ empty, never a thrown frame.
        assertEquals(0, LiveSessionSnapshot.fromWire(obj("""{"connection_id":"c1"}"""), json).configOptions.size)
    }

    @Test
    fun `plan approval decision serializes snake_case`() {
        assertEquals("request_changes", PlanApprovalDecision.REQUEST_CHANGES.wire)
        assertEquals(
            """{"connectionId":"c1","approvalId":"a1","answer":{"decision":"request_changes","feedback":"more tests"}}""",
            CodegJson.request.encodeToString(
                AnswerPlanApprovalBody.serializer(),
                AnswerPlanApprovalBody("c1", "a1", PlanApprovalAnswer(PlanApprovalDecision.REQUEST_CHANGES, "more tests")),
            ),
        )
        // `explicitNulls = false` on the request codec drops an absent feedback.
        assertFalse(
            CodegJson.request.encodeToString(
                PlanApprovalAnswer.serializer(),
                PlanApprovalAnswer(PlanApprovalDecision.APPROVE, null),
            ).contains("feedback"),
        )
    }
}
