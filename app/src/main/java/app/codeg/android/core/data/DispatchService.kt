package app.codeg.android.core.data

import app.codeg.android.core.datastore.DispatchRef
import app.codeg.android.core.datastore.DispatchState
import app.codeg.android.core.datastore.DispatchStore
import app.codeg.android.core.network.ApiError
import app.codeg.android.core.network.displayMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What happened to one dispatch request (batch v140/v141 shared pipeline). */
sealed interface DispatchOutcome {
    /** Server returned a task id; [scheduleNote] carries a schedule failure if any. */
    data class Confirmed(val ref: DispatchRef, val scheduleNote: String? = null) : DispatchOutcome

    /** Request fate unobservable — ledger keeps the ref UNKNOWN, no auto re-send. */
    data class Unknown(val ref: DispatchRef) : DispatchOutcome

    /** Definitive failure (4xx / unreachable before send) — the ledger entry is removed. */
    data class HardFailed(val message: String) : DispatchOutcome
}

/**
 * The one dispatch pipeline every dispatch surface goes through (idea inbox
 * v140, session handoff v141). Owns the request-id ledger: PENDING while in
 * flight, CONFIRMED with the server task id, UNKNOWN when the fate is
 * unobservable. The server has NO idempotency key, so anything ambiguous must
 * surface as [DispatchOutcome.Unknown] and block blind re-sends (PRD §7.5).
 */
@Singleton
class DispatchService @Inject constructor(
    private val dispatchStore: DispatchStore,
    private val repository: ServerRepository,
    // 批v150 P0: the session-mode "first prompt" transmitter. The consumer
    // (SessionDetailViewModel.take) shipped in v149 but nothing ever armed the
    // station — session-mode dispatches opened a blank conversation.
    private val sessionAutoStart: SessionAutoStart,
    // 批v154: the private copies of dispatched files are freed on CONFIRMED.
    private val attachmentStager: AttachmentStager,
    // P0: a session created by the form pins the picked model to THAT
    // conversation, so its first connect starts on the chosen model.
    private val conversationSelector: app.codeg.android.core.datastore.ConversationSelectorStore,
    // 批v162 P1: a session created with 「加入任务板」 on becomes a board member.
    // Written here, at CONFIRMED, so every host (and the UNKNOWN probe that
    // resolves later through confirm()) gets the same behaviour for free.
    private val boardTrackStore: app.codeg.android.core.datastore.BoardTrackStore,
) {
    data class SendRequest(
        val ideaId: String? = null,
        val serverId: String,
        val folderId: Int,
        val folderName: String,
        val title: String,
        val description: String,
        val agentTypeWire: String? = null,
        /** Explicit base branch (ui重构 §6.2 分支选择) — null = the folder's
         *  current branch, exactly the previous behavior. The server derives the
         *  task work branch from it at start. */
        val baseBranch: String? = null,
        /** 批v155: the agent mode picked in the form's 模型 row, validated
         *  against the probed options list by the form. null = mode-less (the
         *  agent's default). Session mode ignores it — a live conversation's
         *  mode is chosen inside the session. */
        val modeId: String? = null,
        /** P0: picked config options (model / reasoning effort) — task mode sends
         *  them as `config_values`; session mode pins them on the conversation. */
        val configValues: Map<String, String> = emptyMap(),
        /** 批v162 P1: session mode only — put the created conversation on the
         *  task board (app-local membership). Ignored for task mode. */
        val addToBoard: Boolean = false,
        val scheduleLocalMillis: Long? = null,
        /** Immediate (non-scheduled) dispatches start the task right after
         * create — the server parks new tasks in todo forever, so without this
         * the "记想法→派发→等它跑" path never runs. Handoff dispatches leave
         * it false: picking up the work is the receiving side's decision. */
        val autoStart: Boolean = false,
        /** 批v149 会话模式：目标是"项目里直接开一个会话"——服务端
         * create_conversation 是纯 DB 插入（不建 worktree、不进任务板），返回
         * 会话 id 记入账本；首条指令由 UI 跳转会话后自动发送。定时/自动开始
         * 对会话无意义，此模式下被忽略。 */
        val sessionMode: Boolean = false,
        /** On a hard failure, put this ref back as UNKNOWN — a re-dispatch must
         * never destroy the only trace of the original ambiguous request. */
        val restoreOnHardFail: DispatchRef? = null,
        /** 批v154 附件：随派发一起上传到目标设备工作目录的本地文件（收件箱想法
         * 附件 / 派发单附件）。上传发生在 create 之前——失败即干净失败，不会留下
         * 一个"任务建了但附件没到"的半成品。 */
        val attachments: List<LocalAttachment> = emptyList(),
    )

    /**
     * Crash-recovery sweep: a PENDING ledger entry means the request was sent
     * but its outcome is unobservable — flip it to UNKNOWN so it surfaces as a
     * resolvable card instead of rotting silently. Every dispatch surface calls
     * this on VM init so the sweep doesn't depend on visiting the inbox.
     *
     * Self-guarded: callers run it from bare VM-init coroutines, so a DataStore
     * IO failure here must degrade to "skip the sweep" (PENDING entries still
     * resolve via probe later), never kill the process — a bare init coroutine
     * on this page took the whole Activity tab down once before (v130 class).
     */
    suspend fun sweepPendingToUnknown() {
        try {
            val now = System.currentTimeMillis()
            dispatchStore.refs.first()
                // Only PENDING entries old enough to be corpses. A fresh PENDING
                // may be a request STILL IN FLIGHT from another surface — flipping
                // it to UNKNOWN mid-flight invites a concurrent redispatch (double
                // create). A create that takes >2 min will surface UNKNOWN on the
                // next sweep round anyway.
                .filter { it.state == DispatchState.PENDING && now - it.createdAt > PENDING_SWEEP_AGE_MS }
                .forEach { dispatchStore.put(it.copy(state = DispatchState.UNKNOWN)) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Ledger unreadable: nothing to sweep; probe/dispatch still work.
        }
    }

    /**
     * True when an UNRESOLVED (UNKNOWN) ledger entry exists that looks like the
     * same dispatch — same server, folder and capped title. Surfaces whose
     * UNKNOWN guard lives only in memory (handoff sheet reset) must consult
     * this before sending, or a reopened sheet can blind-re-send an ambiguous
     * request (double create).
     */
    suspend fun hasUnresolvedDuplicate(serverId: String, folderId: Int, title: String): Boolean =
        dispatchStore.refs.first().any {
            // PENDING counts too: it is by definition unconfirmed (in flight, or
            // left by a killed process until the age-gated sweep flips it) —
            // creating "the same task" on top of it is exactly the double-create
            // this gate exists to stop.
            it.state != DispatchState.CONFIRMED &&
                it.serverId == serverId &&
                it.folderId == folderId &&
                it.title == cap(title.trim(), 120)
        }

    /**
     * P0-3: where dispatched attachments land inside the target workspace.
     * Deliberately NOT `uploads/` — that is the folder the SERVER's own composer
     * uses, and a dispatch must never drop files into the project the user
     * browses. One directory per request keeps two dispatches apart.
     */
    private val STAGE_ROOT = ".codeg/uploads"

    /** Makes git ignore everything staged above (written best-effort, once per dir). */
    private val STAGE_GITIGNORE = "*\n"

    /** PENDING entries younger than this are considered in flight, not dead. */
    private val PENDING_SWEEP_AGE_MS = 120_000L

    /** Serializes duplicate-check + PENDING reservation in send() (Hilt can't
     *  provide a Mutex binding, so this is a plain property — one per service,
     *  which is exactly the serialization scope we need). */
    private val gateMutex = kotlinx.coroutines.sync.Mutex()

    /** Code-point-safe truncation (never splits an emoji surrogate pair).
     *  Companion so call sites can invoke it as `DispatchService.cap(...)`
     *  (R4 P1: it was an instance member but three VM/screen call sites in
     *  批v155 address it statically — that compiled to nothing). */
    companion object {
        fun cap(text: String, max: Int): String =
            if (text.length <= max) text else text.substring(0, text.offsetByCodePoints(0, max))
    }

    suspend fun send(req: SendRequest): DispatchOutcome {
        val now = System.currentTimeMillis()
        val profile = repository.profiles.first().firstOrNull { it.id == req.serverId }
        val client = profile?.let { repository.client(it) }
        // Generated BEFORE the upload: the staging directory is per-request, so
        // the id has to exist while there is still no ledger entry to borrow it
        // from (P0-3 坑一).
        val requestId = UUID.randomUUID().toString()
        val request = DispatchRef(
            requestId = requestId,
            ideaId = req.ideaId,
            serverId = req.serverId,
            serverName = profile?.name.orEmpty(),
            folderId = req.folderId,
            folderName = req.folderName,
            // Cap the title the same way on create and probe: if the server
            // truncates long titles, exact-match probing must use the same string.
            title = cap(req.title.trim(), 120),
            state = DispatchState.PENDING,
            createdAt = now,
            description = req.description.trim(),
            agentTypeWire = req.agentTypeWire,
            // Anchor the probe window at send time — the process can die before
            // the response and the anchor must survive.
            dispatchedAtEpochMs = now,
            scheduleLocalMillis = req.scheduleLocalMillis,
            autoStart = req.autoStart,
            sessionMode = req.sessionMode,
            attachments = req.attachments,
            addToBoard = req.addToBoard && req.sessionMode,
            // 批v166: the model/effort picks ride the ledger so a re-dispatch
            // re-sends them (the probe resolver replays them for sessions too).
            modeId = req.modeId,
            configValues = req.configValues,
        )
        if (client == null) {
            restoreOnHardFail(req.restoreOnHardFail)
            return DispatchOutcome.HardFailed("设备不可用或 Token 缺失")
        }
        // 批v154: upload the attachments FIRST. They must exist on the target device
        // before the task/conversation is created — uploading afterwards would leave
        // a created row whose prompt points at nothing. A failure here is a clean
        // hard failure (the ledger has not reserved the title yet, nothing exists).
        // P0-3: a conversation's first send carries its IMAGES inline (base64 in
        // the prompt), exactly like the composer — no workspace copy at all. Only
        // non-image files need a real path on the target device.
        val inlineImages: List<SessionAutoStart.InlineImage>
        val uploaded: List<UploadedAttachment>
        try {
            inlineImages = if (req.sessionMode) {
                req.attachments.filter { it.isImage }.map { inlineImageOf(it) }
            } else {
                emptyList()
            }
            val toUpload = if (req.sessionMode) {
                req.attachments.filterNot { it.isImage }
            } else {
                req.attachments
            }
            uploaded = if (toUpload.isEmpty()) {
                emptyList()
            } else {
                uploadAttachments(client, req, requestId, toUpload)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            restoreOnHardFail(req.restoreOnHardFail)
            return DispatchOutcome.HardFailed(
                "附件处理失败：" + (e.message ?: "未知错误"),
            )
        }
        // Atomic check-and-reserve: the VM-level gates can both pass for the
        // same title from two surfaces (TOCTOU across VMs) — re-check under a
        // mutex so only one send may move a title from "no unresolved ref" to
        // "PENDING" at a time.
        // 批v155(R2C#2): anchor the uploaded remote paths onto the ledger ref so
        // ANY later resolver (the probe below, the inbox probe, a redispatch)
        // can arm the session's first prompt with files that already sit on the
        // target device — the local copies die on confirm, the paths must not.
        val anchored = if (uploaded.isEmpty()) request else request.copy(
            uploadedPaths = uploaded.map { it.absolutePath },
        )
        gateMutex.withLock {
            if (hasUnresolvedDuplicate(req.serverId, req.folderId, anchored.title)) {
                restoreOnHardFail(req.restoreOnHardFail)
                return DispatchOutcome.HardFailed("已存在同目标的未确认派发记录，请先处理后再派发，避免重复创建")
            }
            dispatchStore.put(anchored)
        }
        // Unified "remote id" result: task mode maps the created row to its id,
        // session mode gets the conversation id straight from create_conversation
        // (a bare JSON int). null/<=0 = the request reached the server but the
        // row did not decode — ambiguous, handled identically below.
        // The server requires a concrete agent_type; resolve 自动 (null) and
        // an UNKNOWN wire to the client default, same fallback the new-task
        // draft screen applies when a device reports no agents. Hoisted out of
        // the session branch because the selector-prefs write after CONFIRMED
        // needs the same value.
        val sessionAgent = req.agentTypeWire?.let { app.codeg.android.core.model.AgentType.fromWire(it) }
            ?.takeIf { it != app.codeg.android.core.model.AgentType.UNKNOWN }
            ?: app.codeg.android.core.model.AgentType.CLAUDE_CODE
        val created: kotlin.Result<Int?> = if (req.sessionMode) {
            runCatching { client.createConversation(req.folderId, sessionAgent, anchored.title) }
        } else {
            val config = DispatchPipeline.taskConfig(
                // Task mode has no attachment channel, so the uploaded paths ride
                // inside the description itself (session mode hands them to
                // SessionAutoStart instead — the composer appends its own note).
                description = req.description.trim() + attachmentNote(uploaded),
                agentTypeWire = req.agentTypeWire,
                modeId = req.modeId,
                baseBranch = req.baseBranch,
                configValues = req.configValues,
            )
            runCatching { client.workTaskCreate(req.folderId, anchored.title, config)?.id }
        }
        return created.fold(
            onSuccess = { remoteId ->
                if (remoteId != null && remoteId > 0) {
                    // Session mode: no schedule/no auto-start — the first prompt
                    // IS the start, sent by the session screen after the jump.
                    // 批v150 P0: that sender finally has a transmitter — arm the
                    // handoff station on every CONFIRMED session create, or the
                    // conversation the UI jumps to opens permanently blank.
                    if (req.sessionMode) {
                        confirm(anchored, remoteId, profile, null, false).also { outcome ->
                            if (outcome is DispatchOutcome.Confirmed) {
                                // P0: the picked model follows the conversation it was
                                // created for (it can't ride create_conversation — the
                                // server row has no model field). The first connect
                                // replays these via acp_connect preferred values.
                                replaySessionSelectors(anchored, remoteId)
                                sessionAutoStart.arm(
                                    req.serverId, remoteId, req.description, uploaded, inlineImages,
                                )
                            }
                        }
                    } else confirm(anchored, remoteId, profile, req.scheduleLocalMillis, req.autoStart)
                } else {
                    markUnknown(anchored)
                }
            },
            onFailure = { e ->
                when {
                    e is CancellationException -> {
                        // The coroutine is already cancelled: any other suspend
                        // call here would immediately re-throw. Honour structured
                        // cancellation — but the request may already be on the
                        // wire, so the ledger trace MUST survive: deleting it (the
                        // old behavior) orphans a possibly-created task with no
                        // UNKNOWN card, no probe, and no duplicate guard.
                        withContext(NonCancellable) { runCatching { markUnknown(anchored) } }
                        throw e
                    }
                    // ApiError.Decoding after a successful send() means the server
                    // ANSWERED (2xx) but the body didn't parse — the create may
                    // well have happened. This is the ambiguous case; treating it
                    // as a hard failure deleted the ledger's only trace and
                    // invited a double create (批v146 对抗审查 #1).
                    e is ApiError.Decoding -> probeOrMarkUnknown(anchored, profile, uploaded, inlineImages)
                    // Timeout-style failures are ambiguous: the task may exist.
                    // Self-heal: one immediate probe — if the request actually
                    // landed, the ledger resolves to CONFIRMED and the user
                    // never sees a 提交结果未知 card at all.
                    isAmbiguous(e) -> probeOrMarkUnknown(anchored, profile, uploaded, inlineImages)
                    else -> {
                        dispatchStore.remove(anchored.requestId)
                        req.restoreOnHardFail?.let { dispatchStore.put(it.copy(state = DispatchState.UNKNOWN)) }
                        DispatchOutcome.HardFailed(
                            (e as? ApiError)?.displayMessage() ?: e.message ?: "派发失败",
                        )
                    }
                }
            },
        )
    }

    /**
     * Upload every attachment into `<folder>/.codeg/uploads/<requestId>/` and
     * return the absolute paths the prompt will point at. Throws on the first
     * real failure — a partial attachment set must never be dispatched silently.
     * (批v162: the per-file mechanics moved to [uploadToWorkspace]; this wrapper
     * only resolves the dispatch target's folder path.)
     */
    private suspend fun uploadAttachments(
        client: app.codeg.android.core.network.CodegClient,
        req: SendRequest,
        requestId: String,
        attachments: List<LocalAttachment>,
    ): List<UploadedAttachment> {
        val folders = client.listFolders()
        val root = folders.firstOrNull { it.id == req.folderId }?.path
            ?: throw IllegalStateException("目标项目路径未找到，附件未上传")
        return uploadToWorkspace(client, root, "$STAGE_ROOT/$requestId", attachments)
    }

    /**
     * 批v162（想法⇄会话快链）：公开的上传入口——把一批本地附件传进目标设备的
     * 工作区 [root] 下的 [targetDir]，返回 prompt 可引用的绝对路径。追加到已有
     * 会话的非图片附件走与派发完全同一条管线（同样的落点约定、同样的 409 语义、
     * 同样的"首败即抛"），不让第二份实现漂移。
     *
     * A 409 is NOT retried under a renamed file: the directory is unique per
     * request, so a collision can only be our own earlier attempt whose bytes
     * already landed — the same path is the honest answer. Renaming would also
     * turn the `.gitignore` seed into `.gitignore-2` and silently un-ignore the
     * whole staging directory (P0-3 坑二).
     */
    suspend fun uploadToWorkspace(
        client: app.codeg.android.core.network.CodegClient,
        root: String,
        targetDir: String,
        attachments: List<LocalAttachment>,
    ): List<UploadedAttachment> {
        // Best-effort: an existing seed (409) is exactly what we want. Without
        // it every dispatch would leave an untracked directory in the repo.
        runCatching {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                client.uploadWorkspaceFile(root, STAGE_ROOT, ".gitignore", STAGE_GITIGNORE.length.toLong()) {
                    STAGE_GITIGNORE.byteInputStream()
                }
            }
        }
        return attachments.map { att ->
            val file = java.io.File(att.localPath)
            if (!file.canRead()) throw IllegalStateException("${att.name}：本地文件不可读")
            try {
                val res = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    client.uploadWorkspaceFile(root, targetDir, att.name, file.length()) {
                        file.inputStream()
                    }
                }
                UploadedAttachment(
                    name = res.name.ifBlank { att.name },
                    absolutePath = root.trimEnd('/') + "/" + res.path.trimStart('/'),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val conflict = e is ApiError.Server &&
                    (e.status == 409 || e.serverMessage.contains("already exists", ignoreCase = true))
                if (!conflict) throw IllegalStateException("${att.name}：${e.displayMessage()}")
                UploadedAttachment(
                    name = att.name,
                    absolutePath = root.trimEnd('/') + "/" + targetDir + "/" + att.name,
                )
            }
        }
    }

    /**
     * 批v162: a fresh per-append staging directory under [STAGE_ROOT] — the same
     * layout a dispatch uses, so two appends (or an append and a dispatch) never
     * share a directory and the gitignore seed above keeps all of them invisible.
     */
    fun newStageDir(): String = "$STAGE_ROOT/${UUID.randomUUID()}"

    /**
     * Encode one local image for INLINE delivery (a conversation's first prompt
     * carries base64, same as the composer). Fails loudly: a silently dropped
     * image is exactly the bug this path exists to fix.
     */
    private suspend fun inlineImageOf(att: LocalAttachment): SessionAutoStart.InlineImage {
        val file = java.io.File(att.localPath)
        if (!file.canRead()) throw IllegalStateException("${att.name}：本地图片不可读")
        val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) { file.readBytes() }
        if (bytes.isEmpty()) throw IllegalStateException("${att.name}：图片为空")
        return SessionAutoStart.InlineImage(
            base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
            mimeType = att.mimeType ?: "image/jpeg",
        )
    }

    /**
     * Machine-facing note appended to a TASK description (task mode has no
     * attachment channel): every uploaded file's absolute path, so the agent can
     * just read it. Empty when nothing was attached.
     */
    fun attachmentNote(files: List<UploadedAttachment>): String {
        if (files.isEmpty()) return ""
        return buildString {
            append("\n\n📎 附件已上传到目标设备的工作目录：\n")
            files.forEach { append("- ").append(it.absolutePath).append('\n') }
            append("请直接读取上述文件。")
        }
    }

    private suspend fun restoreOnHardFail(ref: DispatchRef?) {
        // The old entry was removed by the caller before re-sending; put it back.
        ref?.let { dispatchStore.put(it.copy(state = DispatchState.UNKNOWN)) }
    }

    /**
     * True when the request's fate is unobservable: a dropped connection
     * (IOException, incl. socket timeout) or a wrapped transport error may mean
     * the server created the task without us seeing the response. The server has
     * no idempotency key, so any of these must block re-sending.
     *
     * A [ApiError.Server] response is NOT ambiguous, verified against the server
     * source (v1.0.1-fengx.34): every rejection path — axum's serde rejection
     * (422, pre-handler) and every error the create core returns (DbError →
     * 500, raised on the folder lookup / insert BEFORE any task row exists) —
     * happens without a task being created. The server answered, so we KNOW
     * the create failed; treating 5xx as "unknown" surfaced the misleading
     * 提交结果未知 card for deterministic rejections (e.g. a stale folder id).
     */
    private fun isAmbiguous(e: Throwable): Boolean =
        e is java.io.IOException ||
            e is ApiError.Transport

    private suspend fun confirm(
        request: DispatchRef,
        todoId: Int,
        profile: app.codeg.android.core.datastore.ServerProfile,
        scheduleLocalMillis: Long?,
        autoStart: Boolean,
    ): DispatchOutcome {
        val ref = request.copy(remoteTodoId = todoId, state = DispatchState.CONFIRMED)
        putIfPresent(ref)
        // 批v162 P1: membership is app-local and idempotent; a DataStore hiccup
        // must never turn a successful create into a failed dispatch.
        if (ref.sessionMode && ref.addToBoard) {
            runCatching { boardTrackStore.track(ref.serverId, todoId, ref.folderId) }
        }
        // 批v154: CONFIRMED means the create landed, and every attachment was
        // uploaded BEFORE it — the private copies are now disposable. Unresolved
        // refs (PENDING/UNKNOWN) keep theirs so a redispatch can re-upload.
        ref.attachments.forEach(attachmentStager::discard)
        var scheduleNote: String? = null
        if (scheduleLocalMillis != null) {
            val client = repository.client(profile)
            val scheduled = client?.let {
                try {
                    it.workTaskSchedule(todoId, DispatchPipeline.toUtcRfc3339(scheduleLocalMillis))
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
            } == true
            if (!scheduled) scheduleNote = "任务已创建，但定时设置失败，可在任务中心手动处理"
        } else if (autoStart) {
            // PRD main path is 记想法→派发→等它跑: the server parks a freshly
            // created task in todo forever, so an "immediate" dispatch must also
            // start it (scheduled ones are pulled by the server scheduler
            // instead). Best-effort: if the start call fails the task stays in
            // todo and the note tells the user where to start it by hand.
            val client = repository.client(profile)
            val started = client?.let {
                try {
                    it.workTaskStart(todoId)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
            } == true
            if (!started) scheduleNote = "任务已创建，但自动开始失败，可到任务中心手动开始"
        }
        return DispatchOutcome.Confirmed(ref, scheduleNote)
    }

    /**
     * 批v166: replay the ledger ref's pinned mode + config picks onto the
     * conversation selector so the first acp_connect reconnects with them (the
     * server conversation row has no model field). Shared by the direct create
     * and BOTH probe-confirm paths — a create resolved by the ambiguity probe
     * must land with the model the user picked, or the "never silently revert"
     * guarantee leaks on exactly that path.
     */
    suspend fun replaySessionSelectors(request: DispatchRef, conversationId: Int) {
        val agent = request.agentTypeWire
            ?.let { app.codeg.android.core.model.AgentType.fromWire(it) }
            ?.takeIf { it != app.codeg.android.core.model.AgentType.UNKNOWN }
            ?: app.codeg.android.core.model.AgentType.CLAUDE_CODE
        runCatching {
            request.modeId?.let {
                conversationSelector.saveMode(request.serverId, conversationId, agent, it)
            }
            request.configValues.forEach { (id, value) ->
                conversationSelector.saveConfig(request.serverId, conversationId, agent, id, value)
            }
        }
    }

    private suspend fun markUnknown(request: DispatchRef): DispatchOutcome {
        dispatchStore.put(request.copy(state = DispatchState.UNKNOWN))
        return DispatchOutcome.Unknown(request)
    }

    /**
     * Tombstone check before every delayed ledger write-back (confirm from a
     * probe racing a user's 放弃): a ref the user explicitly discarded must
     * never be resurrected as CONFIRMED — that would both lie about "verified"
     * and un-block a double create. The normal send() path is unaffected (the
     * PENDING entry it wrote is still in the ledger).
     */
    private suspend fun putIfPresent(ref: DispatchRef) {
        val exists = runCatching { dispatchStore.refs.first().any { it.requestId == ref.requestId } }
            .getOrDefault(true) // Ledger unreadable: prefer writing (state UNKNOWN/CONFIRMED both honest) over losing the trace.
        if (exists) dispatchStore.put(ref)
    }

    /**
     * A genuinely ambiguous send (dropped connection mid-flight): mark the
     * ledger UNKNOWN, then immediately run ONE probe against the server. If
     * the request actually landed, the ledger resolves to CONFIRMED and the
     * user never sees the 提交结果未知 card — the probe must stay read-only
     * (list + exact title/folder/time-window match) and any failure keeps
     * the UNKNOWN card for manual resolution, never a silent delete.
     */
    private suspend fun probeOrMarkUnknown(
        request: DispatchRef,
        profile: app.codeg.android.core.datastore.ServerProfile,
        // 批v155(R2C#2): the already-uploaded files — a probe-resolved session
        // create arms its first prompt with them (same as the CONFIRMED path).
        uploaded: List<UploadedAttachment> = emptyList(),
        inlineImages: List<SessionAutoStart.InlineImage> = emptyList(),
    ): DispatchOutcome {
        markUnknown(request)
        try {
            // Give the server a beat — a socket timeout usually means the
            // response was in flight when the connection died.
            kotlinx.coroutines.delay(1_500)
            val client = repository.client(profile) ?: return DispatchOutcome.Unknown(request)
            val started = (request.dispatchedAtEpochMs
                ?: System.currentTimeMillis()).let { java.time.Instant.ofEpochMilli(it) }
                .minusSeconds(300)
            val windowEnd = minOf(
                java.time.Instant.now().plusSeconds(600),
                started.plusSeconds(3_900),
            )
            // 会话模式的探针域是会话列表（任务列表永远"查无此任务"会把一个
            // 可能已创建的会话误判为未创建，直接诱发重派 → 重复会话）。
            if (request.sessionMode) {
                val convs = runCatching { client.listConversations(search = request.title) }.getOrElse {
                    return DispatchOutcome.Unknown(request)
                }
                val matches = DispatchPipeline.findCreatedConversations(
                    convs, request.title, request.folderId, started, windowEnd,
                )
                if (matches.size == 1) {
                    val id = matches.single().id
                    // 批v150 P0: a probe-resolved session create arms the handoff
                    // too — the UI's jump may already be gone (this path runs after
                    // an ambiguous send), so the armed slot can linger; the
                    // staleness window in take() bounds it.
                    // 批v166: the ref's pinned mode/model picks replay here too —
                    // a probe-confirmed conversation must not open on the default.
                    return confirm(request, id, profile, null, false).also { outcome ->
                        if (outcome is DispatchOutcome.Confirmed) {
                            replaySessionSelectors(request, id)
                            sessionAutoStart.arm(
                                request.serverId, id, request.description, uploaded, inlineImages,
                            )
                        }
                    }
                }
            } else {
                val tasks = runCatching { client.workTaskList() }.getOrElse {
                    return DispatchOutcome.Unknown(request)
                }
                val matches = DispatchPipeline.findCreatedTasks(
                    tasks, request.title, request.folderId, started, windowEnd,
                )
                if (matches.size == 1) {
                    return confirm(request, matches.single().id, profile, request.scheduleLocalMillis, request.autoStart)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Ledger write or list failure: stay UNKNOWN — the card remains.
        }
        return DispatchOutcome.Unknown(request)
    }
}
