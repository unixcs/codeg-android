package app.codeg.android.feature.inbox

import app.codeg.android.core.datastore.DispatchRef
import app.codeg.android.core.datastore.DispatchState
import app.codeg.android.core.datastore.IdeaEntry
import app.codeg.android.core.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批v193 一挂一定：锁定 [pickLatestTaskRef] / [buildInsertPayload] /
 * [insertContentReady] 的口径——一键插待办复用账本里最近一笔任务型 CONFIRMED
 * 派发做默认目标；会话型行（v149）永不入选；UNKNOWN agent wire 回落默认；
 * 内容闸与编辑器 InsertTodoSection 的 contentReady 同一条规则。
 */
class InsertTargetTest {

    private fun ref(
        state: DispatchState = DispatchState.CONFIRMED,
        sessionMode: Boolean = false,
        dispatchedAt: Long? = null,
        createdAt: Long = 0,
        agentTypeWire: String? = null,
        modeId: String? = null,
    ) = DispatchRef(
        requestId = "req",
        serverId = "srv",
        folderId = 929,
        title = "t",
        state = state,
        sessionMode = sessionMode,
        dispatchedAtEpochMs = dispatchedAt,
        createdAt = createdAt,
        agentTypeWire = agentTypeWire,
        modeId = modeId,
        configValues = if (modeId == null) emptyMap() else mapOf("model" to "kimi"),
    )

    // ---- pickLatestTaskRef ----

    @Test
    fun emptyLedgerHasNoTarget() {
        assertNull(pickLatestTaskRef(emptyList()))
    }

    @Test
    fun pendingAndUnknownNeverQualify() {
        assertNull(
            pickLatestTaskRef(
                listOf(
                    ref(state = DispatchState.PENDING, dispatchedAt = 200),
                    ref(state = DispatchState.UNKNOWN, dispatchedAt = 300),
                ),
            ),
        )
    }

    @Test
    fun sessionModeRefsNeverQualify() {
        assertNull(pickLatestTaskRef(listOf(ref(sessionMode = true, dispatchedAt = 999))))
    }

    @Test
    fun picksMostRecentlyDispatchedConfirmed() {
        val older = ref(dispatchedAt = 100, agentTypeWire = "codex")
        val newer = ref(dispatchedAt = 200, agentTypeWire = "pi")
        assertEquals(newer, pickLatestTaskRef(listOf(older, newer)))
        assertEquals(newer, pickLatestTaskRef(listOf(newer, older)))
    }

    @Test
    fun fallsBackToCreatedAtWhenDispatchTimeMissing() {
        val oldRow = ref(createdAt = 500) // 老账本行：无 dispatchedAt
        val newer = ref(dispatchedAt = 100)
        assertEquals(oldRow, pickLatestTaskRef(listOf(newer, oldRow)))
    }

    @Test
    fun sessionModeLatestButOlderTaskStillWins() {
        // 账本里最近一笔是会话型（v149），更旧的任务型才可作挂板目标。
        val olderTask = ref(dispatchedAt = 100)
        val newerSession = ref(sessionMode = true, dispatchedAt = 200)
        assertEquals(olderTask, pickLatestTaskRef(listOf(newerSession, olderTask)))
    }

    @Test
    fun sameDispatchedAtFallsBackToFirstInList() {
        // maxByOrNull 对相等键取首项——排序键含 dispatchedAt 时并列即取
        // 账本序第一条，行为确定可测。
        val a = ref(dispatchedAt = 100, agentTypeWire = "codex")
        val b = ref(dispatchedAt = 100, agentTypeWire = "pi")
        assertEquals(a, pickLatestTaskRef(listOf(a, b)))
    }

    // ---- buildInsertPayload ----

    @Test
    fun payloadReplaysTargetSnapshot() {
        val payload = buildInsertPayload(ref(agentTypeWire = "codex", modeId = "custom"))
        assertEquals("srv", payload.serverId)
        assertEquals(929, payload.folderId)
        assertEquals(AgentType.CODEX, payload.agentType)
        assertEquals("custom", payload.modeId)
        assertEquals(mapOf("model" to "kimi"), payload.configValues)
    }

    @Test
    fun unknownAgentWireDropsOverrideButKeepsTarget() {
        val payload = buildInsertPayload(ref(agentTypeWire = "future_agent"))
        assertNull(payload.agentType)
        // agent 回落默认，但目标机器/项目/模式快照必须原样带走。
        assertEquals("srv", payload.serverId)
        assertEquals(929, payload.folderId)
    }

    @Test
    fun nullAgentWireStaysNull() {
        val payload = buildInsertPayload(ref(agentTypeWire = null))
        assertNull(payload.agentType)
    }

    // ---- insertContentReady ----

    @Test
    fun blankIdeaIsNotReady() {
        assertFalse(insertContentReady(IdeaEntry(id = "i", title = "")))
    }

    @Test
    fun titleOnlyIsNotReady() {
        // 有标题无正文/附件：没有 prompt，validate_draft 会 422。
        assertFalse(insertContentReady(IdeaEntry(id = "i", title = "只有标题")))
    }

    @Test
    fun attachmentsOnlyWithoutTitleAndTextIsNotReady() {
        // 纯附件卡：标题只能从正文派生，正文为空 → 插入必死端（与派发不同，
        // 派发允许纯附件；挂板的内容闸更严，与编辑器 contentReady 同口径）。
        assertFalse(
            insertContentReady(
                IdeaEntry(
                    id = "i",
                    title = "",
                    attachments = listOf(
                        app.codeg.android.core.data.LocalAttachment(
                            id = "a1", name = "a.png", localPath = "/data/a.png", isImage = true,
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun descriptionReadyEvenWithoutTitle() {
        // 无标题但有正文：标题可从正文派生，与编辑器 contentReady 同口径。
        assertTrue(insertContentReady(IdeaEntry(id = "i", title = "", description = "先跑一遍 Verify")))
    }

    @Test
    fun attachmentsCountAsContent() {
        assertTrue(
            insertContentReady(
                IdeaEntry(
                    id = "i",
                    title = "截图任务",
                    attachments = listOf(
                        app.codeg.android.core.data.LocalAttachment(
                            id = "a1",
                            name = "a.png",
                            localPath = "/data/a.png",
                            isImage = true,
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun whitespaceOnlyDescriptionDoesNotCount() {
        assertFalse(insertContentReady(IdeaEntry(id = "i", title = "", description = "   ")))
    }
}
