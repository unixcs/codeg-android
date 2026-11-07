package app.codeg.android.feature.sessiondetail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.common.RelativeTime
import app.codeg.android.core.data.HandoffSource
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.datastore.SessionProject
import app.codeg.android.core.designsystem.component.AgentAvatar
import app.codeg.android.core.designsystem.component.AgentVisuals
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.component.FullscreenTextEditorDialog
import app.codeg.android.core.designsystem.component.ImageViewerDialog
import app.codeg.android.core.designsystem.component.ImageViewerSource
import app.codeg.android.core.designsystem.component.InlineError
import app.codeg.android.core.designsystem.component.OptionListSheet
import app.codeg.android.core.designsystem.component.sheetContentScrollLock
import app.codeg.android.core.designsystem.component.PickerOption
import app.codeg.android.core.designsystem.component.LivePulse
import app.codeg.android.core.designsystem.component.LoadingView
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.PlanEntry
import app.codeg.android.feature.handoff.HandoffSheet
import app.codeg.android.feature.handoff.HandoffViewModel
import app.codeg.android.feature.sessions.AddToProjectSheet
import app.codeg.android.feature.sessiondetail.interactive.AskQuestionCard
import app.codeg.android.feature.sessiondetail.interactive.FullscreenPlanUi
import app.codeg.android.feature.sessiondetail.interactive.PermissionRequestCard
import app.codeg.android.feature.sessiondetail.interactive.PlanApprovalCard
import app.codeg.android.feature.sessiondetail.interactive.PlanFullscreenDialog
import app.codeg.android.core.designsystem.component.DocPreviewDialog
import app.codeg.android.core.designsystem.markdown.LocalPathOpener
import app.codeg.android.feature.sessiondetail.timeline.LocalSessionContext
import app.codeg.android.feature.sessiondetail.timeline.LocalTimelineScroll
import app.codeg.android.feature.sessiondetail.timeline.NodeBody
import app.codeg.android.feature.sessiondetail.timeline.NodeContent
import app.codeg.android.feature.sessiondetail.timeline.SessionContextUsage
import app.codeg.android.feature.sessiondetail.timeline.TimelineNode
import app.codeg.android.feature.sessiondetail.timeline.TimelineRow
import app.codeg.android.feature.sessiondetail.timeline.TranscriptTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * The session detail screen: the transcript rendered as a vertical **timeline**
 * (one rail, every event a node) over a pinned composer. Sending resolves/opens an
 * ACP connection, streams the reply, and reconciles against the server transcript
 * on completion. Port of the iOS `SessionDetailView`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    onBack: () -> Unit,
    onOpenSession: (Int) -> Unit = {},
    /** Jump to the session's project (folder) detail screen — batch v125. */
    onOpenFolder: (Int) -> Unit = {},
    seedText: String? = null,
    /** Pairings for the composer's device switch (批v154) — a new task may be
     *  born on any device, not just the one it was launched from. */
    servers: List<ServerProfile> = emptyList(),
    /** Opens the project manager from the "add to project" sheet. */
    onOpenManageProjects: () -> Unit = {},
    viewModel: SessionDetailViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    var draft by remember { mutableStateOf("") }
    // v141 cross-device handoff composer, opened from the top-bar overflow menu.
    var showHandoff by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var handoffSource: HandoffSource? by remember { mutableStateOf(null) }
    val noticeNoSource = stringResource(R.string.handoff_no_source)
    // 批v150 quick handoff: the ball's 会话交接 picker fired the bus right after
    // handing off to the owning device — if THIS session is the addressee, expand
    // the same sheet the overflow menu opens. Consume-on-match: a stale pending
    // (session closed before load) never haunts a later visit.
    val pendingHandoff by app.codeg.android.feature.handoff.HandoffAutoOpenBus.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pendingHandoff, ui.loading) {
        val key = pendingHandoff ?: return@LaunchedEffect
        if (ui.loading) return@LaunchedEffect
        // 过期意图不自动开门（审查轮1 M-1）：用户早已放弃的交接，几十分钟后
        // 偶然点开同一会话不应凭空弹出交接单——过期即清。
        if (!app.codeg.android.feature.handoff.HandoffAutoOpenBus.isFresh()) {
            app.codeg.android.feature.handoff.HandoffAutoOpenBus.consume()
            return@LaunchedEffect
        }
        if (key == viewModel.sessionKey) {
            app.codeg.android.feature.handoff.HandoffAutoOpenBus.consume()
            showHandoff = true
        }
    }
    // Rotation restores the open flag; re-resolve the source snapshot from the VM
    // (and bail with a notice when the session isn't server-linked).
    LaunchedEffect(showHandoff) {
        if (showHandoff && handoffSource == null) {
            val src = viewModel.handoffSource()
            if (src == null) {
                showHandoff = false
                viewModel.showNotice(noticeNoSource)
            } else handoffSource = src
        }
    }
    val openSource = handoffSource
    if (showHandoff && openSource != null) {
        val handoffViewModel: HandoffViewModel = hiltViewModel()
        HandoffSheet(
            source = openSource,
            viewModel = handoffViewModel,
            onClose = { showHandoff = false },
        )
    }
    // Draft memory: hydrate the composer from DataStore on entry — but only AFTER load()
    // has resolved the server profile, so the draft key is the real `<serverId>:<id>` and
    // not the pre-load "?" slot. Only fills an empty composer so a restoreDraft from a
    // failed send always wins. Read failures (IO, prefs file damage) degrade to "no
    // persisted draft" — the composer must never crash the screen over a draft.
    LaunchedEffect(ui.loading) {
        if (!ui.loading) {
            val persisted = runCatching { viewModel.loadPersistedDraft() }.getOrDefault("")
            if (draft.isEmpty() && persisted.isNotEmpty()) draft = persisted
        }
    }
    // Add-to-session seed («@ 添加到会话»): the serialized
    // `codeg://session/<id>` reference lands in the composer verbatim.
    // Appending, not replacing: whatever the composer already holds (typed this
    // session, or restored from DataStore) is the user's work, and the old
    // "only fill an empty composer" rule silently ATE the reference whenever a
    // draft happened to exist — the one outcome the gesture must never produce.
    var seedApplied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(seedText) {
        if (seedText.isNullOrEmpty() || seedApplied) return@LaunchedEffect
        // 批v165 R1 (P1): one-shot per screen instance. `draft` is a plain
        // remember — on rotation it resets to "", the persisted copy may still
        // predate the debounced save, and the endsWith guard below then sees
        // "no seed yet" and appends the reference a second time. A consumed
        // flag survives rotation (rememberSaveable) and dies with the entry,
        // so a genuinely new seed (fresh navigation) still lands.
        seedApplied = true
        val persisted = runCatching { viewModel.loadPersistedDraft() }.getOrDefault("")
        val existing = draft.ifBlank { persisted }
        draft = when {
            existing.isBlank() -> seedText
            existing.trimEnd().endsWith(seedText) -> existing
            else -> existing.trimEnd() + "\n\n" + seedText
        }
    }
    var showInsert by remember { mutableStateOf(false) }
    var showAgentMention by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var showContextUsage by remember { mutableStateOf(false) }
    var showSessionDetails by remember { mutableStateOf(false) }
    var showPlanProgress by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // A failed send returns the user's typed text to the composer so it isn't lost — but only
    // when the composer is empty, so it never clobbers a fresh draft typed during the send.
    // Whether it was applied decides if the returned text is re-persisted (a stale copy
    // must not overwrite the fresher text the user typed during the flight).
    LaunchedEffect(ui.restoreDraft) {
        ui.restoreDraft?.let { restored ->
            val applied = draft.isBlank()
            if (applied) draft = restored
            viewModel.consumeRestoredDraft(applied)
        }
    }
    // 批v165 R5: the gate is the only path where the persisted draft survives
    // its own send (clearPersistedDraft runs in runSend, after the park). A
    // rotation re-hydrates that draft into the composer, and after Enable&send
    // it would sit there as a duplicate-send trap — clear it while the gate is
    // up; the cancel path restores through restoreDraft above.
    LaunchedEffect(ui.delegationGate) {
        if (ui.delegationGate) draft = ""
    }

    if (showOptions) AgentOptionsSheet(
        viewModel = viewModel,
        onOpenSession = onOpenSession,
        onDismiss = { showOptions = false },
    )
    if (showContextUsage) {
        ContextUsageSheet(ui = ui, onDismiss = { showContextUsage = false })
    }
    if (showSessionDetails) {
        SessionDetailsSheet(ui = ui, viewModel = viewModel, onDismiss = { showSessionDetails = false })
    }
    // The running turn's plan card. The chip that opens it only exists while a plan
    // is live AND the turn is in flight — when the turn ends (or the live state is
    // torn down), close the sheet rather than leaving it showing a stale plan.
    if (showPlanProgress) {
        val live = ui.live
        if (live != null && live.livePlan.isNotEmpty()) {
            PlanProgressSheet(live = live, onDismiss = { showPlanProgress = false })
        } else {
            showPlanProgress = false
        }
    }
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(AttachmentPrep.MAX_COUNT),
    ) { uris -> if (uris.isNotEmpty()) viewModel.addAttachments(uris, context.contentResolver) }

    // System file manager for documents (Markdown/PRD/Plan/…)."*/*" keeps every
    // provider selectable; per-file caps live in the view model.
    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) viewModel.addFileAttachments(uris, context.contentResolver) }

    if (showInsert) {
        ComposeInsertSheet(
            viewModel = viewModel,
            onPickFiles = {
                showInsert = false
                pickFiles.launch(arrayOf("*/*"))
            },
            onInsert = { transform ->
                val next = transform(draft)
                draft = next
                viewModel.saveDraft(next)
            },
            onDismiss = { showInsert = false },
        )
    }

    // 批v165 @智能体: the composer's @ button opens the picker; picking inserts
    // the `[@名](codeg://agent/<slug>)` Markdown link at the END of the draft
    // (plain-String composer state until M-B's TextFieldValue chip work — no
    // cursor to honor). A space pads the seam so the link never glues onto the
    // previous word (a glued token wouldn't parse as a Markdown link).
    if (showAgentMention) {
        AgentMentionSheet(
            viewModel = viewModel,
            onPick = { agent ->
                showAgentMention = false
                val seam = when {
                    draft.isEmpty() -> ""
                    draft.last().isWhitespace() -> ""
                    else -> " "
                }
                val next = draft + seam + agentMentionMarkdown(agent) + " "
                draft = next
                viewModel.saveDraft(next)
            },
            onDismiss = { showAgentMention = false },
        )
    }

    // 批v165: the delegation gate — the draft carries an @ link but the server
    // confirmed delegation is off, so the send was parked before any optimistic
    // state landed. Primary action enables delegation and replays the send.
    if (ui.delegationGate) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDelegationGate,
            title = { Text(stringResource(R.string.mention_gate_title)) },
            text = { Text(stringResource(R.string.mention_gate_body)) },
            confirmButton = {
                TextButton(
                    onClick = viewModel::enableDelegationAndSend,
                    enabled = !ui.delegationGateBusy,
                ) {
                    Text(
                        if (ui.delegationGateBusy) stringResource(R.string.mention_gate_enabling)
                        else stringResource(R.string.mention_gate_enable),
                        color = colors.accent,
                    )
                }
            },
            dismissButton = {
                // 批v165 R3 (P2): cancel stays live during Enabling… — the VM's
                // stillOpen guard aborts the replay when the dialog closes. The
                // back key could already do this; the button used to lie.
                TextButton(onClick = viewModel::dismissDelegationGate) {
                    Text(stringResource(R.string.common_cancel), color = colors.textSecondary)
                }
            },
        )
    }

    // Flatten the transcript into timeline nodes. The persisted half is memoized on its
    // own so the ~50ms live stream only re-runs the cheap live append, not a full re-adapt.
    // On reattach the live turn (rebuilt from the snapshot) owns the in-flight reply, so
    // hide the partial copy the server persists into `turns` to avoid a doubled reply.
    val hideReattachDup = ui.liveFromReattach && ui.live != null
    val persistedNodes = remember(ui.turns, ui.pendingUserTurns, ui.agent, hideReattachDup) {
        TranscriptTimeline.buildPersisted(ui.turns, ui.pendingUserTurns, ui.agent, hideReattachDup)
    }
    val nodes = remember(persistedNodes, ui.live, ui.agent) {
        TranscriptTimeline.withLive(persistedNodes, ui.live, ui.agent)
    }
    val hasContent = nodes.isNotEmpty()

    var showToc by remember { mutableStateOf(false) }
    // Fullscreen editor survives rotation; the text itself stays in the shared [draft].
    var showFullscreenEditor by rememberSaveable { mutableStateOf(false) }
    // Set when the user jumps from the TOC; drives a 1s highlight flash on the target row.
    var highlightedNodeId by remember { mutableStateOf<String?>(null) }
    // TOC entries: one per user message — ordinal, up-to-80-char preview of the first text
    // block ("[Image]" for image-only), and compact relative time. Derived from nodes so it
    // always matches what the timeline actually rendered.
    val tocEntries = remember(nodes) {
        nodes.mapIndexedNotNull { idx, node ->
            val turn = (node.content as? NodeContent.User)?.turn ?: return@mapIndexedNotNull null
            val preview = turn.blocks.mapNotNull { (it as? ContentBlock.Text)?.text }
                .firstOrNull { it.isNotBlank() }?.trim()?.take(80)
            TocEntry(index = idx, node = node, preview = preview, timestamp = turn.timestamp)
        }
    }

    // Native stick-to-bottom: auto-follow only while the bottom anchor is in view.
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 1
        }
    }
    var stick by remember { mutableStateOf(true) }
    // M1 review: the older-pages sentinel must not compose on frame 1 — it sits
    // at index 0, so it would fire one unrequested 120-turn page fetch before
    // the initial pin-to-bottom ever ran. Armed once the first stick scroll has
    // actually landed.
    var bottomPinnedOnce by remember { mutableStateOf(false) }
    // Re-evaluate "follow" only when a *user* scroll settles — programmatic scrolls
    // and streamed growth must not flip it (that would race the auto-follow off).
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { inProgress ->
            if (!inProgress) stick = isAtBottom
        }
    }
    // A freshly sent message always re-pins to the bottom.
    LaunchedEffect(ui.pendingUserTurns.lastOrNull()?.id) {
        if (ui.pendingUserTurns.isNotEmpty()) stick = true
    }
    // Follow new / streamed content while pinned. The window's older-pages
    // sentinel occupies one slot before the nodes, so the bottom anchor lives at
    // nodes.size + (sentinel ? 1 : 0) — targeting nodes.size parks the viewport
    // at the top of the last node when it is taller than the screen (fleet M1
    // review blocker: streaming follow died mid-reply on 120+ turn sessions).
    LaunchedEffect(nodes.size, ui.live, stick, ui.canLoadOlder) {
        if (stick && nodes.isNotEmpty()) {
            listState.scrollToItem(nodes.size + if (ui.canLoadOlder) 1 else 0)
            bottomPinnedOnce = true
        }
    }
    val scrollToId: (String) -> Unit = remember(nodes) {
        { id ->
            val idx = nodes.indexOfFirst { it.id == id }
            if (idx >= 0) {
                stick = false // unpin so the stream doesn't immediately yank us back down
                scope.launch { listState.animateScrollToItem(idx) }
            }
        }
    }
    // TOC jump: scroll to the message, then flash-highlight its row for a second so the
    // target is unmistakable even in a long transcript.
    val jumpToMessage: (String) -> Unit = { id ->
        scrollToId(id)
        highlightedNodeId = id
    }
    LaunchedEffect(highlightedNodeId) {
        if (highlightedNodeId != null) {
            delay(1000)
            highlightedNodeId = null
        }
    }

    val title = when {
        ui.isNew -> stringResource(R.string.session_new_task)
        else -> ui.summary?.trimmedTitle ?: stringResource(R.string.session_title_fallback)
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = colors.textPrimary,
                        )
                    }
                },
                actions = {
                    // Batch v125: jump to the session's project (folder) detail — the
                    // transcript's inline file links only fire for absolute paths, so a
                    // bare filename in AI output has no anchor; this button gets the user
                    // to the workspace file browser in one tap instead.
                    ui.selectedFolderId?.let { folderId ->
                        IconButton(onClick = { onOpenFolder(folderId) }) {
                            Icon(
                                Icons.Rounded.FolderOpen,
                                contentDescription = stringResource(R.string.session_open_folder),
                                tint = colors.textSecondary,
                            )
                        }
                    }
                    // The agent avatar is the options button (mode / config / folder / branch).
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable(
                                onClickLabel = stringResource(R.string.session_agent_options),
                                role = Role.Button,
                            ) { showOptions = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        AgentAvatar(ui.agent, size = 28.dp)
                    }
                    if (ui.summary != null) SessionActionsMenu(ui, viewModel, onDeleted = onBack, onHandoff = { showHandoff = true })
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = colors.textPrimary,
                ),
            )
        },
        bottomBar = {
            Column(Modifier.imePadding()) {
                // 批v160: the interactive cards live in a bounded, scrollable strip —
                // a tall card (expanded raw JSON, long plan, several questions) can
                // no longer push its option row off-screen under the IME (the
                // "only one button visible" report). The composer below stays pinned;
                // the strip scrolls instead of clipping.
                if (ui.pendingPermission != null || ui.pendingQuestion != null || ui.pendingPlanApproval != null) {
                    val stripMax = with(androidx.compose.ui.platform.LocalConfiguration.current) {
                        (screenHeightDp * 0.6f).dp
                    }
                    // Fullscreen plan reading (M7): the strip's 300/320dp cap is
                    // fine for options but crushes a long plan — the cards' header
                    // expand button swaps in PlanFullscreenDialog, read-only.
                    var fullscreenPlan by remember { mutableStateOf<FullscreenPlanUi?>(null) }
                    val permFallbackTitle = stringResource(R.string.perm_request_title)
                    val planApprovalTitle = stringResource(R.string.plan_approval_title)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = stripMax)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        ui.pendingPermission?.let { p ->
                            PermissionRequestCard(
                                parsed = p.parsed,
                                options = p.options,
                                onRespond = viewModel::respondPermission,
                                onExpandFullscreen = p.parsed.planMarkdown
                                    ?.takeIf { p.parsed.isPlan && it.isNotBlank() }
                                    ?.let { md -> { fullscreenPlan = FullscreenPlanUi(p.parsed.title.ifBlank { permFallbackTitle }, md) } },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                        ui.pendingQuestion?.let { q ->
                            AskQuestionCard(
                                questions = q.questions,
                                onSubmit = viewModel::answerQuestion,
                                onSkip = viewModel::dismissQuestion,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                        ui.pendingPlanApproval?.let { p ->
                            PlanApprovalCard(
                                planMarkdown = p.planMarkdown,
                                onAnswer = viewModel::answerPlanApproval,
                                onExpandFullscreen = p.planMarkdown.trim()
                                    .takeIf { it.isNotBlank() }
                                    ?.let { md -> { fullscreenPlan = FullscreenPlanUi(planApprovalTitle, md) } },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                    fullscreenPlan?.let { plan ->
                        PlanFullscreenDialog(
                            title = plan.title,
                            markdown = plan.markdown,
                            onDismiss = { fullscreenPlan = null },
                        )
                    }
                }
                // Keep the last shown message so the exit animation has content to fade.
                var lastNotice by remember { mutableStateOf("") }
                LaunchedEffect(ui.notice) { ui.notice?.let { lastNotice = it } }
                AnimatedVisibility(
                    visible = ui.notice != null,
                    enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(180)),
                ) {
                    NoticeBanner(lastNotice, onDismiss = viewModel::dismissNotice)
                }
                if (ui.attachments.isNotEmpty()) AttachmentStrip(ui.attachments, onRemove = viewModel::removeAttachment)
                if (ui.fileAttachments.isNotEmpty()) FileAttachmentStrip(ui.fileAttachments, onRemove = viewModel::removeFileAttachment)
                QueuedDraftsBar(
                    queue = ui.queue,
                    onInsert = viewModel::insertQueuedNow,
                    onEdit = viewModel::dequeueForEdit,
                    onDelete = viewModel::removeQueued,
                    canInsert = ui.steeringAvailable,
                )
                ComposeBar(
                    text = draft,
                    onTextChange = {
                        draft = it
                        viewModel.saveDraft(it)
                    },
                    onSend = {
                        viewModel.send(draft)
                        draft = ""
                    },
                    isInFlight = ui.isInFlight,
                    onStop = viewModel::cancel,
                    onQueue = {
                        viewModel.enqueueDraft(draft)
                        draft = ""
                    },
                    onPlus = { showInsert = true },
                    onAttach = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onMention = { showAgentMention = true },
                    onTocClick = if (tocEntries.isNotEmpty()) ({ showToc = true }) else null,
                    onExpandClick = { showFullscreenEditor = true },
                    canSendOverride = ui.attachments.isNotEmpty() || ui.fileAttachments.isNotEmpty(),
                    sendBlocked = ui.forking,
                    statusBar = { SessionStatusRow(ui = ui) },
                    contextBar = {
                        SessionContextChips(
                            ui = ui,
                            viewModel = viewModel,
                            servers = servers,
                            onOpenManageProjects = onOpenManageProjects,
                            onOpenOptions = { showOptions = true },
                            onOpenContextUsage = { showContextUsage = true },
                            onOpenSessionDetails = { showSessionDetails = true },
                            onOpenPlanProgress = { showPlanProgress = true },
                        )
                    },
                    commandSuggestions = ui.availableCommands,
                    onPickCommand = { command ->
                        // Insert rather than send: the user reviews the command before firing,
                        // and a command that takes arguments keeps the cursor after the space.
                        val next = "/" + command.name.removePrefix("/") + " "
                        draft = next
                        viewModel.saveDraft(next)
                    },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                ui.loading && !hasContent ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingView(message = stringResource(R.string.common_loading))
                    }

                ui.error != null && !hasContent ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        InlineError(
                            icon = Icons.Rounded.Forum,
                            title = stringResource(R.string.session_load_failed),
                            message = ui.error!!,
                            onRetry = { viewModel.load() },
                            retryLabel = stringResource(R.string.common_retry),
                        )
                    }

                !hasContent ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            icon = Icons.Rounded.Forum,
                            title = if (ui.isNew) {
                                stringResource(R.string.session_new_task)
                            } else {
                                stringResource(R.string.session_empty_title)
                            },
                            message = stringResource(R.string.session_empty_message),
                        )
                    }

                else -> {
                    // The footer's context line: the session's live context-window
                    // occupancy (server `usage_update` → sessionStats), kept out of the
                    // timeline so it stays a pure renderer.
                    val sessionContext = remember(ui.sessionStats) {
                        ui.sessionStats?.let {
                            SessionContextUsage(
                                usedTokens = it.contextWindowUsedTokens?.toLong(),
                                maxTokens = it.contextWindowMaxTokens?.toLong(),
                                percent = it.contextWindowUsagePercent,
                            )
                        } ?: SessionContextUsage()
                    }
                    CompositionLocalProvider(
                        LocalTimelineScroll provides scrollToId,
                        LocalSessionContext provides sessionContext,
                        LocalPathOpener provides viewModel::openPathPreview,
                    ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                    ) {
                        // M1 reverse-infinite-scroll: composing this row (the user
                        // scrolled the window's top into view) fires the next older
                        // page; the VM single-flights and re-fires per offset, so
                        // holding the top keeps paging. Legacy servers (no window)
                        // never show it. The trigger stays unarmed until the initial
                        // pin-to-bottom has landed — on frame 1 this row composes at
                        // index 0, and firing then would fetch one unrequested page
                        // on every open (fleet M1 review).
                        if (ui.canLoadOlder) {
                            item(key = "__timeline_older__") {
                                LaunchedEffect(ui.turnsOffset, bottomPinnedOnce) {
                                    if (bottomPinnedOnce) viewModel.loadOlderTurns()
                                }
                                Box(
                                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (ui.loadingOlder) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    }
                                }
                            }
                        }
                        items(nodes, key = { it.id }, contentType = { it.contentTypeKey }) { node ->
                            // TOC-jump flash: the target row's background tweens up to a
                            // soft accent wash and back (keyed by node id so recycled rows
                            // never inherit a stale highlight).
                            val flashTarget = node.id == highlightedNodeId
                            val flashColor by animateColorAsState(
                                targetValue = if (flashTarget) colors.accent.copy(alpha = 0.14f) else Color.Transparent,
                                animationSpec = tween(if (flashTarget) 120 else 500),
                                label = "toc-flash-${node.id}",
                            )
                            Box(
                                Modifier
                                    // animateItem belongs on the item ROOT (this Box), or
                                    // the flash wash would snap while the row animated.
                                    .then(if (node.content is NodeContent.AssistantBlock) Modifier else Modifier.animateItem())
                                    .background(flashColor),
                            ) {
                                TimelineRow(
                                    marker = node.marker,
                                    connectTop = node.connectTop,
                                    connectBottom = node.connectBottom,
                                    startsGroup = node.startsGroup,
                                    rail = node.rail,
                                ) {
                                    NodeBody(
                                        node,
                                        // Fork entry rides the footer of every completed
                                        // assistant group; null hides it for non-Codex agents
                                        // and while a turn is in flight.
                                        onForkFromTurn = if (ui.forkSupported && !ui.isInFlight && !ui.forking) {
                                            viewModel::forkFromTurn
                                        } else null,
                                    )
                                }
                            }
                        }
                        item(key = "__timeline_bottom_anchor__") { Spacer(Modifier.height(1.dp)) }
                    }
                    }
                }
            }
            // Floating "jump to latest" — overlaid bottom-center OVER the transcript rather
            // than sitting in the composer stack, where its full-width row pushed the bottom
            // of the transcript up under a band. Shown only while scrolled up.
            AnimatedVisibility(
                visible = hasContent && !isAtBottom,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150)),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            ) {
                JumpToLatestButton {
                    stick = true
                    scope.launch { listState.animateScrollToItem(nodes.size + if (ui.canLoadOlder) 1 else 0) }
                }
            }
        }
    }

    if (showToc) MessageTocSheet(
        entries = tocEntries,
        onJump = { id ->
            showToc = false
            jumpToMessage(id)
        },
        onDismiss = { showToc = false },
    )
    ui.docPreview?.let { preview ->
        DocPreviewDialog(state = preview, onDismiss = viewModel::dismissDocPreview)
    }
    if (showFullscreenEditor) FullscreenTextEditorDialog(
        text = draft,
        onTextChange = {
            draft = it
            viewModel.saveDraft(it)
        },
        onDismiss = { showFullscreenEditor = false },
        label = stringResource(R.string.compose_edit_fullscreen),
        onSend = {
            showFullscreenEditor = false
            viewModel.send(draft)
            draft = ""
        },
        // Parity with the compact composer: no send while a turn is in flight or
        // a fork is being created (the dialog used to take these as separate flags).
        sendEnabled = (draft.isNotBlank() || ui.attachments.isNotEmpty()) && !ui.isInFlight && !ui.forking,
    )
}

/** One row in the message TOC: the timeline index of a user message plus a preview. */
private data class TocEntry(
    val index: Int,
    val node: TimelineNode,
    val preview: String?,
    val timestamp: Instant,
)

/** Bottom sheet listing every user message in order; tapping one scrolls the transcript
 *  to it and flash-highlights the row. Hidden entirely when there are no user messages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageTocSheet(
    entries: List<TocEntry>,
    onJump: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CodegTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.session_toc_open),
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(
            // Content-bound height: a 2-entry TOC shouldn't stretch into a full-height sheet.
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).sheetContentScrollLock(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            itemsIndexed(entries, key = { _, e -> e.node.id }) { ordinal, entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onJump(entry.node.id) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "${ordinal + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textTertiary,
                        modifier = Modifier.widthIn(min = 20.dp),
                    )
                    Text(
                        entry.preview ?: stringResource(R.string.session_toc_image),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        RelativeTime.compact(entry.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary,
                    )
                }
            }
        }
    }
}

/** A circular "jump to latest" affordance, floated above the composer when scrolled up. */
@Composable
private fun JumpToLatestButton(onClick: () -> Unit) {
    val colors = CodegTheme.colors
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(colors.bgElevated.copy(alpha = 0.96f))
            .border(0.5.dp, colors.surfaceStroke, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.ArrowDownward,
            contentDescription = stringResource(R.string.session_jump_to_latest),
            tint = colors.accent,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SessionStatusRow(ui: SessionDetailUiState) {
    val colors = CodegTheme.colors
    AnimatedVisibility(
        visible = ui.isInFlight,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(160)),
    ) {
        // A 1s ticker drives the elapsed clock; it only exists while the row is visible,
        // so an idle session costs nothing.
        var now by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(ui.runStartedAt, ui.isInFlight) {
            while (ui.isInFlight) {
                now = System.currentTimeMillis()
                delay(1000)
            }
        }
        val startedAt = ui.runStartedAt
        val elapsed = startedAt?.let {
            val parts = elapsedParts(now - it)
            when {
                parts.hours > 0 -> stringResource(
                    R.string.elapsed_hours_minutes,
                    parts.hours.toInt(),
                    parts.minutes.toInt(),
                )
                parts.minutes > 0 -> stringResource(
                    R.string.elapsed_minutes_seconds,
                    parts.minutes.toInt(),
                    parts.seconds.toInt(),
                )
                else -> stringResource(R.string.elapsed_seconds, parts.seconds.toInt())
            }
        }
        // The running model, from the session's config options — agents without a
        // model selector contribute nothing (never a fabricated name).
        val modelLabel = remember(ui.configOptions) { currentModelLabel(ui.configOptions) }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LivePulse(dotSize = 7.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = buildString {
                    append(stringResource(R.string.session_status_generating))
                    modelLabel?.let { append(" · ").append(it) }
                },
                fontSize = 12.sp,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (elapsed != null) {
                Text(elapsed, fontSize = 12.sp, color = colors.textTertiary)
            }
        }
    }
}

@Composable
private fun NoticeBanner(message: String, onDismiss: () -> Unit) {
    val colors = CodegTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.danger.copy(alpha = 0.14f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_dismiss), tint = colors.textSecondary)
        }
    }
}

/** A horizontal strip of staged document attachments (name + size chips), each removable. */
@Composable
private fun FileAttachmentStrip(files: List<FileAttachment>, onRemove: (String) -> Unit) {
    val colors = CodegTheme.colors
    LazyRow(
        Modifier.fillMaxWidth().background(colors.bgElevated.copy(alpha = 0.92f)).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(files, key = { it.id }) { f ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.codeSurface)
                    .border(0.5.dp, colors.surfaceStroke, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.InsertDriveFile,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
                Column {
                    Text(f.name, fontSize = 13.sp, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                    Text(
                        if (f.isInline) stringResource(R.string.file_attach_inline) else formatBytes(f.size),
                        fontSize = 11.sp,
                        color = colors.textTertiary,
                    )
                }
                Box(
                    Modifier.size(18.dp).clip(RoundedCornerShape(50)).background(colors.bg.copy(alpha = 0.7f)).clickable { onRemove(f.id) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_remove), tint = colors.textPrimary, modifier = Modifier.size(12.dp)) }
            }
        }
    }
}

/** Compact human-readable byte size for the file chips. */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000f)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}

/** A horizontal strip of pending image attachments, each removable.
 *  批v155 段3: tapping a thumbnail opens the fullscreen viewer across all
 *  images in the strip. */
@Composable
private fun AttachmentStrip(attachments: List<Attachment>, onRemove: (String) -> Unit) {
    val colors = CodegTheme.colors
    // rememberSaveable: rotation no longer closes the viewer (R2D#4).
    var viewerIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    LazyRow(
        Modifier.fillMaxWidth().background(colors.bgElevated.copy(alpha = 0.92f)).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(attachments, key = { it.id }) { att ->
            // Decode off the main thread (batch v130): up to 10 base64 + bitmap decodes
            // inline in composition froze the first frame of the composer strip.
            val bitmap by produceState<ImageBitmap?>(null, att.id) {
                value = withContext(Dispatchers.Default) {
                    runCatching {
                        val bytes = android.util.Base64.decode(att.base64, android.util.Base64.DEFAULT)
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    }.getOrNull()
                }
            }
            Box(Modifier.size(60.dp)) {
                val bmp = bitmap
            if (bmp != null) {
                    Image(bmp, contentDescription = stringResource(R.string.sessiondetail_attachment), contentScale = ContentScale.Crop, modifier = Modifier.size(60.dp).clip(RoundedCornerShape(10.dp)).clickable { viewerIndex = attachments.indexOfFirst { it.id == att.id }.takeIf { idx -> idx >= 0 } })
                } else {
                    Box(Modifier.size(60.dp).clip(RoundedCornerShape(10.dp)).background(colors.codeSurface))
                }
                Box(
                    Modifier.align(Alignment.TopEnd).padding(2.dp).size(18.dp).clip(RoundedCornerShape(50)).background(colors.bg.copy(alpha = 0.7f)).clickable { onRemove(att.id) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_remove), tint = colors.textPrimary, modifier = Modifier.size(12.dp)) }
            }
        }
    }
    viewerIndex?.let { idx ->
        ImageViewerDialog(
            images = attachments.map { ImageViewerSource.Base64(it.base64) },
            initialIndex = idx,
            onDismiss = { viewerIndex = null },
        )
    }
}

/**
 * The composer's context chips (① in the batch-v106 plan): device · folder · agent,
 * always visible so a new-task screen answers "where / what am I talking to" without a
 * trip to the top-right avatar. On a draft the folder and agent chips are tappable
 * pickers wired to the existing [SessionDetailViewModel.selectFolder]/[selectAgent].
 * Order (batch v122): the two "doors" — 会话信息 then the "⋯" options sheet — lead the
 * passive context; the agent avatar row opens a draft (live sessions show no agent
 * chip; the header avatar already names the agent). Everything the chips don't show
 * (branch, mode/config, full descriptions) stays in AgentOptionsSheet — the top-right
 * avatar remains the untouched fallback entry point.
 */
@Composable
private fun SessionContextChips(
    ui: SessionDetailUiState,
    viewModel: SessionDetailViewModel,
    servers: List<ServerProfile>,
    onOpenManageProjects: () -> Unit,
    onOpenOptions: () -> Unit,
    onOpenContextUsage: () -> Unit,
    onOpenSessionDetails: () -> Unit,
    onOpenPlanProgress: () -> Unit,
) {
    val colors = CodegTheme.colors
    val editable = ui.isDraftEditable && !ui.isInFlight
    val agentDescription = stringResource(R.string.compose_ctx_agent)
    // 批v150：文件夹选择从锚定菜单换成统一 OptionListSheet（大列表可搜索）。
    var folderSheet by rememberSaveable { mutableStateOf(false) }
    // 批v154：设备切换（草稿专用）与「添加到项目」。
    var deviceSheet by rememberSaveable { mutableStateOf(false) }
    var projectSheet by rememberSaveable { mutableStateOf(false) }
    val projects by viewModel.sessionProjects.collectAsStateWithLifecycle()
    val projectMembers by viewModel.projectMembers.collectAsStateWithLifecycle()

    LazyRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Agent picker — drafts only (it's the pick that starts the session). A live
        // session gets NO agent label chip: the header's avatar (top bar, also the
        // options entry) already identifies the agent, and a read-only name card
        // here spent row space without doing anything.
        if (editable && ui.availableAgents.isNotEmpty()) {
            item(key = "ctx-agent") {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.codeSurface)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        // One semantic control: "Agent — pick from these avatars".
                        .semantics { contentDescription = agentDescription },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ui.availableAgents.forEach { agent ->
                        val selected = agent == ui.agent
                        Box(
                            Modifier
                                .then(
                                    if (selected) Modifier.border(
                                        1.5.dp,
                                        AgentVisuals.accent(agent),
                                        CircleShape,
                                    ) else Modifier,
                                )
                                .clip(CircleShape)
                                .clickable { viewModel.selectAgent(agent) },
                        ) {
                            AgentAvatar(agent, size = 24.dp, modifier = Modifier.alpha(if (selected) 1f else 0.55f))
                        }
                    }
                }
            }
        } else {
            // Restored in v123 (v122 had dropped it with the options move): a live
            // session's row-leading agent label — the CLI logo before 会话信息.
            item(key = "ctx-agent-label") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AgentAvatar(ui.agent, size = 20.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        ui.agent.shortName,
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
        // Context-window gauge (the web client's above-composer popup trigger).
        if (ui.sessionStats != null) {
            item(key = "ctx-usage") {
                ContextChip(
                    icon = Icons.Rounded.DataUsage,
                    label = stringResource(
                        R.string.context_usage_chip,
                        ui.sessionStats?.contextWindowUsagePercent?.let(::formatPercent) ?: "—",
                    ),
                    contentDescription = stringResource(R.string.context_usage_title),
                    enabled = true,
                    onClick = onOpenContextUsage,
                )
            }
        }
        // Live plan progress (the web sidebar's stage card): "计划 x/y" while the running
        // turn has a plan. Gated on isInFlight so a failed turn's residual live state can't
        // leave the chip hanging after the round trip is over.
        val planEntries = ui.live?.livePlan.orEmpty()
        if (ui.isInFlight && planEntries.isNotEmpty()) {
            item(key = "ctx-plan") {
                ContextChip(
                    icon = Icons.Rounded.Checklist,
                    label = stringResource(
                        R.string.plan_progress_chip,
                        planEntries.count { it.normalizedStatus == PlanEntry.Status.COMPLETED },
                        planEntries.size,
                    ),
                    contentDescription = stringResource(R.string.plan_progress_title),
                    enabled = true,
                    onClick = onOpenPlanProgress,
                )
            }
        }
        // Session handoff card: working dir, CLI id, JSON export, reconnect.
        item(key = "ctx-session-info") {
            ContextChip(
                icon = Icons.Rounded.Info,
                label = stringResource(R.string.session_info_chip),
                contentDescription = stringResource(R.string.session_info_chip),
                enabled = true,
                onClick = onOpenSessionDetails,
            )
        }
        // Overflow chip → the full options sheet (mode/config/branch + the same
        // pickers). v133 (owner call): the draft's folder switcher moved up right
        // after this — model and folder are the two picks a new session needs,
        // so they lead the row together.
        item(key = "ctx-more") {
            ContextChip(
                icon = Icons.Rounded.MoreHoriz,
                label = null,
                contentDescription = stringResource(R.string.session_agent_options),
                enabled = true,
                onClick = onOpenOptions,
            )
        }
        // Folder chip: dropdown of the draft's available folders — placed right
        // after the options chip (v133 owner call: 新建会话时模型/目录都顺手可切).
        if (ui.folderName != null || editable) {
            item(key = "ctx-folder") {
                if (editable) {
                    ContextChip(
                        icon = Icons.Rounded.Folder,
                        label = ui.folderName ?: stringResource(R.string.agentopts_choose_folder),
                        contentDescription = stringResource(R.string.compose_ctx_folder),
                        enabled = ui.availableFolders.isNotEmpty(),
                        onClick = { folderSheet = true },
                    )
                } else {
                    ContextChip(
                        icon = Icons.Rounded.Folder,
                        label = ui.folderName ?: "—",
                        contentDescription = stringResource(R.string.compose_ctx_folder),
                        enabled = false,
                    )
                }
            }
        }
        // 添加到项目 (批v154): the same sheet the session list's long-press opens,
        // here in the composer — picks queue on the draft and land on creation;
        // a live session writes through immediately.
        item(key = "ctx-project") {
            ContextChip(
                icon = Icons.Rounded.FolderSpecial,
                label = projectChipLabel(ui.projectIds, projects),
                contentDescription = stringResource(R.string.compose_ctx_project),
                enabled = true,
                onClick = { projectSheet = true },
            )
        }
        // Device chip (批v154): a DRAFT can still move — the launch device used to be
        // baked into the composer, so every new task was born wherever its session
        // list lived. Once the conversation exists the id is server-local and the
        // chip goes read-only.
        if (ui.serverName != null) {
            item(key = "ctx-device") {
                ContextChip(
                    icon = Icons.Rounded.Dns,
                    label = ui.serverName,
                    contentDescription = stringResource(R.string.compose_ctx_device),
                    enabled = ui.isNew && ui.isDraftEditable && servers.size > 1,
                    onClick = { deviceSheet = true },
                )
            }
        }
        // Branch chip: shows the resolved branch; tapping opens the full sheet (which
        // hosts the worktree-aware branch picker — too heavy for a dropdown).
        if (ui.currentBranch != null) {
            item(key = "ctx-branch") {
                ContextChip(
                    icon = Icons.AutoMirrored.Rounded.AltRoute,
                    label = ui.currentBranch,
                    contentDescription = stringResource(R.string.agentopts_branch),
                    enabled = true,
                    onClick = onOpenOptions,
                )
            }
        }
    }

    // 批v150：与共享派发表单同款的项目选择 sheet——可搜索、路径作 note、高亮当前值。
    if (folderSheet) {
        OptionListSheet(
            title = stringResource(R.string.agentopts_choose_folder),
            options = ui.availableFolders.map { PickerOption(id = it.id.toString(), label = it.name, note = it.path) },
            selectedId = ui.selectedFolderId?.toString(),
            onSelect = { opt -> ui.availableFolders.firstOrNull { it.id.toString() == opt.id }?.let(viewModel::selectFolder) },
            onDismiss = { folderSheet = false },
        )
    }

    if (deviceSheet) {
        // Same visual language as the new-task form's device chips; the current
        // device is checked and tapping another moves the draft there.
        OptionListSheet(
            title = stringResource(R.string.session_switch_device),
            options = servers.map {
                PickerOption(
                    id = it.id,
                    label = it.name,
                    note = if (it.id == ui.serverId) stringResource(R.string.compose_ctx_device) else null,
                )
            },
            selectedId = ui.serverId,
            onSelect = { opt -> viewModel.selectDevice(opt.id) },
            onDismiss = { deviceSheet = false },
        )
    }

    if (projectSheet) {
        // A draft has no id yet, so membership only queues locally; created
        // sessions write straight through to the project store.
        AddToProjectSheet(
            targetTitle = ui.summary?.trimmedTitle ?: stringResource(R.string.session_new_task),
            projects = projects,
            memberOf = ui.projectIds,
            memberCount = { p -> projectMembers[p.id].orEmpty().size },
            onToggle = viewModel::toggleDraftProject,
            onCreate = { name, onCreated -> viewModel.createDraftProject(name, onCreated) },
            onManage = {
                projectSheet = false
                onOpenManageProjects()
            },
            onDismiss = { projectSheet = false },
        )
    }
}

/** Chip label: the project's name when unambiguous, else a count. */
@Composable
private fun projectChipLabel(ids: Set<String>, projects: List<SessionProject>): String {
    val names = projects.filter { it.id in ids }.map { it.name }
    return when {
        names.isEmpty() -> stringResource(R.string.compose_ctx_project)
        names.size == 1 -> names.first()
        else -> names.first() + " +" + (names.size - 1)
    }
}

/**
 * One pill in the context row. Disabled = read-only context (device, a live session's
 * locked folder); enabled = opens a picker. Long labels ellipsize rather than wrap —
 * the row scrolls horizontally.
 */
@Composable
private fun ContextChip(
    icon: ImageVector,
    label: String?,
    contentDescription: String,
    enabled: Boolean,
    onClick: (() -> Unit)? = null,
) {
    val colors = CodegTheme.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.codeSurface)
            .then(if (enabled && onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .alpha(if (enabled) 1f else 0.7f),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) colors.accent else colors.textTertiary,
            modifier = Modifier.size(13.dp),
        )
        if (!label.isNullOrBlank()) {
            Text(
                label,
                fontSize = 11.sp,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 130.dp),
            )
        }
    }
}
