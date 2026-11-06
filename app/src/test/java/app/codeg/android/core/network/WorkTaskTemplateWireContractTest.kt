package app.codeg.android.core.network

import app.codeg.android.core.model.WorkTaskTemplateInfo
import app.codeg.android.core.model.WorkTaskWire
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fleet M5 wire contract, pinned against the Rust server
 * (`web/handlers/work_task.rs` + `db/service/work_task_service.rs` @ 0.41.1002):
 *
 * - `work_task_template_save` params: `TemplateSaveParams` carries a single
 *   `draft` field whose {name,title,config} keys are all single-word — no
 *   rename rule can bite, and `config` (serde_json::Value) rides verbatim.
 * - `work_task_template_list` returns `Vec<WorkTaskTemplateInfo>` of plain
 *   snake_case Serialize structs (server sorts by name asc, id asc), decoded
 *   here through the real [CodegJson.response] coder.
 * - `work_task_template_delete` takes the plain camelCase `IdParams` (`{"id":N}`)
 *   and answers unit; a missing row is NOT an error.
 */
class WorkTaskTemplateWireContractTest {

    @Test
    fun `save body wraps one draft and passes config through verbatim`() {
        val config = buildJsonObject {
            put("display_text", "周报正文")
            put("agent_type", "claude_code")
            put("mode_id", "plan")
            put("prompt_blocks", buildJsonArray {
                add(buildJsonObject { put("text", "第一段") })
                add(buildJsonObject { put("text", "第二段") })
            })
        }
        val root = CodegJson.request
            .parseToJsonElement(WorkTaskWire.templateSaveBody("周报", "周报", config).toString())
            .jsonObject
        // envelope: exactly {draft:{name,title,config}} — no camelCase renaming
        // anywhere (all single-word keys), no extra keys to trip serde deny.
        assertEquals(setOf("draft"), root.keys)
        val draft = root["draft"]!!.jsonObject
        assertEquals(setOf("name", "title", "config"), draft.keys)
        assertEquals("周报", draft["name"]!!.jsonPrimitive.content)
        assertEquals("周报", draft["title"]!!.jsonPrimitive.content)
        // config is an opaque Value: snake_case keys and nesting survive as-is.
        val cfg = draft["config"]!!.jsonObject
        assertEquals("周报正文", cfg["display_text"]!!.jsonPrimitive.content)
        assertEquals("claude_code", cfg["agent_type"]!!.jsonPrimitive.content)
        assertEquals("plan", cfg["mode_id"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("第一段", "第二段"),
            cfg["prompt_blocks"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `list rows decode snake_case wire through the response coder`() {
        val rows = CodegJson.response.decodeFromString(
            ListSerializer(WorkTaskTemplateInfo.serializer()),
            """
            [
              {
                "id": 3,
                "name": "周报",
                "title": "周报",
                "config": { "display_text": "正文", "agent_type": "codex", "unknown_future": 1 },
                "created_at": "2026-11-06T10:00:00Z",
                "updated_at": "2026-11-06T11:00:00Z"
              },
              {
                "id": 1,
                "name": "empty",
                "title": "empty"
              }
            ]
            """.trimIndent(),
        )
        val weekly = rows[0]
        assertEquals(3, weekly.id)
        assertEquals("周报", weekly.name)
        assertEquals("周报", weekly.title)
        assertEquals("2026-11-06T10:00:00Z", weekly.createdAt)
        assertEquals("2026-11-06T11:00:00Z", weekly.updatedAt)
        // config stays a verbatim object (unknown keys preserved, not renamed).
        assertEquals("codex", weekly.config["agent_type"]?.jsonPrimitive?.content)
        assertEquals("正文", weekly.config["display_text"]?.jsonPrimitive?.content)
        assertEquals(1, weekly.config["unknown_future"]?.jsonPrimitive?.content?.toInt())
        // Minimal row: config defaults to an empty object, timestamps to null.
        assertEquals(0, rows[1].config.size)
        assertEquals(null, rows[1].createdAt)
    }

    @Test
    fun `delete body is the plain camelCase IdParams`() {
        assertEquals("""{"id":7}""", WorkTaskWire.idBody(7).toString())
    }
}
