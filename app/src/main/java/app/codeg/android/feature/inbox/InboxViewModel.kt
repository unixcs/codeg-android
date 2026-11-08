package app.codeg.android.feature.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.AGG_DEVICE_TIMEOUT_MS
import app.codeg.android.core.data.AttachmentStager
import app.codeg.android.core.data.DispatchOutcome
import app.codeg.android.core.data.DispatchPipeline
import app.codeg.android.core.data.DispatchService
import app.codeg.android.core.data.DispatchTargetLoader
import app.codeg.android.core.data.DispatchTargets
import app.codeg.android.core.data.LocalAttachment
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.data.SessionAutoStart
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.datastore.DispatchRef
import app.codeg.android.core.datastore.DispatchState
import app.codeg.android.core.datastore.DispatchStore
import app.codeg.android.core.datastore.DraftStore
import app.codeg.android.core.datastore.IdeaEntry
import app.codeg.android.core.datastore.IdeaStore
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.network.ApiError
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.displayMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/**
 * The idea inbox + unified dispatch (batch v140). Ideas are local drafts; the
 * dispatch pipeline turns one into a server-native work_task and keeps a
 * request-id ledger so an ambiguous submit can never silently re-create tasks
 * (server has no idempotency key — PRD §7.5).
 */
@HiltViewModel
class InboxViewModel @Inject constructor(
    private val ideaStore: IdeaStore,
    private val dispatchStore: DispatchStore,
    private val dispatchService: DispatchService,
    private val targetLoader: DispatchTargetLoader,
    private val repository: ServerRepository,
    /** 批v155: the dispatch form's 模型 row probes agent modes through this. */
    private val agentOptionsProber: app.codeg.android.core.data.AgentOptionsProber,
    private val sessionAutoStart: SessionAutoStart,
    // 批v162 反向快链：「仅看板」筛选读看板成员账本。
    private val boardTrackStore: app.codeg.android.core.datastore.BoardTrackStore,
    /** 批v154 附件：SAF 选择 → 应用私有目录落盘（想法要跨重启/跨设备存活）。
     *  派发表单也通过这个接口自己收附件（同一套存储布局）。 */
    override val drafts: app.codeg.android.core.datastore.DraftStore,
    override val attachmentStager: AttachmentStager,
    private val appSettingsStore: app.codeg.android.core.datastore.AppSettingsStore,
) : ViewModel(), app.codeg.android.feature.dispatch.DispatchFormHost {

    /** App-wide settings — the dispatch form reads its preselected defaults
     *  (device/project/agent, 设置→通用→派发默认选项) from this flow. */
    override val settings = appSettingsStore.settings
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly,
            app.codeg.android.core.datastore.AppSettings.INITIAL)

    /** 批v160: persist the idea-list column count (1 or 2). */
    fun setInboxColumns(columns: Int) = viewModelScope.launch {
        appSettingsStore.setInboxColumns(columns)
    }

    data class UiState(
        val ideas: List<IdeaEntry> = emptyList(),
        /** Historical name: these are UNKNOWN refs awaiting resolution. */
        val pendingRefs: List<DispatchRef> = emptyList(),
        /** PENDING refs: request sent, receipt not yet observed (in flight or
         * left by a dead process until the age-gated sweep flips them). */
        val inFlightRefs: List<DispatchRef> = emptyList(),
        val confirmedRefs: List<DispatchRef> = emptyList(),
        val dispatching: Boolean = false,
        val dispatchError: String? = null,
        /** requestIds with a probe (回查) in flight, for card busy feedback. */
        val probingIds: Set<String> = emptySet(),
        /** requestIds whose LATEST probe came back "not created" — only these
         *  may re-dispatch (任务书 §5.4 未核实不能再次创建). */
        val probeClearedIds: Set<String> = emptySet(),
        /** Branch options for the selected target folder (§6.2 分支选择). */
        val branches: List<String> = emptyList(),
        val branchesLoading: Boolean = false,
        val branchesError: Boolean = false,
        /** serverId:folderPath key the current branch list belongs to — a stale
         *  list must never be offered for a different project. */
        val branchFolderKey: String? = null,
        /** serverId:ideaId of the last created task, for the success line. */
        val lastCreated: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _target = MutableStateFlow(DispatchTargets())
    val target: StateFlow<DispatchTargets> = _target.asStateFlow()

    // ---- DispatchFormHost (批v150 §1.5 宿主 A): form-facing projections of the
    // state this VM already owns. The ledger / probe machinery STAYS inbox-only.

    override val targets: StateFlow<DispatchTargets> get() = target

    /** The ledger's dispatching/error plus the branch state, as the shared form
     *  reads them. */
    override val formUi: StateFlow<app.codeg.android.feature.dispatch.DispatchFormUi> =
        ui.map { u ->
            app.codeg.android.feature.dispatch.DispatchFormUi(
                dispatching = u.dispatching,
                error = u.dispatchError,
                branches = u.branches,
                branchesLoading = u.branchesLoading,
                branchesError = u.branchesError,
                branchFolderKey = u.branchFolderKey,
            )
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly,
            app.codeg.android.feature.dispatch.DispatchFormUi())

    init {
        // Crash mid-flight sweep: a PENDING ledger entry means the request was
        // already sent but its outcome is unobservable — flip it to UNKNOWN so it
        // surfaces as a resolvable card instead of silently rotting (or being
        // blindly re-sent, which could double-create on the server). The sweep
        // itself is self-guarded in DispatchService; these outer nets keep any
        // OTHER DataStore/flow failure from killing the process (a bare init
        // coroutine crashing here took the whole Activity tab down, v130 class).
        viewModelScope.launch {
            try {
                dispatchService.sweepPendingToUnknown()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Sweep already logged its own failure path; nothing else to do.
            }
        }
        viewModelScope.launch {
            // Resubscribe loop: a DataStore failure must degrade to "empty for a
            // moment", not (a) crash, nor (b) a one-shot .catch that freezes the
            // inbox empty forever — an empty inbox invites blind re-dispatch of
            // an unresolved task (double create, PRD §7.5).
            while (currentCoroutineContext().isActive) {
                try {
                    combine(ideaStore.ideas, dispatchStore.refs, repository.visibleProfiles) { ideas, refs, profiles ->
                        Triple(ideas, refs, profiles)
                    }.collect { (ideas, refs, profiles) ->
                        _ui.value = _ui.value.copy(
                            ideas = ideas,
                            pendingRefs = refs.filter { it.state == DispatchState.UNKNOWN },
                            inFlightRefs = refs.filter { it.state == DispatchState.PENDING },
                            confirmedRefs = refs.filter { it.state == DispatchState.CONFIRMED }.take(20),
                        )
                        if (_target.value.profiles != profiles) {
                            _target.value = _target.value.copy(profiles = profiles)
                        }
                    }
                    break // completed normally (scope cancelling)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    kotlinx.coroutines.delay(2_000)
                }
            }
        }
    }

    init {
        // PENDING entries are only swept at VM construction; a ref left by a
        // killed process would sit invisible forever within a long session.
        viewModelScope.launch {
            while (currentCoroutineContext().isActive) {
                kotlinx.coroutines.delay(30_000)
                dispatchService.sweepPendingToUnknown()
            }
        }
    }

    fun saveIdea(
        id: String?,
        title: String,
        description: String,
        source: IdeaEntry? = null,
        /** 批v154: files staged by the editor (already copied to private storage). */
        attachments: List<LocalAttachment>? = null,
    ) {
        val existing = source ?: id?.let { key -> _ui.value.ideas.firstOrNull { it.id == key } }
        if (title.isBlank() && description.isBlank() && attachments.isNullOrEmpty() &&
            existing?.attachments.isNullOrEmpty() != false
        ) {
            return
        }
        val now = System.currentTimeMillis()
        // Removing an attachment from the editor must free its private copy too;
        // compute the dropped set before the entry is rebuilt.
        val kept = attachments ?: existing?.attachments.orEmpty()
        val dropped = existing?.attachments.orEmpty().filter { old -> kept.none { it.id == old.id } }
        val entry = existing
            ?.copy(title = title.trim(), description = description.trim(), updatedAt = now, attachments = kept)
            ?: IdeaEntry(
                id = UUID.randomUUID().toString(),
                title = title.trim(),
                description = description.trim(),
                createdAt = now,
                updatedAt = now,
                sourceServerId = null,
                attachments = kept,
            )
        viewModelScope.launch {
            try {
                ideaStore.upsert(entry)
                // Only after the row is durable — a failed save must not delete
                // bytes a still-persisted entry points at.
                dropped.forEach(attachmentStager::discard)
                // 批v164: a NEW idea made it to the inbox — its composer draft has
                // served its purpose. Edit-mode saves don't touch the draft: it
                // belongs to the next abandoned 新想法, not to this row.
                if (existing == null) clearIdeaDraft()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _ui.value = _ui.value.copy(dispatchError = "想法保存失败（本地存储异常）")
            }
        }
    }

    // ---- 批v164: 新想法 composer draft memory (same recipe as the dispatch
    // form: DataStore-backed, restored on reopen, persisted on a 500ms debounce,
    // cleared only once the idea is durably in the inbox) ----

    /** 批v164 审查R2: once the idea from the CURRENT editor session is durably
     *  saved (保存 or 一键插待办), further debounced writes are stale — the
     *  dialog can stay open for minutes on an UNKNOWN dispatch, and a late
     *  keystroke or a rotation-restarted saver would otherwise resurrect the
     *  cleared draft as the NEXT 新想法's prefill. DispatchForm's draftClosed
     *  gate, VM-side so the composable can't forget it. */
    private var ideaDraftSuperseded = false

    suspend fun loadIdeaDraft(): app.codeg.android.core.datastore.DraftEntry {
        // A fresh editor session may write drafts again.
        ideaDraftSuperseded = false
        return runCatching { drafts.draftNow(DraftStore.ideaKey(IDEA_DRAFT_KEY)) }
            .getOrDefault(app.codeg.android.core.datastore.DraftEntry())
    }

    fun saveIdeaDraft(title: String, description: String) {
        if (ideaDraftSuperseded) return
        viewModelScope.launch {
            runCatching {
                if (title.isBlank() && description.isBlank()) {
                    drafts.clearDraft(DraftStore.ideaKey(IDEA_DRAFT_KEY))
                } else {
                    drafts.setDraft(DraftStore.ideaKey(IDEA_DRAFT_KEY), title, description)
                }
            }
        }
    }

    private fun clearIdeaDraft() {
        ideaDraftSuperseded = true
        viewModelScope.launch {
            runCatching { drafts.clearDraft(DraftStore.ideaKey(IDEA_DRAFT_KEY)) }
        }
    }

    /** Seed a new idea from a conversation (traceability only). */
    fun newIdeaFrom(serverId: String, projectId: Int, sessionId: String): IdeaEntry =
        IdeaEntry(
            id = UUID.randomUUID().toString(),
            title = "",
            description = "",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            sourceServerId = serverId,
            sourceProjectId = projectId,
            sourceSessionId = sessionId,
        )

    /**
     * 批v155 想法一键插待办: persist the draft FIRST (an UNKNOWN outcome binds
     * its resolution card to this idea id), then run the SAME dispatch pipeline
     * the idea card's 派发 button uses — task mode, no auto-start (the created
     * task parks in 待办), no schedule. On CONFIRMED the pipeline frees the
     * draft with its attachments, exactly like a card dispatch — the idea never
     * double-lives as a card AND a todo task.
     */
    fun quickInsertTodo(
        ideaId: String,
        title: String,
        description: String,
        attachments: List<LocalAttachment>,
        serverId: String,
        folderId: Int,
        folderName: String,
        agentType: AgentType?,
        modeId: String?,
        /** 批v166: 模型/推理强度 picks — rides config_values (the shared rows). */
        configValues: Map<String, String> = emptyMap(),
        /** 批v164 审查R1: only a NEW idea's composer draft is retired by an
         *  insert. Editing an existing idea and inserting it must not wipe the
         *  unrelated half-typed 新想法 draft. */
        clearComposerDraft: Boolean,
        onDone: (DispatchRef) -> Unit,
    ) {
        if (_ui.value.dispatching) {
            _ui.value = _ui.value.copy(dispatchError = "正在派发，请稍候")
            return
        }
        viewModelScope.launch {
            // dispatch() rejects a blank title; an untitled idea derives one from
            // the description so one-tap insert never dead-ends. cap() (not take)
            // — a raw take(40) can split an emoji surrogate pair in half.
            val trimmedTitle = title.trim().ifBlank {
                description.trim().lineSequence().firstOrNull { it.isNotBlank() }
                    ?.let { app.codeg.android.core.data.DispatchService.cap(it, 40).trim() }
                    .orEmpty()
            }
            val now = System.currentTimeMillis()
            // 批v155(R2C#5): reusing an existing idea row (editor opened from that
            // idea) must not wipe its provenance/createdAt — upsert replaces the
            // row wholesale, so carry the origin fields over. R4 P3-1: the
            // in-memory list may not have loaded yet — fall back to the store.
            val prior = _ui.value.ideas.firstOrNull { it.id == ideaId }
                ?: runCatching { ideaStore.ideas.first().firstOrNull { it.id == ideaId } }.getOrNull()
            val entry = IdeaEntry(
                id = ideaId,
                title = trimmedTitle,
                description = description.trim(),
                createdAt = prior?.createdAt ?: now,
                updatedAt = now,
                sourceServerId = prior?.sourceServerId,
                sourceProjectId = prior?.sourceProjectId,
                sourceSessionId = prior?.sourceSessionId,
                attachments = attachments,
            )
            try {
                ideaStore.upsert(entry)
                // 批v164: the idea row is durable — the composer draft that fed
                // it must not resurface in the next 新想法. (The insert may still
                // end UNKNOWN/HardFailed downstream, but the text is safe in the
                // inbox either way.)
                if (clearComposerDraft) clearIdeaDraft()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _ui.value = _ui.value.copy(dispatchError = "想法保存失败（本地存储异常），未派发")
                return@launch
            }
            dispatch(
                idea = entry,
                serverId = serverId,
                folderId = folderId,
                folderName = folderName,
                agentType = agentType,
                title = entry.title,
                description = entry.description,
                baseBranch = null,
                modeId = modeId,
                configValues = configValues,
                scheduleLocalMillis = null,
                onDone = onDone,
                autoStart = false,
                sessionMode = false,
                // 批v155(R2C#1 P1): without this the staged files were silently
                // destroyed on CONFIRMED (ideaStore.delete + discard) without
                // ever being uploaded — the task carried no file paths either.
                attachments = attachments,
            )
        }
    }

    fun deleteIdea(id: String) = viewModelScope.launch {
        val doomed = _ui.value.ideas.firstOrNull { it.id == id }
        try {
            ideaStore.delete(id)
            // 批v154: the row is gone, so its private copies are unreachable.
            doomed?.attachments?.forEach(attachmentStager::discard)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Delete failed: the idea stays; harmless retryable.
        }
    }

    /** 批v160 multi-select batch delete: one store rewrite, then the same
     *  attachment-discard rule as [deleteIdea] for every removed row. */
    fun deleteIdeas(ids: Collection<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val doomed = ids.mapNotNull { id -> _ui.value.ideas.firstOrNull { it.id == id } }
            try {
                ideaStore.removeIds(ids)
                doomed.forEach { idea -> idea.attachments.forEach(attachmentStager::discard) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Delete failed: the ideas stay; harmless retryable.
            }
        }
    }

    fun clearDispatchError() { _ui.value = _ui.value.copy(dispatchError = null) }

    // region Branch loading (ui重构 §6.2 — 分支选择, real upstream capability:
    // git_list_all_branches + WorkTaskConfig.base_branch. No list → no field.)

    private var branchSeq = 0

    /** Branches for [folder]'s repo path; failure keeps the form usable with
     *  the project default (null base branch), never blocks dispatch. */
    override fun loadBranches(serverId: String, folder: app.codeg.android.core.model.FolderDetail) {
        val seq = ++branchSeq
        val key = "$serverId:${folder.path}"
        _ui.value = _ui.value.copy(branchesLoading = true, branchesError = false, branchFolderKey = key)
        viewModelScope.launch {
            val profile = repository.profiles.first().firstOrNull { it.id == serverId }
            val client = profile?.let { repository.client(it) }
            val list = client?.let { c ->
                runCatching { c.gitListAllBranches(folder.path) }
                    .onFailure { e -> android.util.Log.w("InboxViewModel", "git_list_all_branches failed: ${e.message}") }
                    .getOrNull()
            }
            if (seq != branchSeq) return@launch
            _ui.value = if (list == null) {
                _ui.value.copy(branchesLoading = false, branchesError = true, branches = emptyList())
            } else {
                // Local first (what the user usually means), remotes after,
                // deduped. Worktree branches excluded: they are checked out in
                // another worktree — a poor base-branch default.
                _ui.value.copy(
                    branchesLoading = false,
                    branchesError = false,
                    branches = (list.local + list.remote).distinct(),
                )
            }
        }
    }

    fun clearBranches() {
        branchSeq++
        _ui.value = _ui.value.copy(branches = emptyList(), branchesLoading = false, branchesError = false, branchFolderKey = null)
    }

    // endregion

    /** 批v155: the shared form's 模型 row — cache-first probe, shared store. */
    override fun setTaskAutoStartDefault(value: Boolean) {
        viewModelScope.launch { appSettingsStore.setTaskAutoStartDefault(value) }
    }

    override suspend fun probeAgentOptions(
        serverId: String,
        agentType: app.codeg.android.core.model.AgentType,
        workingDir: String?,
        configValues: Map<String, String>,
    ): app.codeg.android.core.model.AgentOptionsSnapshot? =
        agentOptionsProber.probe(serverId, agentType, workingDir, configValues)

    override suspend fun modelProviderEntries(
        serverId: String,
        agentType: AgentType,
    ): List<app.codeg.android.core.model.ModelProviderInfo> =
        agentOptionsProber.modelProviderEntries(serverId, agentType)

    // region Dispatch


    /** Precheck + load the target device's projects and agents (PRD §7.3). */
    private var targetSeq = 0

    override fun loadTarget(serverId: String) {
        val seq = ++targetSeq
        viewModelScope.launch {
            _target.value = _target.value.copy(folders = emptyList(), agents = emptyList(), foldersLoading = true, error = null)
            val loaded = targetLoader.load(serverId)
            // A slow response for a previously selected device must never
            // overwrite the current device's folder/agent lists. profiles stay
            // owned by the combine collector — never copied from the loader.
            if (seq != targetSeq) return@launch
            _target.value = _target.value.copy(
                folders = loaded.folders,
                agents = loaded.agents,
                foldersLoading = loaded.foldersLoading,
                error = loaded.error,
                // 批v155(R2C#3): without these the form renders a failed
                // list_folders / acp_list_agents as a silent "无项目/无 Agent"
                // — no error row, no retry, no warning tick on the device chip.
                agentsError = loaded.agentsError,
                foldersError = loaded.foldersError,
                loadedForServerId = serverId,
            )
        }
    }

    fun dispatch(
        idea: IdeaEntry?,
        serverId: String,
        folderId: Int,
        folderName: String,
        agentType: AgentType?,
        title: String,
        description: String,
        baseBranch: String? = null,
        /** 批v155: the form's 模型 row (agent mode), list-validated upstream. */
        modeId: String? = null,
        scheduleLocalMillis: Long?,
        onDone: (ref: DispatchRef) -> Unit,
        /** Immediate dispatches start the task after create (idea path). */
        autoStart: Boolean = true,
        /** 批v149 会话模式：目标是直接创建会话（remoteTodoId=会话 id），
         * 定时/自动开始不适用；成功后 UI 侧负责跳转会话并发送首条指令。 */
        sessionMode: Boolean = false,
        /** On a hard (non-ambiguous) failure, put this ref back as UNKNOWN — used by
         * redispatch so a failed re-send never destroys the only trace of the
         * original ambiguous request. */
        restoreOnHardFail: DispatchRef? = null,
        /** 批v154: files to ride along (uploaded on the target device before create). */
        attachments: List<LocalAttachment> = emptyList(),
        /** P0-2: model / reasoning-effort selections from the shared form. */
        configValues: Map<String, String> = emptyMap(),
        /** 批v162 P1: session mode — put the created conversation on the board. */
        addToBoard: Boolean = false,
    ) {
        if (_ui.value.dispatching) {
            // A redispatch whose old ref was ALREADY removed must not lose it on
            // a rejected entry — restore the sole trace of the original request.
            restoreOnHardFail?.let { old ->
                viewModelScope.launch { runCatching { dispatchStore.put(old.copy(state = DispatchState.UNKNOWN)) } }
            }
            _ui.value = _ui.value.copy(dispatchError = "正在派发，请稍候")
            return
        }
        if (title.isBlank()) {
            restoreOnHardFail?.let { old ->
                viewModelScope.launch { runCatching { dispatchStore.put(old.copy(state = DispatchState.UNKNOWN)) } }
            }
            _ui.value = _ui.value.copy(dispatchError = "标题不能为空")
            return
        }
        // Set synchronously (not inside the coroutine) so rapid double-taps can
        // never both pass the guard above.
        _ui.value = _ui.value.copy(dispatching = true, dispatchError = null, lastCreated = null)
        viewModelScope.launch {
            try {
                // Ledger gate: an unresolved (PENDING/UNKNOWN) ref with the same
                // target+title means a previous request's fate is unconfirmed —
                // creating again is the classic double-create (PRD §7.5).
                if (dispatchService.hasUnresolvedDuplicate(serverId, folderId, title)) {
                    fail("存在同目标「结果未知」的派发记录，请先在派发记录中检查/放弃后再派发，避免重复创建")
                    return@launch
                }
                // A schedule time that already passed can't be honored — the
                // sheet's 30s-ticker guard has a stale window; normalize here so
                // the server never receives a past scheduledAt (it would run
                // immediately — the exact behavior the guard warns against).
                val effectiveSchedule = scheduleLocalMillis?.takeIf { it > System.currentTimeMillis() }
                // Same stale-selection check the handoff sheet has: the folder
                // list reloads when the device changes; a restored sheet state
                // could carry another device's folder id.
                val folders = _target.value.folders
                if (folders.isNotEmpty() && folders.none { it.id == folderId }) {
                    fail("目标项目已失效，请重新选择项目后再派发")
                    return@launch
                }
                val outcome = dispatchService.send(
                    DispatchService.SendRequest(
                        ideaId = idea?.id,
                        serverId = serverId,
                        folderId = folderId,
                        folderName = folderName,
                        title = title,
                        description = description,
                        agentTypeWire = agentType?.wire,
                        baseBranch = baseBranch,
                        modeId = modeId,
                        scheduleLocalMillis = effectiveSchedule,
                        autoStart = autoStart,
                        sessionMode = sessionMode,
                        addToBoard = addToBoard,
                        restoreOnHardFail = restoreOnHardFail,
                        attachments = attachments,
                        configValues = configValues,
                    ),
                )
                when (outcome) {
                    is DispatchOutcome.Confirmed -> {
                        // 任务书 §5.4 step 9: the server CONFIRMED the create —
                        // only now may the local draft leave the inbox. A failed
                        // local delete must NOT fail the dispatch (the task
                        // exists); it surfaces as a note warning against
                        // re-dispatching the same idea.
                        var note = outcome.scheduleNote
                        if (idea != null) {
                            try {
                                ideaStore.delete(idea.id)
                                // 批v154: the files now live on the target device —
                                // free the private copies with their only owner.
                                idea.attachments.forEach(attachmentStager::discard)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                note = listOfNotNull(
                                    note,
                                    "任务已创建，但本地想法删除失败——请勿再次派发该想法，可手动删除",
                                ).joinToString("；")
                            }
                        }
                        _ui.value = _ui.value.copy(
                            dispatching = false,
                            lastCreated = "${outcome.ref.serverId}:${outcome.ref.remoteTodoId}",
                            dispatchError = note,
                        )
                        onDone(outcome.ref)
                    }
                    is DispatchOutcome.Unknown -> _ui.value = _ui.value.copy(
                        dispatching = false,
                        dispatchError = "提交结果未知",
                    )
                    is DispatchOutcome.HardFailed -> fail(outcome.message)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // VM cleared mid-send: the request may be on the wire. Put the
                // redispatched trace back (NonCancellable) before dying — no
                // card would otherwise exist for a possibly-created task.
                restoreOnHardFail?.let { old ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        runCatching { dispatchStore.put(old.copy(state = DispatchState.UNKNOWN)) }
                    }
                }
                throw e
            } catch (e: Exception) {
                // Last-resort guard: a DataStore IO failure inside send() must
                // never crash the process — degrade to a dispatch error. If this
                // was a redispatch, send() may have died BEFORE consuming
                // restoreOnHardFail — put the original UNKNOWN trace back so the
                // only evidence of the ambiguous request is not lost.
                restoreOnHardFail?.let { old ->
                    runCatching { dispatchStore.put(old.copy(state = DispatchState.UNKNOWN)) }
                }
                fail(e.message ?: "派发失败")
            }
        }
    }

    /** DispatchFormHost entry (批v150 §1.5 宿主 A): the shared form submits
     *  through here; the ledger pipeline below (idea resolution, dup gate,
     *  UNKNOWN semantics) remains the single source of truth. */
    override fun dispatch(
        submit: app.codeg.android.feature.dispatch.DispatchFormSubmit,
        onDone: (DispatchRef) -> Unit,
    ) {
        dispatch(
            idea = submit.ideaId?.let { id -> _ui.value.ideas.firstOrNull { it.id == id } },
            serverId = submit.serverId,
            folderId = submit.folderId,
            folderName = submit.folderName,
            agentType = submit.agentType,
            title = submit.title,
            description = submit.description,
            baseBranch = submit.baseBranch,
            modeId = submit.modeId,
            scheduleLocalMillis = submit.scheduleLocalMillis,
            onDone = onDone,
            autoStart = submit.autoStart,
            sessionMode = submit.sessionMode,
            // 批v154: files picked in the form ride along; the pipeline uploads
            // them to the target device before creating anything.
            attachments = submit.attachments,
            configValues = submit.configValues,
            addToBoard = submit.addToBoard,
        )
    }

    /** requestIds with a probe in flight, so the button can't fire concurrently. */
    private val probing = mutableSetOf<String>()

    /**
     * Resolve an UNKNOWN request by listing the server's tasks and looking for the
     * one we may have created (title + folder + time window). Found → CONFIRMED;
     * a failed check keeps the ref UNKNOWN and says so — it must never be
     * presented as "not found", which would invite a double create.
     */
    fun probe(requestId: String) {
        synchronized(probing) { if (!probing.add(requestId)) return }
        _ui.value = _ui.value.copy(probingIds = _ui.value.probingIds + requestId)
        // Only a completed probe that returned EXACTLY "not created" grants the
        // re-dispatch right (§5.4); every other outcome — failure, ambiguity,
        // confirmed-exists — revokes it, so a stale clearance can never outlive
        // a failing check.
        var verifiedNotCreated = false
        viewModelScope.launch {
            try {
                val ref = dispatchStore.refs.first().firstOrNull { it.requestId == requestId } ?: return@launch
                if (ref.state == DispatchState.PENDING) {
                    // 提交还在途（第1轮派发审查修正）：此刻拉列表永远"查无此任务"，
                    // 会误发"未创建"诱发双建——探针只对 UNKNOWN 有意义（§5.4）。
                    _ui.value = _ui.value.copy(dispatchError = "派发仍在提交中，请稍候再检查")
                    return@launch
                }
                val client = repository.profiles.first()
                    .firstOrNull { it.id == ref.serverId }?.let { repository.client(it) }
                if (client == null) {
                    _ui.value = _ui.value.copy(dispatchError = "设备不可用，无法检查")
                    return@launch
                }
                val started = (ref.dispatchedAtEpochMs?.let { Instant.ofEpochMilli(it) }
                    ?: Instant.now().minusSeconds(600))
                    // The window compares the PHONE's send moment against the SERVER's
                    // createdAt: allow for clock skew so a slightly slow server clock
                    // can't push the real task below the window (false negative →
                    // double create).
                    .minusSeconds(300)
                // End of window: symmetric clock slack (+10min) against a FAST
                // server clock, but capped relative to the dispatch moment —
                // an unbounded "now+10min" would keep absorbing unrelated
                // same-titled tasks forever as the probe gets later.
                val refDispatched = ref.dispatchedAtEpochMs?.let { Instant.ofEpochMilli(it) } ?: started
                val windowEnd = minOf(
                    Instant.now().plusSeconds(600),
                    refDispatched.plusSeconds(3_600),
                )
                // 批v149：会话模式的探针域是会话列表——任务列表永远"查无此任务"，
                // 会把一个可能已创建的会话误判为未创建，直接诱发重派 → 重复会话。
                data class ProbeHits(val ids: List<Int>, val sameTitleAnywhere: Boolean)
                val hits = if (ref.sessionMode) {
                    runCatching { client.listConversations(search = ref.title) }.getOrElse {
                        _ui.value = _ui.value.copy(dispatchError = "检查失败，请稍后重试（结果未知，勿直接重派）")
                        return@launch
                    }.let { convs ->
                        ProbeHits(
                            DispatchPipeline.findCreatedConversations(
                                convs, ref.title, ref.folderId, started, windowEnd,
                            ).map { it.id },
                            convs.any { it.title == ref.title && it.folderId == ref.folderId },
                        )
                    }
                } else {
                    runCatching { client.workTaskList() }.getOrElse {
                        _ui.value = _ui.value.copy(dispatchError = "检查失败，请稍后重试（结果未知，勿直接重派）")
                        return@launch
                    }.let { tasks ->
                        ProbeHits(
                            DispatchPipeline.findCreatedTasks(
                                tasks, ref.title, ref.folderId, started, windowEnd,
                            ).map { it.id },
                            tasks.any { it.title == ref.title && it.folderId == ref.folderId },
                        )
                    }
                }
                val noun = if (ref.sessionMode) "会话" else "任务"
                if (hits.ids.size == 1) {
                    val matchId = hits.ids.single()
                    // The ref may have been discarded while the probe was in
                    // flight — write back only onto a live row, never resurrect
                    // a tombstone (same rule as DispatchService.confirm).
                    val stillLive = dispatchStore.refs.first().any { it.requestId == requestId }
                    if (stillLive) {
                        dispatchStore.put(ref.copy(remoteTodoId = matchId, state = DispatchState.CONFIRMED))
                        if (ref.sessionMode) {
                            // 探针确认的会话同样武装自动起跑（第1轮派发审查修正）：
                            // 与 DispatchService 的两条确认路同权——否则跳转时机已
                            // 过，用户得去设备里手动翻这条刚确认的会话。
                            // 批v155(R2C#2): the uploaded remote paths ride the
                            // ledger ref — arm WITHOUT them and the first prompt
                            // never mentions files that are already on the device.
                            // 批v166: the ref's pinned mode/model picks replay too
                            // (shared with DispatchService's two confirm paths).
                            dispatchService.replaySessionSelectors(ref, matchId)
                            sessionAutoStart.arm(
                                ref.serverId,
                                matchId,
                                ref.description,
                                ref.uploadedPaths.map {
                                    app.codeg.android.core.data.UploadedAttachment(
                                        name = it.substringAfterLast('/'),
                                        absolutePath = it,
                                    )
                                },
                            )
                        }
                    }
                    _ui.value = _ui.value.copy(
                        dispatchError = null,
                        lastCreated = "${ref.serverId}:$matchId",
                        // Resolved as created — definitely not clear to re-send.
                        probeClearedIds = _ui.value.probeClearedIds - requestId,
                    )
                } else if (hits.ids.size > 1) {
                    // Multiple same-titled candidates: claiming one risks binding
                    // the wrong id. Stay UNKNOWN — and do NOT say "not found",
                    // which invites a re-dispatch (double create). The task
                    // almost certainly EXISTS.
                    _ui.value = _ui.value.copy(
                        dispatchError = "服务端找到 ${hits.ids.size} 个同名$noun，无法确认归属；请到设备上人工核对后再处理，未确认前勿重新派发",
                    )
                } else {
                    // Exactly 0 in-window matches is NOT yet "not created": the
                    // task may exist outside the window (slow server write,
                    // clock drift). Only when the whole list has no same-titled
                    // task in this folder is a re-dispatch safe (§5.4).
                    if (hits.sameTitleAnywhere) {
                        _ui.value = _ui.value.copy(
                            dispatchError = "服务端发现同名$noun（创建时间不在派发窗口内），无法确认是否本条；未核实前勿重新派发",
                        )
                    } else {
                        // Verified "not created" — this ref alone earns the
                        // re-dispatch right (任务书 §5.4 未核实不能再次创建).
                        verifiedNotCreated = true
                        _ui.value = _ui.value.copy(
                            dispatchError = "服务端未找到该$noun，可重新派发或放弃",
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Ledger read or confirm-write failed: stay UNKNOWN and say so —
                // never let a bare coroutine crash the process, never report
                // "not found" on an unverified check.
                _ui.value = _ui.value.copy(dispatchError = "检查失败，请稍后重试（结果未知，勿直接重派）")
            } finally {
                synchronized(probing) { probing.remove(requestId) }
                _ui.value = _ui.value.copy(
                    probingIds = _ui.value.probingIds - requestId,
                    probeClearedIds = if (verifiedNotCreated) _ui.value.probeClearedIds + requestId
                    else _ui.value.probeClearedIds - requestId,
                )
            }
        }
    }

    /** Give up on an UNKNOWN request (the user checked the server themselves). */
    fun discard(requestId: String) {
        viewModelScope.launch {
            try {
                dispatchStore.remove(requestId)
                _ui.value = _ui.value.copy(dispatchError = null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _ui.value = _ui.value.copy(dispatchError = "放弃失败（本地存储异常），记录保留")
            }
        }
    }

    /** Re-send after the user explicitly resolved an UNKNOWN as not-created. */
    fun redispatch(ref: DispatchRef, scheduleLocalMillis: Long?) {
        if (_ui.value.dispatching) {
            _ui.value = _ui.value.copy(dispatchError = "正在派发，请稍候")
            return
        }
        // Defense in depth beyond the UI gate: without a fresh "not found"
        // probe this dispatch's fate is unverified — re-sending could
        // double-create. The user must run 检查服务器 first (§5.4).
        if (ref.requestId !in _ui.value.probeClearedIds) {
            _ui.value = _ui.value.copy(dispatchError = "请先「检查服务器」确认任务未创建，再重新派发")
            return
        }
        viewModelScope.launch {
            // Remove the old ledger entry only after the guard, so a rejected
            // re-dispatch can never drop the only trace of an unresolved request.
            // A failed remove aborts the re-send entirely: dispatch() would
            // create a SECOND task while the original trace is still UNKNOWN.
            try {
                dispatchStore.remove(ref.requestId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _ui.value = _ui.value.copy(dispatchError = "本地记录更新失败，未重新派发（结果未知，勿手动重试创建）")
                return@launch
            }
            // Re-send exactly what was sent the first time (the idea may have been
            // edited since; the ledger snapshot is what the ambiguous request carried).
            dispatch(
                idea = ref.ideaId?.let { id -> _ui.value.ideas.firstOrNull { it.id == id } },
                serverId = ref.serverId,
                folderId = ref.folderId,
                folderName = ref.folderName,
                autoStart = ref.autoStart,
                // fromWire maps unknown wires to UNKNOWN — re-sending that would
                // create a task on the default agent instead of the chosen one.
                agentType = ref.agentTypeWire?.let { AgentType.fromWire(it) }?.takeIf { it != AgentType.UNKNOWN },
                title = ref.title,
                description = ref.description,
                // Preserve the original schedule intent — but a timestamp already
                // in the past can't be honored by re-sending it; drop it so the
                // re-dispatch runs immediately instead of landing in a dead slot.
                scheduleLocalMillis = ref.scheduleLocalMillis
                    ?.takeIf { it > System.currentTimeMillis() }
                    ?: scheduleLocalMillis,
                // 批v166: the ledger snapshot now carries the pinned mode + model
                // picks — re-sending without them would silently revert the task
                // (or session) to the agent default model.
                modeId = ref.modeId,
                configValues = ref.configValues,
                // 会话型未知单重派必须仍是会话——否则一条"可能已建会话"的
                // 记录会在任务板上再落一个任务（跨模式重复创建）。
                sessionMode = ref.sessionMode,
                onDone = {},
                restoreOnHardFail = ref,
                // 批v154: the ledger snapshot's local copies are the only way to
                // re-upload the same files — the target-side paths from the first
                // attempt belong to a device we can't assume anything about.
                attachments = ref.attachments.filter { java.io.File(it.localPath).canRead() },
            )
        }
    }

    private fun fail(message: String) {
        _ui.value = _ui.value.copy(dispatching = false, dispatchError = message)
    }

    // endregion

    // region 批v162 反向快链：想法 → 追加到已有会话

    /**
     * 选择器的状态（与派发账本的 dispatching 互不干扰——追加不是创建，
     * 不走 hasUnresolvedDuplicate 闸门）。error 只属于追加执行，加载失败走 failed。
     */
    data class PickerUi(
        val loading: Boolean = false,
        /** 每台设备都失败且零行——给一次点击重试。 */
        val failed: Boolean = false,
        val rows: List<SessionPickerRow> = emptyList(),
        /** 看板成员 key（serverId:conversationId，未归档）——「仅看板」筛选用。 */
        val trackedKeys: Set<String> = emptySet(),
        /** 追加发送在途（上传附件/武装指令）。 */
        val sending: Boolean = false,
        /** 上一次追加执行的失败原因（成功后清空）。 */
        val error: String? = null,
    )

    private val _picker = MutableStateFlow(PickerUi())
    val picker: StateFlow<PickerUi> = _picker.asStateFlow()

    /** 追加批次代号：一个迟到的设备切片不许覆盖新一轮。 */
    private var pickerSeq = 0

    /**
     * 选择器数据：每台可见设备一轮 listConversations(sortBy="updated")（聚合
     * 超时族、单设备失败隔离），合并按 updatedAt 倒序取前 [PICKER_LIMIT]。
     * 一次性拉取——选择器是挑目标，不是浏览器，不做轮询；每次打开重拉一轮。
     */
    fun loadPickerSessions() {
        val seq = ++pickerSeq
        _picker.value = _picker.value.copy(loading = true, failed = false)
        viewModelScope.launch {
            val profiles = runCatching { repository.visibleProfiles.first() }.getOrDefault(emptyList())
            // 看板成员账本一次读：读坏了「仅看板」退化为空集（筛不出东西），不拖死选择器。
            val trackedKeys = runCatching {
                boardTrackStore.trackedNow().orEmpty().filter { it.archivedAt == null }.map { it.key }.toSet()
            }.getOrDefault(emptySet())
            if (seq != pickerSeq) return@launch
            val merged = mutableListOf<SessionPickerRow>()
            var anySuccess = false
            try {
                coroutineScope {
                    val slices = profiles.map { profile -> async { fetchPickerSlice(profile) } }
                    slices.forEach { slice ->
                        slice.await()?.let { rows -> anySuccess = true; merged += rows }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Scope 级失败：本轮按无数据处理（failed 已由结果兜底）。
            }
            if (seq != pickerSeq) return@launch
            _picker.value = PickerUi(
                loading = false,
                failed = !anySuccess && merged.isEmpty(),
                rows = merged.sortedByDescending { it.updatedAt }.take(PICKER_LIMIT),
                trackedKeys = trackedKeys,
            )
        }
    }

    /** 一台设备的会话切片；null = 拉取失败/超时（区别于"成功但零行"）。 */
    private suspend fun fetchPickerSlice(profile: ServerProfile): List<SessionPickerRow>? = runCatching {
        val client = repository.client(profile) ?: error("token missing")
        withTimeoutOrNull(AGG_DEVICE_TIMEOUT_MS) {
            val convs = client.listConversations(sortBy = "updated").take(PICKER_PER_DEVICE)
            // 项目名尽力而为：listFolders 挂了只掉项目标签，不掉整台设备的行。
            val folders = runCatching { client.listFolders() }.getOrDefault(emptyList())
                .associate { it.id to it.name }
            convs.map { c ->
                SessionPickerRow(
                    serverId = profile.id,
                    serverName = profile.name,
                    conversationId = c.id,
                    title = c.trimmedTitle ?: "#${c.id}",
                    agent = c.agentType,
                    folderId = c.folderId,
                    folderName = folders[c.folderId],
                    updatedAt = c.updatedAt,
                    // 选择器的圆点用会话状态旗标即可：不做活跃连接探针（那是
                    // Activity 轮询的事，一次拉取多打一轮 RPC 不值）。
                    live = c.status.isLive,
                )
            }
        } ?: error("device timeout")
    }.getOrNull()

    /**
     * 反向执行链（选中目标会话后）：上传非图片附件 → 图片降采样内联 →
     * 构建首条指令 → SessionAutoStart.arm → 记 DispatchRef。调用方在
     * onSent 里关单并跳转目标会话（跳转后 SessionDetailViewModel 消费
     * armed 指令自动发送）。
     *
     * 不变量与派发同款：先附件后发送，任何附件失败=干净失败（没 arm、
     * 没账本、没跳转）；账本写失败不回滚发送（指令已武装，留痕是尽力而为）。
     * 思想法保留在收件箱（拷贝+留痕，不是搬走），卡片出「已派进会话」小标。
     */
    fun sendIntoSession(idea: IdeaEntry, target: SessionPickerRow, onSent: () -> Unit) {
        if (_picker.value.sending) return
        val prompt = IdeaPromptBuilder.compose(idea.title, idea.description)
        if (prompt.isBlank() && idea.attachments.isEmpty()) {
            _picker.value = _picker.value.copy(error = "这条想法没有可追加的内容")
            return
        }
        _picker.value = _picker.value.copy(sending = true, error = null)
        viewModelScope.launch {
            val ok = try {
                val profile = repository.profiles.first().firstOrNull { it.id == target.serverId }
                    ?: error("目标设备不存在")
                val client = repository.client(profile) ?: error("设备不可用或 Token 缺失")
                // 目标会话的项目路径（附件上传落点）：会话 id 反查 folder，
                // 再取 path——与派发服务 resolve 根目录同一件事，只是入口是会话。
                val detail = client.conversationDetail(target.conversationId)
                val folders = runCatching { client.listFolders() }.getOrDefault(emptyList())
                val folder = folders.firstOrNull { it.id == detail.summary.folderId }
                // 图片：内联（降采样，聚合 ≤ 2MiB prompt 上限的 1.3MB 预算）——
                // 与派发会话模式同款语义，绝不往用户仓库里塞图片副本。
                val images = idea.attachments.filter { it.isImage }
                val inlineImages = prepareInlineImages(images)
                // 非图片：工作区上传（DispatchService 同一条管线，409 语义同款）。
                val files = idea.attachments.filterNot { it.isImage }
                val uploaded = if (files.isEmpty()) {
                    emptyList()
                } else {
                    val root = folder?.path ?: error("目标会话的项目路径未找到，附件未上传")
                    dispatchService.uploadToWorkspace(client, root, dispatchService.newStageDir(), files)
                }
                // 空 prompt + 只有文件：arm 自带空 payload 防线（文件也算有效载荷）。
                sessionAutoStart.arm(target.serverId, target.conversationId, prompt, uploaded, inlineImages)
                // 留痕：会话已存在，无创建歧义，直接 CONFIRMED（remoteTodoId=会话 id，
                // sessionMode 让这笔账永远按会话解读）。账本写失败不拦发送。
                runCatching {
                    dispatchStore.put(
                        DispatchRef(
                            requestId = java.util.UUID.randomUUID().toString(),
                            ideaId = idea.id,
                            serverId = target.serverId,
                            serverName = target.serverName,
                            folderId = folder?.id ?: 0,
                            folderName = folder?.name.orEmpty(),
                            title = DispatchService.cap(ledgerTitle(idea), 120),
                            remoteTodoId = target.conversationId,
                            state = DispatchState.CONFIRMED,
                            createdAt = System.currentTimeMillis(),
                            description = idea.description,
                            sessionMode = true,
                            attachments = idea.attachments,
                            uploadedPaths = uploaded.map { it.absolutePath },
                        ),
                    )
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _picker.value = _picker.value.copy(sending = false, error = "追加失败：" + (e.message ?: "未知错误"))
                false
            }
            if (!ok) return@launch
            _picker.value = _picker.value.copy(sending = false, error = null)
            onSent()
        }
    }

    /**
     * 想法图片 → 内联块。聚合预算与 composer 同一条公式（批越大单张目标越小）；
     * 任一张读不出/压不进预算即抛——半套附件的"追加"是谎言（干净失败）。
     */
    private suspend fun prepareInlineImages(
        images: List<LocalAttachment>,
    ): List<SessionAutoStart.InlineImage> {
        if (images.isEmpty()) return emptyList()
        val out = mutableListOf<SessionAutoStart.InlineImage>()
        images.forEachIndexed { index, att ->
            val remaining = images.size - index
            val currentRawBytes = out.sumOf { it.base64.length } * 3 / 4
            val adaptiveTarget = (((app.codeg.android.feature.sessiondetail.AttachmentPrep.MAX_TOTAL_BYTES - currentRawBytes) * 19 / 20) / remaining)
                .coerceIn(64_000, app.codeg.android.feature.sessiondetail.AttachmentPrep.TARGET_BYTES)
            val prepared = app.codeg.android.feature.sessiondetail.AttachmentPrep.fromLocalFile(att.localPath, adaptiveTarget)
                ?: error("${att.name}：图片读取或压缩失败")
            out += SessionAutoStart.InlineImage(prepared.base64, prepared.mimeType)
        }
        return out
    }

    /** 账本标题：标题空则从描述第一行派生（与 quickInsertTodo 同一条规则）。 */
    private fun ledgerTitle(idea: IdeaEntry): String =
        idea.title.trim().ifBlank {
            idea.description.trim().lineSequence().firstOrNull { it.isNotBlank() }
                ?.let { DispatchService.cap(it, 40).trim() }
                .orEmpty()
        }

    /** 选择器/追加的行数上限与单设备切片上限。 */
    private companion object {
        const val PICKER_LIMIT = 60
        const val PICKER_PER_DEVICE = 50

        /** 批v164: the 新想法 dialog's draft key — one slot, new ideas only. */
        const val IDEA_DRAFT_KEY = "inbox"
    }

    // endregion
}
