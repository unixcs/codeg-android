package app.codeg.android.feature.sessiondetail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.R
import app.codeg.android.core.data.HandoffSource
import app.codeg.android.core.data.LocalAttachment
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.ConversationSelectorStore
import app.codeg.android.core.datastore.DeviceAgentPrefsStore
import app.codeg.android.core.datastore.DraftFolderPrefsStore
import app.codeg.android.core.datastore.DraftStore
import app.codeg.android.core.datastore.IdeaEntry
import app.codeg.android.core.datastore.IdeaStore
import app.codeg.android.core.datastore.SessionProject
import app.codeg.android.core.datastore.SessionRef
import app.codeg.android.core.datastore.SelectorPrefs
import app.codeg.android.core.datastore.SelectorPrefsStore
import app.codeg.android.core.datastore.resolveSelectorPrefs
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.datastore.filterHiddenAgents
import app.codeg.android.core.model.AcpEvent
import app.codeg.android.core.model.AgentOptionsSnapshot
import app.codeg.android.core.model.AgentMention
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.AvailableCommandInfo
import app.codeg.android.core.model.ConnectionStatus
import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.ExpertListItem
import app.codeg.android.core.model.PlanApprovalDecision
import app.codeg.android.core.model.QuickMessage
import android.content.ContentResolver
import android.net.Uri
import app.codeg.android.core.model.ConversationConnectionInfo
import app.codeg.android.core.model.ConversationDetail
import app.codeg.android.core.model.ConversationStatus
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.core.model.FolderVisibility
import app.codeg.android.core.model.GitBranchList
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.PromptInputBlock
import app.codeg.android.core.model.SessionConfigKind
import app.codeg.android.core.model.SessionConfigOption
import app.codeg.android.core.model.SessionModeState
import app.codeg.android.core.model.SessionSnapshot
import app.codeg.android.core.model.SessionStats
import app.codeg.android.core.model.TurnRole
import app.codeg.android.core.network.ApiError
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.EventStream
import app.codeg.android.core.network.StreamFrame
import app.codeg.android.core.network.displayMessage
import app.codeg.android.core.network.isStaleConnection
import app.codeg.android.feature.sessiondetail.interactive.ParsedPermission
import app.codeg.android.feature.sessiondetail.timeline.dropTrailingAssistantRun
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import app.codeg.android.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.time.Instant
import java.util.UUID
import android.os.SystemClock
import app.codeg.android.core.data.AgentCatalogStore
import javax.inject.Inject

/** WS `detached` reason meaning the ACP connection itself is gone (terminal — a
 *  socket reopen can't recover it). Any other reason is a transient socket drop. */
private const val REASON_CONNECTION_GONE = "connection_gone"

private const val TAG = "SessionDetailVM"

/** Max consecutive silent re-attaches before falling back to a transcript reconcile. */
private const val MAX_STREAM_RECONNECTS = 6

/** The selector key used for the session mode (config options key by their own id). */
private const val MODE_KEY = "mode"

/** RPC session snapshot freshness window (see snapshotFor in the VM). */
private const val SNAPSHOT_TTL_MS = 30_000L

/** 批v165 @智能体: the link scheme the server's prompt preprocessor resolves
 *  into a delegation route. Its presence in a draft is what arms the
 *  delegation gate — no parsing beyond `contains`, by design (调研规划 §3.1:
 *  the app never interprets the token, it only knows it's there). */
private const val CODEG_AGENT_LINK = "codeg://agent/"

/** M1 turn windowing (web parity): the transcript opens on the last N turns and
 *  older pages join through [SessionDetailViewModel.loadOlderTurns]. Same values
 *  as the web client's conversation-runtime-store. */
private const val TAIL_TURNS_DEFAULT = 120
private const val OLDER_TURNS_PAGE_SIZE = 120

/** Server-side folder (inside the working directory) that receives session handoff JSON. */
private const val HANDOFF_DIR = ".codeg-handoff"

/**
 * Session file-upload staging dir (root-relative) for documents picked from the
 * phone. P0-3: `.codeg/uploads` instead of the project's `uploads/` — sending a
 * PDF from the composer must not litter the repository root the user browses.
 */
private const val UPLOAD_DIR = ".codeg/uploads"

/** Per-session staged file caps: the server allows up to 100 MB per multipart upload. */
private const val FILE_MAX_COUNT = 5
private const val FILE_MAX_BYTES = 20_000_000L
/** Above this a text file is too big to inline when the session has no workspace folder. */
private const val FILE_MAX_INLINE_BYTES = 200_000

/** Bounded snapshot poll after an options apply (~2.7s total) — `set_*` only enqueues
 *  the change server-side (fire-and-forget), so only a settled snapshot can tell an
 *  applied pick from a refused one. 150ms first step, 500ms after. */
private const val RECONCILE_ATTEMPTS = 6

/** Status-line copy shown while the agent is thinking (no tool running). Hardcoded to
 *  match the send-flow literal — the transient status micro-copy is intentionally not
 *  localized (see the app's i18n notes). */
private const val STATUS_THINKING = "Thinking…"

/** Keystroke → DataStore debounce for the composer draft (the Element X / Compose
 *  convention: 300–1000ms; fast enough that a process kill mid-typing loses at
 *  most this window of text). */
private const val DRAFT_SAVE_DEBOUNCE_MS = 500L

/** Post-turn grace before the queue's auto-flush starts a new turn: the reconcile
 *  that just swapped the finished reply into `turns` must finish first — an
 *  immediate send cancels it and folds the turn as a lossy `live-*` snapshot. */
private const val QUEUE_FLUSH_LEAD_MS = 2_000L

/** Flush retries before giving up (with [flushBackoffMs] between tries: 5s/15s/45s). */
private const val QUEUE_FLUSH_TRIES = 3

/**
 * Drives the session detail screen end to end: loads the persisted transcript,
 * then runs the live ACP lifecycle — resolve/open a connection, attach to the
 * `/ws/events` stream, send a prompt, fold streamed events into a live turn, and
 * reconcile against the server transcript on completion. Faithful port of the
 * core of the iOS `SessionDetailViewModel` (the reconnect-with-backoff and
 * snapshot live-message reconstruction are simplified; see notes).
 */
@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    private val repository: ServerRepository,
    private val selectorPrefs: SelectorPrefsStore,
    private val conversationPrefs: ConversationSelectorStore,
    private val draftStore: DraftStore,
    private val deviceAgentPrefs: DeviceAgentPrefsStore,
    private val catalogStore: AgentCatalogStore,
    private val draftFolderPrefs: DraftFolderPrefsStore,
    private val projectRepo: app.codeg.android.core.data.SessionProjectRepository,
    private val sessionAutoStart: app.codeg.android.core.data.SessionAutoStart,
    // 批v162 正向快链：插入表单的「想法」来源与收件箱同一条 IdeaStore 流。
    private val ideaStore: IdeaStore,
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val appContext: Context,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val conversationIdArg: Int? = savedStateHandle.get<Int>("id")?.takeIf { it >= 0 }
    /** A new task may be pre-seeded with a folder (e.g. a worktree a branch switch opened). */
    private val folderIdArg: Int? = savedStateHandle.get<Int>("folderId")?.takeIf { it >= 0 }

    private var profile: ServerProfile? = null
    private var client: CodegClient? = null
    private var conversationId: Int? = conversationIdArg

    /** "<serverId>:<conversationId>" once resolved — the key HandoffAutoOpenBus
     *  addresses (批v150 quick handoff). Null until the profile + id are known. */
    val sessionKey: String?
        get() = profile?.id?.let { sid -> conversationId?.let { cid -> "$sid:$cid" } }
    private var connectionId: String? = null
    private var folder: FolderDetail? = null
    private var allFolders: List<FolderDetail> = emptyList()
    private var agent: AgentType = AgentType.CLAUDE_CODE
    private var externalId: String? = null
    private var lastSeq: Long = 0
    /** True once a draft's first send begins — locks the agent/folder pickers. */
    private var hasStartedFirstSend = false
    /** The working tree's current branch, shown + checkmarked in the branch picker. */
    private var currentBranch: String? = null

    private var stream: EventStream? = null
    private var consumerJob: Job? = null
    private var sendJob: Job? = null
    private var streamGeneration = 0
    private var reconnectJob: Job? = null
    /** The post-turn transcript reconcile. Tracked so a new send / cancel can cancel it,
     *  preventing an orphaned reconcile from a finished turn wiping a freshly-started one. */
    private var reconcileJob: Job? = null
    /** Revision notes waiting to be sent as a follow-up prompt after a plan-approval
     *  "request changes" (see [answerPlanApproval]), bound to the turn that produced
     *  the approval so no other turn can ever deliver them. */
    private var pendingPlanFollowUp: ParkedPlanNotes? = null
    /** Stable across reconnects so the server tracks one logical subscription. */
    private val subscriptionId = UUID.randomUUID().toString()
    /** Consecutive silent re-attaches for the current turn; reset on a confirmed attach. */
    private var streamReconnects = 0
    /** True while a reattach still owes its first snapshot seed. If the socket drops
     *  mid-handshake, the reconnect re-requests the seed instead of streaming blind. */
    private var pendingReattachSeed = false
    private var liveBuilder: LiveTurnBuilder? = null
    private var liveFlushScheduled = false
    private var turnBaseline = 0
    /** Monotonic transcript-window op counter (fleet M1 review, web fetch-generation
     *  parity): every splice / wholesale adopt bumps it. Async detail/page fetches
     *  capture it at issue time and discard (or re-derive) when it moved — a fetch
     *  issued before a splice must not clobber the just-spliced window. */
    private var windowOpSeq = 0L
    /** The queued-draft auto-flush. Single-flight: one flush at a time, restarted by
     *  the NEXT turn's end (a flush that fires a send starts a new turn). */
    private var flushJob: Job? = null
    /** Set in [onCleared]. A send still winding down on [appScope] checks it to skip
     *  the attach handshake — see [connectStream]. */
    @Volatile private var cleared = false

    private val _ui = MutableStateFlow(SessionDetailUiState(isNew = conversationIdArg == null))
    val ui: StateFlow<SessionDetailUiState> = _ui.asStateFlow()

    init {
        load()
    }

    // region Draft persistence (survives leaving the screen AND a process kill)

    /** DataStore key for this screen's composer text, resolved FRESH on every access: an
     *  existing conversation keys by its id, the new-task screen keys by folder (the
     *  conversation row only exists after the first send lands). A `lazy` would freeze
     *  both the pre-load profile (null → "?" server slot) and the pre-send conversation
     *  id, sending post-creation keystrokes to a stale key. */
    private fun currentDraftKey(): String =
        conversationId?.let { DraftStore.conversationKey(profile?.id, it) }
            ?: DraftStore.newTaskKey(profile?.id, folderIdArg ?: -1)

    /** Initial composer text (persisted draft), read once by the screen on entry. */
    suspend fun loadPersistedDraft(): String = draftStore.draftNow(currentDraftKey()).text

    /** Debounced save on every keystroke; empty text deletes the entry (a send or a
     *  manual clear must not resurrect the text on the next visit). */
    fun saveDraft(text: String) {
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            delay(DRAFT_SAVE_DEBOUNCE_MS)
            runCatching { draftStore.setText(currentDraftKey(), text) }
        }
    }

    private var draftSaveJob: Job? = null

    private suspend fun clearPersistedDraft(preSendKey: String? = null) {
        draftSaveJob?.cancel()
        // One atomic edit removing both keys: the key the draft lived under before the
        // send (new-task key on a first send — the one that used to leak into the next
        // new-task screen) plus whichever key currentDraftKey() resolves to now
        // (conversation key after creation; identical to preSendKey on later sends).
        val keys = buildList {
            if (preSendKey != null) add(preSendKey)
            add(currentDraftKey())
        }
        runCatching { draftStore.setTextAll(keys, "") }
    }

    // endregion

    /**
     * The pickers a NEW task's composer offers on [p]: top-level open folders and
     * installed/enabled agents, plus the default folder/agent picks. Factored out
     * of [load] so switching devices mid-draft reloads exactly the same sources
     * (批v154) instead of duplicating the resolution rules.
     */
    private suspend fun loadDraftSources(p: ServerProfile, c: CodegClient) {
        val open = runCatching { c.listOpenFolders() }.getOrDefault(emptyList())
        val topLevel = FolderVisibility.filterTopLevel(open)
            .sortedByDescending { it.lastOpenedAt ?: Instant.MIN }
        val agents = runCatching { c.acpListAgents() }.getOrDefault(emptyList())
            .filter { it.available && it.enabled }
            .sortedBy { it.sortOrder }
            .map { it.agentType }
            .distinct()
            .ifEmpty { AgentType.entries.filter { it != AgentType.UNKNOWN } }
            // Per-device visibility (v123): applied AFTER the ifEmpty fallback —
            // inside the chain the fallback would re-admit hidden agents when a
            // server reports no enabled ones. May legitimately yield empty.
            .let { filterHiddenAgents(it, deviceAgentPrefs.hiddenNow(profileId = p.id)) }
        // v134 folder memory: arg → pin → last used → most recent, each remembered
        // id validated against the picker-visible top-level set. Folder ids are
        // device-local, so a device switch re-resolves from THIS device's memory.
        val folderPrefs = runCatching { draftFolderPrefs.foldersNow(profileId = p.id) }.getOrNull()
        folder = resolveDraftFolder(
            allFolders = allFolders,
            topLevel = topLevel,
            folderIdArg = folderIdArg,
            pinnedFolderId = folderPrefs?.pinnedFolderId,
            lastUsedFolderId = folderPrefs?.lastUsedFolderId,
        )
        agent = folder?.defaultAgentType?.takeIf { it in agents }
            ?: agents.firstOrNull()
            ?: AgentType.CLAUDE_CODE
        currentBranch = folder?.gitBranch
        _ui.update {
            it.copy(
                loading = false,
                serverName = p.name,
                serverId = p.id,
                agent = agent,
                folderName = folder?.let { f -> FolderVisibility.displayName(f, allFolders) },
                folderPath = folder?.path,
                selectedFolderId = folder?.id,
                currentBranch = currentBranch,
                isDraftEditable = true,
                availableFolders = topLevel,
                availableAgents = agents,
            )
        }
    }

    /**
     * 批v154 设备解耦：a NEW task may target ANY device — the device chip in the
     * composer opens this. The launch device used to be baked in by
     * [load]'s one `selectedProfile.first()` read, which is why every new task
     * created from a device's session list was pinned there.
     *
     * Only a draft can move: an existing conversation id is server-local, so a
     * live session's device stays read-only. The app-wide selection follows the
     * pick (the device bar, the next new task and the Activity feed then address
     * the device the session is actually born on).
     */
    fun selectDevice(serverId: String) {
        val current = profile
        if (!_ui.value.isNew || hasStartedFirstSend || serverId == current?.id) return
        viewModelScope.launch {
            try {
                val p = repository.profiles.first().firstOrNull { it.id == serverId } ?: return@launch
                val c = repository.client(p)
                if (c == null) {
                    _ui.update { it.copy(notice = appContext.getString(R.string.session_device_no_token, p.name)) }
                    return@launch
                }
                runCatching { repository.select(p.id) }
                profile = p
                client = c
                connectionId = null
                externalId = null
                allFolders = runCatching { c.listFolders() }.getOrDefault(emptyList())
                loadDraftSources(p, c)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(notice = e.displayMessage()) }
            }
        }
    }

    /** Returns the reload's [Job] so callers that must observe completion (the Fork
     *  latch) can hook it; ordinary callers ignore it. */
    fun load(): Job {
        _ui.update { it.copy(loading = true, error = null) }
        return viewModelScope.launch {
            try {
                val p = repository.selectedProfile.first() ?: throw IllegalStateException("No server selected")
                profile = p
                val c = repository.client(p) ?: throw IllegalStateException("Missing token for this server")
                client = c

                allFolders = runCatching { c.listFolders() }.getOrDefault(emptyList())
                // The Fork gate's forkSupported for an idle conversation is seeded by
                // loadSlashCommands() below (its snapshotFor call). No separate prewarm:
                // it fired the very same RPC twice on every open.

                val id = conversationId
                if (id != null) {
                    // M1: open on a TAIL window (web parity) — a 100+ turn session
                    // paints its first frame without the whole transcript. Older
                    // pages join through [loadOlderTurns]; the window fields ride
                    // the response (null = legacy server, windowing stays off).
                    val detail = c.conversationDetail(id, tailTurns = TAIL_TURNS_DEFAULT)
                    agent = detail.summary.agentType
                    externalId = detail.summary.externalId
                    folder = allFolders.firstOrNull { it.id == detail.summary.folderId }
                    currentBranch = detail.summary.gitBranch ?: folder?.gitBranch
                    _ui.update {
                        it.copy(
                            loading = false,
                            summary = detail.summary,
                            serverName = p.name,
                            serverId = p.id,
                            turns = detail.turns,
                            turnsOffset = detail.turnsOffset,
                            turnsTotal = detail.turnsTotal,
                            windowPrefixHash = detail.prefixHash,
                            assistantTurnsBeforeOffset = detail.assistantTurnsBeforeOffset,
                            userTurnsBeforeOffset = detail.userTurnsBeforeOffset,
                            sessionStats = detail.sessionStats,
                            externalId = detail.summary.externalId,
                            agent = detail.summary.agentType,
                            folderName = folder?.let { f -> FolderVisibility.displayName(f, allFolders) },
                            folderPath = folder?.path,
                            selectedFolderId = folder?.id,
                            currentBranch = currentBranch,
                            isDraftEditable = false,
                            error = null,
                        )
                    }
                    // 批v154: the chip's selection mirrors the SERVER membership.
                    observeProjects(p.id)
                    // Reattach only when a turn is GENUINELY executing. The
                    // conversation STATUS cannot play this role — every freshly
                    // created row is born in_progress (server create_inner), so
                    // status.isLive is true for a conversation that has never seen
                    // a turn, and gating on it skipped the dispatch auto-send
                    // below forever (v149.1: session-mode detail always blank).
                    // 批v156: in_flight_user_turn_id 并不可靠——工作台启动的整轮运行
                    // 与 qoder 等 agent 自驱轮次实测都停在 null（它只在主链路
                    // AcpEvent::UserMessage 落库时置位），而 pet_list_active_sessions
                    // 的活跃连接表是准的。detail 说空闲时再查一次活跃表兜底：在表中
                    // →重连直播；旧服务端或查询失败返回 null→维持旧行为。
                    var liveTurn = detail.inFlightUserTurnId != null
                    if (!liveTurn) {
                        liveTurn = id in (runCatching { c.liveConversationIds() }.getOrNull() ?: emptySet())
                    }
                    if (liveTurn) reattachIfLive()
                    // 批v149 会话模式派发：收件箱"会话"方式创建会话后跳进来，这里
                    // 消费armed 的首条指令并发送。只在真有轮次在跑时让位（live 轮
                    // 由 isInFlight 守卫挡下）；指令总是先取出（consume-once），
                    // 绝不让它从一个更晚的无关访问里复活。
                    sessionAutoStart.takePending(p.id, id)?.let { armed ->
                        if (!liveTurn) {
                            // 批v154: files dispatched with the idea ride on the same
                            // first send — uploaded on the target device by the
                            // dispatch pipeline, so they arrive as workspace paths.
                            send(
                                armed.prompt,
                                withAttachments = false,
                                extraFiles = armed.files.map { f ->
                                    FileAttachment(
                                        id = "dispatch-${UUID.randomUUID()}",
                                        name = f.name,
                                        size = 0,
                                        absolutePath = f.absolutePath,
                                    )
                                },
                                // P0-3: dispatched images arrive as base64 blocks —
                                // the same path a composer image takes.
                                extraImages = armed.images.map { img ->
                                    Attachment(
                                        id = "dispatch-${UUID.randomUUID()}",
                                        base64 = img.base64,
                                        mimeType = img.mimeType,
                                    )
                                },
                            )
                        }
                    }
                } else {
                    loadDraftSources(p, c)
                }
                // Slash commands for the composer popup: live snapshot → conversation
                // snapshot → agent catalog. Runs for an idle existing session (no live
                // event will arrive) and for a draft (no conversation id yet).
                viewModelScope.launch { loadSlashCommands() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.displayMessage()) }
            }
        }
    }

    // region Projects (批v154 添加到项目)

    /** Projects offered by the composer's "add to project" sheet. */
    val sessionProjects: StateFlow<List<SessionProject>> = projectRepo.projects
    val projectMembers: StateFlow<Map<String, List<SessionRef>>> = projectRepo.members

    /** Projects the composer keeps in sync with the transcript (a tapped row is
     *  the target, so `projectIds` must follow the SERVER's membership, not a
     *  stale local selection). */
    fun observeProjects(serverId: String) {
        projectsJob?.cancel()
        val id = conversationId ?: return
        projectsJob = viewModelScope.launch {
            runCatching {
                projectRepo.members.collect { members ->
                    val mine = members.filterValues { refs ->
                        refs.any { it.serverId == serverId && it.conversationId == id }
                    }.keys
                    _ui.update { if (it.projectIds == mine) it else it.copy(projectIds = mine) }
                }
            }
        }
    }

    /** Add/remove the draft (or the created session) from a project. Before the
     *  conversation exists the pick is remembered and written on creation. */
    fun toggleDraftProject(projectId: String, member: Boolean) {
        _ui.update {
            it.copy(projectIds = if (member) it.projectIds + projectId else it.projectIds - projectId)
        }
        val sid = profile?.id ?: return
        val id = conversationId ?: return
        val ref = SessionRef(sid, id)
        viewModelScope.launch {
            runCatching { projectRepo.setMembership(projectId, ref, member) }
        }
    }

    /** Create a project from the composer's sheet and join it in one step. */
    fun createDraftProject(name: String, onCreated: (SessionProject) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val project = projectRepo.create(name)
                toggleDraftProject(project.id, true)
                onCreated(project)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Sheet keeps its input; the project simply wasn't created.
            }
        }
    }

    private var projectsJob: Job? = null

    // endregion

    // region Send / cancel

    fun send(text: String) = send(text, withAttachments = true)

    /**
     * Send the composer's draft — or, with [withAttachments] `false`, a prompt the app
     * itself generated (today: the revision notes from a plan-approval "request
     * changes", which Grok expects as a follow-up turn). A generated send never
     * consumes the composer's pending attachments, so images the user was staging
     * survive.
     */
    private fun send(
        text: String,
        withAttachments: Boolean,
        /** Files an app-generated prompt brings along (批v154 dispatch payload) —
         *  already on the target device, so nothing to upload here. */
        extraFiles: List<FileAttachment> = emptyList(),
        /** P0-3: images dispatched with the idea ride INLINE (base64), exactly
         *  like composer images — never a workspace copy. */
        extraImages: List<Attachment> = emptyList(),
    ) {
        val trimmed = text.trim()
        val attachments = (if (withAttachments) _ui.value.attachments else emptyList()) + extraImages
        val fileAttachments = (if (withAttachments) _ui.value.fileAttachments else emptyList()) + extraFiles
        if ((trimmed.isEmpty() && attachments.isEmpty() && fileAttachments.isEmpty()) || _ui.value.isInFlight) return
        // A fork is mid-flight: the session row is about to be re-pointed, so a send
        // now would land on the wrong session (or race the re-point entirely).
        if (_ui.value.forking) return

        // 2 MiB axum prompt-body ceiling (v149.2): the composer's aggregate
        // attachment cap enforces this at pick time, but a restored draft or a
        // future caller could bypass it — refuse here with a clear reason
        // instead of a bare plaintext 413 the agent screen can't explain.
        val attachmentChars = attachments.sumOf { it.base64.length }
        if (attachmentChars > AttachmentPrep.MAX_TOTAL_BYTES * 4 / 3) {
            _ui.update { it.copy(notice = appContext.getString(R.string.send_prompt_too_large)) }
            return
        }

        // 批v165 @智能体 gate: a draft carrying `codeg://agent/` only becomes a
        // sub-session if the server's delegation is ON. Off (the default) +
        // HOST_TOOLS stripping on the agent side, the link arrives as inert text
        // and NOTHING runs — the exact silent failure the 调研规划 forbade
        // shipping. Check fresh at send time (settings change on the server;
        // a cached flag gates sends that would work), and only block on a
        // CONFIRMED `enabled == false`: a failed probe must not block a send
        // that may be fine. Gate up → park the send; the dialog's primary
        // action enables delegation and replays it.
        if (trimmed.contains(CODEG_AGENT_LINK)) {
            pendingGateSend = { sendNow(text, withAttachments, extraFiles, extraImages) }
            viewModelScope.launch {
                val enabled = delegationEnabledNow()
                if (enabled) {
                    val proceed = pendingGateSend
                    pendingGateSend = null
                    proceed?.invoke()
                } else {
                    _ui.update { it.copy(delegationGate = true) }
                }
            }
            return
        }
        sendNow(text, withAttachments, extraFiles, extraImages)
    }

    /** 委派开关的当前值，best-effort。Missing key / failed probe → `true`:
     *  the gate only ever blocks on a confirmed-off setting (murphy rule: the
     *  blocker must never fire on our own uncertainty). */
    private suspend fun delegationEnabledNow(): Boolean {
        val c = client ?: return true
        val raw = runCatching { c.getDelegationSettings() }.getOrNull() ?: return true
        return raw["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
    }

    /** The parked send the delegation gate is holding (批v165). A lambda so the
     *  parked request keeps its original arguments verbatim through the
     *  enable-and-replay round trip. */
    private var pendingGateSend: (() -> Unit)? = null

    /** 批v165 @智能体: refresh the mention picker's table (every sheet open —
     *  the agent list is the server's live config, not app state). Enabled rows
     *  only: a disabled agent can't accept a delegation, so offering it would
     *  manufacture the same silent failure the gate exists to prevent. */
    fun loadMentionAgents() {
        val c = client ?: return
        viewModelScope.launch {
            val agents = runCatching { c.acpListAgentMentions() }.getOrNull()
            _ui.update {
                it.copy(
                    // distinctBy slug: the picker's LazyColumn keys on the slug —
                    // a duplicated wire row (hand-edited config) would crash the
                    // list before the user could read it.
                    mentionAgents = agents.orEmpty().filter { a -> a.enabled }.distinctBy { a -> a.slug },
                    mentionLoadFailed = agents == null,
                )
            }
        }
    }

    /** The gate dialog's dismiss (取消): drop the parked send, keep the draft. */
    fun dismissDelegationGate() {
        pendingGateSend = null
        _ui.update { it.copy(delegationGate = false) }
    }

    /** The gate dialog's primary action (开启并发送): patch `enabled=true` into
     *  the FULL settings object (`set_delegation_settings` replaces the record —
     *  a partial object would fail the server's non-defaulted `depth_limit`),
     *  then replay the parked send. A failed patch surfaces a notice and keeps
     *  the draft; it never sends half-configured. */
    fun enableDelegationAndSend() {
        val c = client
        val proceed = pendingGateSend
        if (c == null || proceed == null) {
            dismissDelegationGate()
            return
        }
        _ui.update { it.copy(delegationGateBusy = true) }
        viewModelScope.launch {
            val current = runCatching { c.getDelegationSettings() }.getOrNull()
            val enabled = current != null && current.isNotEmpty() && runCatching {
                c.setDelegationSettings(JsonObject(current + ("enabled" to JsonPrimitive(true))))
            }.isSuccess
            _ui.update { it.copy(delegationGate = false, delegationGateBusy = false) }
            if (enabled) {
                pendingGateSend = null
                proceed()
            } else {
                _ui.update { it.copy(notice = appContext.getString(R.string.mention_delegation_enable_failed)) }
            }
        }
    }

    /** The guarded-and-gated send body: everything from the reconcile fold to
     *  the [runSend] dispatch. Split out of [send] by the 批v165 gate so the
     *  delegation pre-check can run BEFORE any optimistic state lands. */
    private fun sendNow(
        text: String,
        withAttachments: Boolean,
        extraFiles: List<FileAttachment>,
        extraImages: List<Attachment>,
    ) {
        val trimmed = text.trim()
        val attachments = (if (withAttachments) _ui.value.attachments else emptyList()) + extraImages
        val fileAttachments = (if (withAttachments) _ui.value.fileAttachments else emptyList()) + extraFiles

        // Supersede any post-turn reconcile still running for the previous turn so it can't
        // wipe the turn we're about to start (its identity guard is the backstop).
        reconcileJob?.cancel()
        // A previous turn that finished / cancelled / errored but was never reconciled into
        // `turns` still lives in `liveBuilder`; fold it in before we reuse the live slot so
        // its (partial) reply isn't dropped by this send. Mirrors iOS `send()`.
        liveBuilder?.takeIf { !it.isStreaming && !it.isEmpty }?.let { promoteUnreconciled() }

        // A draft's first send locks the agent/folder pickers (the conversation is
        // about to be created against them).
        val wasNew = conversationId == null
        if (wasNew) hasStartedFirstSend = true

        // Optimistic user turn: text (if any), the file note (if any), then image blocks.
        val userTurn = MessageTurn(
            id = "pending-${UUID.randomUUID()}",
            role = TurnRole.USER,
            blocks = buildList {
                if (trimmed.isNotEmpty()) add(ContentBlock.Text(trimmed))
                fileSummaryNote(fileAttachments)?.let { add(ContentBlock.Text(it)) }
                attachments.forEach { add(ContentBlock.Image(it.imageData)) }
            },
            timestamp = Instant.now(),
        )
        val builder = LiveTurnBuilder()
        liveBuilder = builder
        turnBaseline = (_ui.value.turnsOffset ?: 0) + _ui.value.turns.size
        _ui.update {
            it.copy(
                pendingUserTurns = it.pendingUserTurns + userTurn,
                live = builder.snapshot(),
                liveFromReattach = false,
                isInFlight = true,
                runStartedAt = System.currentTimeMillis(),
                sendStatus = "Connecting…",
                notice = null,
                restoreDraft = null,
                attachments = if (withAttachments) emptyList() else it.attachments,
                fileAttachments = if (withAttachments) emptyList() else it.fileAttachments,
                isDraftEditable = false,
            )
        }
        // Run the send on the process-lifetime scope (re-dispatched to Main so the state
        // writes stay on the main thread): a back-navigation clears this VM mid-flight,
        // and the old `viewModelScope` home cancelled the coroutine before the prompt
        // POST landed — the optimistic bubble, the cleared draft and the server turns all
        // ended up empty. The send outlives the screen now.
        sendJob = appScope.launch(Dispatchers.Main.immediate) {
            runSend(trimmed, userTurn, attachments, fileAttachments, wasNew, clearDraft = withAttachments)
        }
    }

    /**
     * Human-facing one-liner per staged file, shown as its own text block in the
     * optimistic user bubble. The machine-facing variant (with inline contents)
     * is [filePromptAppendix].
     */
    private fun fileSummaryNote(files: List<FileAttachment>): String? {
        if (files.isEmpty()) return null
        return buildString {
            append("📎 ")
            files.forEachIndexed { i, f ->
                if (i > 0) append("\n")
                append(f.name)
                f.absolutePath?.let { append("（已上传：$it）") }
            }
        }
    }

    /** Text appended to the prompt so the agent receives the staged files. */
    private fun filePromptAppendix(files: List<FileAttachment>): String = buildString {
        files.forEach { f ->
            when {
                f.absolutePath != null ->
                    append("\n\n📎 附件「${f.name}」已上传到服务器工作目录：${f.absolutePath}（请直接读取该文件）")
                f.inlineContent != null ->
                    append("\n\n📎 附件「${f.name}」内容：\n```\n${f.inlineContent}\n```")
            }
        }
    }

    private suspend fun runSend(
        text: String,
        userTurn: MessageTurn,
        attachments: List<Attachment>,
        fileAttachments: List<FileAttachment>,
        createdConversation: Boolean,
        clearDraft: Boolean,
    ) {
        val c = client ?: return
        // One id for both attempts so the server dedupes if the first prompt actually landed.
        val clientMessageId = UUID.randomUUID().toString()
        // The EXACT conversation this send created up front (a first send), so a rollback deletes
        // that row and not whatever conversationId happens to be by the time the failure lands.
        var createdId: Int? = null
        // The draft lived under the new-task key ("<server>:new:<folderId>") until the
        // conversation below is created; after that currentDraftKey() resolves to the
        // conversation key, so clearing only "the current key" would strand the sent
        // text under the old key and leak it into the next new-task screen. Capture the
        // pre-send key up front and clear BOTH.
        val preSendDraftKey = currentDraftKey()
        try {
            ensureConversationCreated(text.ifEmpty { fileAttachments.firstOrNull()?.name ?: "Image" })
            if (createdConversation) createdId = conversationId
            // The screen has already emptied its composer; clear the persisted draft here —
            // on [appScope], so a back-navigation can't cancel it mid-DataStore-write and
            // resurrect the sent text on the next visit. Generated sends (plan follow-ups)
            // must not consume the user's pending draft.
            if (clearDraft) clearPersistedDraft(preSendDraftKey)
            try {
                attemptSend(c, text, attachments, fileAttachments, clientMessageId, freshConnection = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A stale connection — a reused id the server has since dropped, or an attach
                // the server rejected — can't carry the prompt. Drop it, connect fresh, retry
                // ONCE. A second failure falls through to the outer catch (discard + notice).
                if (e is StreamAttachFailed || (e is ApiError && e.isStaleConnection)) {
                    closeStream()
                    connectionId = null
                    attemptSend(c, text, attachments, fileAttachments, clientMessageId, freshConnection = true)
                } else throw e
            }
            _ui.update { if (it.isInFlight) it.copy(sendStatus = "Thinking…") else it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError.TurnInProgress) {
            // 批v156: 服务端说忙就是忙（它的 gate 是权威信号，客户端的 in-flight
            // 感知与它可能失同步——qoder 实测 detail 报空闲而 gate 拦人）。回滚本次
            // 乐观发送后立刻重连活跃连接：UI 采纳运行态（停止按钮+实时流），横幅
            // 用本地化文案，不再直出服务端英文原话。
            discardOptimistic(
                userTurn, text, attachments, createdConversation, createdId,
                notice = appContext.getString(R.string.session_turn_in_progress),
            )
            reattachIfLive()
        } catch (e: Exception) {
            discardOptimistic(userTurn, text, attachments, createdConversation, createdId, notice = e.displayMessage())
        }
    }

    /** One send attempt: resolve (or force-fresh) a connection, attach, then prompt. */
    private suspend fun attemptSend(
        c: CodegClient,
        text: String,
        attachments: List<Attachment>,
        fileAttachments: List<FileAttachment>,
        clientMessageId: String,
        freshConnection: Boolean,
    ) {
        val conn = if (freshConnection) connectFresh(c) else resolveConnection(c)
        connectionId = conn
        openStream(conn)
        c.prompt(
            connectionId = conn,
            blocks = buildList {
                val promptText = text + filePromptAppendix(fileAttachments)
                if (promptText.isNotEmpty()) add(PromptInputBlock.Text(promptText))
                attachments.forEach { add(it.promptBlock) }
            },
            folderId = folder?.id,
            conversationId = conversationId,
            clientMessageId = clientMessageId,
        )
    }

    /** Force a brand-new ACP connection, bypassing any cached/looked-up id and resetting
     *  the event cursor so the fresh stream is seeded from its own snapshot (no stale
     *  `since_seq` from the connection we just abandoned). */
    private suspend fun connectFresh(c: CodegClient): String {
        lastSeq = 0
        return connectWithPreferences(c)
    }

    /** `acp_connect` for a specific agent/folder (defaulting to the session's current ones),
     *  carrying that agent's remembered mode/config (per-agent [SelectorPrefsStore]) so the
     *  server applies them before reporting state. A per-conversation override
     *  ([ConversationSelectorStore]) wins when THIS conversation has its own picks — that's
     *  the model lock: one conversation's model change never leaks into other sessions.
     *  The explicit [connectAgent]/[connectFolder] let the options path pin the context
     *  captured before any suspension, so a draft agent/folder switch mid-connect can't
     *  retarget the connection. web/iOS parity. */
    private suspend fun connectWithPreferences(
        c: CodegClient,
        connectAgent: AgentType = agent,
        connectFolder: FolderDetail? = folder,
    ): String {
        val convId = conversationId
        // Layered resolution (v133): the conversation layer overrides per field; the
        // agent-level pref (e.g. YOLO) survives a conversation that only ever picked a model.
        val conv = if (convId != null) {
            runCatching { conversationPrefs.prefs(profile?.id, convId, connectAgent) }.getOrNull()
        } else null
        val agentPrefs = runCatching { selectorPrefs.prefs(connectAgent) }.getOrNull() ?: SelectorPrefs()
        val prefs = resolveSelectorPrefs(conv, agentPrefs)
        return c.connect(
            agentType = connectAgent,
            workingDir = connectFolder?.path,
            sessionId = externalId,
            preferredModeId = prefs.modeId,
            preferredConfigValues = prefs.configValues?.takeIf { it.isNotEmpty() },
        )
    }

    private suspend fun ensureConversationCreated(firstPrompt: String) {
        if (conversationId != null) return
        val c = client ?: return
        val f = folder ?: throw IllegalStateException("No folder available to start a task")
        val id = c.createConversation(f.id, agent, title = firstPrompt.take(60))
        conversationId = id
        // v134 folder memory: the accepted default is still a choice — record it so
        // the next new task keeps defaulting here even if the user never opened the picker.
        rememberFolderChoice(f)
        // 批v154: the project picks made on the draft become real memberships now
        // that the session has an id. Best-effort — a failed write must not fail
        // the send (the session exists; the chip retries on the next tap).
        linkDraftProjects(id)
        _ui.update { it.copy(isNew = false) }
    }

    /** Persist the composer's project picks for a just-created session. */
    private suspend fun linkDraftProjects(conversationId: Int) {
        val ids = _ui.value.projectIds
        if (ids.isEmpty()) return
        val serverId = profile?.id ?: return
        val ref = SessionRef(serverId, conversationId)
        ids.forEach { projectId ->
            runCatching { projectRepo.setMembership(projectId, ref, true) }
        }
    }

    private suspend fun resolveConnection(c: CodegClient): String {
        connectionId?.let { return it }
        val convId = conversationId
        if (convId != null) {
            runCatching { c.findConnection(convId, externalId, agent) }.getOrNull()?.let { info ->
                lastSeq = info.eventSeq
                return info.connectionId
            }
        }
        return connectWithPreferences(c)
    }

    /** Open the WS stream for a fresh turn: reset the reconnect budget, attach, and
     *  suspend until the server confirms the subscription (or a 12s safety timeout).
     *  [seedOnAttach] rebuilds an in-flight turn from the attach snapshot (reattach).
     *  [keepWhenIdle] keeps the connection alive when the snapshot shows an idle
     *  session (the manual reconnect of a wedged-but-idle CLI) instead of aborting. */
    private suspend fun openStream(conn: String, seedOnAttach: Boolean = false, keepWhenIdle: Boolean = false) {
        streamReconnects = 0
        pendingReattachSeed = seedOnAttach
        reconnectJob?.cancel()
        reconnectJob = null
        connectStream(conn, awaitAttach = true, seedOnAttach = seedOnAttach, keepWhenIdle = keepWhenIdle)
    }

    /** Launch a WS consumer for [conn], attaching with the current [lastSeq] so a
     *  re-attach resumes via the server's replay. When [awaitAttach], suspend until
     *  the server confirms the subscription (snapshot/replay) or a 12s safety timeout;
     *  a silent reconnect passes false and lets events flow in as they arrive.
     *  [seedOnAttach] seeds the live turn from the first snapshot (reattach only).
     *  [keepWhenIdle] — a manual reconnect targets a wedged CLI that usually sits
     *  IDLE, so an idle snapshot there means success: keep the fresh connection (the
     *  default abort path would tear it down and report a reconnect that isn't). */
    private suspend fun connectStream(
        conn: String,
        awaitAttach: Boolean,
        seedOnAttach: Boolean = false,
        keepWhenIdle: Boolean = false,
    ) {
        val p = profile ?: return
        streamGeneration += 1
        val gen = streamGeneration
        val attached = CompletableDeferred<Unit>()
        consumerJob?.cancel()
        // The consumer's body is wrapped in ONE try/catch (v130 crash fix): a throw inside
        // `collect` (applyEvent/seedLiveFromSnapshot/restorePending — e.g. the unguarded
        // ParsedPermission.parse on a malformed permission card) would otherwise sail
        // through this unhandled `viewModelScope.launch` and kill the process. Only
        // RUNNING sessions open a stream, which is exactly the reported crash shape
        // ("偶发性点开动态页运行中的会话闪退"). A failure downgrades to the same paths
        // a dead socket takes: in-flight turns fail with a notice, idle ones reconcile.
        consumerJob = viewModelScope.launch {
            try {
                val s = repository.eventStream(p) ?: run {
                    if (!attached.isCompleted) attached.complete(Unit)
                    return@launch
                }
                stream = s
                s.frames().collect { frame ->
                    if (gen != streamGeneration) return@collect
                    when (frame) {
                        StreamFrame.Ready -> s.attach(subscriptionId, conn, lastSeq.takeIf { it > 0 })
                        is StreamFrame.Snapshot -> {
                            streamReconnects = 0
                            frame.snapshot.eventSeq?.let { lastSeq = it }
                            // Steering eligibility rides EVERY snapshot (initial send, attach,
                            // reconnect): one unconditional update covers all five openStream
                            // paths, so the queue's insert affordance tracks the session's real
                            // channels instead of a stale attach-time guess.
                            _ui.update {
                                it.copy(
                                    steeringAvailable = frame.snapshot.nativeSteeringAvailable ||
                                        frame.snapshot.feedbackToolAvailable,
                                    // Fork support rides every snapshot too (server-computed
                                    // for the attached CLI). The one-shot `fork_supported`
                                    // event can fire before this client's stream attaches,
                                    // which is exactly the NEW-session first-turn case —
                                    // without this the button never appears there.
                                    forkSupported = frame.snapshot.forkSupported,
                                )
                            }
                            if (seedOnAttach && !seedLiveFromSnapshot(frame.snapshot)) {
                                // Idle connection — nothing in flight.
                                if (keepWhenIdle) {
                                    // Manual reconnect: adopt the snapshot's identity +
                                    // advertised state, drop the idle-turn placeholder, and
                                    // keep the NEW connection (do NOT close the stream —
                                    // that's the reconnect's whole point). Still reconcile:
                                    // the turn may have finished while the CLI looked wedged.
                                    restorePending(frame.snapshot)
                                    liveBuilder = null
                                    _ui.update {
                                        it.copy(
                                            isInFlight = false,
                                            runStartedAt = null,
                                            sendStatus = null,
                                            live = null,
                                            liveFromReattach = false,
                                        )
                                    }
                                    if (!attached.isCompleted) attached.complete(Unit)
                                    reconcileAfterMissedLive()
                                } else {
                                    // Automatic reattach of a turn that just ended — abort and
                                    // let the transcript reconcile show the final state.
                                    if (!attached.isCompleted) attached.complete(Unit)
                                    abortIdleReattach()
                                }
                                return@collect
                            }
                            // Only adopt a snapshot's pending permission/question on a reattach or a
                            // mid-turn reconnect — the initial send handshake snapshot is pre-prompt
                            // state and could surface a stale card (iOS skips it there).
                            if (seedOnAttach || !awaitAttach) restorePending(frame.snapshot)
                            if (!attached.isCompleted) attached.complete(Unit)
                        }
                        is StreamFrame.Replay -> {
                            frame.events.forEach { applyEvent(it.event, it.seq) }
                            // Reset the reconnect budget only AFTER the replay landed:
                            // resetting first would let a poison payload (an event that
                            // throws every time, so this catch fires before any reset
                            // could survive) keep the reconnect loop alive forever.
                            streamReconnects = 0
                            pendingReattachSeed = false
                            if (!attached.isCompleted) attached.complete(Unit)
                        }
                        is StreamFrame.Event -> applyEvent(frame.envelope.event, frame.envelope.seq)
                        is StreamFrame.Detached -> {
                            // A detach before the attach is confirmed means the server rejected this
                            // connection (stale/unknown). Fail the awaited handshake so the send path
                            // retries with a fresh connect, rather than reconnecting to a dead id. A
                            // silent reconnect (awaitAttach=false) still drives the reconnect loop.
                            if (awaitAttach && !attached.isCompleted) {
                                attached.completeExceptionally(StreamAttachFailed(frame.reason))
                            } else {
                                handleStreamEnded(gen, frame.reason)
                            }
                        }
                        StreamFrame.Pong -> Unit
                        is StreamFrame.Closed -> {
                            if (awaitAttach && !attached.isCompleted) {
                                attached.completeExceptionally(StreamAttachFailed(frame.reason))
                            } else {
                                if (!attached.isCompleted) attached.complete(Unit)
                                handleStreamEnded(gen, frame.reason)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // Same two-way split as the Detached/Closed frames above: an unconfirmed
                // handshake only fails the await (the send/reattach path retries with a
                // fresh connect) — driving the reconnect loop here too would run two
                // connection attempts against a dead id and burn reconnect budget.
                // A silent reconnect (awaitAttach=false) downgrades to the normal
                // ended-stream logic: in-flight turns reconnect, idle ones reconcile.
                Log.w(TAG, "stream consumer failed", e)
                if (awaitAttach && !attached.isCompleted) {
                    attached.completeExceptionally(StreamAttachFailed(e.message))
                } else {
                    handleStreamEnded(gen, e.message)
                }
            }
        }
        // Fail the send if the subscription isn't confirmed in time, instead of prompting
        // blind against an unconfirmed stream (whose first events could be lost). The send
        // path treats this like a stale connection and retries once with a fresh connect.
        // EXCEPT when the VM is already gone (send outliving a back-navigation): the
        // consumer that would complete [attached] runs on the dead viewModelScope and the
        // wait could never succeed. The server enqueues the prompt on the RPC's return —
        // no client stream needs to be watching — so skip the handshake and POST directly;
        // re-entering the session re-seeds everything from the snapshot/reconcile.
        if (awaitAttach) {
            if (cleared) {
                if (!attached.isCompleted) attached.complete(Unit)
            } else {
                withTimeoutOrNull(12_000) { attached.await() } ?: throw StreamAttachFailed("attach timeout")
            }
        }
    }

    fun cancel() {
        val builder = liveBuilder
        if (builder != null) {
            builder.isStreaming = false
            if (builder.isEmpty) builder.errorMessage = appContext.getString(R.string.live_cancelled)
            emitLiveNow()
        }
        sendJob?.cancel()
        reconcileJob?.cancel()
        closeStream()
        val conn = connectionId
        if (conn != null) viewModelScope.launch { runCatching { client?.cancel(conn) } }
        clearInteractivePrompts()
    }

    // endregion

    // region Streaming

    private fun reattachIfLive() {
        val c = client ?: return
        val convId = conversationId ?: return
        viewModelScope.launch {
            // The live ACP connection may not be discoverable the instant the turn is
            // reported live; retry a few times with a short backoff (iOS parity).
            var info: ConversationConnectionInfo? = null
            for (attempt in 0 until 4) {
                info = runCatching { c.findConnection(convId, externalId, agent) }.getOrNull()
                if (info != null) break
                delay(400L * (attempt + 1))
            }
            val found = info ?: run {
                // Server said live but no connection surfaced — the turn likely just
                // finished. Refetch so we show its final persisted state.
                reconcileAfterMissedLive()
                return@launch
            }
            connectionId = found.connectionId
            // Attach with NO since_seq so the server sends a full snapshot to seed the
            // in-flight turn from (a since_seq makes it replay events instead). The
            // snapshot's eventSeq becomes the resume point for any later mid-turn reconnect.
            lastSeq = 0
            // Rebuild the in-flight turn from the attach snapshot (seedOnAttach) and keep
            // streaming; the snapshot's live message + active tools are seeded on arrival.
            val builder = LiveTurnBuilder()
            liveBuilder = builder
            turnBaseline = (_ui.value.turnsOffset ?: 0) + _ui.value.turns.size
            _ui.update { it.copy(isInFlight = true, sendStatus = "Reattaching…", live = builder.snapshot()) }
            try {
                openStream(found.connectionId, seedOnAttach = true)
            } catch (e: StreamAttachFailed) {
                // The live connection vanished before we could attach — the turn has ended;
                // clear the placeholder and refetch the final transcript. Close the half-
                // open stream first or the consumer job and its socket linger (batch v130).
                closeStream()
                abortIdleReattach()
            }
        }
    }

    private fun applyEvent(event: AcpEvent, seq: Long) {
        if (seq > 0) lastSeq = seq
        val builder = liveBuilder
        when (event) {
            is AcpEvent.ContentDelta -> { builder?.appendText(event.text); scheduleLiveEmit() }
            is AcpEvent.Thinking -> { builder?.appendThinking(event.text); scheduleLiveEmit() }
            is AcpEvent.ToolCall -> {
                builder?.upsertToolCall(event.id, event.title, event.kind, event.status, event.rawInput, event.rawOutput, event.content, event.meta)
                scheduleLiveEmit()
            }
            is AcpEvent.ToolCallUpdate -> {
                builder?.updateToolCall(event.id, event.title, event.status, event.rawInput, event.rawOutput, event.content, event.append, event.meta)
                scheduleLiveEmit()
            }
            is AcpEvent.PlanUpdate -> { builder?.updatePlan(event.entries); scheduleLiveEmit() }
            is AcpEvent.UsageUpdate -> if (event.used > 0 && event.size > 0) _ui.update {
                val max = event.size.toInt()
                val used = event.used.toInt()
                // Agents re-send identical occupancy every few seconds (CodeBuddy does it
                // mid-turn); skip the write when nothing moved or this fires a full
                // screen-state pass for a value nobody can see change.
                val cur = it.sessionStats
                if (cur?.contextWindowUsedTokens == used && cur.contextWindowMaxTokens == max) it
                else it.copy(
                    sessionStats = (it.sessionStats ?: SessionStats()).copy(
                        contextWindowUsedTokens = used,
                        contextWindowMaxTokens = max,
                        contextWindowUsagePercent = (used.toDouble() / max * 100.0).coerceIn(0.0, 100.0),
                    ),
                )
            }
            is AcpEvent.ConversationLinked -> adoptLinkedConversation(event.conversationId)
            is AcpEvent.StatusChanged -> when (event.status) {
                ConnectionStatus.ERROR -> failLive(appContext.getString(R.string.live_agent_connection_errored))
                ConnectionStatus.PROMPTING -> _ui.update {
                    // The agent is actively prompting — reflect it as "Thinking…" unless a tool is
                    // currently running (that status is more specific). Mirrors the iOS .prompting.
                    if (it.isInFlight && liveBuilder?.activeToolTitle() == null) it.copy(sendStatus = STATUS_THINKING) else it
                }
                // .connecting is a no-op here: the status line only renders while in-flight, and
                // by then we're already past connecting (iOS shows it only from its idle state).
                else -> Unit
            }
            is AcpEvent.TurnComplete -> finalizeTurn(event.stopReason)
            is AcpEvent.Error -> failLive(event.message)
            is AcpEvent.PermissionRequest -> _ui.update {
                it.copy(pendingPermission = PendingPermissionUi(event.requestId, ParsedPermission.parse(event.toolCall), event.options))
            }
            is AcpEvent.PermissionResolved -> _ui.update {
                if (it.pendingPermission?.requestId == event.requestId) it.copy(pendingPermission = null) else it
            }
            is AcpEvent.QuestionRequest -> _ui.update {
                it.copy(pendingQuestion = PendingQuestionUi(event.questionId, event.questions))
            }
            is AcpEvent.QuestionResolved -> _ui.update {
                if (it.pendingQuestion?.questionId == event.questionId) it.copy(pendingQuestion = null) else it
            }
            // Grok finished planning and is blocked until the user decides. The card is
            // pinned above the compose bar (like the permission/question ones), so no
            // scroll nudge is needed — it can't be scrolled out of view.
            is AcpEvent.PlanApprovalRequest -> _ui.update {
                it.copy(pendingPlanApproval = PendingPlanApprovalUi(event.approvalId, event.toolCallId, event.planMarkdown))
            }
            is AcpEvent.PlanApprovalResolved -> _ui.update {
                if (it.pendingPlanApproval?.approvalId == event.approvalId) it.copy(pendingPlanApproval = null) else it
            }
            is AcpEvent.SessionStarted -> {
                // The CLI reports its own session id here (and again on reconnects); the
                // session-info card and the handoff JSON both read it.
                externalId = event.sessionId
                _ui.update { it.copy(externalId = event.sessionId) }
                // The CLI (re)started: the cached snapshot's connection id and
                // config options belong to the previous process.
                evictSnapshotCache()
            }
            is AcpEvent.ForkSupported -> _ui.update { it.copy(forkSupported = event.supported) }
            // Same expert filter as [loadSlashCommands]: an expert-backed name must not
            // surface as a slash command regardless of which channel advertised it.
            is AcpEvent.AvailableCommands -> _ui.update {
                val filtered = event.commands.filter { cmd -> cmd.name !in expertIds }
                // CodeBuddy re-publishes the full command list mid-turn; an identical
                // payload must not churn the screen state.
                if (filtered.isEmpty() || filtered == it.availableCommands) it else it.copy(availableCommands = filtered)
            }
            // The agent (re)published its config options — the model selector's current
            // value is what the status line shows. Replace wholesale: this event is the
            // connection's authoritative option state (web parity).
            is AcpEvent.SessionConfigOptions -> _ui.update {
                if (event.options.isNotEmpty() && event.options != it.configOptions) it.copy(configOptions = event.options) else it
            }
            else -> Unit // user_message / etc. are informational
        }
    }

    /**
     * A blocked permission / question / plan-approval can't outlive its turn: clear any
     * pending card when the turn finalizes, fails, or is cancelled, and return the
     * composer to Send.
     *
     * Parked plan-revision notes are dropped here too — they are meaningful only as the
     * immediate follow-up to the keep-planning turn that produced them. A turn that
     * failed or was cancelled never gets that follow-up, and leaving them parked would
     * let a LATER, unrelated turn's completion send them. [finalizeTurn] — the one path
     * that legitimately delivers them — reads them before calling this.
     */
    private fun clearInteractivePrompts() {
        pendingPlanFollowUp = null
        _ui.update {
            it.copy(
                isInFlight = false,
                runStartedAt = null,
                sendStatus = null,
                pendingPermission = null,
                pendingQuestion = null,
                pendingPlanApproval = null,
            )
        }
    }

    /** A new task's first prompt links a server-side conversation. Android creates that
     *  row up front (so [conversationId] is already set), but has no summary for it yet —
     *  background-fetch the identity (summary + stats) so the header shows the real title
     *  and usage DURING the first turn instead of only after [reconcileAfterTurn]. Never
     *  touches the live turn or transcript. Gated on a missing summary, so it's a no-op for
     *  an existing session (which already carries one). Mirrors iOS `adoptLinkedConversation`. */
    private fun adoptLinkedConversation(id: Int) {
        if (conversationId == null) conversationId = id
        if (_ui.value.summary != null) return
        val c = client ?: return
        val target = conversationId ?: id
        viewModelScope.launch {
            val detail = runCatching { c.conversationDetail(target) }.getOrNull() ?: return@launch
            // Bail if the conversation changed, or a summary arrived, while we fetched.
            if (conversationId != target) return@launch
            _ui.update {
                if (it.summary != null) it
                else it.copy(summary = detail.summary, sessionStats = detail.sessionStats ?: it.sessionStats)
            }
        }
    }

    fun respondPermission(optionId: String) {
        val pending = _ui.value.pendingPermission ?: return
        val conn = connectionId ?: return
        _ui.update { it.copy(pendingPermission = null) }
        viewModelScope.launch {
            runCatching { client?.respondPermission(conn, pending.requestId, optionId) }
                .onFailure { e -> _ui.update { it.copy(notice = e.displayMessage(), pendingPermission = pending) } }
        }
    }

    fun answerQuestion(answer: app.codeg.android.core.model.QuestionAnswer) {
        val pending = _ui.value.pendingQuestion ?: return
        val conn = connectionId ?: return
        _ui.update { it.copy(pendingQuestion = null) }
        viewModelScope.launch {
            runCatching { client?.answerQuestion(conn, pending.questionId, answer) }
                .onFailure { e -> _ui.update { it.copy(notice = e.displayMessage(), pendingQuestion = pending) } }
        }
    }

    fun dismissQuestion() = answerQuestion(app.codeg.android.core.model.QuestionAnswer.dismissed)

    /**
     * Resolve Grok's blocked `exit_plan_mode`. Optimistic clear on success; the card
     * comes back (with a notice) if the post fails.
     *
     * "Request changes" needs one extra step: Grok DISCARDS the reply's `feedback` on
     * the keep-planning path (only approve/abandon consume it), and its own TUI instead
     * delivers the revision notes as a follow-up user turn. Mirror that — otherwise the
     * notes vanish and Grok re-presents the same plan. The keep-planning turn is usually
     * still winding down at this point, so the follow-up is parked and flushed when the
     * turn completes.
     */
    fun answerPlanApproval(decision: PlanApprovalDecision, feedback: String?) {
        val pending = _ui.value.pendingPlanApproval ?: return
        val conn = connectionId ?: return
        val notes = feedback?.trim().orEmpty()
        // The turn that produced this approval. The post below is a suspension point, so
        // that turn can finalize, fail, be cancelled, or be replaced by an unrelated new
        // one before we get to act on the notes — the identity check below is what keeps
        // the follow-up bound to the turn it belongs to.
        val origin = liveBuilder
        _ui.update { it.copy(pendingPlanApproval = null) }
        viewModelScope.launch {
            runCatching { client?.answerPlanApproval(conn, pending.approvalId, decision, notes.ifEmpty { null }) }
                .onSuccess {
                    if (decision != PlanApprovalDecision.REQUEST_CHANGES || notes.isEmpty()) return@onSuccess
                    when (
                        planFollowUpAction(
                            sameTurn = liveBuilder === origin,
                            inFlight = _ui.value.isInFlight,
                            stopReason = origin?.stopReason,
                            errorMessage = origin?.errorMessage,
                        )
                    ) {
                        PlanFollowUp.PARK -> pendingPlanFollowUp = origin?.let { ParkedPlanNotes(it.id, notes) }
                        PlanFollowUp.SEND_NOW -> send(notes, withAttachments = false)
                        PlanFollowUp.DROP ->
                            _ui.update { it.copy(notice = appContext.getString(R.string.plan_approval_notes_dropped)) }
                    }
                }
                .onFailure { e -> _ui.update { it.copy(notice = e.displayMessage(), pendingPlanApproval = pending) } }
        }
    }

    /** Seed the live builder from a reattach snapshot, returning true when the session is
     *  actually in flight (a live message, active tools, a pending card, or actively
     *  prompting) and false when the connection is idle — the caller then aborts the
     *  reattach. Flags the turn reattach-owned so the transcript hides the partial copy
     *  the server has persisted. Mirrors the iOS `buildLiveTurn(from:)` nil check. */
    private fun seedLiveFromSnapshot(snap: app.codeg.android.core.model.LiveSessionSnapshot): Boolean {
        val builder = liveBuilder ?: return false
        val inFlight = !snap.liveMessage?.content.isNullOrEmpty() ||
            snap.activeToolCalls.isNotEmpty() ||
            snap.pendingPermission != null || snap.pendingQuestion != null ||
            snap.pendingPlanApproval != null ||
            snap.status == app.codeg.android.core.model.ConnectionStatus.PROMPTING
        if (!inFlight) return false
        pendingReattachSeed = false
        if (builder.isEmpty) builder.seedFrom(snap)
        // Only claim the reply (hiding the server's persisted partial copy) when the seed
        // actually produced content. A pending-only snapshot (a permission/question with no
        // assistant message yet) seeds nothing, so hiding the transcript there could drop a
        // persisted reply with no live copy to show in its place.
        _ui.update {
            it.copy(
                live = builder.snapshot(),
                liveFromReattach = !builder.isEmpty,
                // The server owns the turn clock; using it here keeps the elapsed time
                // continuous across a mid-turn reattach (a local clock would restart).
                runStartedAt = snap.liveMessage?.startedAt ?: it.runStartedAt,
                externalId = snap.externalId ?: it.externalId,
                availableCommands = snap.availableCommands.ifEmpty { it.availableCommands },
                configOptions = snap.configOptions.ifEmpty { it.configOptions },
            )
        }
        return true
    }

    /** The reattach snapshot showed an idle connection — nothing is in flight. Clear the
     *  placeholder live turn, drop the connection, tear the stream down, and reconcile in
     *  case the turn just finished (leaving the loaded transcript a beat stale). */
    private fun abortIdleReattach() {
        pendingReattachSeed = false
        connectionId = null
        liveBuilder = null
        _ui.update { it.copy(isInFlight = false, runStartedAt = null, sendStatus = null, live = null, liveFromReattach = false) }
        closeStream()
        reconcileAfterMissedLive()
    }

    /** Server reported a live turn at load but no in-flight state surfaced — refetch so
     *  the transcript shows the final reply rather than the beat-stale load. M1: a
     *  windowed session refetches its slice (fromIndex = window start) instead of the
     *  full transcript; a seam mismatch (the prefix was rewritten underneath us) falls
     *  back to a full tail reload, same as the web client. */
    private fun reconcileAfterMissedLive() {
        val c = client ?: return
        val id = conversationId ?: return
        val seqAtFetch = windowOpSeq
        viewModelScope.launch {
            val offset = _ui.value.turnsOffset
            val d = runCatching {
                if (offset != null) c.conversationDetail(id, fromIndex = offset)
                else c.conversationDetail(id)
            }.getOrNull() ?: return@launch
            // A splice/tail reload landed while this fetch was out — its coordinates
            // are stale; drop rather than clobber the newer window state.
            if (windowOpSeq != seqAtFetch) return@launch
            val state = _ui.value
            // Windowed-response consistency (web fetchDetailWindowed parity): the
            // parse must still be ours — prefix fingerprint unchanged — and the
            // total must cover the requested offset. A mid-run compaction rewrites
            // the prefix; mixing its window under old-parse paged pages is worse
            // than one whole tail reload, so mismatch falls back to that.
            val parseMatches = offset == null || d.turnsOffset == null ||
                (d.prefixHash != null && d.prefixHash == state.windowPrefixHash &&
                    (d.turnsTotal ?: 0) >= offset)
            if (state.windowPrefixHash != null && d.prefixHash != null && d.prefixHash != state.windowPrefixHash ||
                !parseMatches
            ) {
                reloadTailWindow()
            } else {
                adoptWindowDetail(d)
            }
        }
    }

    /** M1: fold a fetched detail (windowed or legacy) into the transcript state —
     *  the single place that owns the window fields on a wholesale replace. Bumps
     *  [windowOpSeq]: any detail/page fetch still in flight predates this state. */
    private fun adoptWindowDetail(d: ConversationDetail) {
        windowOpSeq++
        _ui.update {
            it.copy(
                turns = d.turns,
                turnsOffset = d.turnsOffset,
                turnsTotal = d.turnsTotal,
                windowPrefixHash = d.prefixHash,
                assistantTurnsBeforeOffset = d.assistantTurnsBeforeOffset,
                userTurnsBeforeOffset = d.userTurnsBeforeOffset,
                sessionStats = d.sessionStats ?: it.sessionStats,
                summary = d.summary,
            )
        }
    }

    /** M1 reverse-infinite-scroll: prepend the page of turns just before the
     *  window start. The page's `prefix_hash_before_index` must equal our window
     *  fingerprint — the seam proof that the prefix did not change between the
     *  original load and now; a mismatch (or an offset/page-size inconsistency)
     *  means the page cannot legally join, and we reload the tail window whole.
     *  Compose's LazyListState anchors the viewport by item key, so the prepend
     *  never shifts what the user is looking at. Silent on failure: the sentinel
     *  row just re-fires on the next scroll attempt. */
    fun loadOlderTurns() {
        val c = client ?: return
        val id = conversationId ?: return
        val offset = _ui.value.turnsOffset ?: return // legacy full transcript — nothing older
        if (_ui.value.loadingOlder || offset <= 0) return
        _ui.update { it.copy(loadingOlder = true) }
        val seqAtFetch = windowOpSeq
        viewModelScope.launch {
            try {
                val page = c.conversationTurnsPage(id, beforeIndex = offset, limit = OLDER_TURNS_PAGE_SIZE)
                // A reconcile/adopt landed while the page was out — its join check
                // would run against coordinates we no longer hold; drop the page.
                if (windowOpSeq != seqAtFetch) {
                    _ui.update { it.copy(loadingOlder = false) }
                    return@launch
                }
                val seam = _ui.value.windowPrefixHash
                // Strict seam proof (web parity): no hash, no join — a window at
                // offset > 0 always carries a prefix fingerprint on a compliant
                // server; a missing one must not splice unchecked.
                val joins = page.turnsOffset + page.turns.size == offset &&
                    seam != null && page.prefixHashBeforeIndex == seam
                if (!joins) {
                    reloadTailWindow()
                    return@launch
                }
                windowOpSeq++
                _ui.update {
                    it.copy(
                        turns = page.turns + it.turns,
                        turnsOffset = page.turnsOffset,
                        turnsTotal = page.turnsTotal,
                        windowPrefixHash = page.prefixHash,
                        assistantTurnsBeforeOffset = page.assistantTurnsBeforeOffset,
                        userTurnsBeforeOffset = page.userTurnsBeforeOffset,
                        loadingOlder = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _ui.update { it.copy(loadingOlder = false) }
            }
        }
    }

    /** M1 seam-mismatch fallback: refetch the tail window whole (web parity).
     *  Clears loadingOlder whatever the outcome, so the sentinel can retry. */
    private fun reloadTailWindow() {
        val c = client
        val id = conversationId
        if (c == null || id == null) {
            _ui.update { it.copy(loadingOlder = false) }
            return
        }
        viewModelScope.launch {
            runCatching { c.conversationDetail(id, tailTurns = TAIL_TURNS_DEFAULT) }
                .getOrNull()?.let { adoptWindowDetail(it) }
            _ui.update { it.copy(loadingOlder = false) }
        }
    }

    private fun restorePending(snap: app.codeg.android.core.model.LiveSessionSnapshot) {
        _ui.update {
            it.copy(
                externalId = snap.externalId ?: it.externalId,
                availableCommands = snap.availableCommands.ifEmpty { it.availableCommands },
                configOptions = snap.configOptions.ifEmpty { it.configOptions },
            )
        }
        snap.pendingPermission?.let { p ->
            _ui.update { it.copy(pendingPermission = PendingPermissionUi(p.requestId, ParsedPermission.parse(p.toolCall), p.options)) }
        }
        snap.pendingQuestion?.let { q ->
            _ui.update { it.copy(pendingQuestion = PendingQuestionUi(q.questionId, q.questions)) }
        }
        // Set OR clear: the attach snapshot is the connection's authoritative pending
        // state, so an approval another client resolved while we were reconnecting must
        // not leave a stale, still-actionable card behind (answering it would post a
        // decision for an approval that no longer exists). The permission/question
        // restores above deliberately keep their existing set-only behaviour.
        _ui.update {
            it.copy(
                pendingPlanApproval = snap.pendingPlanApproval
                    ?.let { p -> PendingPlanApprovalUi(p.approvalId, p.toolCallId, p.planMarkdown) },
            )
        }
    }

    private fun finalizeTurn(stopReason: String) {
        val builder = liveBuilder ?: return
        builder.isStreaming = false
        builder.stopReason = stopReason
        emitLiveNow()
        // Return the composer to Send and clear the status line immediately — the turn is done.
        // The transcript keeps showing the finished live reply until `reconcileAfterTurn` swaps
        // in the server copy in the background. Mirrors iOS's computed `isInFlight`, which flips
        // the instant `live.isStreaming` goes false rather than waiting on the reconcile.
        // Read before clearing: this is the one transition that delivers parked
        // plan-revision notes (every other terminal path drops them). Notes parked by a
        // DIFFERENT turn — one whose own completion never came, so a reconcile ended it
        // instead — are dropped here rather than riding out on this unrelated turn.
        val parked = pendingPlanFollowUp
        val planFollowUp = parked?.notesFor(builder.id)
        clearInteractivePrompts()
        if (parked != null && planFollowUp == null) {
            _ui.update { it.copy(notice = appContext.getString(R.string.plan_approval_notes_dropped)) }
        }
        closeStream()
        // Turn boundaries invalidate the cached snapshot (the CLI may have switched
        // modes mid-turn); the next sheet open re-reads it.
        evictSnapshotCache()
        reconcileAfterTurn()
        // The keep-planning turn just ended — deliver the revision notes as the follow-up
        // prompt Grok expects (it discards them on the reply itself). `send` supersedes
        // the reconcile it just started and folds the finished reply in locally, exactly
        // as a user typing the instant a turn ends would.
        if (planFollowUp != null) send(planFollowUp, withAttachments = false)
    }

    private fun failLive(message: String) {
        val builder = liveBuilder
        if (builder != null) {
            builder.errorMessage = message
            builder.isStreaming = false
            emitLiveNow()
        }
        closeStream()
        clearInteractivePrompts()
    }

    private fun handleStreamEnded(gen: Int, reason: String?) {
        if (gen != streamGeneration) return
        // If the turn already finished, this is a normal close.
        if (!_ui.value.isInFlight) return
        // `connection_gone` is terminal — the ACP connection itself is gone, so a
        // socket reopen can't recover it; reconcile against the server transcript.
        if (reason == REASON_CONNECTION_GONE) {
            // Terminal: the ACP connection itself is gone, so reopening the socket can't
            // recover it. Supersede the consumer first so the socket's own trailing close
            // can't fall through to a doomed reconnect against the dead connection.
            closeStream()
            reconcileAfterTurn()
            return
        }
        // Any other drop (socket blip, `lagged`, `server_shutdown`) is transient: the
        // ACP connection outlives the socket, so re-attach with `since_seq` and let
        // the server replay whatever we missed instead of ending the live turn.
        reconnectStream()
    }

    /** Silently re-attach a fresh stream to the same connection after a transient
     *  drop, resuming from [lastSeq]. Backs off exponentially (0.5s→8s) and, after
     *  [MAX_STREAM_RECONNECTS] tries, gives up to a one-shot transcript reconcile so
     *  the final reply is never lost. Mirrors the iOS `reconnectStream`. */
    private fun reconnectStream() {
        streamReconnects += 1
        if (streamReconnects > MAX_STREAM_RECONNECTS) {
            reconcileAfterTurn()
            return
        }
        val conn = connectionId ?: run { reconcileAfterTurn(); return }
        // Supersede the dying consumer immediately so its trailing frames can't slip
        // through the reconnect budget during the backoff window.
        streamGeneration += 1
        consumerJob?.cancel()
        consumerJob = null
        val gen = streamGeneration
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            val backoffMs = (500L shl (streamReconnects - 1)).coerceAtMost(8_000L)
            _ui.update { if (it.isInFlight) it.copy(sendStatus = "Reconnecting…") else it }
            delay(backoffMs)
            // Bail if the turn ended or was superseded (cancel / finalize) while we waited.
            if (gen != streamGeneration || !_ui.value.isInFlight) return@launch
            connectStream(conn, awaitAttach = false, seedOnAttach = pendingReattachSeed)
        }
    }

    private fun reconcileAfterTurn() {
        val c = client ?: return
        val id = conversationId ?: return
        // Capture the turn this reconcile belongs to. If a new send / cancel supersedes the
        // live builder while we poll, bail rather than wiping the newer turn's state (its
        // pending user turn, live reply, in-flight flag). Mirrors iOS's `liveTurn === live`.
        val builder = liveBuilder
        reconcileJob?.cancel()
        reconcileJob = viewModelScope.launch {
            repeat(5) { attempt ->
                delay(if (attempt == 0) 300 else 600)
                if (liveBuilder !== builder) return@launch
                // M1: a windowed session refetches its slice — the turn that just
                // finished appends at the END of the transcript, always inside the
                // window. turnBaseline is a GLOBAL turn count, so advance is judged
                // in global coordinates: offset + slice size.
                val offset = _ui.value.turnsOffset
                val seqAtFetch = windowOpSeq
                val detail = runCatching {
                    if (offset != null) c.conversationDetail(id, fromIndex = offset)
                    else c.conversationDetail(id)
                }.getOrNull() ?: return@repeat
                // A page splice / tail reload landed while this fetch was out: our
                // coordinates are stale, and adopting would clobber the spliced
                // window (drop paged turns). Re-derive from the current state on
                // the next attempt instead.
                if (windowOpSeq != seqAtFetch) return@repeat
                val last = detail.turns.lastOrNull()
                val advanced = (offset ?: 0) + detail.turns.size > turnBaseline &&
                    last?.role == TurnRole.ASSISTANT && last.blocks.isNotEmpty()
                if (advanced) {
                    if (liveBuilder !== builder) return@launch
                    // Windowed-response consistency (web fetchDetailWindowed parity):
                    // the response's parse must still match ours — prefix fingerprint
                    // unchanged, total covering the requested offset — or the new-parse
                    // window would sit under old-parse paged pages. Fall back to one
                    // whole tail reload (drops paged history) instead of adopting.
                    val parseMatches = offset == null || detail.turnsOffset == null ||
                        (detail.prefixHash != null && detail.prefixHash == _ui.value.windowPrefixHash &&
                            (detail.turnsTotal ?: 0) >= offset)
                    // This turn ended without a `turn_complete` of its own (a dead
                    // connection, an exhausted reconnect), so `finalizeTurn` never ran:
                    // resolve its parked plan-revision notes here instead of leaving them
                    // for whatever turn finalizes next.
                    val orphanedNotes = builder?.let { pendingPlanFollowUp?.notesFor(it.id) }
                    if (orphanedNotes != null) pendingPlanFollowUp = null
                    if (!parseMatches) {
                        _ui.update {
                            it.copy(
                                pendingUserTurns = emptyList(),
                                live = null,
                                liveFromReattach = false,
                                isInFlight = false,
                                runStartedAt = null,
                                sendStatus = null,
                                pendingPermission = null,
                                pendingQuestion = null,
                                pendingPlanApproval = null,
                                notice = if (orphanedNotes != null) {
                                    appContext.getString(R.string.plan_approval_notes_dropped)
                                } else {
                                    it.notice
                                },
                            )
                        }
                        liveBuilder = null
                        maybeFlushQueue()
                        reloadTailWindow()
                        return@launch
                    }
                    _ui.update {
                        it.copy(
                            turns = detail.turns,
                            turnsOffset = detail.turnsOffset ?: it.turnsOffset,
                            turnsTotal = detail.turnsTotal,
                            windowPrefixHash = detail.prefixHash,
                            assistantTurnsBeforeOffset = detail.assistantTurnsBeforeOffset
                                ?: it.assistantTurnsBeforeOffset,
                            userTurnsBeforeOffset = detail.userTurnsBeforeOffset
                                ?: it.userTurnsBeforeOffset,
                            sessionStats = detail.sessionStats ?: it.sessionStats,
                            summary = detail.summary,
                            pendingUserTurns = emptyList(),
                            live = null,
                            liveFromReattach = false,
                            isInFlight = false,
                            runStartedAt = null,
                            sendStatus = null,
                            pendingPermission = null,
                            pendingQuestion = null,
                            pendingPlanApproval = null,
                            notice = if (orphanedNotes != null) {
                                appContext.getString(R.string.plan_approval_notes_dropped)
                            } else {
                                it.notice
                            },
                        )
                    }
                    liveBuilder = null
                    // The one flush trigger: the turn's transcript landed on the server.
                    maybeFlushQueue()
                    return@launch
                }
            }
            // Couldn't confirm — fold the streamed reply in locally so nothing is lost.
            if (liveBuilder === builder) promoteUnreconciled()
        }
    }

    private fun promoteUnreconciled() {
        val builder = liveBuilder
        _ui.update { state ->
            // On a reattach-owned reply, the tail of `turns` is the server's partial copy
            // of the same reply — drop it so folding the live turn doesn't double it.
            val base = if (state.liveFromReattach) state.turns.dropTrailingAssistantRun() else state.turns
            val folded = buildList {
                addAll(base)
                addAll(state.pendingUserTurns)
                // Only fold in an assistant turn that has renderable blocks. A finalized
                // builder can be non-empty solely because of an inline error / "Cancelled."
                // message (which snapshotAsMessageTurn can't represent as a block), and folding
                // its zero-block snapshot would render as an empty "No content" turn (iOS parity).
                if (builder != null && !builder.isEmpty) {
                    val snapshot = builder.snapshotAsMessageTurn()
                    if (snapshot.blocks.isNotEmpty()) add(snapshot)
                }
            }
            state.copy(
                turns = folded,
                pendingUserTurns = emptyList(),
                live = null,
                liveFromReattach = false,
                isInFlight = false,
                runStartedAt = null,
                sendStatus = null,
            )
        }
        liveBuilder = null
    }

    private fun discardOptimistic(
        userTurn: MessageTurn,
        text: String,
        attachments: List<Attachment>,
        createdConversation: Boolean,
        createdConversationId: Int?,
        notice: String,
    ) {
        liveBuilder = null
        closeStream()
        // A failed FIRST send may have created a conversation row up front; delete THAT exact
        // orphan (by the id this send created, not the current conversationId) so it doesn't
        // linger, and never touch a conversation a later send may already be using. Runs on
        // [appScope] — the discard itself usually IS the post-cleared failure path.
        if (createdConversationId != null) {
            appScope.launch {
                runCatching { client?.deleteConversation(createdConversationId) }
                repository.notifyConversationsChanged()
            }
        }
        // Reopen the draft whenever this was a first send that failed before the prompt landed —
        // whether the row was created-then-rolled-back OR creation itself failed (id == null) — so
        // the agent/folder pickers are editable for the retry. iOS resets first-send state whenever
        // no conversation exists.
        if (createdConversation) {
            conversationId = null
            externalId = null
            connectionId = null
            hasStartedFirstSend = false
        }
        _ui.update {
            it.copy(
                pendingUserTurns = it.pendingUserTurns.filterNot { t -> t.id == userTurn.id },
                live = null,
                isInFlight = false,
                runStartedAt = null,
                sendStatus = null,
                notice = notice,
                // Restore the composer so a failed send never silently loses the user's work —
                // but don't clobber fresh input the user added during the in-flight window (the
                // draft guard lives in the screen; attachments are owned here). iOS parity.
                restoreDraft = text.ifEmpty { null },
                attachments = if (it.attachments.isEmpty()) attachments else it.attachments,
                summary = if (createdConversation) null else it.summary,
                isNew = if (createdConversation) true else it.isNew,
                isDraftEditable = if (createdConversation) true else it.isDraftEditable,
            )
        }
    }

    /** The screen consumes [SessionDetailUiState.restoreDraft] back into the composer once.
     *  [applied] tells whether the composer actually took the text (it refuses when the
     *  user already typed a fresher draft during the in-flight window) — only then is the
     *  returned text re-persisted, so a stale failed-send copy can never overwrite it. */
    fun consumeRestoredDraft(applied: Boolean) {
        val restored = _ui.value.restoreDraft ?: return
        _ui.update { it.copy(restoreDraft = null) }
        if (applied) saveDraft(restored)
    }

    fun dismissNotice() = _ui.update { it.copy(notice = null) }

    // endregion

    // region Queued drafts (send-while-running; MindFS-style bar + auto flush)

    /** Add [text] to the queue while a turn is running. The screen clears its composer
     *  after calling this (same contract as [send]). No-op on blank text. */
    fun enqueueDraft(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        _ui.update {
            it.copy(queue = it.queue + QueuedDraft(UUID.randomUUID().toString(), trimmed, Instant.now()))
        }
    }

    /** Pull a queued item back into the composer (edit-withdraw): the row leaves the
     *  queue and its text rides the existing restoreDraft → composer chain. */
    fun dequeueForEdit(id: String) {
        val item = _ui.value.queue.firstOrNull { it.id == id } ?: return
        _ui.update { it.copy(queue = it.queue.filterNot { d -> d.id == id }, restoreDraft = item.text) }
    }

    fun removeQueued(id: String) {
        _ui.update { it.copy(queue = it.queue.filterNot { d -> d.id == id }) }
    }

    /**
     * "Insert now" on a queued row: inject the text into the RUNNING turn's steering
     * channel. Outcomes:
     * - injected → the row leaves the queue;
     * - `no active turn` (the turn ended between tap and post — message-text match,
     *   the HTTP layer folds every feedback error into 400 `invalid_input`) →
     *   the row STAYS queued and the queue flush runs; [maybeFlushQueue] sends the
     *   head through the ordinary prompt path (with backoff retries). An immediate
     *   [send] here would be silently dropped — `send` no-ops while
     *   `isInFlight` is still true (the TurnComplete frame may not have landed
     *   yet), which would delete the row and lose the message. The row is also
     *   NOT deleted before flushing: if it isn't the queue head it waits for a
     *   later turn end like any other queued draft (FIFO), instead of jumping ahead.
     * - anything else (`live feedback is disabled`, stale connection, oversized
     *   draft, network) → the row stays queued with a notice. Keeping the row is
     *   deliberate: a later flush delivers it as a normal prompt, so nothing is lost.
     */
    fun insertQueuedNow(id: String) {
        val item = _ui.value.queue.firstOrNull { it.id == id } ?: return
        val c = client ?: return
        val conn = connectionId
        if (conn == null) {
            _ui.update { it.copy(notice = appContext.getString(R.string.queue_insert_unsupported)) }
            return
        }
        viewModelScope.launch {
            try {
                c.submitSessionFeedback(conn, item.text)
                removeQueued(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isNoActiveTurnError(e)) {
                    // The turn just ended — deliver through the queue flush.
                    maybeFlushQueue()
                } else {
                    _ui.update { it.copy(notice = appContext.getString(R.string.queue_insert_unsupported)) }
                }
            }
        }
    }

    /**
     * Try to flush the queue's head as a normal message. Called from
     * [reconcileAfterTurn]'s advanced branch and from [insertQueuedNow]'s
     * no-active-turn fallback (finalizeTurn funnels through reconcile, so a hook
     * there would double-fire). The delay gives the reconcile room to finish —
     * an immediate send would cancel it mid-flight and fold the finished turn as a
     * `live-*` snapshot, losing its usage/duration/model/completedAt footer.
     */
    private fun maybeFlushQueue() {
        if (flushJob?.isActive == true) return
        val c = client ?: return
        val id = conversationId ?: return
        flushJob = viewModelScope.launch {
            // Let the post-turn reconcile settle before starting a new turn.
            delay(QUEUE_FLUSH_LEAD_MS)
            var attempt = 0
            while (true) {
                if (_ui.value.isInFlight) return@launch
                // A fork in flight re-points the session row; hold the queue until
                // it lands (the reload flushes again on the new session).
                if (_ui.value.forking) return@launch
                val head = _ui.value.queue.firstOrNull() ?: return@launch
                when (sendQueued(c, id, head.text)) {
                    SendOutcome.SENT -> {
                        removeQueued(head.id)
                        return@launch
                    }
                    SendOutcome.BUSY -> return@launch
                    SendOutcome.FAILED -> {
                        attempt += 1
                        if (attempt >= QUEUE_FLUSH_TRIES) {
                            _ui.update { it.copy(notice = appContext.getString(R.string.queue_flush_failed)) }
                            return@launch
                        }
                        delay(flushBackoffMs(attempt))
                    }
                }
            }
        }
    }

    /** The flush's send: same server path as [runSend] but no optimistic bubble (the
     *  queued row is the visual), no conversation creation (the queue only exists on
     *  a live session) and — critically — failures RETURN instead of discarding to
     *  the composer, so the row never silently vanishes. */
    private suspend fun sendQueued(c: CodegClient, conversation: Int, text: String): SendOutcome {
        return try {
            val conn = connectionId ?: run {
                val info = runCatching { c.findConnection(conversation, externalId, agent) }.getOrNull()
                    ?: return SendOutcome.FAILED
                connectionId = info.connectionId
                info.connectionId
            }
            openStream(conn)
            c.prompt(
                connectionId = conn,
                blocks = listOf(PromptInputBlock.Text(text)),
                folderId = folder?.id,
                conversationId = conversation,
                clientMessageId = UUID.randomUUID().toString(),
            )
            SendOutcome.SENT
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError.TurnInProgress) {
            SendOutcome.BUSY
        } catch (e: Exception) {
            SendOutcome.FAILED
        }
    }

    /** 5s → 15s → 45s between flush retries (×3 per attempt, exponent capped).
     *  Internal for tests. */
    internal fun flushBackoffMs(attempt: Int): Long {
        var ms = 5_000L
        repeat((attempt - 1).coerceIn(0, 3)) { ms *= 3 }
        return ms
    }

    // endregion

    // region Live emit coalescing (~50ms, matching iOS LiveTextRun)

    private fun scheduleLiveEmit() {
        if (liveFlushScheduled) return
        liveFlushScheduled = true
        viewModelScope.launch {
            delay(50)
            liveFlushScheduled = false
            emitLiveNow()
        }
    }

    private fun emitLiveNow() {
        val snapshot = liveBuilder?.snapshot()
        _ui.update { it.copy(live = snapshot) }
    }

    private fun closeStream() {
        streamGeneration += 1 // supersede the current consumer so its Closed is ignored
        reconnectJob?.cancel()
        reconnectJob = null
        consumerJob?.cancel()
        consumerJob = null
        stream = null
    }

    override fun onCleared() {
        // A send must survive this screen closing — the prompt POST may still be in
        // flight on [appScope], and cancelling here is exactly the lost-message bug
        // (bubble + draft gone, server never saw the turn). The job ends on its own;
        // its late `_ui` writes land on a StateFlow with no subscribers.
        cleared = true
        closeStream()
        super.onCleared()
    }

    // endregion

    // region Conversation actions (rename / pin / status / delete)

    /** True once the session is server-linked and its summary has loaded. */
    val canManage: Boolean get() = conversationId != null && _ui.value.summary != null
    val isPinned: Boolean get() = _ui.value.summary?.pinnedAt != null

    fun rename(title: String) {
        val c = client ?: return
        val id = conversationId ?: return
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        val previous = _ui.value.summary
        _ui.update { it.copy(summary = it.summary?.copy(title = trimmed)) }
        viewModelScope.launch {
            runCatching { c.renameConversation(id, trimmed) }
                .onSuccess { repository.notifyConversationsChanged() }
                .onFailure { e -> _ui.update { it.copy(summary = previous, notice = e.displayMessage()) } }
        }
    }

    /** Fork this conversation's session at [turnId] (batch v134). The gate is
     *  `ui.forkSupported` — the server's own verdict for the attached CLI, fed by the
     *  RPC snapshot, the WS snapshot and the `fork_supported` event. Agents the server
     *  does not advertise (ZCode today) never see the button.
     *
     *  Semantics: the server re-points the CURRENT conversation row at the new
     *  session (history truncated to the branch point, title gets a `[Fork] ` prefix)
     *  and adds a sibling row keeping the original history — so on success we STAY on
     *  this screen: [load] re-pulls the now-forked detail, and the lists notification
     *  surfaces the sibling. No navigation. */
    fun forkFromTurn(turnId: String) {
        val c = client ?: return
        if (!_ui.value.forkSupported || _ui.value.isInFlight || _ui.value.forking) return
        val convId = conversationId ?: return
        // One-shot: a double tap must not post two acp_fork calls (the second would
        // race the first's row re-point).
        _ui.update { it.copy(forking = true) }
        viewModelScope.launch {
            var heldByReload = false
            try {
                // Fork rides the conversation's attached CLI connection. Prefer the live
                // stream's connection, then a FRESH directory lookup, then the RPC
                // snapshot's report — last, because a cached id can predate a reconnect.
                val conn = connectionId
                    ?: runCatching { c.findConnection(convId, externalId, agent)?.connectionId }.getOrNull()
                    ?: snapshotConnectionId
                if (conn == null) {
                    _ui.update { it.copy(notice = appContext.getString(R.string.fork_unavailable)) }
                    return@launch
                }
                val result = try {
                    c.forkSession(conn, convId, folder?.id, turnId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A dead connection id must not poison the next attempt: the cached
                    // snapshot goes, and unless the server said "busy" (which proves the
                    // connection is alive) the live id does too, so the retry resolves
                    // a fresh one.
                    evictSnapshotCache()
                    if (e !is ApiError.TurnInProgress) connectionId = null
                    val msg = if (e is ApiError.TurnInProgress) {
                        appContext.getString(R.string.fork_turn_in_progress)
                    } else e.displayMessage()
                    _ui.update { it.copy(notice = msg) }
                    return@launch
                }
                // The server re-pointed THIS row at the forked session; keep the
                // screen's external id in step before the reload replaces it.
                externalId = result.forkedSessionId
                evictSnapshotCache()
                // The pre-fork turn is truncated server-side now. Drop the live builder
                // and any running reconcile BEFORE the reload, or that reconcile's tail
                // fetch would fold the truncated replies back into the new timeline.
                reconcileJob?.cancel()
                reconcileJob = null
                // A queued flush waiting out its 2s lead would otherwise prompt the
                // just-forked session (or collide with the re-point) with text the
                // user queued against the OLD history.
                flushJob?.cancel()
                flushJob = null
                liveBuilder = null
                _ui.update {
                    it.copy(
                        notice = appContext.getString(R.string.fork_succeeded),
                        live = null,
                        liveFromReattach = false,
                        isInFlight = false,
                        runStartedAt = null,
                        sendStatus = null,
                        // Prompts sent before the fork are either inside the truncated
                        // history (they come back with the reload) or no longer exist.
                        // Left in place they would render as a duplicate or a pending
                        // bubble that never lands.
                        pendingUserTurns = emptyList(),
                    )
                }
                repository.notifyConversationsChanged()
                // Hold the one-shot latch until the reload actually lands. Clearing it
                // here would reopen the button while the pre-fork timeline is still on
                // screen — a second tap would then fork the *new* session.
                heldByReload = true
                load().invokeOnCompletion { _ui.update { it.copy(forking = false) } }
            } finally {
                if (!heldByReload) _ui.update { it.copy(forking = false) }
            }
        }
    }

    fun togglePin() {
        val c = client ?: return
        val id = conversationId ?: return
        val summary = _ui.value.summary ?: return
        val pin = summary.pinnedAt == null
        // Optimistic: only `pinnedAt != null` drives the visual state, so any
        // non-null instant works as a placeholder until the list refetches.
        _ui.update { it.copy(summary = it.summary?.copy(pinnedAt = if (pin) summary.updatedAt else null)) }
        viewModelScope.launch {
            runCatching { c.setPinned(id, pin) }
                .onSuccess { repository.notifyConversationsChanged() }
                .onFailure { e -> _ui.update { it.copy(summary = summary, notice = e.displayMessage()) } }
        }
    }

    fun setStatus(status: ConversationStatus) {
        val c = client ?: return
        val id = conversationId ?: return
        val previous = _ui.value.summary
        _ui.update { it.copy(summary = it.summary?.copy(status = status)) }
        viewModelScope.launch {
            runCatching { c.setStatus(id, status.wire) }
                .onSuccess { repository.notifyConversationsChanged() }
                .onFailure { e -> _ui.update { it.copy(summary = previous, notice = e.displayMessage()) } }
        }
    }

    /** Delete the conversation; [onDeleted] runs (on the main thread) only after the server confirms. */
    fun deleteConversation(onDeleted: () -> Unit) {
        val c = client ?: return
        val id = conversationId ?: return
        viewModelScope.launch {
            runCatching { c.deleteConversation(id) }
                .onSuccess {
                    profile?.let { p -> projectRepo.removeRef(p.id, id) }
                    repository.notifyConversationsChanged(); onDeleted()
                }
                .onFailure { e -> _ui.update { it.copy(notice = e.displayMessage()) } }
        }
    }

    // endregion

    // region Compose insert (quick messages / experts / slash commands)

    /** The session's agent — drives the expert mention prefix (`$` for codex, `/` otherwise). */
    val agentForInsert: AgentType get() = agent

    suspend fun loadQuickMessages(): List<QuickMessage> =
        client?.let { runCatching { it.quickMessagesList() }.getOrDefault(emptyList()) } ?: emptyList()

    /** Experts linked to this session's agent (the insertable set). */
    suspend fun loadExpertsForInsert(): List<ExpertListItem> {
        val c = client ?: return emptyList()
        return runCatching { c.expertsListForAgent(agent) }.getOrDefault(emptyList())
    }

    /** Slash commands advertised for this session, excluding expert-backed names. Returns
     *  the commands plus the known-expert id set (so the UI can strip an existing
     *  expert mention when inserting another).
     *
     *  Order: the live WS event/snapshot → the per-conversation snapshot RPC → the
     *  per-agent catalog probe. The last step is what makes a draft (no conversation
     *  id yet) show commands at all. The same probes seed [SessionDetailUiState.configOptions]
     *  (the model selector) when they carry them — a non-empty result wins over what's
     *  cached, so a stale agent's slow response can't shadow a fresh context. */
    suspend fun loadSlashCommands(): List<AvailableCommandInfo> {
        val c = client ?: return emptyList()
        var commands = _ui.value.availableCommands
        if (commands.isEmpty()) {
            val id = conversationId
            val snap = id?.let { snapshotFor(c, it) }
            // Seed the config options from whichever probe produced state (event filter:
            // an existing conversation gets its own; a draft gets the agent catalog's).
            snap?.configOptions?.takeIf { it.isNotEmpty() }?.let { seeded ->
                _ui.update { it.copy(configOptions = seeded) }
            }
            commands = snap?.availableCommands.orEmpty().ifEmpty {
                val described = describeCatalog(c)
                described?.configOptions?.takeIf { it.isNotEmpty() }?.let { seeded ->
                    _ui.update { it.copy(configOptions = seeded) }
                }
                described?.availableCommands.orEmpty()
            }
        }
        if (commands.isEmpty()) return emptyList()
        val knownExperts = runCatching { c.expertsList().map { it.id }.toSet() }.getOrDefault(emptySet())
        expertIds = knownExperts
        val filtered = commands.filter { it.name !in knownExperts }
        // Store the filtered list: the composer popup reads `ui.availableCommands`, and an
        // expert-backed name must not surface there as a slash command.
        if (filtered.isNotEmpty()) {
            _ui.update { it.copy(availableCommands = filtered) }
        }
        return filtered
    }

    /** Cached known-expert ids ([loadSlashCommands] fetches them); lets the live
     *  `available_commands` event apply the same expert filter (M5). */
    private var expertIds: Set<String> = emptySet()

    /** Bumped on every draft agent/folder switch; a background refresh of the
     *  command/config lists checks it before landing, so a slow response for the
     *  OLD context can't overwrite the new context's state (M4). */
    private var workspaceGeneration = 0

    suspend fun knownExpertIds(): Set<String> =
        client?.let { runCatching { it.expertsList().map { e -> e.id }.toSet() }.getOrDefault(emptySet()) } ?: emptySet()

    // endregion

    // region Attachments

    /** Prepare + add picked images (downscaled/encoded off-thread), enforcing the count + total-byte caps. */
    fun addAttachments(uris: List<Uri>, resolver: ContentResolver) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            var added = 0
            var skipped = 0
            for ((index, uri) in uris.withIndex()) {
                if (_ui.value.attachments.size >= AttachmentPrep.MAX_COUNT) { skipped++; continue }
                // 批v155: the count cap is now 20, but the 2 MiB axum ceiling did
                // not move — each image's prep target shrinks with what's left of
                // the budget, so a big batch lands uniformly compressed instead of
                // the first few images hogging it and the rest being skipped.
                val currentRawBytes = _ui.value.attachments.sumOf { it.base64.length } * 3 / 4
                val remainingInBatch = uris.size - index
                val adaptiveTarget = (((AttachmentPrep.MAX_TOTAL_BYTES - currentRawBytes) * 19 / 20) / remainingInBatch)
                    .coerceIn(64_000, AttachmentPrep.TARGET_BYTES)
                val att = AttachmentPrep.fromUri(uri, resolver, adaptiveTarget)
                if (att == null) { skipped++; continue }
                val currentBytes = _ui.value.attachments.sumOf { it.base64.length }
                // base64 length ≈ 4/3 of raw bytes; cap on the aggregate raw estimate.
                if (currentBytes + att.base64.length > AttachmentPrep.MAX_TOTAL_BYTES * 4 / 3) { skipped++; continue }
                _ui.update { it.copy(attachments = it.attachments + att) }
                added++
            }
            if (added > 0 || skipped > 0) {
                _ui.update { it.copy(notice = appContext.getString(R.string.image_attach_done, added, skipped)) }
            }
        }
    }

    fun removeAttachment(id: String) {
        _ui.update { it.copy(attachments = it.attachments.filterNot { a -> a.id == id }) }
    }

    // region 批v162 想法快链（正向：想法 → 当前会话 composer）

    /** 想法卡列表（插入表单的「想法」来源）——与收件箱同一条 IdeaStore 流，读失败退化为空表。 */
    suspend fun loadIdeas(): List<IdeaEntry> =
        runCatching { ideaStore.ideas.first() }.getOrDefault(emptyList())

    /**
     * 正向①：想法文字追加进当前草稿（不覆盖）。草稿真身在 screen 的本地状态
     * （VM 不持有它），所以这里只提供与「快捷消息」同一条 append 语义；screen
     * 拿返回值经插入表单的 onInsert 通道落回草稿并走既有 [saveDraft]——绝不
     * 另开直写 DataStore 的旁路（那会跟屏幕内存态打架）。
     */
    fun importIdeaText(text: String, into: String): String =
        ComposeInsert.appendMessage(text, into)

    /**
     * 正向②：想法附件进 composer。图片走与相册图完全相同的降采样 base64 管线
     * （含 2MiB 聚合预算的自适应目标），只是源从相册 Uri 换成私有目录文件；
     * 非图片直接复用 [stageWorkspaceFile] 的工作区上传（409 自动改名同款）——
     * file:// Uri 本进程内 ContentResolver 打得开，所以一行上传逻辑都不复制。
     * 逐个失败计入跳过数，不中断整批。
     */
    fun importIdeaAttachments(attachments: List<LocalAttachment>) {
        if (attachments.isEmpty()) return
        val images = attachments.filter { it.isImage }
        val files = attachments.filterNot { it.isImage }
        if (images.isEmpty() && files.isEmpty()) return
        viewModelScope.launch {
            var added = 0
            var skipped = 0
            val failures = mutableListOf<String>()
            images.forEachIndexed { index, att ->
                if (_ui.value.attachments.size >= AttachmentPrep.MAX_COUNT) { skipped++; return@forEachIndexed }
                // 与 addAttachments 同一条自适应预算公式：批越大，单张目标越小。
                val currentRawBytes = _ui.value.attachments.sumOf { it.base64.length } * 3 / 4
                val remainingInBatch = images.size - index
                val adaptiveTarget = (((AttachmentPrep.MAX_TOTAL_BYTES - currentRawBytes) * 19 / 20) / remainingInBatch)
                    .coerceIn(64_000, AttachmentPrep.TARGET_BYTES)
                val prepared = AttachmentPrep.fromLocalFile(att.localPath, adaptiveTarget)
                if (prepared == null) { skipped++; return@forEachIndexed }
                val currentBytes = _ui.value.attachments.sumOf { it.base64.length }
                if (currentBytes + prepared.base64.length > AttachmentPrep.MAX_TOTAL_BYTES * 4 / 3) { skipped++; return@forEachIndexed }
                _ui.update { it.copy(attachments = it.attachments + prepared) }
                added++
            }
            if (files.isNotEmpty()) {
                val root = folder?.path ?: _ui.value.folderPath
                val c = client
                val resolver = appContext.contentResolver
                for (att in files) {
                    if (_ui.value.fileAttachments.size >= FILE_MAX_COUNT) { skipped++; continue }
                    if (att.size > FILE_MAX_BYTES) {
                        skipped++
                        failures += appContext.getString(R.string.file_attach_reason_too_large, att.name)
                        continue
                    }
                    val uri = Uri.fromFile(File(att.localPath))
                    val prepared: FileAttachment? = if (c != null && !root.isNullOrBlank()) {
                        when (val staged = stageWorkspaceFile(c, root, uri, att.name, att.size, resolver)) {
                            is StageResult.Ok -> staged.att
                            is StageResult.Failed -> { failures += staged.reason; null }
                        }
                    } else {
                        inlineTextAttachment(resolver, uri, att.name, att.size)
                    }
                    if (prepared == null) { skipped++; continue }
                    _ui.update { it.copy(fileAttachments = it.fileAttachments + prepared) }
                    added++
                }
            }
            if (added > 0 || skipped > 0 || failures.isNotEmpty()) {
                _ui.update {
                    it.copy(
                        notice = if (failures.isEmpty()) {
                            appContext.getString(R.string.idea_import_done, added, skipped)
                        } else {
                            appContext.getString(R.string.file_attach_failed_list, added, skipped, failures.joinToString("；"))
                        },
                    )
                }
            }
        }
    }

    // endregion

    // endregion

    // region File attachments (system file manager)

    /**
     * Pick documents (Markdown/PRD/Plan/…) via the system file manager and stage them
     * for the next send. With a workspace folder each file uploads into `uploads/`
     * immediately (same multipart path the project file browser uses); without one,
     * small text files fall back to inlining their content into the prompt. Per-file
     * failures are counted, not aborting the batch.
     *
     * v149.2: failures surface their REASON (server 413/409/404/401 …) instead of
     * being swallowed into an opaque "过大或失败" count, and an "already exists"
     * conflict retries once under an auto-renamed name — a prior attempt that
     * errored client-side may have landed the bytes, and the server refuses to
     * overwrite, so the old flow failed the same retry forever.
     */
    fun addFileAttachments(uris: List<Uri>, resolver: ContentResolver) {
        if (uris.isEmpty()) return
        val root = folder?.path ?: _ui.value.folderPath
        viewModelScope.launch {
            val c = client
            var added = 0
            var skipped = 0
            val failures = mutableListOf<String>()
            for (uri in uris) {
                if (_ui.value.fileAttachments.size >= FILE_MAX_COUNT) { skipped++; continue }
                val name = queryDisplayName(resolver, uri) ?: uri.lastPathSegment ?: "file-${System.currentTimeMillis()}"
                val size = querySize(resolver, uri) ?: 0L
                if (size > FILE_MAX_BYTES) {
                    skipped++
                    failures += appContext.getString(R.string.file_attach_reason_too_large, name)
                    continue
                }
                val att: FileAttachment? = if (c != null && !root.isNullOrBlank()) {
                    when (val staged = stageWorkspaceFile(c, root, uri, name, size, resolver)) {
                        is StageResult.Ok -> staged.att
                        is StageResult.Failed -> { failures += staged.reason; null }
                    }
                } else {
                    inlineTextAttachment(resolver, uri, name, size)
                }
                if (att == null) { skipped++; continue }
                _ui.update { it.copy(fileAttachments = it.fileAttachments + att) }
                added++
            }
            if (added > 0 || skipped > 0 || failures.isNotEmpty()) {
                _ui.update {
                    it.copy(
                        notice = if (failures.isEmpty()) {
                            appContext.getString(R.string.file_attach_done, added, skipped)
                        } else {
                            appContext.getString(R.string.file_attach_failed_list, added, skipped, failures.joinToString("；"))
                        }
                    )
                }
            }
        }
    }

    /** Outcome of one workspace staging attempt ([stageWorkspaceFile]). */
    private sealed interface StageResult {
        data class Ok(val att: FileAttachment) : StageResult
        data class Failed(val reason: String) : StageResult
    }

    /**
     * Upload one document into `uploads/`; on a 409 "already exists" retry once
     * under [autoRenamedFileName]. Any other failure surfaces its reason —
     * never a silent null (the old `catch (_: Exception) { null }` made a 413
     * from an old server indistinguishable from an unreadable file).
     */
    private suspend fun stageWorkspaceFile(
        c: CodegClient,
        root: String,
        uri: Uri,
        name: String,
        size: Long,
        resolver: ContentResolver,
    ): StageResult {
        suspend fun attempt(fileName: String): FileAttachment {
            val uploaded = withContext(Dispatchers.IO) {
                c.uploadWorkspaceFile(root, UPLOAD_DIR, fileName, size) {
                    resolver.openInputStream(uri) ?: throw java.io.IOException("unreadable: $fileName")
                }
            }
            return FileAttachment(
                id = UUID.randomUUID().toString(),
                name = uploaded.name.ifBlank { fileName },
                size = uploaded.size.takeIf { it > 0 } ?: size,
                absolutePath = root.trimEnd('/') + "/" + uploaded.path.trimStart('/'),
            )
        }

        // fold keeps the error val provably non-null in onFailure — no nullable
        // smart-cast holes like `when (exceptionOrNull())` without a null branch.
        val first = runCatching { attempt(name) }.fold(
            onSuccess = { StageResult.Ok(it) },
            onFailure = { e ->
                if (e is CancellationException) throw e
                val conflict = e is ApiError.Server &&
                    (e.status == 409 || e.serverMessage.contains("already exists", ignoreCase = true))
                if (conflict) null else StageResult.Failed("$name：${e.displayMessage()}")
            },
        )
        if (first != null) return first

        // "already exists": a prior attempt may have landed the bytes before the
        // client saw an error, and the server refuses to overwrite — retry once
        // under an auto-renamed name instead of failing the same retry forever.
        return runCatching { attempt(autoRenamedFileName(name)) }.fold(
            onSuccess = { StageResult.Ok(it) },
            onFailure = { e ->
                if (e is CancellationException) throw e
                StageResult.Failed("$name：${e.displayMessage()}")
            },
        )
    }

    fun removeFileAttachment(id: String) {
        _ui.update { it.copy(fileAttachments = it.fileAttachments.filterNot { f -> f.id == id }) }
    }

    /** No-workspace fallback: small text files ride inside the prompt as a fenced block. */
    private suspend fun inlineTextAttachment(resolver: ContentResolver, uri: Uri, name: String, size: Long): FileAttachment? {
        if (size > FILE_MAX_INLINE_BYTES) return null
        val kind = docPreviewKindOf(name)
        if (kind != DocPreviewKind.TEXT && kind != DocPreviewKind.MARKDOWN) return null
        return withContext(Dispatchers.IO) {
            val text = runCatching { resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
                ?: return@withContext null
            FileAttachment(id = UUID.randomUUID().toString(), name = name, size = size, inlineContent = text)
        }
    }

    // endregion

    /** SAF display-name lookup for a picked document (mirrors FolderFilesView). */
    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        }.getOrNull()

    private fun querySize(resolver: ContentResolver, uri: Uri): Long? =
        runCatching {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else null
                } else null
            }
        }.getOrNull()

    // region Workspace (draft agent/folder pickers + git branch)

    /** Change the draft's agent before the first send. Drops any resolved
     *  connection so the next apply/send re-resolves against the new agent, and
     *  refreshes the command/config lists for the new context. */
    fun selectAgent(newAgent: AgentType) {
        if (!_ui.value.isDraftEditable || newAgent == agent) return
        agent = newAgent
        connectionId = null
        workspaceGeneration += 1
        _ui.update { it.copy(agent = newAgent) }
        refreshWorkspaceCatalog()
    }

    /** Persist the accepted draft folder as the next default. A worktree/subfolder is
     *  recorded as its PARENT: the picker only lists top-level folders, so a child id
     *  would never resolve again (a branch-switch worktree would silently kill the
     *  remembered default). */
    private fun rememberFolderChoice(f: FolderDetail) {
        val id = f.parentId ?: f.id
        appScope.launch { runCatching { draftFolderPrefs.setLastUsed(profile?.id, id) } }
    }

    /** Change the draft's folder before the first send (the agent's working dir),
     *  refreshing the branch label, dropping any resolved connection, and refreshing
     *  the command/config lists for the new context. */
    fun selectFolder(newFolder: FolderDetail) {
        if (!_ui.value.isDraftEditable || newFolder.id == folder?.id) return
        folder = newFolder
        currentBranch = newFolder.gitBranch
        connectionId = null
        workspaceGeneration += 1
        // v134 folder memory: remember the pick for the next new task. Fire-and-forget
        // on the app scope — persistence must not gate or delay the picker.
        rememberFolderChoice(newFolder)
        _ui.update {
            it.copy(
                folderName = FolderVisibility.displayName(newFolder, allFolders),
                folderPath = newFolder.path,
                selectedFolderId = newFolder.id,
                currentBranch = newFolder.gitBranch,
            )
        }
        refreshWorkspaceCatalog()
    }

    /** Re-probe the agent catalog after a draft agent/folder switch: clear the stale
     *  command/config lists up front, then write the fresh ones — but only if the
     *  workspace hasn't switched again while the probe was in flight (generation
     *  check), and only when they actually changed. */
    private fun refreshWorkspaceCatalog() {
        val c = client ?: return
        val gen = workspaceGeneration
        _ui.update { it.copy(availableCommands = emptyList(), configOptions = emptyList()) }
        viewModelScope.launch {
            val described = describeCatalog(c) ?: return@launch
            if (gen != workspaceGeneration) return@launch
            _ui.update {
                it.copy(
                    availableCommands = described.availableCommands,
                    configOptions = described.configOptions,
                )
            }
        }
    }

    /** List the folder's branches for the picker; refreshes the current-branch
     *  label opportunistically. Null when there's no git context / on failure. */
    suspend fun loadBranches(): GitBranchList? {
        val c = client ?: return null
        val path = folder?.path ?: return null
        return try {
            val list = c.gitListAllBranches(path)
            runCatching { c.getGitBranch(path) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { cur ->
                currentBranch = cur
                _ui.update { it.copy(currentBranch = cur) }
            }
            list
        } catch (e: Exception) {
            _ui.update { it.copy(notice = e.displayMessage()) }
            null
        }
    }

    /** Switch to [branch] (worktree-aware, web/iOS parity). Returns the navigation
     *  intent so the picker can dismiss in place or open a session in another
     *  worktree. Surfaces failures via [SessionDetailUiState.notice]. */
    suspend fun switchBranch(branch: String, isRemote: Boolean): BranchSwitchOutcome {
        val c = client ?: return BranchSwitchOutcome.Failed
        val active = folder ?: return BranchSwitchOutcome.Failed
        if (branch == currentBranch) return BranchSwitchOutcome.Noop

        // Find where the branch is checked out (skip for a remote pick — those
        // always check out fresh in the root).
        val resolution = if (isRemote) null
        else runCatching { c.resolveWorktreeFolder(active.path, branch) }.getOrNull()
        val plan = FolderVisibility.planBranchSwitch(active, resolution, allFolders, isRemote)

        return when (plan) {
            FolderVisibility.BranchSwitchPlan.Noop -> BranchSwitchOutcome.Noop
            is FolderVisibility.BranchSwitchPlan.NavigateRegistered -> {
                // Already a registered folder — ensure it's open, then navigate.
                allFolders.firstOrNull { it.id == plan.folderId }?.let { runCatching { c.openFolder(it.path) } }
                repository.notifyFoldersChanged()
                BranchSwitchOutcome.OpenSession(plan.folderId)
            }
            is FolderVisibility.BranchSwitchPlan.NavigateExternal -> {
                // Worktree dir not registered yet — register it (parented to the root).
                val detail = runCatching { c.openWorktreeFolder(plan.path, plan.rootId) }.getOrNull()
                if (detail == null) {
                    _ui.update { it.copy(notice = appContext.getString(R.string.branch_open_worktree_failed)) }
                    BranchSwitchOutcome.Failed
                } else {
                    repository.notifyFoldersChanged()
                    BranchSwitchOutcome.OpenSession(detail.id)
                }
            }
            is FolderVisibility.BranchSwitchPlan.CheckoutInRoot -> checkoutInRoot(c, active, plan.root, branch)
        }
    }

    private suspend fun checkoutInRoot(c: CodegClient, active: FolderDetail, root: FolderDetail, branch: String): BranchSwitchOutcome {
        try {
            c.gitCheckout(root.path, branch)
        } catch (e: Exception) {
            // git refuses a branch already checked out by another worktree. Recover
            // the way the web's resolve step would — locate it and open a session there.
            recoverFromWorktreeCheckout(c, branch, root, e)?.let { return it }
            _ui.update { it.copy(notice = e.displayMessage()) }
            return BranchSwitchOutcome.Failed
        }
        return if (root.id == active.id) {
            // In place — reflect the ACTUAL resulting HEAD (a remote ref lands on its local branch).
            val actual = runCatching { c.getGitBranch(root.path) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: branch
            currentBranch = actual
            _ui.update { it.copy(currentBranch = actual) }
            BranchSwitchOutcome.SwitchedInPlace
        } else {
            // We were in a worktree; the checkout happened in the root → open a session there.
            repository.notifyFoldersChanged()
            BranchSwitchOutcome.OpenSession(root.id)
        }
    }

    private suspend fun recoverFromWorktreeCheckout(c: CodegClient, branch: String, root: FolderDetail, error: Exception): BranchSwitchOutcome? {
        runCatching { c.resolveWorktreeFolder(root.path, branch) }.getOrNull()?.path?.let { path ->
            return openWorktreeSession(c, path, root)
        }
        worktreePathFromError(error.displayMessage())?.let { path ->
            return openWorktreeSession(c, path, root)
        }
        return null
    }

    private suspend fun openWorktreeSession(c: CodegClient, path: String, root: FolderDetail): BranchSwitchOutcome? {
        val detail = runCatching { c.openWorktreeFolder(path, root.id) }.getOrNull() ?: return null
        repository.notifyFoldersChanged()
        return BranchSwitchOutcome.OpenSession(detail.id)
    }

    /** Extract the worktree path from git's "already used by worktree at '<path>'"
     *  checkout error, or null when the message isn't that collision. */
    private fun worktreePathFromError(message: String): String? {
        if (!message.contains("already used by worktree")) return null
        val start = message.indexOf("at '").takeIf { it >= 0 }?.plus(4) ?: return null
        val end = message.indexOf('\'', start).takeIf { it >= 0 } ?: return null
        return message.substring(start, end).takeIf { it.isNotEmpty() }
    }

    /** Create [name] off [from] (null = current HEAD) and check it out. */
    suspend fun createBranch(name: String, from: String?): Boolean {
        val c = client ?: return false
        val path = folder?.path ?: return false
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        return try {
            c.gitNewBranch(path, trimmed, from)
            currentBranch = trimmed
            _ui.update { it.copy(currentBranch = trimmed) }
            true
        } catch (e: Exception) {
            _ui.update { it.copy(notice = e.displayMessage()) }
            false
        }
    }

    // endregion

    // region Agent options (mode / config)

    /**
     * In-memory cache of the per-agent options catalog (the `describeAgentOptions`
     * probe that spawns a real agent CLI server-side and takes seconds). Keyed by
     * (agent, workspace path); lives for the VM's lifetime, so reopening the sheet
     * — the common case: new session → look at models → close → reopen — renders
     * instantly from the cache while a background refresh revalidates.
     * [CachedOptions] is only the raw catalog; the remembered-pick resolution below
     * still runs per open so a changed preference is honored without re-probing.
     */
    /** RPC session snapshot with a 30s per-conversation TTL, shared by three consumers
     *  (slash-command load, the options sheet, and the load() prewarm) — and the Fork
     *  gate's data source (v134): the wire carries `fork_supported` + `connection_id`
     *  for whatever CLI is attached to the conversation, INCLUDING an idle one, which
     *  never opens a WS stream and would otherwise never surface fork support. Only a
     *  snapshot that actually carries selection state is cached — an empty one means
     *  "no live connection", and caching it would freeze an idle→live transition for
     *  the TTL. applyMode/applyConfig evict on success so a confirmed pick isn't
     *  visually rolled back by a pre-apply snapshot on the next sheet open. */
    private data class CachedSnapshot(val snapshot: SessionSnapshot, val at: Long)
    private var snapshotCache: CachedSnapshot? = null
    private var snapshotCacheConvId: Int? = null

    /** Connection id the server reports for this conversation's attached CLI — the
     *  Fork call's LAST fallback, after the live stream and a fresh findConnection
     *  (a cached id can predate a reconnect). */
    var snapshotConnectionId: String? = null
        private set

    private suspend fun snapshotFor(c: CodegClient, convId: Int): SessionSnapshot? {
        snapshotCache?.let { cached ->
            if (snapshotCacheConvId == convId &&
                SystemClock.elapsedRealtime() - cached.at < SNAPSHOT_TTL_MS
            ) {
                // Restore the connection fallback only. Deliberately NOT the Fork
                // gate: the non-cached path already seeded it when it populated this
                // entry, and a WS snapshot or `fork_supported` event may have raised it
                // since — replaying the cached value would hide the button for the rest
                // of the TTL.
                cached.snapshot.connectionId?.takeIf { it.isNotBlank() }
                    ?.let { snapshotConnectionId = it }
                return cached.snapshot
            }
        }
        val snap = runCatching { c.sessionSnapshotByConversation(convId) }.getOrNull()
        if (snap == null) {
            // RPC failed / conversation gone: drop the positive cache rather than let
            // it outlive its truth for the rest of the TTL.
            evictSnapshotCache()
            return null
        }
        if (snap.modes != null || snap.configOptions.isNotEmpty()) {
            snapshotCache = CachedSnapshot(snap, SystemClock.elapsedRealtime())
            snapshotCacheConvId = convId
        } else {
            // An empty snapshot means "no live connection". Keeping the previous
            // positive entry would freeze a live→idle transition for the TTL.
            evictSnapshotCache()
        }
        snap.connectionId?.takeIf { it.isNotBlank() }?.let { snapshotConnectionId = it }
        _ui.update { it.copy(forkSupported = snap.forkSupported) }
        return snap
    }

    private fun evictSnapshotCache() {
        snapshotCache = null
        snapshotCacheConvId = null
        snapshotConnectionId = null
    }

    private fun catalogKey(): Pair<AgentType, String?> = agent to folder?.path

    /** Store key: the VM-level pair plus the serverId — workspace paths collide across
     *  devices, and a probe result is only valid for the server that produced it. */
    private fun catalogStoreKey(): String {
        val (agentType, path) = catalogKey()
        return "${profile?.id ?: "-"}|${agentType.wire}|${path.orEmpty()}"
    }

    /** [AgentCatalogStore]-backed catalog read. Default path is stale-first (instant);
     *  `force = true` skips the cache read for the sheet's revalidate pass while still
     *  sharing the store's single-flight probe — a failed probe never destroys the
     *  stale entry, so "close and reopen the sheet" is always instant. */
    private suspend fun describeCatalog(c: CodegClient, force: Boolean = false): AgentOptionsSnapshot? {
        val key = catalogKey()
        if (!force) catalogStore.get(catalogStoreKey())?.let { return it }
        return catalogStore.getOrProbe(catalogStoreKey()) {
            runCatching { c.describeAgentOptions(key.first, key.second) }.getOrNull()
        }.await()
    }

    /** Load the session's modes + config plus the selection to highlight. When a live
     *  session exists its snapshot is authoritative; otherwise it probes the per-agent
     *  catalog (slower) and pre-selects the user's remembered pick (validated against the
     *  catalog), falling back to the catalog's fresh-session defaults. iOS parity.
     *  Returns the catalog from the in-memory cache when present (instant sheet open);
     *  the caller may then [refreshAgentOptions] to revalidate in the background. */
    suspend fun loadAgentOptions(): AgentOptionsData {
        val c = client ?: return AgentOptionsData()
        val convId = conversationId
        val snapshot = if (convId != null) snapshotFor(c, convId) else null
        if (snapshot != null && (snapshot.modes != null || snapshot.configOptions.isNotEmpty())) {
            // Live session — its current mode/config is authoritative.
            return AgentOptionsData(
                modes = snapshot.modes,
                configOptions = snapshot.configOptions,
                initialModeId = snapshot.modes?.currentModeId,
                initialConfig = snapshot.configOptions.currentValues(),
            )
        }
        // No live session (draft / idle) — probe the catalog and pre-select the remembered
        // pick so the sheet opens on the user's last-used mode/config, not the server default.
        val described = describeCatalog(c)
        val modes = described?.modes
        val options = described?.configOptions.orEmpty()
        // Same layered resolution as [connectWithPreferences] (v133): conversation picks
        // override per field; the agent-level pref fills in whatever it never picked.
        val conv = if (convId != null) {
            runCatching { conversationPrefs.prefs(profile?.id, convId, agent) }.getOrNull()
        } else null
        val prefs = resolveSelectorPrefs(conv, runCatching { selectorPrefs.prefs(agent) }.getOrNull() ?: SelectorPrefs())
        val modeId = prefs.modeId?.takeIf { saved -> modes?.availableModes?.any { it.id == saved } == true }
            ?: modes?.currentModeId
        val config = options.associate { opt ->
            val saved = prefs.configValues?.get(opt.id)?.takeIf { it in opt.kind.allSelectValues() }
            opt.id to (saved ?: opt.kind.currentValue ?: "")
        }.filterValues { it.isNotEmpty() }
        return AgentOptionsData(modes, options, modeId, config)
    }

    /**
     * Background revalidation for a cached options sheet. A cache hit makes the sheet
     * open instantly, but the catalog may have drifted (a config the agent dropped, a
     * new model). This re-probes [describeCatalog] (dropping the cached entry first)
     * and reports the fresh resolution via [onLoaded], unless the agent/folder context
     * changed meanwhile. Errors are swallowed: a stale cache beats no data, and the
     * user's next apply reconciles. Returns the Job so a caller can join it.
     */
    fun refreshAgentOptions(onLoaded: suspend (AgentOptionsData) -> Unit): Job {
        val c = client ?: return Job().apply { complete() }
        val key = catalogKey()
        val forAgent = key.first
        val forFolder = folder
        // Draft→live can migrate mid-refresh (send created the conversation); a stale
        // callback would then resolve prefs against the wrong layer — guard on it too.
        val forConvId = conversationId
        return viewModelScope.launch {
            try {
                val described = describeCatalog(c, force = true) ?: return@launch
                // Context switched while the probe ran — the sheet already reloaded for
                // the new context; this result is for a dead key.
                if (agent != forAgent || folder?.path != forFolder?.path || conversationId != forConvId) return@launch
                val modes = described.modes
                val options = described.configOptions
                val conv = forConvId?.let {
                    runCatching { conversationPrefs.prefs(profile?.id, it, forAgent) }.getOrNull()
                }
                val prefs = resolveSelectorPrefs(conv, runCatching { selectorPrefs.prefs(forAgent) }.getOrDefault(SelectorPrefs()))
                val modeId = prefs.modeId
                    ?.takeIf { saved -> modes?.availableModes?.any { it.id == saved } == true }
                    ?: modes?.currentModeId
                val config = options.associate { opt ->
                    val saved = prefs.configValues?.get(opt.id)?.takeIf { it in opt.kind.allSelectValues() }
                    opt.id to (saved ?: opt.kind.currentValue ?: "")
                }.filterValues { it.isNotEmpty() }
                onLoaded(AgentOptionsData(modes, options, modeId, config))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Stale cache stays on screen; nothing to do.
            }
        }
    }

    /** Apply a mode pick: enqueue `set_mode` on a resolvable connection and reconcile
     *  against the authoritative snapshot. Mode is a sticky global preference (v133
     *  owner spec): a pick that lands is remembered in the conversation's own record AND
     *  promoted to the agent-level default, so every conversation reconnects at the
     *  last-picked mode (e.g. YOLO).
     *
     *  Saved-first (v136): with no live connection the remembered pick IS the
     *  application. Opening a conversation never connects (only a RUNNING turn is
     *  reattached), so an idle session resolves no connection at all — reporting that
     *  as a failure was the reported "changed but still warned" bug, because the pref is
     *  exactly what the next connectWithPreferences ships as preferredModeId. Never
     *  spawn a real agent CLI just to apply a setting (that connect was the multi-second
     *  spin). A rejected/busy pick is never persisted, so it can't silently ship later. */
    suspend fun applyMode(modeId: String): OptionApplyOutcome {
        // Pin the agent/folder/server BEFORE the first suspension, so a draft agent,
        // folder or device switch mid-apply can't save the pref under the wrong keys.
        val forAgent = agent
        val forFolder = folder
        val serverId = profile?.id
        val convId = conversationId
        val c = client
        // Config-model asymmetry: the MODEL stays a per-conversation lock ([applyConfig]),
        // while a MODE pick becomes the agent's global default once it is in effect.
        //
        // Busy must be detected LOCALLY: acp_set_mode is fire-and-forget (HTTP 200 even
        // when the agent rejects it), so the old `catch (ApiError.TurnInProgress)` could
        // never fire — a pick during a running turn claimed success (v136).
        if (_ui.value.isInFlight) return OptionApplyOutcome.Busy
        if (c == null) return savedModeOutcome(serverId, forAgent, convId, modeId)
        val resolved = resolveApplyConnection(c, forAgent, forFolder, convId)
        if (resolved.isFailure) return OptionApplyOutcome.Failed
        val conn = resolved.getOrNull()
        if (conn == null) {
            // No connection to diverge from: the saved pref carries the pick.
            evictSnapshotCache()
            return savedModeOutcome(serverId, forAgent, convId, modeId)
        }
        return try {
            // The whole RPC + post-processing runs uncancellable: closing the sheet after
            // the pick must not abort the write that is already accepted server-side.
            withContext(NonCancellable) {
                c.acpSetMode(conn, modeId)
                when (reconcileSelection(c, conn, key = MODE_KEY, desired = modeId)) {
                    ReconcileResult.Confirmed -> {
                        // Only a set that went through is remembered: a Busy/Failed pick
                        // must not be saved and then silently ship on the next connect.
                        rememberMode(serverId, forAgent, convId, modeId)
                        evictSnapshotCache()
                        OptionApplyOutcome.Applied(modeId)
                    }
                    ReconcileResult.Rejected -> {
                        // acp_set_mode is fire-and-forget: HTTP 200 only means the RPC was
                        // queued. A snapshot that settles on a different mode means the
                        // agent refused/normalized it — claiming success here both lied to
                        // the picker and shipped the refused mode on the next connect.
                        dropStaleOptionsConnection(conn)
                        OptionApplyOutcome.Failed
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError.TurnInProgress) {
            OptionApplyOutcome.Busy
        } catch (e: Exception) {
            // The set may have landed even though its response didn't (timeout, dropped
            // socket). Probe the authoritative snapshot and distinguish three states — a
            // GC'd idle connection must NOT be reported as a failure (its saved pick is
            // exactly what the next connect ships), and the server maps an unknown
            // connection to a 500, so isStaleConnection cannot be the discriminator.
            withContext(NonCancellable) {
                when (val probe = probeSelection(c, conn, MODE_KEY)) {
                    is SelectionProbe.Value -> when (probe.value) {
                        modeId -> {
                            rememberMode(serverId, forAgent, convId, modeId)
                            evictSnapshotCache()
                            OptionApplyOutcome.Applied(modeId)
                        }
                        // Readable snapshot with no current value: session still
                        // initializing or no selector. Unknown, not a refusal — same
                        // rule as the reconcile poll, so the two paths agree.
                        null -> {
                            dropStaleOptionsConnection(conn)
                            evictSnapshotCache()
                            savedModeOutcome(serverId, forAgent, convId, modeId)
                        }
                        else -> {
                            dropStaleOptionsConnection(conn)
                            OptionApplyOutcome.Failed
                        }
                    }
                    SelectionProbe.NoSession -> {
                        dropStaleOptionsConnection(conn)
                        evictSnapshotCache()
                        savedModeOutcome(serverId, forAgent, convId, modeId)
                    }
                    SelectionProbe.Unreadable -> {
                        dropStaleOptionsConnection(conn)
                        if (e is ApiError && e.isStaleConnection) {
                            evictSnapshotCache()
                            savedModeOutcome(serverId, forAgent, convId, modeId)
                        } else {
                            OptionApplyOutcome.Failed
                        }
                    }
                }
            }
        }
    }

    /** Apply a config-option pick: enqueue `set_config_option` and reconcile, with the
     *  same saved-first contract as [applyMode].
     *  Model lock: an existing conversation saves the pick to ITS own layer
     *  ([ConversationSelectorStore]) so it only ever applies back to this conversation.
     *  A fresh draft (no conversation yet) saves to the global per-agent prefs instead.
     *  There is NO migration: once the draft becomes a conversation, that conversation's
     *  own layer (already populated from the agent default at resolve time) shadows the
     *  draft-era value. */
    suspend fun applyConfig(configId: String, valueId: String): OptionApplyOutcome {
        val forAgent = agent
        val forFolder = folder
        val serverId = profile?.id
        val convId = conversationId
        val c = client
        if (_ui.value.isInFlight) return OptionApplyOutcome.Busy
        if (c == null) return savedConfigOutcome(serverId, forAgent, convId, configId, valueId)
        val resolved = resolveApplyConnection(c, forAgent, forFolder, convId)
        if (resolved.isFailure) return OptionApplyOutcome.Failed
        val conn = resolved.getOrNull()
        if (conn == null) {
            // No resolvable connection ⇒ the saved pref is what the next connect ships
            // as preferredConfigValues.
            evictSnapshotCache()
            return savedConfigOutcome(serverId, forAgent, convId, configId, valueId)
        }
        return try {
            // Same dismissal-proof commit as [applyMode].
            withContext(NonCancellable) {
                c.acpSetConfigOption(conn, configId, valueId)
                when (reconcileSelection(c, conn, key = configId, desired = valueId)) {
                    ReconcileResult.Confirmed -> {
                        rememberConfig(serverId, forAgent, convId, configId, valueId)
                        evictSnapshotCache()
                        OptionApplyOutcome.Applied(valueId)
                    }
                    ReconcileResult.Rejected -> {
                        // Same fire-and-forget contract as acp_set_mode: a settled snapshot
                        // that disagrees is a refusal, not a success to persist.
                        dropStaleOptionsConnection(conn)
                        OptionApplyOutcome.Failed
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError.TurnInProgress) {
            OptionApplyOutcome.Busy
        } catch (e: Exception) {
            // Same three-state probe as [applyMode]: confirmed value / readable
            // disagreement / connection gone / probe unreadable.
            withContext(NonCancellable) {
                when (val probe = probeSelection(c, conn, configId)) {
                    is SelectionProbe.Value -> when (probe.value) {
                        valueId -> {
                            rememberConfig(serverId, forAgent, convId, configId, valueId)
                            evictSnapshotCache()
                            OptionApplyOutcome.Applied(valueId)
                        }
                        // Unknown (initializing / no selector), not a refusal — same
                        // rule as applyMode.
                        null -> {
                            dropStaleOptionsConnection(conn)
                            evictSnapshotCache()
                            savedConfigOutcome(serverId, forAgent, convId, configId, valueId)
                        }
                        else -> {
                            dropStaleOptionsConnection(conn)
                            OptionApplyOutcome.Failed
                        }
                    }
                    SelectionProbe.NoSession -> {
                        dropStaleOptionsConnection(conn)
                        evictSnapshotCache()
                        savedConfigOutcome(serverId, forAgent, convId, configId, valueId)
                    }
                    SelectionProbe.Unreadable -> {
                        dropStaleOptionsConnection(conn)
                        if (e is ApiError && e.isStaleConnection) {
                            evictSnapshotCache()
                            savedConfigOutcome(serverId, forAgent, convId, configId, valueId)
                        } else {
                            OptionApplyOutcome.Failed
                        }
                    }
                }
            }
        }
    }

    /** Resolve the live connection an apply targets. `Result.success(null)` means "no
     *  connection" (saved-first path, incl. a draft with no conversation). A FAILED
     *  lookup is deliberately not null: a broken findConnection against a live session
     *  must surface as a failure, not masquerade as "nothing to apply" and silently
     *  claim the pref was used. */
    private suspend fun resolveApplyConnection(
        c: CodegClient,
        forAgent: AgentType,
        forFolder: FolderDetail?,
        convId: Int?,
    ): Result<String?> {
        connectionId?.takeIf { agent == forAgent && folder?.id == forFolder?.id }?.let {
            return Result.success(it)
        }
        if (convId == null) return Result.success(null)
        return try {
            Result.success(c.findConnection(convId, externalId, forAgent)?.connectionId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.failure(IllegalStateException("connection lookup failed"))
        }
    }

    /** What an authoritative snapshot read told us about a failed apply (v136). The
     *  server GCs idle connections and maps an unknown id to a 500, so the HTTP status
     *  cannot separate "the connection is gone" (the saved pref is the application)
     *  from "the agent rejected the pick" (a real failure). A null snapshot can. */
    private sealed interface SelectionProbe {
        data object NoSession : SelectionProbe
        data object Unreadable : SelectionProbe
        data class Value(val value: String?) : SelectionProbe
    }

    private suspend fun probeSelection(c: CodegClient, conn: String, key: String): SelectionProbe =
        try {
            val snap = c.sessionSnapshot(conn)
            if (snap == null) SelectionProbe.NoSession else SelectionProbe.Value(snap.selectionFor(key))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            SelectionProbe.Unreadable
        }

    /** The saved-only outcome: on these paths the preference IS the application, so a
     *  failed durable write must surface as a failure instead of a phantom success. */
    private suspend fun savedModeOutcome(
        serverId: String?,
        forAgent: AgentType,
        convId: Int?,
        modeId: String,
    ): OptionApplyOutcome =
        if (rememberMode(serverId, forAgent, convId, modeId)) {
            OptionApplyOutcome.Applied(modeId)
        } else {
            OptionApplyOutcome.Failed
        }

    /** Persist a mode pick where the next connect reads it: the conversation's own
     *  record when live, plus the agent-level default (sticky mode). The conversation
     *  write is decisive; the promotion is best effort (it only affects OTHER
     *  conversations). Runs uncancellable: the saved-first paths call this while the
     *  sheet's scope is still live, and a dismiss right after a pick must not drop the
     *  write that IS the apply. [CancellationException] is not a write failure. */
    private suspend fun rememberMode(
        serverId: String?,
        forAgent: AgentType,
        convId: Int?,
        modeId: String,
    ): Boolean = withContext(NonCancellable) {
        if (convId == null) {
            return@withContext try {
                selectorPrefs.saveMode(forAgent, modeId)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
        val saved = try {
            conversationPrefs.saveMode(serverId, convId, forAgent, modeId)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        // Promote only when the conversation write actually landed: the agent-level
        // default must never carry a pick this conversation failed to store.
        if (saved) {
            try {
                selectorPrefs.saveMode(forAgent, modeId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best effort — the conversation record above already carries the pick.
            }
        }
        saved
    }

    private suspend fun savedConfigOutcome(
        serverId: String?,
        forAgent: AgentType,
        convId: Int?,
        configId: String,
        valueId: String,
    ): OptionApplyOutcome =
        if (rememberConfig(serverId, forAgent, convId, configId, valueId)) {
            OptionApplyOutcome.Applied(valueId)
        } else {
            OptionApplyOutcome.Failed
        }

    /** Persist a config-option pick. No agent-level promotion: the model is a
     *  per-conversation lock (see [applyConfig]). Uncancellable for the same reason as
     *  [rememberMode]. */
    private suspend fun rememberConfig(
        serverId: String?,
        forAgent: AgentType,
        convId: Int?,
        configId: String,
        valueId: String,
    ): Boolean = withContext(NonCancellable) {
        try {
            if (convId != null) {
                conversationPrefs.saveConfig(serverId, convId, forAgent, configId, valueId)
            } else {
                selectorPrefs.saveConfig(forAgent, configId, valueId)
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** An apply failed against [conn] — if it's still the cached connection AND no turn is now
     *  running on it, drop it so the next tap re-resolves (findConnection → connect) instead of
     *  reusing a possibly-dead id forever (which would leave the picker permanently non-functional
     *  once the server GCs an idle connection). The in-flight guard keeps a reattach that adopted
     *  this connection after the apply began from having its live turn's connection nulled (Stop /
     *  stream reconnect need it). Mirrors iOS re-validating the connection between applies. */
    private fun dropStaleOptionsConnection(conn: String) {
        if (connectionId == conn && !_ui.value.isInFlight) connectionId = null
    }

    /** Poll the authoritative snapshot of [conn] until it reports [desired] for [key]
     *  (the mode or a config-option id) or the ~2.7s budget elapses.
     *
     *  `acp_set_mode` / `set_config_option` are fire-and-forget server-side (HTTP 200
     *  just means "queued"), so only the snapshot can tell an applied pick from a
     *  refused one. Only the LAST readable observation decides, and only a definite
     *  non-null value that differs is [ReconcileResult.Rejected]; an unreadable or
     *  value-less snapshot at the end is unknown, not a refusal — the RPC was
     *  accepted, so claiming failure there is exactly the "it changed but still
     *  warned me" report (a slow agent past the budget looks the same as a refusal).
     *  Persisting a genuinely refused pick would ship it on the next connect, which
     *  is why an observed mismatch is never treated as Applied. Mirrors the iOS
     *  reconcile. */
    private suspend fun reconcileSelection(
        c: CodegClient,
        conn: String,
        key: String,
        desired: String,
    ): ReconcileResult {
        var verdict: ReconcileResult? = null
        repeat(RECONCILE_ATTEMPTS) { attempt ->
            // 150ms then 500ms steps: a mode switch can take a beat to show up, and an
            // early "still old" read must not be mistaken for a refusal.
            delay(if (attempt == 0) 150L else 500L)
            val snap = try {
                c.sessionSnapshot(conn)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val current = snap?.selectionFor(key)
            verdict = when {
                current == null -> null // unreadable / selector absent: unknown
                current == desired -> return ReconcileResult.Confirmed
                else -> ReconcileResult.Rejected
            }
        }
        return verdict ?: ReconcileResult.Confirmed
    }

    // endregion

    // region Session handoff (copy / JSON export / reconnect)

    /**
     * Snapshot for the v141 cross-device handoff composer (null when the session
     * isn't server-linked yet). The transcript slice is taken at open time — the
     * composer edits a copy, not live state.
     */
    fun handoffSource(): HandoffSource? {
        val p = profile ?: return null
        val state = _ui.value
        val summary = state.summary ?: return null
        val folder = folder
        return HandoffSource(
            serverId = p.id,
            serverName = state.serverName ?: p.name,
            conversationId = summary.id,
            sessionTitle = summary.trimmedTitle.orEmpty(),
            folderId = state.selectedFolderId ?: summary.folderId,
            folderName = state.folderName.orEmpty(),
            folderPath = folder?.path ?: state.folderPath,
            agent = state.agent,
            gitBranch = currentBranch ?: summary.gitBranch?.takeIf { it.isNotBlank() },
            turns = state.turns + state.pendingUserTurns,
        )
    }

    /** Surface a one-shot message in the snackbar. */
    fun showNotice(text: String) {
        _ui.update { it.copy(notice = text) }
    }

    /** Copy [text] and confirm it, for values the sheet already knows are present. */
    fun copyText(text: String) {
        copyToClipboard(text)
        showNotice(appContext.getString(R.string.session_info_copied))
    }

    /** Copy the CLI's own session id (`external_id`) so it can seed a fresh session. */
    fun copySessionId() {
        val id = externalId ?: _ui.value.externalId
        if (id.isNullOrBlank()) {
            showNotice(appContext.getString(R.string.session_info_no_id))
            return
        }
        copyToClipboard(id)
        showNotice(appContext.getString(R.string.session_info_copied))
    }

    /** Copy the agent's working directory (the server-side absolute path). */
    fun copyWorkingDir() {
        val path = folder?.path ?: _ui.value.folderPath
        if (path.isNullOrBlank()) {
            showNotice(appContext.getString(R.string.session_info_unavailable))
            return
        }
        copyToClipboard(path)
        showNotice(appContext.getString(R.string.session_info_copied))
    }

    /**
     * One-tap copy of the session's alignment block (batch v123): working dir, title,
     * agent-prefixed CLI id, Codeg id, git branch. Pasting it into a fresh session gives
     * the agent (and the human) everything needed to find the context again — the Codeg
     * id itself has no server-side lookup, the working directory and title do the work.
     */
    fun copySessionSummary() {
        val state = _ui.value
        val summary = state.summary
        val text = buildSessionSummaryText(
            workingDir = folder?.path ?: state.folderPath,
            title = summary?.trimmedTitle,
            agentName = state.agent.displayName,
            cliId = externalId ?: state.externalId,
            codegId = summary?.id?.toString(),
            gitBranch = summary?.gitBranch?.takeIf { it.isNotBlank() },
        )
        if (text == null) {
            showNotice(appContext.getString(R.string.session_info_unavailable))
            return
        }
        copyToClipboard(text)
        showNotice(appContext.getString(R.string.session_info_copied))
    }

    // region Document preview (v123: tap a file path in the transcript → in-app preview)

    /**
     * Open the in-app preview for an absolute path tapped in rendered Markdown.
     * The path must live under the session's working directory (the preview RPC is
     * rooted there — same rule as FolderFilesView); anything else is surfaced as a
     * notice rather than a silent no-op.
     */
    fun openPathPreview(path: String) {
        val c = client ?: return
        val root = folder?.path ?: _ui.value.folderPath
        if (root.isNullOrBlank()) {
            showNotice(appContext.getString(R.string.session_info_unavailable))
            return
        }
        val rel = if (path.startsWith("$root/")) {
            path.removePrefix("$root/")
        } else {
            showNotice(appContext.getString(R.string.doc_preview_outside_workspace, path))
            return
        }
        viewModelScope.launch {
            val result = runCatching { c.readFilePreview(root, rel) }
            val preview = result.getOrNull()
            if (preview == null) {
                showNotice(result.exceptionOrNull()?.displayMessage() ?: appContext.getString(R.string.files_read_failed))
                return@launch
            }
            _ui.update {
                it.copy(
                    docPreview = DocPreviewState(
                        title = rel,
                        kind = docPreviewKindOf(rel),
                        content = preview.content,
                    ),
                )
            }
        }
    }

    fun dismissDocPreview() = _ui.update { it.copy(docPreview = null) }

    // endregion

    /**
     * Write a self-contained handoff JSON into the server-side working directory and copy
     * its absolute path, so a NEW session on any client can read the effective context
     * (agent, working dir, CLI session id, branch, transcript) without the phone's storage.
     */
    fun exportSessionJson() {
        if (_ui.value.isExporting) return
        val c = client
        val state = _ui.value
        val root = folder?.path ?: state.folderPath
        if (c == null || root.isNullOrBlank()) {
            showNotice(appContext.getString(R.string.session_info_unavailable))
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(isExporting = true) }
            val temp = File(appContext.cacheDir, "handoff-${System.currentTimeMillis()}.json")
            try {
                // Serializing a long transcript + writing it is real work — keep it off
                // the main thread (the JSON build also allocates heavily). Batch v130:
                // the write itself moved to IO too — writeText on Main was a multi-MB
                // flash write jank spike on long exports.
                val json = withContext(Dispatchers.Default) { buildSessionHandoffJson(state) }
                withContext(Dispatchers.IO) { temp.writeText(json) }
                val name = "session-${externalId ?: conversationId ?: "draft"}-${System.currentTimeMillis()}.json"
                val uploaded = c.uploadWorkspaceFile(
                    rootPath = root,
                    targetDir = HANDOFF_DIR,
                    fileName = name,
                    size = temp.length(),
                    openInput = { temp.inputStream() },
                )
                val absolute = root.trimEnd('/') + "/" + uploaded.path.trimStart('/')
                copyToClipboard(absolute)
                _ui.update {
                    it.copy(
                        isExporting = false,
                        notice = appContext.getString(R.string.session_info_export_done, absolute),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        isExporting = false,
                        notice = appContext.getString(R.string.session_info_export_failed, e.displayMessage()),
                    )
                }
            } finally {
                runCatching { temp.delete() }
            }
        }
    }

    /**
     * Drop the current ACP connection and rebuild it from scratch, then re-attach the event
     * stream. The manual escape hatch for a wedged CLI process: it bypasses
     * `findConnection` (which would happily adopt a dead id forever) and resets the event
     * cursor so the fresh stream is seeded from its own snapshot.
     */
    fun reconnectSession() {
        if (_ui.value.isReconnecting) return
        val c = client
        val root = folder?.path ?: _ui.value.folderPath
        val previous = connectionId
        if (c == null || root.isNullOrBlank()) {
            showNotice(appContext.getString(R.string.session_info_unavailable))
            return
        }
        if ((externalId ?: _ui.value.externalId).isNullOrBlank()) {
            // Reconnecting without a CLI id would silently start a brand-new session.
            showNotice(appContext.getString(R.string.session_info_no_id))
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(isReconnecting = true) }
            // An optimistic send stranded by the reconnect (its turn never confirmed
            // either way) must be rolled back BEFORE the connection drops, or its bubble
            // would hang forever with no reply path. discardOptimistic puts the text back
            // in the composer (and restores attachments); the reconnect's own notice below
            // tells the user what happened — one banner for the whole operation.
            _ui.value.pendingUserTurns.lastOrNull()?.let { stranded ->
                val text = stranded.blocks.filterIsInstance<ContentBlock.Text>()
                    .joinToString("\n") { it.text }
                val orphanRow = hasStartedFirstSend && _ui.value.turns.isEmpty()
                discardOptimistic(
                    userTurn = stranded,
                    text = text,
                    attachments = _ui.value.attachments,
                    createdConversation = orphanRow,
                    createdConversationId = if (orphanRow) conversationId else null,
                    notice = "", // superseded by the reconnect notice below
                )
            }
            // The old connection is about to go away, so its stream must stop folding
            // events into the live turn before we tear it down.
            sendJob?.cancel()
            sendJob = null
            reconcileJob?.cancel()
            reconcileJob = null
            closeStream()
            connectionId = null
            lastSeq = 0
            if (!previous.isNullOrBlank()) {
                runCatching { c.disconnect(previous) }
            }
            var failure: String? = null
            // An idle snapshot here is success (the wedged CLI usually sits idle), so the
            // fresh connection is KEPT rather than aborted — see [connectStream].
            val ok = runCatching {
                val conn = connectFresh(c)
                connectionId = conn
                openStream(conn, seedOnAttach = true, keepWhenIdle = true)
            }.onFailure { failure = it.displayMessage() }.isSuccess
            // One notice for the whole outcome — including any stranded send recovered
            // above — instead of two banners fighting for the same snackbar slot.
            _ui.update {
                it.copy(
                    isReconnecting = false,
                    notice = if (ok) {
                        appContext.getString(R.string.session_info_reconnected)
                    } else {
                        appContext.getString(R.string.session_info_reconnect_failed, failure.orEmpty())
                    },
                )
            }
        }
    }

    /** The handoff document: enough for a fresh agent session to pick the work up. */
    private fun buildSessionHandoffJson(state: SessionDetailUiState): String {
        val turns = state.turns + state.pendingUserTurns
        return buildJsonObject {
            put("kind", "codeg-session-handoff")
            put("version", 1)
            put("exportedAt", Instant.now().toString())
            put("server", state.serverName.orEmpty())
            put("agent", agent.wire)
            put("workingDir", folder?.path ?: state.folderPath.orEmpty())
            put("cliSessionId", externalId ?: state.externalId.orEmpty())
            put("codegConversationId", conversationId ?: 0)
            put("title", state.summary?.trimmedTitle.orEmpty())
            put("gitBranch", currentBranch ?: state.summary?.gitBranch.orEmpty())
            putJsonArray("turns") {
                for (turn in turns) {
                    add(
                        buildJsonObject {
                            put("role", turn.role.wire)
                            put("timestamp", turn.timestamp.toString())
                            put(
                                "text",
                                turn.blocks.filterIsInstance<ContentBlock.Text>()
                                    .joinToString("\n") { it.text },
                            )
                            val thinking = turn.blocks.filterIsInstance<ContentBlock.Thinking>()
                                .joinToString("\n") { it.text }
                            if (thinking.isNotEmpty()) put("thinking", thinking)
                            val tools = turn.blocks.filterIsInstance<ContentBlock.ToolUse>()
                            if (tools.isNotEmpty()) {
                                putJsonArray("tools") {
                                    for (tool in tools) {
                                        add(
                                            buildJsonObject {
                                                put("name", tool.name)
                                                tool.inputPreview?.let { preview -> put("inputPreview", preview) }
                                            },
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }.toString()
    }

    private fun copyToClipboard(text: String) {
        val manager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        manager.setPrimaryClip(ClipData.newPlainText("codeg-handoff", text))
    }

    // endregion
}

/**
 * Pure builder for the one-tap copy alignment block (see [SessionDetailViewModel.copySessionSummary]).
 * Returns null when there is nothing actionable to copy (no working directory AND no ids).
 * Optional lines (title/CLI id/branch) are dropped when absent; every present line keeps its
 * label so a pasted block stays self-describing.
 */
internal fun buildSessionSummaryText(
    workingDir: String?,
    title: String?,
    agentName: String,
    cliId: String?,
    codegId: String?,
    gitBranch: String?,
): String? {
    val lines = mutableListOf<String>()
    workingDir?.takeIf { it.isNotBlank() }?.let { lines += "工作目录: $it" }
    title?.takeIf { it.isNotBlank() }?.let { lines += "会话标题: $it" }
    cliId?.takeIf { it.isNotBlank() }?.let { lines += "$agentName CLI ID: $it" }
    codegId?.let { lines += "Codeg ID: $it" }
    gitBranch?.takeIf { it.isNotBlank() }?.let { lines += "Git 分支: $it" }
    if (lines.isEmpty()) return null
    return lines.joinToString("\n")
}

/** Thrown when the event-stream handshake ends (detached/closed) before the server
 *  confirms the attach — i.e. the connection was rejected. The send path treats this like
 *  a stale connection and retries once with a fresh connect. */
private class StreamAttachFailed(val reason: String?) : Exception("stream attach failed: ${reason ?: "closed"}")

/** The modes + config a session exposes plus the selection to highlight — authoritative
 *  when sourced from a live snapshot, or the remembered/last-used pick for a draft. */
data class AgentOptionsData(
    val modes: SessionModeState? = null,
    val configOptions: List<SessionConfigOption> = emptyList(),
    val initialModeId: String? = null,
    val initialConfig: Map<String, String> = emptyMap(),
)

/** The outcome of a mode/config pick (v136). [Applied] means the pick is in effect —
 *  either the server confirmed it on a live connection, or there was no connection to
 *  apply to and the saved preference (shipped on the next connect) IS the application.
 *  Only [Busy] and [Failed] may revert the sheet's optimistic row. */
sealed interface OptionApplyOutcome {
    /** The value to keep highlighted (server-normalized, or the saved pick). */
    data class Applied(val value: String) : OptionApplyOutcome
    /** A turn is running on this session, so the option cannot change right now. */
    data object Busy : OptionApplyOutcome
    /** The apply was rejected or could not be confirmed. */
    data object Failed : OptionApplyOutcome
}

/** What a post-set snapshot poll could establish about a pick (v136 review). */
private sealed interface ReconcileResult {
    /** The snapshot reported the requested value, or never became readable — the RPC
     *  was accepted and nothing honest contradicts the pick. */
    data object Confirmed : ReconcileResult

    /** The last readable snapshot settled on a DIFFERENT value: the agent refused or
     *  normalized the pick. It must neither stay highlighted nor be persisted. */
    data object Rejected : ReconcileResult
}

/** The authoritative current value the snapshot reports for a selector key. */
private fun SessionSnapshot.selectionFor(key: String): String? =
    if (key == MODE_KEY) modes?.currentModeId
    else configOptions.firstOrNull { it.id == key }?.kind?.currentValue


/** The current value for each config option, dropping empties. */
private fun List<SessionConfigOption>.currentValues(): Map<String, String> =
    associate { it.id to (it.kind.currentValue ?: "") }.filterValues { it.isNotEmpty() }

/** Every selectable value (flat + grouped) of a config option — to validate a cached
 *  preference against the live catalog before pre-selecting it. */
private fun SessionConfigKind.allSelectValues(): List<String> =
    options.map { it.value } + groups.flatMap { g -> g.options.map { it.value } }

/** The navigation intent a branch switch resolved to (mirrors iOS `BranchSwitchOutcome`). */
sealed interface BranchSwitchOutcome {
    /** Already on the branch — nothing changed. */
    data object Noop : BranchSwitchOutcome
    /** Checked out in the current folder's working tree; the row's checkmark moves. */
    data object SwitchedInPlace : BranchSwitchOutcome
    /** The branch lives in another worktree folder — open a new session there. */
    data class OpenSession(val folderId: Int) : BranchSwitchOutcome
    /** The switch failed (a notice was surfaced). */
    data object Failed : BranchSwitchOutcome
}

data class SessionDetailUiState(
    val isNew: Boolean,
    val loading: Boolean = false,
    val summary: ConversationSummary? = null,
    /** The connected server's display name — the "which device am I on" chip. */
    val serverName: String? = null,
    /** 批v154: the device this composer targets. A NEW task follows the device
     *  chip (the launch device no longer locks it); an existing session is pinned
     *  to the device that owns it — conversation ids are server-local. */
    val serverId: String? = null,
    /** 批v154: projects this draft will join on creation / this session belongs
     *  to. The chip row edits it in place; creation writes the memberships. */
    val projectIds: Set<String> = emptySet(),
    val folderName: String? = null,
    val folderPath: String? = null,
    val selectedFolderId: Int? = null,
    val currentBranch: String? = null,
    val isDraftEditable: Boolean = false,
    val availableFolders: List<FolderDetail> = emptyList(),
    val availableAgents: List<AgentType> = emptyList(),
    val turns: List<MessageTurn> = emptyList(),
    // M1 turn windowing: the transcript holds full[turnsOffset..] of the server's
    // turn list. All three null together = a legacy full response (old server) —
    // windowing disabled, nothing older to load. windowPrefixHash fingerprints
    // full[0..turnsOffset) and is the seam an older page must match to join.
    val turnsOffset: Int? = null,
    val turnsTotal: Int? = null,
    val windowPrefixHash: String? = null,
    // M1 review (web prepend-reducer parity): assistant/user turn counts ABOVE the
    // window start — updated on every adopt and splice so a future send-time
    // baseline consumer never reads stale values.
    val assistantTurnsBeforeOffset: Int? = null,
    val userTurnsBeforeOffset: Int? = null,
    /** An older page is in flight (the transcript's top sentinel shows it). */
    val loadingOlder: Boolean = false,
    val pendingUserTurns: List<MessageTurn> = emptyList(),
    val live: LiveTurnState? = null,
    /** The live turn was rebuilt from a reattach snapshot (owns the in-flight reply). */
    val liveFromReattach: Boolean = false,
    val sessionStats: SessionStats? = null,
    /** The agent CLI's own session id (`external_id`), for handoff / reconnects. */
    val externalId: String? = null,
    /** When the running turn started (server `started_at`, else the local send time). */
    val runStartedAt: Long? = null,
    /** Slash commands advertised by the agent for this session. */
    val availableCommands: List<AvailableCommandInfo> = emptyList(),
    /** The session's config options (incl. the model selector), seeded from the
     *  snapshot / `session_config_options` events / the agent catalog — drives the
     *  status line's current-model label. */
    val configOptions: List<SessionConfigOption> = emptyList(),
    /** The session-JSON export is in flight. */
    val isExporting: Boolean = false,
    /** A manual reconnect is in flight. */
    val isReconnecting: Boolean = false,
    val agent: AgentType = AgentType.CLAUDE_CODE,
    val isInFlight: Boolean = false,
    /** A live mid-turn insert has a delivery channel here: native steering push, or the
     *  server-injected `check_user_feedback` pull tool (codeg settings → Live Feedback
     *  on). Gates the queued bar's insert button. */
    val steeringAvailable: Boolean = false,
    /** Server-reported fork support for the attached CLI (v134). Fed by the RPC
     *  snapshot, the WS snapshot and the `fork_supported` event; false for agents or
     *  servers without the capability (Codex is the only one advertising it today). */
    val forkSupported: Boolean = false,
    /** A fork request is in flight — the footer entry is one-shot until it settles. */
    val forking: Boolean = false,
    val sendStatus: String? = null,
    val notice: String? = null,
    // ---- 批v165 @智能体 (M-A) ----
    /** The mention picker's table: `acp_list_agents`, enabled rows only, RAW
     *  slugs (unknown agents included — the @ link needs no client knowledge).
     *  Reloaded on every sheet open. */
    val mentionAgents: List<AgentMention> = emptyList(),
    /** The picker's probe died (network/decode) — the sheet offers a retry
     *  instead of an empty list that would read as "no agents". */
    val mentionLoadFailed: Boolean = false,
    /** The delegation gate is up: the draft carries an @ link but the server
     *  reports delegation disabled — sending would deliver inert text (the
     *  HOST_TOOLS=agent tool group strips delegation entirely), never a
     *  sub-session. Explicit blocker, never a silent downgrade (调研规划 §3.3). */
    val delegationGate: Boolean = false,
    /** The gate's primary action (enable + replay send) is in flight. */
    val delegationGateBusy: Boolean = false,
    /** Set when a failed send returns the user's typed text to the composer (consumed once). */
    val restoreDraft: String? = null,
    val error: String? = null,
    val pendingPermission: PendingPermissionUi? = null,
    val pendingQuestion: PendingQuestionUi? = null,
    val pendingPlanApproval: PendingPlanApprovalUi? = null,
    val attachments: List<Attachment> = emptyList(),
    /** Documents staged via the file-manager picker (uploaded to the workspace at pick time). */
    val fileAttachments: List<FileAttachment> = emptyList(),
    /** Drafts typed while a turn was running, waiting for it to finish (in-memory,
     *  like the web client's queue — a process kill loses them). */
    val queue: List<QueuedDraft> = emptyList(),
    /** The document preview opened from a tapped file path in the transcript (v123). */
    val docPreview: DocPreviewState? = null,
) {
    /** M1: an older page exists and may be requested. While [loadingOlder] the
     *  sentinel row stays mounted (its LaunchedEffect is the single-flight caller),
     *  so this deliberately does NOT gate on it. */
    val canLoadOlder: Boolean get() = (turnsOffset ?: 0) > 0
}

/** State of the in-app document preview opened from a tapped path (batch v123). */
data class DocPreviewState(
    val title: String,
    val kind: DocPreviewKind,
    val content: String,
)

/** How the preview dialog should render the content. Mirrors FolderFilesView's PreviewKind (text subset). */
enum class DocPreviewKind { TEXT, MARKDOWN, HTML, OTHER }

/**
 * Map a file name to the preview rendering (pure; mirrors FolderFilesView's
 * `previewKindOf` text subset). Extracted so the session preview and tests share it.
 */
fun docPreviewKindOf(name: String): DocPreviewKind {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "md", "markdown" -> DocPreviewKind.MARKDOWN
        "html", "htm" -> DocPreviewKind.HTML
        "", "txt", "json", "xml", "yml", "yaml", "toml", "ini", "conf", "sh", "bash", "py", "rs", "kt", "java", "js", "ts", "css", "c", "h", "cpp", "go", "rb", "php", "sql", "log", "env", "csv", "properties" -> DocPreviewKind.TEXT
        else -> DocPreviewKind.OTHER
    }
}

/**
 * A document staged for the next send, picked via the system file manager (SAF).
 * With a workspace folder the bytes are uploaded into `uploads/` at pick time and
 * [absolutePath] is injected into the prompt so the agent can read the file
 * directly; without a folder, small text files fall back to [inlineContent]
 * (embedded in the prompt as a fenced block).
 */
data class FileAttachment(
    val id: String,
    val name: String,
    val size: Long,
    val absolutePath: String? = null,
    val inlineContent: String? = null,
) {
    val isInline: Boolean get() = absolutePath == null && inlineContent != null
}

/** One queued draft in the send-while-running bar. */
data class QueuedDraft(
    val id: String,
    val text: String,
    val queuedAt: Instant,
)

/** Result of a queued-flush send attempt — see [SessionDetailViewModel.sendQueued]. */
internal enum class SendOutcome { SENT, BUSY, FAILED }

/**
 * The server's stable rejection text for "submit live feedback with no turn running"
 * (AcpError::NoActiveTurn, carried verbatim through the HTTP error envelope). That
 * rejection IS recoverable — the row stays queued and the flush delivers it as an
 * ordinary prompt. Anything else (FeedbackDisabled, stale connection, transport,
 * the HTTP layer's generic `not_implemented` for a route this client name-mangled)
 * is not: keep the row, surface the notice. Top-level so it's testable without a
 * VM instance.
 */
internal fun isNoActiveTurnError(e: Exception): Boolean =
    e is ApiError.Server && e.serverMessage.contains("no active turn", ignoreCase = true)

/** What to do with a plan approval's revision notes — see [planFollowUpAction]. */
internal enum class PlanFollowUp { PARK, SEND_NOW, DROP }

/**
 * Plan-revision notes parked for the turn that produced the approval, keyed by that
 * turn's id.
 *
 * The key is what makes the parking safe: the originating turn may end through a path
 * that never reaches `finalizeTurn` (a dead connection reconciled against the server
 * transcript, an exhausted reconnect), and without the key the next unrelated turn's
 * completion would pick the notes up and send them.
 */
internal data class ParkedPlanNotes(val turnId: String, val notes: String) {
    /** The notes, but only for the turn that parked them; null for any other. */
    fun notesFor(turnId: String): String? = notes.takeIf { this.turnId == turnId }
}

/**
 * Where a "request changes" decision's revision notes should go, decided once the
 * answer post comes back. That post is a suspension point, so by then the turn that
 * produced the approval may have finalized, failed, been cancelled, or been replaced
 * by an unrelated new one — and Grok reads these notes from a follow-up user turn, so
 * sending them to the wrong turn starts work nobody asked for.
 *
 * - [PlanFollowUp.PARK] — the originating turn is still winding down; `finalizeTurn`
 *   flushes the notes when it completes.
 * - [PlanFollowUp.SEND_NOW] — it finished cleanly meanwhile (a stop reason, no error),
 *   so the follow-up is due immediately.
 * - [PlanFollowUp.DROP] — it was cancelled or failed, or a different turn is live now.
 *
 * Extracted for testing (the branch is otherwise only reachable through a real
 * in-flight network round trip).
 */
internal fun planFollowUpAction(
    sameTurn: Boolean,
    inFlight: Boolean,
    stopReason: String?,
    errorMessage: String?,
): PlanFollowUp = when {
    !sameTurn -> PlanFollowUp.DROP
    inFlight -> PlanFollowUp.PARK
    stopReason != null && errorMessage == null -> PlanFollowUp.SEND_NOW
    else -> PlanFollowUp.DROP
}

/** A `permission_request` awaiting the user's choice (parsed for the card). */
data class PendingPermissionUi(
    val requestId: String,
    val parsed: app.codeg.android.feature.sessiondetail.interactive.ParsedPermission,
    val options: List<app.codeg.android.core.model.PermissionOption>,
)

/** A `question_request` awaiting answers. */
data class PendingQuestionUi(
    val questionId: String,
    val questions: List<app.codeg.android.core.model.QuestionSpec>,
)

/**
 * A Grok `exit_plan_mode` awaiting the user's decision. Distinct from
 * [PendingPermissionUi]: Grok's plan approval is its own blocking ext request with three
 * outcomes, not a permission option list. (Claude's ExitPlanMode still arrives as a
 * permission and keeps that path.)
 */
data class PendingPlanApprovalUi(
    val approvalId: String,
    /** Grok's `toolCallId` for the `exit_plan_mode` call. Not used for the answer (which
     *  keys on [approvalId]) — kept so the card can correlate with the in-stream call. */
    val toolCallId: String,
    /** The plan, read from Grok's `plan.md`. May be empty — the card then shows an
     *  empty-state notice rather than hiding, since the turn is blocked either way. */
    val planMarkdown: String,
)
