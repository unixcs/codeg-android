package app.codeg.android.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Work-task (Codeg 原生看板) wire models — batch v139.
 *
 * Server DTO (`models/work_task.rs`) carries many fields whose shape varies
 * across server versions, so the list is decoded by hand with null-tolerant
 * accessors: one malformed field must degrade to `null`, never break the whole
 * board. Wire keys are snake_case (no naming strategy on hand decoders).
 *
 * Status comes through verbatim as [WorkTaskInfo.status] — the display column
 * mapping lives in `core/data/TaskBoard.kt` and must never rewrite it.
 */
data class WorkTaskInfo(
    val id: Int,
    val title: String,
    val status: String,
    val folderId: Int? = null,
    val agentType: String? = null,
    val connectionId: String? = null,
    val conversationId: Int? = null,
    val resultSummary: String? = null,
    val verdict: String? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    val changedFileCount: Int? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val scheduledAt: String? = null,
    val archivedAt: String? = null,
    /** 批v160 W5 (web parity): lifecycle timestamps for the card's meta "when"
     *  and the detail grid's 开始/完成时间 — finished ?? settled ?? started. */
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val settledAt: String? = null,
    val deferred: Boolean = false,
    val failureReason: String? = null,
    /** Branch the task was forked from (null = followed HEAD at launch). */
    val baseBranch: String? = null,
    /** Per-task work branch the agent commits to. */
    val workBranch: String? = null,
    /**
     * 批v155(R3 P2-3): provenance of the task — server constants are
     * `forge_issue` / `forge_pr` (null = created by hand). A `forge_pr` task's
     * review merge is REJECTED server-side ("this task came from a pull
     * request — deliver it back"), so the UI must not offer 合并 for it.
     */
    val sourceKind: String? = null,
    /**
     * 批v155: the raw config object verbatim — powers the detail sheet's 任务描述
     * and the edit prefill. `work_task_update` overwrites title+config WHOLE, so
     * an edit must rebuild the config from this (unknown fields preserved).
     */
    val rawConfig: JsonObject? = null,
) {
    /** Server SOURCE_KIND_PR — see [sourceKind]. */
    val fromPullRequest: Boolean get() = sourceKind == "forge_pr"
    /** Task description the editor wrote (`config.display_text`). */
    val configDisplayText: String? get() = rawConfig?.stringOrNull("display_text")

    /**
     * 批v155: the FULL prompt text the task launches with — `prompt_blocks[].text`
     * joined (display_text is a 2-000-code-point truncation and must never feed
     * an edit back). Falls back to display_text for rows that predate
     * prompt_blocks. Empty when the task was created without content.
     */
    val configPromptText: String?
        get() {
            val blocks = rawConfig?.get("prompt_blocks") as? JsonArray ?: return configDisplayText
            val joined = blocks.mapNotNull { (it as? JsonObject)?.get("text") as? JsonPrimitive }
                .mapNotNull { it.contentOrNull }
                .joinToString("\n\n")
            return joined.ifBlank { configDisplayText }
        }

    /** Mode/model id pinned in the config (`config.mode_id`), if any. */
    val configModeId: String? get() = rawConfig?.stringOrNull("mode_id")

    /** Agent wire name inside the config (`config.agent_type`), if any. */
    val configAgentType: String? get() = rawConfig?.stringOrNull("agent_type")

    /** Base branch pinned at creation (`config.base_branch`); edit must resend it. */
    val configBaseBranch: String? get() = rawConfig?.stringOrNull("base_branch")

    /** Extra config options (`config.config_values`), string-valued only — the
     *  edit rebuild passes them through untouched. */
    val configValues: Map<String, String>
        get() = (rawConfig?.get("config_values") as? JsonObject)
            ?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: "" }
            ?.filter { it.value.isNotEmpty() }
            ?: emptyMap()

    val isTerminal: Boolean get() = status in TERMINAL_STATUSES

    /**
     * Shelved rows: the upstream `archived_at` flag, or a stray
     * `status="archived"` row from a server build that wrote archived as a
     * status. Both hide behind the "show archived" filter; a status-only
     * archived row carries no live-state signal, so it must not resurface
     * as a runnable task after a routine filter flip.
     */
    val isArchivedLike: Boolean get() = archivedAt != null || status == "archived"

    companion object {
        /** States seen in server source (`db/entities/work_task.rs`); display only.
         *  `cancelled`/`archived` are not in current upstream source but exist in
         *  the wild (dell/win databases written by other builds) — listing them
         *  here only marks them display-known; the raw string is kept either way. */
        val KNOWN_STATUSES = setOf(
            "todo", "queued", "preparing", "running", "awaiting_input",
            "review", "merging", "done", "failed", "canceled",
            "cancelled", "archived",
        )

        /** failed/canceled — ended without acceptance; shown in their own group. */
        val TERMINAL_STATUSES = setOf("failed", "canceled", "cancelled")

        /**
         * Null-tolerant decode of one `work_task_list` row. Returns null only when
         * the row lacks an id or title — i.e. is not plausibly a task at all.
         */
        fun fromWire(obj: JsonObject): WorkTaskInfo? {
            val id = obj.intOrNull("id") ?: return null
            val title = obj.stringOrNull("title")?.takeIf { it.isNotBlank() } ?: return null
            return WorkTaskInfo(
                id = id,
                title = title,
                status = obj.stringOrNull("status") ?: "unknown",
                folderId = obj.intOrNull("folder_id"),
                agentType = obj.stringOrNull("agent_type"),
                connectionId = obj.stringOrNull("connection_id"),
                conversationId = obj.intOrNull("conversation_id"),
                resultSummary = obj.stringOrNull("result_summary"),
                verdict = obj.stringOrNull("verdict"),
                additions = obj.intOrNull("additions"),
                deletions = obj.intOrNull("deletions"),
                // Server's `files_changed` is an Option<i32> COUNT, not an array.
                changedFileCount = obj.intOrNull("files_changed"),
                createdAt = obj.stringOrNull("created_at"),
                updatedAt = obj.stringOrNull("updated_at"),
                scheduledAt = obj.stringOrNull("scheduled_at"),
                archivedAt = obj.stringOrNull("archived_at"),
                startedAt = obj.stringOrNull("started_at"),
                finishedAt = obj.stringOrNull("finished_at"),
                settledAt = obj.stringOrNull("settled_at"),
                // Server's `deferred_at` is an ISO timestamp STRING, not an object.
                deferred = obj.stringOrNull("deferred_at") != null || obj.boolOrNull("deferred") == true,
                failureReason = obj.stringOrNull("failure_reason"),
                baseBranch = obj.stringOrNull("base_branch"),
                workBranch = obj.stringOrNull("work_branch"),
                // R5 P1-1: the field existed but was never parsed — fromPullRequest
                // was hardcoded false and the forge_pr merge gate was dead code.
                sourceKind = obj.stringOrNull("source_kind"),
                rawConfig = obj.objectOrNull("config"),
            )
        }
    }
}

/** One entry of `work_task_events` (per-task history, limit-capped by the server). */
data class WorkTaskEvent(
    val kind: String,
    val actor: String?,
    val summary: String?,
    /** 批v160 W5 (web parity): status_changed's target status (payload.to) — the
     *  timeline head colors by it like the web's status dot does. */
    val to: String? = null,
    val createdAt: String?,
) {
    companion object {
        fun fromWire(obj: JsonObject): WorkTaskEvent? {
            val kind = obj.stringOrNull("kind") ?: return null
            val payload = obj.objectOrNull("payload")
            return WorkTaskEvent(
                kind = kind,
                actor = obj.stringOrNull("actor"),
                summary = payload?.let { p ->
                    p.stringOrNull("title")
                        ?: p.stringOrNull("message")
                        ?: p.stringOrNull("note")
                        ?: p.stringOrNull("reason")
                },
                to = payload?.stringOrNull("to"),
                createdAt = obj.stringOrNull("created_at"),
            )
        }
    }
}

/** One entry of `work_task_changed_files`. */
data class WorkTaskChangedFile(
    val path: String,
    val additions: Int? = null,
    val deletions: Int? = null,
) {
    companion object {
        fun fromWire(obj: JsonObject): WorkTaskChangedFile? {
            // Server's WorkTaskChangedFile serializes the field as `file`.
            val path = obj.stringOrNull("file") ?: return null
            return WorkTaskChangedFile(
                path = path,
                additions = obj.intOrNull("additions"),
                deletions = obj.intOrNull("deletions"),
            )
        }
    }
}

/**
 * Fleet M5: one row of `work_task_template_list` — a named title+config snapshot
 * the composer's 模板 chips prefill from (save/delete via [WorkTaskWire]).
 *
 * Server struct is a plain snake_case Serialize (`models/work_task.rs`), so this
 * decodes through [app.codeg.android.core.network.CodegJson.response]:
 * created_at/updated_at map to camelCase automatically and unknown keys are
 * ignored. [config] is the task's raw config object verbatim (serde_json::Value
 * on the server, always an object there — save validates it) — same shape as
 * [WorkTaskInfo.rawConfig]; the form reads display_text / prompt_blocks /
 * agent_type out of it and restores nothing else (V1: no mode_id/config_values).
 */
@Serializable
data class WorkTaskTemplateInfo(
    val id: Int,
    val name: String,
    val title: String,
    val config: JsonObject = JsonObject(emptyMap()),
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

/**
 * Requests. EVERY top-level `work_task_*` params struct on the server
 * (handlers/work_task.rs) carries `#[serde(rename_all = "camelCase")]` — the
 * wire for these bodies is camelCase, and an unknown key is SILENTLY dropped
 * while a missing required key 422s (MergeParams/CompleteParams `deleteWorktree`
 * have no serde default). The snake_case exceptions are the NESTED payloads:
 * WorkTaskDraft (`folder_id`) and WorkTaskConfig (`prompt_blocks`…) have no
 * rename_all. (Verified against the server source in 批v146 — an earlier
 * session had this backwards and briefly broke merge/complete; the wire-key
 * unit test now pins all of this.)
 */
object WorkTaskWire {
    fun idBody(id: Int): JsonObject = buildJsonObject { put("id", id) }

    /** Outer CreateParams is camelCase; the nested WorkTaskDraft is NOT —
     *  `folder_id` really is snake_case (models/work_task.rs, no rename_all). */
    fun createBody(folderId: Int, title: String, config: JsonObject): JsonObject =
        buildJsonObject {
            put("draft", buildJsonObject {
                put("folder_id", folderId)
                put("title", title)
                put("config", config)
            })
        }

    fun cancelBody(id: Int, deleteWorktree: Boolean = false, reason: String? = null): JsonObject =
        buildJsonObject {
            put("id", id)
            put("deleteWorktree", deleteWorktree)
            // 批v155: CancelParams is camelCase and `reason` rides the canceled
            // timeline event (web parity) — blank reasons are simply omitted.
            reason?.takeIf { it.isNotBlank() }?.let { put("reason", it) }
        }

    /** RetryParams is camelCase; the optional note feeds the retry prompt. */
    fun retryBody(id: Int, note: String? = null): JsonObject =
        buildJsonObject {
            put("id", id)
            note?.takeIf { it.isNotBlank() }?.let { put("note", it) }
        }

    /** UpdateParams camelCase outer (`id`), nested WorkTaskDraft snake (`folder_id`). */
    fun updateBody(id: Int, folderId: Int, title: String, config: JsonObject): JsonObject =
        buildJsonObject {
            put("id", id)
            put("draft", buildJsonObject {
                put("folder_id", folderId)
                put("title", title)
                put("config", config)
            })
        }

    fun mergeBody(id: Int, deleteWorktree: Boolean = true, message: String? = null): JsonObject =
        buildJsonObject {
            put("id", id)
            put("deleteWorktree", deleteWorktree)
            message?.let { put("message", it) }
        }

    fun completeBody(id: Int, deleteWorktree: Boolean = false): JsonObject =
        buildJsonObject {
            put("id", id)
            put("deleteWorktree", deleteWorktree)
        }

    fun deleteBody(id: Int, deleteWorktree: Boolean = false): JsonObject =
        buildJsonObject {
            put("id", id)
            put("deleteWorktree", deleteWorktree)
        }

    fun deferBody(id: Int, deferred: Boolean): JsonObject =
        buildJsonObject {
            put("id", id)
            put("deferred", deferred)
        }

    fun requeueBody(id: Int, note: String? = null): JsonObject =
        buildJsonObject {
            put("id", id)
            note?.takeIf { it.isNotBlank() }?.let { put("note", it) }
        }

    fun scheduleBody(id: Int, scheduledAtIsoUtc: String?): JsonObject =
        buildJsonObject {
            put("id", id)
            scheduledAtIsoUtc?.let { put("scheduledAt", it) }
        }

    /** Fleet M5: TemplateSaveParams (handlers/work_task.rs) is a camelCase
     *  struct whose only field is `draft`; the nested {name,title,config} keys
     *  are all single-word so no rename rule can bite. Config rides verbatim —
     *  the server stores it as an opaque Value and upserts by exact name. */
    fun templateSaveBody(name: String, title: String, config: JsonObject): JsonObject =
        buildJsonObject {
            put("draft", buildJsonObject {
                put("name", name)
                put("title", title)
                put("config", config)
            })
        }
}

// Slim re-declarations of the null-tolerant accessors used above. The shared ones
// in `core.model.wire` are `internal` to that package's users — duplicating four
// tiny helpers keeps this model file dependency-free.
private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.let { p ->
        if (p.isString) p.content.toIntOrNull() else runCatching { p.content.toInt() }.getOrNull()
    }

private fun JsonObject.boolOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.let { p ->
        when (p.content) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

private fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.arrayOrNull(key: String): JsonArray? = this[key] as? JsonArray
