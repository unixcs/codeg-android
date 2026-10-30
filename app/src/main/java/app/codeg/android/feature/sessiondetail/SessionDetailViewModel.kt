package app.codeg.android.feature.sessiondetail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.R
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.ConversationSelectorStore
import app.codeg.android.core.datastore.DeviceAgentPrefsStore
import app.codeg.android.core.datastore.DraftFolderPrefsStore
import app.codeg.android.core.datastore.DraftStore
import app.codeg.android.core.datastore.SelectorPrefs
import app.codeg.android.core.datastore.SelectorPrefsStore
import app.codeg.android.core.datastore.resolveSelectorPrefs
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.datastore.filterHiddenAgents
import app.codeg.android.core.model.AcpEvent
import app.codeg.android.core.model.AgentOptionsSnapshot
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
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

/** Server-side folder (inside the working directory) that receives session handoff JSON. */
private const val HANDOFF_DIR = ".codeg-handoff"

/** Bounded snapshot poll after an options apply (~2.3s total) — `set_*` only enqueues
 *  the change server-side, so we reconcile against the authoritative snapshot. */
private const val RECONCILE_ATTEMPTS = 3

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
    /** In-flight options-connection resolve plus the agent/folder it targets. Shared across
     *  concurrent applies so a rapid mode+config change spawns ONE connection, but keyed by
     *  agent/folder so a draft's agent/folder switch doesn't reuse a now-stale-context connect.
     *  Mirrors the iOS `AgentOptionsModel.sharedConnection()`. Cleared when the connect settles. */
    private var optionsConnect: OptionsConnect? = null
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
                    val detail = c.conversationDetail(id)
                    agent = detail.summary.agentType
                    externalId = detail.summary.externalId
                    folder = allFolders.firstOrNull { it.id == detail.summary.folderId }
                    currentBranch = detail.summary.gitBranch ?: folder?.gitBranch
                    _ui.update {
                        it.copy(
                            loading = false,
                            summary = detail.summary,
                            serverName = p.name,
                            turns = detail.turns,
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
                    // Reattach when the server reports the turn live — via an explicit
                    // in-flight user turn id or the conversation status (iOS parity).
                    if (detail.inFlightUserTurnId != null || detail.summary.status.isLive) reattachIfLive()
                } else {
                    // New task: load the draft pickers' sources (top-level open folders +
                    // installed/enabled agents) and pick sensible defaults — honoring a
                    // preselected folder (e.g. a worktree a branch switch opened).
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
                    // v134 folder memory: arg → pin → last used → most recent, each
                    // remembered id validated against the picker-visible top-level set.
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

    // region Send / cancel

    fun send(text: String) = send(text, withAttachments = true)

    /**
     * Send the composer's draft — or, with [withAttachments] `false`, a prompt the app
     * itself generated (today: the revision notes from a plan-approval "request
     * changes", which Grok expects as a follow-up turn). A generated send never
     * consumes the composer's pending attachments, so images the user was staging
     * survive.
     */
    private fun send(text: String, withAttachments: Boolean) {
        val trimmed = text.trim()
        val attachments = if (withAttachments) _ui.value.attachments else emptyList()
        if ((trimmed.isEmpty() && attachments.isEmpty()) || _ui.value.isInFlight) return
        // A fork is mid-flight: the session row is about to be re-pointed, so a send
        // now would land on the wrong session (or race the re-point entirely).
        if (_ui.value.forking) return

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

        // Optimistic user turn: text (if any) then image blocks.
        val userTurn = MessageTurn(
            id = "pending-${UUID.randomUUID()}",
            role = TurnRole.USER,
            blocks = buildList {
                if (trimmed.isNotEmpty()) add(ContentBlock.Text(trimmed))
                attachments.forEach { add(ContentBlock.Image(it.imageData)) }
            },
            timestamp = Instant.now(),
        )
        val builder = LiveTurnBuilder()
        liveBuilder = builder
        turnBaseline = _ui.value.turns.size
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
                isDraftEditable = false,
            )
        }
        // Run the send on the process-lifetime scope (re-dispatched to Main so the state
        // writes stay on the main thread): a back-navigation clears this VM mid-flight,
        // and the old `viewModelScope` home cancelled the coroutine before the prompt
        // POST landed — the optimistic bubble, the cleared draft and the server turns all
        // ended up empty. The send outlives the screen now.
        sendJob = appScope.launch(Dispatchers.Main.immediate) {
            runSend(trimmed, userTurn, attachments, wasNew, clearDraft = withAttachments)
        }
    }

    private suspend fun runSend(
        text: String,
        userTurn: MessageTurn,
        attachments: List<Attachment>,
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
            ensureConversationCreated(text.ifEmpty { "Image" })
            if (createdConversation) createdId = conversationId
            // The screen has already emptied its composer; clear the persisted draft here —
            // on [appScope], so a back-navigation can't cancel it mid-DataStore-write and
            // resurrect the sent text on the next visit. Generated sends (plan follow-ups)
            // must not consume the user's pending draft.
            if (clearDraft) clearPersistedDraft(preSendDraftKey)
            try {
                attemptSend(c, text, attachments, clientMessageId, freshConnection = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A stale connection — a reused id the server has since dropped, or an attach
                // the server rejected — can't carry the prompt. Drop it, connect fresh, retry
                // ONCE. A second failure falls through to the outer catch (discard + notice).
                if (e is StreamAttachFailed || (e is ApiError && e.isStaleConnection)) {
                    closeStream()
                    connectionId = null
                    attemptSend(c, text, attachments, clientMessageId, freshConnection = true)
                } else throw e
            }
            _ui.update { if (it.isInFlight) it.copy(sendStatus = "Thinking…") else it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError.TurnInProgress) {
            discardOptimistic(userTurn, text, attachments, createdConversation, createdId, notice = "A turn is already running on this session.")
        } catch (e: Exception) {
            discardOptimistic(userTurn, text, attachments, createdConversation, createdId, notice = e.displayMessage())
        }
    }

    /** One send attempt: resolve (or force-fresh) a connection, attach, then prompt. */
    private suspend fun attemptSend(
        c: CodegClient,
        text: String,
        attachments: List<Attachment>,
        clientMessageId: String,
        freshConnection: Boolean,
    ) {
        val conn = if (freshConnection) connectFresh(c) else resolveConnection(c)
        connectionId = conn
        openStream(conn)
        c.prompt(
            connectionId = conn,
            blocks = buildList {
                if (text.isNotEmpty()) add(PromptInputBlock.Text(text))
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
        _ui.update { it.copy(isNew = false) }
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
            turnBaseline = _ui.value.turns.size
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
                _ui.update { if (it.isInFlight) it.copy(sendStatus = runningStatus()) else it }
                scheduleLiveEmit()
            }
            is AcpEvent.ToolCallUpdate -> {
                builder?.updateToolCall(event.id, event.title, event.status, event.rawInput, event.rawOutput, event.content, event.append, event.meta)
                // A tool finishing must revert the status line from "Running <tool>…" back to
                // "Thinking…" (or to the next still-running tool); iOS recomputes on every update.
                _ui.update { if (it.isInFlight) it.copy(sendStatus = runningStatus()) else it }
                scheduleLiveEmit()
            }
            is AcpEvent.PlanUpdate -> { builder?.updatePlan(event.entries); scheduleLiveEmit() }
            is AcpEvent.UsageUpdate -> if (event.used > 0 && event.size > 0) _ui.update {
                val max = event.size.toInt()
                val used = event.used.toInt()
                it.copy(
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
                if (filtered.isEmpty()) it else it.copy(availableCommands = filtered)
            }
            // The agent (re)published its config options — the model selector's current
            // value is what the status line shows. Replace wholesale: this event is the
            // connection's authoritative option state (web parity).
            is AcpEvent.SessionConfigOptions -> _ui.update {
                if (event.options.isNotEmpty()) it.copy(configOptions = event.options) else it
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

    /** The status-line copy for the current live state: the running tool's title, else
     *  "Thinking…". Recomputed on tool start/finish so it never sticks on a done tool.
     *  Hardcoded English to match the send-flow literals (transient status micro-copy is
     *  intentionally not localized — see the app's i18n notes). */
    private fun runningStatus(): String =
        liveBuilder?.activeToolTitle()?.let { "Running ${it.ifBlank { "tool" }}…" } ?: STATUS_THINKING

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
     *  the transcript shows the final reply rather than the beat-stale load. */
    private fun reconcileAfterMissedLive() {
        val c = client ?: return
        val id = conversationId ?: return
        viewModelScope.launch {
            runCatching { c.conversationDetail(id) }.getOrNull()?.let { d ->
                _ui.update {
                    it.copy(turns = d.turns, sessionStats = d.sessionStats ?: it.sessionStats, summary = d.summary)
                }
            }
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
                val detail = runCatching { c.conversationDetail(id) }.getOrNull() ?: return@repeat
                val last = detail.turns.lastOrNull()
                val advanced = detail.turns.size > turnBaseline &&
                    last?.role == TurnRole.ASSISTANT && last.blocks.isNotEmpty()
                if (advanced) {
                    if (liveBuilder !== builder) return@launch
                    // This turn ended without a `turn_complete` of its own (a dead
                    // connection, an exhausted reconnect), so `finalizeTurn` never ran:
                    // resolve its parked plan-revision notes here instead of leaving them
                    // for whatever turn finalizes next.
                    val orphanedNotes = builder?.let { pendingPlanFollowUp?.notesFor(it.id) }
                    if (orphanedNotes != null) pendingPlanFollowUp = null
                    _ui.update {
                        it.copy(
                            turns = detail.turns,
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
            for (uri in uris) {
                if (_ui.value.attachments.size >= AttachmentPrep.MAX_COUNT) { skipped++; continue }
                val att = AttachmentPrep.fromUri(uri, resolver)
                if (att == null) { skipped++; continue }
                val currentBytes = _ui.value.attachments.sumOf { it.base64.length }
                // base64 length ≈ 4/3 of raw bytes; cap on the aggregate raw estimate.
                if (currentBytes + att.base64.length > AttachmentPrep.MAX_TOTAL_BYTES * 4 / 3) { skipped++; continue }
                _ui.update { it.copy(attachments = it.attachments + att) }
                added++
            }
            if (skipped > 0) _ui.update { it.copy(notice = "Added $added image(s); $skipped skipped (too large or over the limit).") }
        }
    }

    fun removeAttachment(id: String) {
        _ui.update { it.copy(attachments = it.attachments.filterNot { a -> a.id == id }) }
    }

    // endregion

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

    /** A live connection to apply options to. Reuses a cached/live connection, and — for a
     *  draft or idle session with none — establishes one on demand (carrying the remembered
     *  mode/config), so the pickers apply instead of silently no-op'ing. Mirrors iOS
     *  `resolveConnectionForOptions`. Concurrent applies share one in-flight resolve
     *  (`optionsConnectDeferred`) so a rapid mode+config change can't spawn two orphaned
     *  connections. Returns null only when there's no client or the connect fails. */
    private suspend fun optionsConnection(forAgent: AgentType, forFolder: FolderDetail?): String? {
        val forFolderId = forFolder?.id
        // Reuse the shared connection only when it still matches THIS apply's pinned agent/folder.
        // connectionId is nulled on every agent/folder switch, so a non-null value always belongs
        // to the current context; if the current context no longer matches the pinned one, this
        // apply raced a switch and must resolve a connection for its own (pinned) agent instead of
        // returning the other agent's connection.
        connectionId?.takeIf { agent == forAgent && folder?.id == forFolderId }?.let { return it }
        val c = client ?: return null
        // Reuse an in-flight connect only when it targets the SAME agent/folder the caller pinned —
        // after a draft agent/folder switch, a reused stale-context connect would apply to the
        // wrong agent.
        val cached = optionsConnect
        val deferred = if (cached != null && cached.agent == forAgent && cached.folderId == forFolderId) {
            cached.deferred
        } else {
            // A connect for a now-abandoned agent/folder context is obsolete — cancel it so it
            // can't complete and win adoption with a preference snapshot taken before a later
            // setting was saved (a same-context connect started afterward carries the newer prefs;
            // if the stale one were adopted, the next send would reuse it and drop that setting).
            cached?.deferred?.cancel()
            val d = viewModelScope.async {
                val convId = conversationId
                val found = if (convId != null) {
                    runCatching { c.findConnection(convId, externalId, forAgent)?.connectionId }.getOrNull()
                } else null
                // Pin the connect to the captured agent/folder so a switch mid-connect can't
                // retarget it to a different agent/workspace.
                val conn = found ?: connectWithPreferences(c, forAgent, forFolder)
                // Adopt as the shared connection only if nothing else has claimed it and the
                // draft's agent/folder is still the captured one — never clobber a connection the
                // send path already established (which Stop / stream reconnect target).
                if (connectionId == null && agent == forAgent && folder?.id == forFolderId) connectionId = conn
                conn
            }
            optionsConnect = OptionsConnect(forAgent, forFolderId, d)
            // Clear the memo when the connect itself settles — not when a single awaiter is
            // cancelled — so a dismissed sheet / cancelled apply can't drop the shared reference
            // mid-flight and let the next apply spawn a second (orphaned) connection.
            d.invokeOnCompletion { if (optionsConnect?.deferred === d) optionsConnect = null }
            d
        }
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Apply a mode pick. Remembers it (draft: straight to the per-agent default; live:
     *  the conversation's own record first, and the per-agent default ONLY after the
     *  server confirms — a failed apply must not pollute the global default), resolves/
     *  establishes a connection ([optionsConnection]), enqueues `set_mode`, and reconciles
     *  against the authoritative snapshot. Returns the confirmed/normalized mode to
     *  highlight, or null when the connect/apply failed (the sheet reverts the optimistic
     *  pick). Mode is a sticky global preference (v133 owner spec): unless manually
     *  switched, every conversation reconnects at the last-picked mode (e.g. YOLO). */
    suspend fun applyMode(modeId: String): String? {
        // Pin the agent/folder BEFORE the first suspension (saveMode), so a draft agent/folder
        // switch mid-apply can't save the pref under the wrong agent or retarget the connect.
        val forAgent = agent
        val forFolder = folder
        val convId = conversationId
        // Config-model asymmetry: the MODEL stays a per-conversation lock ([applyConfig]),
        // while a confirmed MODE pick becomes the agent's global default (written below).
        if (convId != null) {
            runCatching { conversationPrefs.saveMode(profile?.id, convId, forAgent, modeId) }
        } else {
            runCatching { selectorPrefs.saveMode(forAgent, modeId) }
        }
        val c = client
        // Fast path (batch v130), DRAFT ONLY: a fresh draft's saved preference already
        // carries to the server on the next acp_connect (preferredModeId) — do NOT spawn
        // a real agent CLI just to apply a setting (that connect was the multi-second
        // spin). A live conversation has no next-connect carrier, so if its connection
        // can't be resolved we must revert (null) instead of reporting false success.
        val draft = convId == null
        if (c == null) return if (draft) modeId else null
        val conn = connectionId?.takeIf { agent == forAgent && folder?.id == forFolder?.id }
            ?: convId?.let { cid -> runCatching { c.findConnection(cid, externalId, forAgent)?.connectionId }.getOrNull() }
            ?: return if (draft) modeId else null
        return try {
            c.acpSetMode(conn, modeId)
            // reconcileSelection returns the optimistic value even if its budget ran out —
            // "applied" (the set went through), NOT "server-verified". Promoting it is still
            // safe: the server ignores an unknown preferredModeId on the next connect, so a
            // silently-rejected mode self-heals instead of sticking.
            val applied = reconcileSelection(conn, key = MODE_KEY, desired = modeId)
            // Live pick applied to the server: promote it to the agent-level default so
            // other conversations reconnect at the same mode (draft already wrote above).
            if (!draft) runCatching { selectorPrefs.saveMode(forAgent, applied) }
            evictSnapshotCache()
            applied
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dropStaleOptionsConnection(conn)
            null
        }
    }

    /** Apply a config-option pick. Same contract as [applyMode] (remember, connect, apply,
     *  reconcile); returns the confirmed/normalized value or null.
     *  Model lock: an existing conversation saves the pick to ITS own layer
     *  ([ConversationSelectorStore]) so it only ever applies back to this conversation;
     *  a fresh draft (no conversation yet) saves to the global per-agent prefs, which the
     *  conversation layer will then shadow from the first send on. */
    suspend fun applyConfig(configId: String, valueId: String): String? {
        val forAgent = agent
        val forFolder = folder
        val convId = conversationId
        if (convId != null) {
            runCatching { conversationPrefs.saveConfig(profile?.id, convId, forAgent, configId, valueId) }
        } else {
            runCatching { selectorPrefs.saveConfig(forAgent, configId, valueId) }
        }
        val c = client
        // Fast path, same as [applyMode]: draft-only optimism; a live conversation whose
        // connection can't be resolved reverts (null).
        val draft = convId == null
        if (c == null) return if (draft) valueId else null
        val conn = connectionId?.takeIf { agent == forAgent && folder?.id == forFolder?.id }
            ?: convId?.let { cid -> runCatching { c.findConnection(cid, externalId, forAgent)?.connectionId }.getOrNull() }
            ?: return if (draft) valueId else null
        return try {
            c.acpSetConfigOption(conn, configId, valueId)
            reconcileSelection(conn, key = configId, desired = valueId).also { evictSnapshotCache() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dropStaleOptionsConnection(conn)
            null
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
     *  (the mode or a config-option id), or it settles on the agent's normalized value, or
     *  the budget (~2.3s) elapses. Adopts the authoritative value when seen; otherwise
     *  keeps the optimistic [desired] as a best effort. Mirrors the iOS reconcile. */
    private suspend fun reconcileSelection(conn: String, key: String, desired: String): String {
        val c = client ?: return desired
        var lastSeen: String? = null
        repeat(RECONCILE_ATTEMPTS) { attempt ->
            // Batch v130: 150/250/250ms — the optimistic checkmark is already on the pick;
            // reconcile only confirms or reverts, so a long budget just holds the spinner.
            delay(if (attempt == 0) 150L else 250L)
            val snap = runCatching { c.sessionSnapshot(conn) }.getOrNull() ?: return@repeat
            val current = snap.selectionFor(key) ?: return@repeat
            lastSeen = current
            if (current == desired) return current
        }
        // Budget exhausted: the set itself succeeded, so the server adopts momentarily.
        // Adopting a still-old snapshot value here would flip the checkmark back off.
        return desired
    }

    // endregion

    // region Session handoff (copy / JSON export / reconnect)

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

/** An in-flight options-connection resolve tagged with the agent/folder it targets, so the
 *  memo is only reused for a matching context (see [SessionDetailViewModel.optionsConnection]). */
private class OptionsConnect(val agent: AgentType, val folderId: Int?, val deferred: Deferred<String>)

/** The modes + config a session exposes plus the selection to highlight — authoritative
 *  when sourced from a live snapshot, or the remembered/last-used pick for a draft. */
data class AgentOptionsData(
    val modes: SessionModeState? = null,
    val configOptions: List<SessionConfigOption> = emptyList(),
    val initialModeId: String? = null,
    val initialConfig: Map<String, String> = emptyMap(),
)

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
    val folderName: String? = null,
    val folderPath: String? = null,
    val selectedFolderId: Int? = null,
    val currentBranch: String? = null,
    val isDraftEditable: Boolean = false,
    val availableFolders: List<FolderDetail> = emptyList(),
    val availableAgents: List<AgentType> = emptyList(),
    val turns: List<MessageTurn> = emptyList(),
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
    /** Set when a failed send returns the user's typed text to the composer (consumed once). */
    val restoreDraft: String? = null,
    val error: String? = null,
    val pendingPermission: PendingPermissionUi? = null,
    val pendingQuestion: PendingQuestionUi? = null,
    val pendingPlanApproval: PendingPlanApprovalUi? = null,
    val attachments: List<Attachment> = emptyList(),
    /** Drafts typed while a turn was running, waiting for it to finish (in-memory,
     *  like the web client's queue — a process kill loses them). */
    val queue: List<QueuedDraft> = emptyList(),
    /** The document preview opened from a tapped file path in the transcript (v123). */
    val docPreview: DocPreviewState? = null,
)

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
