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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import android.view.WindowManager
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.common.RelativeTime
import app.codeg.android.core.designsystem.component.AgentAvatar
import app.codeg.android.core.designsystem.component.AgentVisuals
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.component.InlineError
import app.codeg.android.core.designsystem.component.LivePulse
import app.codeg.android.core.designsystem.component.LoadingView
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.PlanEntry
import app.codeg.android.feature.sessiondetail.interactive.AskQuestionCard
import app.codeg.android.feature.sessiondetail.interactive.PermissionRequestCard
import app.codeg.android.feature.sessiondetail.interactive.PlanApprovalCard
import app.codeg.android.feature.sessiondetail.timeline.LocalSessionContext
import app.codeg.android.feature.sessiondetail.timeline.LocalTimelineScroll
import app.codeg.android.feature.sessiondetail.timeline.NodeBody
import app.codeg.android.feature.sessiondetail.timeline.NodeContent
import app.codeg.android.feature.sessiondetail.timeline.SessionContextUsage
import app.codeg.android.feature.sessiondetail.timeline.TimelineNode
import app.codeg.android.feature.sessiondetail.timeline.TimelineRow
import app.codeg.android.feature.sessiondetail.timeline.TranscriptTimeline
import kotlinx.coroutines.delay
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
    seedText: String? = null,
    viewModel: SessionDetailViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    var draft by remember { mutableStateOf("") }
    // Draft memory: hydrate the composer from DataStore on entry — but only AFTER load()
    // has resolved the server profile, so the draft key is the real `<serverId>:<id>` and
    // not the pre-load "?" slot. Only fills an empty composer so a restoreDraft from a
    // failed send always wins.
    LaunchedEffect(ui.loading) {
        if (!ui.loading) {
            val persisted = viewModel.loadPersistedDraft()
            if (draft.isEmpty() && persisted.isNotEmpty()) draft = persisted
        }
    }
    // Handoff seed (「接手到新会话继续」): the serialized `codeg://session/<id>`
    // reference lands in the composer verbatim, like the web's add-to-session. A
    // fresh navigation gets a new composition, so `remember { seedText }` is
    // enough — no saveable (re-entering this route must re-seed, not restore).
    // After process death the persisted draft (edits made before the crash) wins
    // over the raw seed, so an entry that was already saved-as-draft isn't reset.
    LaunchedEffect(seedText) {
        if (!seedText.isNullOrEmpty() && draft.isEmpty() && viewModel.loadPersistedDraft().isEmpty()) {
            draft = seedText
        }
    }
    var showInsert by remember { mutableStateOf(false) }
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

    if (showInsert) {
        ComposeInsertSheet(
            viewModel = viewModel,
            onInsert = { transform ->
                val next = transform(draft)
                draft = next
                viewModel.saveDraft(next)
            },
            onDismiss = { showInsert = false },
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
    // Follow new / streamed content while pinned.
    LaunchedEffect(nodes.size, ui.live, stick) {
        if (stick && nodes.isNotEmpty()) listState.scrollToItem(nodes.size)
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
                    if (ui.summary != null) SessionActionsMenu(ui, viewModel, onDeleted = onBack)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = colors.textPrimary,
                ),
            )
        },
        bottomBar = {
            Column(Modifier.imePadding()) {
                ui.pendingPermission?.let { p ->
                    PermissionRequestCard(
                        parsed = p.parsed,
                        options = p.options,
                        onRespond = viewModel::respondPermission,
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
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
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
                    onTocClick = if (tocEntries.isNotEmpty()) ({ showToc = true }) else null,
                    onExpandClick = { showFullscreenEditor = true },
                    canSendOverride = ui.attachments.isNotEmpty(),
                    statusBar = { SessionStatusRow(ui = ui) },
                    contextBar = {
                        SessionContextChips(
                            ui = ui,
                            viewModel = viewModel,
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
                            onRetry = viewModel::load,
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
                    ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                    ) {
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
                                    NodeBody(node)
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
                    scope.launch { listState.animateScrollToItem(nodes.size) }
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
    if (showFullscreenEditor) FullscreenEditorDialog(
        text = draft,
        onTextChange = {
            draft = it
            viewModel.saveDraft(it)
        },
        onDismiss = { showFullscreenEditor = false },
        onSend = {
            showFullscreenEditor = false
            viewModel.send(draft)
            draft = ""
        },
        isInFlight = ui.isInFlight,
        canSend = draft.isNotBlank() || ui.attachments.isNotEmpty(),
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
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
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

/** Near-fullscreen editor for long prompts, sharing the composer's [text] state — edits
 *  apply live underneath, and closing (back / close / send) lands exactly where the
 *  compact field would have. Header: close · char count · send.
 *
 *  IME behaviour (batch v121): the dialog window must NOT react to the keyboard —
 *  `ADJUST_NOTHING` stops the window-level pan that used to fight `imePadding()` and
 *  make the editor bounce twice as the IME appeared. The single animation source is
 *  the `imePadding()` on the Surface. Focus is requested on the field so the keyboard
 *  opens onto it (never STATE_VISIBLE without focus — first keystrokes would drop).
 *  androidx only re-stamps the dialog's soft-input mode on API < 31, so one set is
 *  stable on the main flavor (minSdk 31); the legacy flavor (minSdk 28) keeps the
 *  stock resize behaviour. Scrolling lives in an outer `verticalScroll` Box rather
 *  than the field's internal scroll: the cursor's bring-into-view requests bubble to
 *  that container, so tapping near the bottom keeps the viewport there instead of
 *  snapping back to the first line.
 */
@Composable
private fun FullscreenEditorDialog(
    text: String,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSend: () -> Unit,
    isInFlight: Boolean,
    canSend: Boolean,
) {
    val colors = CodegTheme.colors
    Dialog(
        onDismissRequest = onDismiss,
        // decorFitsSystemWindows=false lets the content window see the IME insets, so
        // imePadding below actually lifts the field above the keyboard (with the default
        // `true` the dialog pans instead and the prompt tail hides under it).
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // The dialog window itself must hold still while the IME animates: pan/resize
        // here doubles the motion against imePadding(). (`.parent` — the DialogLayout,
        // not the inner compose view, implements DialogWindowProvider.)
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(dialogWindow) {
            dialogWindow?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
            )
        }
        val scroll = rememberScrollState()
        val focusRequester = remember { FocusRequester() }
        Surface(
            color = colors.bg,
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalIconButton(
                        onClick = onDismiss,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = colors.textPrimary.copy(alpha = 0.06f),
                            contentColor = colors.textSecondary,
                        ),
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_close))
                    }
                    Text(
                        text = stringResource(R.string.compose_edit_fullscreen) + " · " + text.length,
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textSecondary,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    FilledIconButton(
                        onClick = onSend,
                        enabled = canSend && !isInFlight,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = colors.accent,
                            contentColor = colors.onAccent,
                        ),
                    ) {
                        Icon(Icons.Rounded.ArrowUpward, contentDescription = stringResource(R.string.compose_send))
                    }
                }
                HorizontalDivider(thickness = Dp.Hairline, color = colors.surfaceStroke)
                Box(
                    Modifier
                        .weight(1f)
                        .verticalScroll(scroll),
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = colors.textPrimary,
                            textDirection = TextDirection.Content,
                        ),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .focusRequester(focusRequester),
                    )
                }
            }
        }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
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

/** A horizontal strip of pending image attachments, each removable. */
@Composable
private fun AttachmentStrip(attachments: List<Attachment>, onRemove: (String) -> Unit) {
    val colors = CodegTheme.colors
    LazyRow(
        Modifier.fillMaxWidth().background(colors.bgElevated.copy(alpha = 0.92f)).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(attachments, key = { it.id }) { att ->
            val bitmap = remember(att.id) {
                runCatching {
                    val bytes = android.util.Base64.decode(att.base64, android.util.Base64.DEFAULT)
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()
            }
            Box(Modifier.size(60.dp)) {
                if (bitmap != null) {
                    Image(bitmap, contentDescription = stringResource(R.string.sessiondetail_attachment), contentScale = ContentScale.Crop, modifier = Modifier.size(60.dp).clip(RoundedCornerShape(10.dp)))
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
}

/**
 * The composer's context chips (① in the batch-v106 plan): device · folder · agent,
 * always visible so a new-task screen answers "where / what am I talking to" without a
 * trip to the top-right avatar. On a draft the folder and agent chips are tappable
 * pickers wired to the existing [SessionDetailViewModel.selectFolder]/[selectAgent];
 * everything else (branch, mode/config, the same pickers with full descriptions)
 * stays in AgentOptionsSheet via the trailing "⋯" chip — the top-right avatar remains
 * the untouched fallback entry point.
 */
@Composable
private fun SessionContextChips(
    ui: SessionDetailUiState,
    viewModel: SessionDetailViewModel,
    onOpenOptions: () -> Unit,
    onOpenContextUsage: () -> Unit,
    onOpenSessionDetails: () -> Unit,
    onOpenPlanProgress: () -> Unit,
) {
    val colors = CodegTheme.colors
    val editable = ui.isDraftEditable && !ui.isInFlight
    val agentDescription = stringResource(R.string.compose_ctx_agent)

    LazyRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Agent picker FIRST (batch v121): it's the highest-frequency control on this
        // row — every new session starts with an agent pick — and the row scrolls
        // horizontally, so first position is the only slot that never needs a swipe.
        // The usage/plan gauges are run-time info and read fine one swipe in.
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
        // Device chip: read-only. A session is pinned to its server; switching devices
        // happens on the Servers tab, not mid-composer.
        if (ui.serverName != null) {
            item(key = "ctx-device") {
                ContextChip(
                    icon = Icons.Rounded.Dns,
                    label = ui.serverName,
                    contentDescription = stringResource(R.string.compose_ctx_device),
                    enabled = false,
                )
            }
        }
        // Folder chip: dropdown of the draft's available folders.
        if (ui.folderName != null || editable) {
            item(key = "ctx-folder") {
                if (editable) {
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        ContextChip(
                            icon = Icons.Rounded.Folder,
                            label = ui.folderName ?: stringResource(R.string.agentopts_choose_folder),
                            contentDescription = stringResource(R.string.compose_ctx_folder),
                            enabled = ui.availableFolders.isNotEmpty(),
                            onClick = { expanded = true },
                        )
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            ui.availableFolders.forEach { f ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(f.name, fontSize = 14.sp, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(f.path, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    },
                                    leadingIcon = { Icon(Icons.Rounded.Folder, null, tint = if (f.id == ui.selectedFolderId) colors.accent else colors.textTertiary, modifier = Modifier.size(16.dp)) },
                                    onClick = { viewModel.selectFolder(f); expanded = false },
                                )
                            }
                        }
                    }
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
        // Overflow chip → the full options sheet (mode/config/branch + the same pickers).
        item(key = "ctx-more") {
            ContextChip(
                icon = Icons.Rounded.MoreHoriz,
                label = null,
                contentDescription = stringResource(R.string.session_agent_options),
                enabled = true,
                onClick = onOpenOptions,
            )
        }
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
