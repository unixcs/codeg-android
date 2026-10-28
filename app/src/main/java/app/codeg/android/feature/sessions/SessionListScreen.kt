package app.codeg.android.feature.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.data.FocusItem
import app.codeg.android.core.datastore.ChatListDisplayStore
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.datastore.SessionRef
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.component.InlineError
import app.codeg.android.core.designsystem.component.LoadingView
import app.codeg.android.core.designsystem.component.deviceBadgeColor
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.designsystem.theme.colorFromHex
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.feature.main.LocalBarsVisible
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The Chats tab: the grouped session list for the selected server (Pinned /
 * per-folder / Other), a server switcher in the title, and a "New Task" action.
 *
 * The iOS card-zoom interaction is adapted to native **collapsible sections**: tap a
 * section header to fold/unfold its rows. The grouping itself is computed off the main
 * thread ([SessionListViewModel.sections]) and the list is virtualized with per-type
 * recycling, so a very large session list stays smooth.
 *
 * Folder sections can be **hidden** (long-press a header): hidden groups vanish from
 * the list but stay reachable — a dim "N hidden" row at the bottom opens a manage
 * sheet to restore them. Hidden + collapsed state is per-server, persisted locally
 * ([ChatListDisplayStore]); the Activity tab is never filtered, so a hidden group's
 * live sessions still surface there.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionListScreen(
    servers: List<ServerProfile>,
    selectedId: String?,
    onManageServers: () -> Unit,
    onOpenConversation: (Int) -> Unit,
    onNewTask: () -> Unit,
    onHandoff: (folderId: Int, reference: String) -> Unit = { _, _ -> },
    /** Aggregate ("全部") mode row taps: MainShell performs the owning-device
     *  selection dance before pushing the detail (ids are server-local). */
    onOpenAggregateRow: (serverId: String, conversationId: Int) -> Unit = { _, _ -> },
    onHandoffAggregate: (serverId: String, folderId: Int, reference: String) -> Unit = { _, _, _ -> },
    onManageProjects: () -> Unit = {},
    viewModel: SessionListViewModel = hiltViewModel(),
) {
    // Cross-device aggregate mode ("全部" + optional project filter) renders a
    // completely separate list; the branches share no state (review #1).
    val aggregate by viewModel.chatsAll.collectAsStateWithLifecycle()
    if (aggregate) {
        AggregateChatsList(
            viewModel = viewModel,
            onOpenRow = onOpenAggregateRow,
            onHandoff = onHandoffAggregate,
            onManageProjects = onManageProjects,
        )
        return
    }

    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val allSections by viewModel.sections.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    val selectedName = servers.firstOrNull { it.id == selectedId }?.name
        ?: stringResource(R.string.app_name)

    // Per-server display prefs (hidden sections + persisted fold state). Read as a
    // flow so a change (hide/restore/collapse) recomposes the list immediately.
    val displayStore = remember { viewModel.chatListDisplayStore }
    val display by remember(selectedId) {
        displayStore.display(selectedId ?: "")
    }.collectAsState(initial = ChatListDisplayDefaults)
    val scope = rememberCoroutineScope()

    var collapsed by remember(selectedId, display) { mutableStateOf(display.collapsedSections) }
    var hiddenTarget by remember { mutableStateOf<SessionSection?>(null) }
    var showHiddenManager by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ConversationSummary?>(null) }
    // Hoisted "添加到项目" target (one sheet per screen, review #9): the owning
    // device id rides along because conversation ids are server-local.
    var addToTarget by remember { mutableStateOf<Pair<String, ConversationSummary>?>(null) }
    val sessionProjects by viewModel.sessionProjects.collectAsStateWithLifecycle()
    val projectMembers by viewModel.projectMembers.collectAsStateWithLifecycle()

    // Hideable = folder sections only (Pinned is a transient override bucket; Other
    // holds orphans that would otherwise become unreachable from this list).
    val hiddenSections = display.hiddenSections
    val sections = remember(allSections, hiddenSections) {
        allSections.filter { it.id !in hiddenSections }
    }
    val hiddenCount = hiddenSections.size

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                // The app-global device bar (MainShell) owns server switching now —
                // the title is a plain label, and the Dns icon keeps the manage entry.
                title = { Text(selectedName) },
                actions = {
                    IconButton(onClick = onManageServers) {
                        Icon(
                            Icons.Rounded.Dns,
                            contentDescription = stringResource(R.string.home_manage_servers),
                            tint = colors.textSecondary,
                        )
                    }
                },
                // The app-global device bar (MainShell) already owns the status-bar
                // inset on tab routes; applying it here too doubles the top gap.
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = colors.textPrimary,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewTask,
                // Follows the bottom bar: collapses to just "+" while scrolling up,
                // expands back to the full pill when scrolling down (driven by MainShell).
                expanded = LocalBarsVisible.current,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.session_new_task)) },
                containerColor = colors.accent,
                contentColor = colors.onAccent,
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                ui.isLoading && !ui.hasLoaded -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingView(message = stringResource(R.string.common_loading))
                    }
                }

                !ui.hasLoaded && ui.error != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        InlineError(
                            icon = Icons.Rounded.Forum,
                            title = stringResource(R.string.sessions_load_failed),
                            message = ui.error!!,
                            onRetry = viewModel::refresh,
                            retryLabel = stringResource(R.string.common_retry),
                        )
                    }
                }

                ui.hasLoaded && ui.isEmpty -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            icon = Icons.Rounded.Forum,
                            title = stringResource(R.string.sessions_empty_title),
                            message = stringResource(R.string.sessions_empty_message),
                            actionLabel = stringResource(R.string.session_new_task),
                            onAction = onNewTask,
                        )
                    }
                }

                else -> {
                    PullToRefreshBox(
                        isRefreshing = ui.isRefreshing,
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        SessionList(
                            sections = sections,
                            hiddenCount = hiddenCount,
                            collapsed = collapsed,
                            onToggleSection = { id ->
                                val next = if (id in collapsed) collapsed - id else collapsed + id
                                collapsed = next
                                scope.launch {
                                    displayStore.setCollapsed(selectedId ?: "", next)
                                }
                            },
                            onOpenConversation = onOpenConversation,
                            onTogglePin = { conv -> viewModel.setPinned(conv, !conv.isPinned) },
                            onHandoff = onHandoff,
                            onDelete = { conv -> pendingDelete = conv },
                            onAddToProject = selectedId?.let { sid ->
                                { conv -> addToTarget = sid to conv }
                            },
                            onLongPressSection = { section -> hiddenTarget = section },
                            onOpenHiddenManager = { showHiddenManager = true },
                            // A refresh that failed over a still-populated list: surface
                            // it inline above the rows rather than swallowing it.
                            refreshError = ui.error,
                            onRetry = viewModel::refresh,
                            onDismissError = viewModel::dismissError,
                        )
                    }
                }
            }
        }
    }

    hiddenTarget?.let { target ->
        HideSectionSheet(
            section = target,
            onDismiss = { hiddenTarget = null },
            onHide = {
                scope.launch {
                    displayStore.setHidden(selectedId ?: "", hiddenSections + target.id)
                }
                hiddenTarget = null
            },
        )
    }

    if (showHiddenManager) {
        HiddenSectionsSheet(
            hidden = hiddenSections,
            sections = allSections,
            onDismiss = { showHiddenManager = false },
            onRestore = { id ->
                scope.launch {
                    displayStore.setHidden(selectedId ?: "", hiddenSections - id)
                }
            },
        )
    }

    pendingDelete?.let { conversation ->
        SessionDeleteDialog(
            onConfirm = {
                viewModel.delete(conversation)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    addToTarget?.let { (serverId, conversation) ->
        val ref = SessionRef(serverId, conversation.id)
        AddToProjectSheet(
            targetTitle = conversation.trimmedTitle ?: "#${conversation.id}",
            projects = sessionProjects,
            memberOf = projectMembers.filterValues { refs -> refs.any { it.key == ref.key } }.keys,
            memberCount = { project -> projectMembers[project.id]?.size ?: 0 },
            onToggle = { projectId, member -> viewModel.toggleProjectMembership(projectId, ref, member) },
            onCreate = viewModel::createProject,
            onManage = {
                addToTarget = null
                onManageProjects()
            },
            onDismiss = { addToTarget = null },
        )
    }
}

/** Initial collect value: nothing hidden, nothing collapsed. */
private val ChatListDisplayDefaults = app.codeg.android.core.datastore.ChatListDisplay()

@Composable
private fun SessionList(
    sections: List<SessionSection>,
    hiddenCount: Int,
    collapsed: Set<String>,
    onToggleSection: (String) -> Unit,
    onOpenConversation: (Int) -> Unit,
    onTogglePin: (ConversationSummary) -> Unit,
    onHandoff: (folderId: Int, reference: String) -> Unit,
    onDelete: (ConversationSummary) -> Unit,
    onAddToProject: ((ConversationSummary) -> Unit)?,
    onLongPressSection: (SessionSection) -> Unit,
    onOpenHiddenManager: () -> Unit,
    refreshError: String?,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 8.dp,
            end = 8.dp,
            top = 8.dp,
            bottom = 88.dp, // clear the extended FAB
        ),
    ) {
        if (refreshError != null) {
            item(key = "refresh-error", contentType = "banner") {
                RefreshErrorBanner(
                    message = refreshError,
                    onRetry = onRetry,
                    onDismiss = onDismissError,
                    modifier = Modifier.animateItem().padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
        }

        sections.forEach { section ->
            val isCollapsed = section.id in collapsed
            item(key = "h-${section.id}", contentType = "header") {
                val style = sectionStyle(section.kind)
                CollapsibleSectionHeader(
                    icon = style.icon,
                    tint = style.tint,
                    label = style.label,
                    count = section.count,
                    collapsed = isCollapsed,
                    onToggle = { onToggleSection(section.id) },
                    collapsible = section.count > 0,
                    onLongClick = { onLongPressSection(section) },
                    modifier = Modifier.animateItem(),
                )
            }
            if (!isCollapsed) {
                items(
                    items = section.rows,
                    key = { "row-${it.conversation.id}" },
                    contentType = { "row" },
                ) { row ->
                    SessionRow(
                        conversation = row.conversation,
                        onClick = { onOpenConversation(row.conversation.id) },
                        modifier = Modifier.animateItem(),
                        folderName = row.folderName,
                        onTogglePin = { onTogglePin(row.conversation) },
                        onHandoff = { ref -> onHandoff(row.conversation.folderId, ref) },
                        onDelete = { onDelete(row.conversation) },
                        onAddToProject = onAddToProject?.let { cb -> { cb(row.conversation) } },
                    )
                }
            }
        }

        if (hiddenCount > 0) {
            item(key = "hidden-manage", contentType = "hidden-footer") {
                HiddenManageRow(
                    count = hiddenCount,
                    onClick = onOpenHiddenManager,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

/** Resolved header presentation for a [SectionKind] (icon + theme-aware tint + label). */
private data class SectionStyle(val icon: ImageVector, val tint: Color, val label: String)

@Composable
private fun sectionStyle(kind: SectionKind): SectionStyle {
    val colors = CodegTheme.colors
    return when (kind) {
        SectionKind.Pinned -> SectionStyle(
            icon = Icons.Rounded.PushPin,
            tint = colors.accent,
            label = stringResource(R.string.sessions_pinned),
        )
        is SectionKind.Folder -> SectionStyle(
            icon = Icons.Rounded.Folder,
            tint = colorFromHex(kind.colorHex) ?: colors.accent,
            label = kind.name,
        )
        SectionKind.Other -> SectionStyle(
            icon = Icons.Rounded.Inbox,
            tint = colors.textSecondary,
            label = stringResource(R.string.sessions_other),
        )
    }
}

/** Dim "N hidden" footer row — the non-intrusive way back to the manage sheet. */
@Composable
private fun HiddenManageRow(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CodegTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Rounded.VisibilityOff,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(R.string.sessions_hidden_manage, count),
            style = MaterialTheme.typography.labelMedium,
            color = colors.textTertiary,
        )
    }
}

/** Confirmation sheet for hiding one section (long-press on a header). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HideSectionSheet(
    section: SessionSection,
    onDismiss: () -> Unit,
    onHide: () -> Unit,
) {
    val colors = CodegTheme.colors
    val style = sectionStyle(section.kind)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(
                text = stringResource(R.string.sessions_hide_title, style.label),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
            )
            Text(
                text = stringResource(R.string.sessions_hide_message),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                TextButton(onClick = onHide) {
                    Text(stringResource(R.string.sessions_hide_action), color = colors.accent)
                }
            }
        }
    }
}

/** Manage sheet: restore any currently hidden section. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HiddenSectionsSheet(
    hidden: Set<String>,
    sections: List<SessionSection>,
    onDismiss: () -> Unit,
    onRestore: (String) -> Unit,
) {
    val colors = CodegTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
            Text(
                text = stringResource(R.string.sessions_hidden_manage, hidden.size),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            if (hidden.isEmpty()) {
                Text(
                    text = stringResource(R.string.sessions_hidden_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            } else {
                sections.filter { it.id in hidden }.forEach { section ->
                    val style = sectionStyle(section.kind)
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { onRestore(section.id) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(style.icon, null, tint = style.tint, modifier = Modifier.size(20.dp))
                        Text(
                            style.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(R.string.sessions_restore),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.accent,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A dismissible banner for a refresh that failed while rows are still on screen —
 * the stale rows stay visible and the error is surfaced inline with Retry / dismiss
 * (the Android analogue of iOS `RefreshErrorBanner`). The initial-load failure still
 * uses the full-screen [InlineError]; this is only for the keep-the-list case.
 */
@Composable
private fun RefreshErrorBanner(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.danger.copy(alpha = 0.12f))
            .border(CodegTheme.dimens.hairlineWidth, colors.danger.copy(alpha = 0.35f), shape)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = colors.danger,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onRetry,
            colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
        ) {
            Text(stringResource(R.string.common_retry))
        }
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = stringResource(R.string.common_dismiss),
                tint = colors.textTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ---- Cross-device aggregate ("全部") list — batch v132 ----

/** Owner id the Chats list registers under in [SessionFocusHub] (HomeTab.CHATS.route). */
private const val CHATS_FOCUS_OWNER = "chats"

/** One virtualized item of the aggregate LazyColumn — the single source of truth
 *  for rendering keys AND focus/scroll indexing, so the two can never drift. */
private sealed interface AggListItem {
    val key: String
    val contentType: String

    data object Banner : AggListItem {
        override val key get() = "agg-banner"
        override val contentType get() = "banner"
    }

    /** Some (not all) devices failed this fetch — the all-failed case is [Banner]. */
    data object PartialBanner : AggListItem {
        override val key get() = "agg-partial-banner"
        override val contentType get() = "partial-banner"
    }

    data class Header(val section: AggregateSection) : AggListItem {
        override val key get() = "agg-h-${section.id}"
        override val contentType get() = "header"
    }

    data class RowItem(val row: AggregateRow) : AggListItem {
        // serverId is part of the key: conversation ids are server-local and
        // collide across devices (review #6).
        override val key get() = "agg-${row.serverId}-${row.conversation.id}"
        override val contentType get() = "row"
    }
}

/**
 * The Chats tab's aggregate list: a cross-device "Pinned" bucket + one section
 * per device, optionally narrowed to the active project (the filter applies in
 * the view model's section builder — switching projects never refetches). Row
 * taps bounce through MainShell's owning-device dance; pin/delete resolve the
 * OWNING device's client.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun AggregateChatsList(
    viewModel: SessionListViewModel,
    onOpenRow: (serverId: String, conversationId: Int) -> Unit,
    onHandoff: (serverId: String, folderId: Int, reference: String) -> Unit,
    onManageProjects: () -> Unit,
) {
    val ui by viewModel.aggregateUi.collectAsStateWithLifecycle()
    val sections by viewModel.aggregateSections.collectAsStateWithLifecycle()
    val projects by viewModel.sessionProjects.collectAsStateWithLifecycle()
    val members by viewModel.projectMembers.collectAsStateWithLifecycle()
    val activeProjectId by viewModel.activeProjectId.collectAsStateWithLifecycle()
    val highlight by viewModel.focusHub.highlight.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    val listState = rememberLazyListState()

    var collapsed by rememberSaveable { mutableStateOf(setOf<String>()) }
    var pendingDelete by remember { mutableStateOf<AggregateRow?>(null) }
    var addToTarget by remember { mutableStateOf<AggregateRow?>(null) }

    // Failure counters are remember keys (R3-4): a device with no visible rows
    // going offline changes neither sections nor error — the banner must still appear.
    val items = remember(sections, collapsed, ui.error, ui.failedDevices, ui.reachableDevices) {
        buildList {
            if (ui.error != null) add(AggListItem.Banner)
            if (ui.failedDevices > 0 && ui.reachableDevices > 0) add(AggListItem.PartialBanner)
            sections.forEach { section ->
                add(AggListItem.Header(section))
                if (section.id !in collapsed) {
                    section.rows.forEach { add(AggListItem.RowItem(it)) }
                }
            }
        }
    }

    // Register the visible rows for the floating-ball cycle; NavHost disposal on
    // tab switch unregisters automatically (review #7: owner-scoped, never merged).
    // Keyed on the ROW REFS + TITLES, not `items` (R1-2/R2-4): banner toggles or
    // an unrelated recomposition must not restart this effect (register() would
    // wipe a highlight), while a rename must re-register to keep the preview card
    // honest.
    val focusRowKeys = items.filterIsInstance<AggListItem.RowItem>()
        .map { "${it.row.refKey}|${it.row.conversation.trimmedTitle.orEmpty()}" }
    DisposableEffect(focusRowKeys) {
        viewModel.focusHub.register(
            CHATS_FOCUS_OWNER,
            items.filterIsInstance<AggListItem.RowItem>()
                .map { it.row }
                .map { row ->
                    FocusItem(
                        ref = SessionRef(row.serverId, row.conversation.id),
                        title = row.conversation.trimmedTitle ?: "#${row.conversation.id}",
                        serverName = row.serverName,
                    )
                },
        )
        onDispose { viewModel.focusHub.unregister(CHATS_FOCUS_OWNER) }
    }

    // Ball-driven highlight: resolve the scroll index from the CURRENT item list
    // at effect time (never store an index — review #12); skip when the row is
    // gone (e.g. filtered out) — the hub clears a vanished highlight itself.
    // Dedupe on the highlight STAMP (R2-1/R3-3): `items` is rebuilt by refetches
    // and optimistic updates, but only a NEW highlight may scroll — the same ref
    // after a wrap-around or filter change must re-scroll, which ref-dedupe ate.
    var lastScrolledStamp by remember { mutableStateOf(0L) }
    LaunchedEffect(highlight, items) {
        val hl = highlight?.takeIf { it.owner == CHATS_FOCUS_OWNER } ?: return@LaunchedEffect
        if (hl.stamp == lastScrolledStamp) return@LaunchedEffect
        val idx = items.indexOfFirst { it is AggListItem.RowItem && it.row.refKey == hl.item.ref.key }
        if (idx >= 0) {
            listState.animateScrollToItem(idx)
            lastScrolledStamp = hl.stamp
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.activity_all)) },
                actions = {
                    IconButton(onClick = onManageProjects) {
                        Icon(
                            Icons.Rounded.Bookmark,
                            contentDescription = stringResource(R.string.project_filter_manage),
                            tint = colors.textSecondary,
                        )
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = colors.textPrimary,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                ui.isLoading && !ui.hasLoaded -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingView(message = stringResource(R.string.common_loading))
                    }
                }

                !ui.hasLoaded && ui.error != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        InlineError(
                            icon = Icons.Rounded.Forum,
                            title = stringResource(R.string.sessions_load_failed),
                            message = ui.error!!,
                            onRetry = viewModel::refresh,
                            retryLabel = stringResource(R.string.common_retry),
                        )
                    }
                }

                ui.hasLoaded && sections.isEmpty() -> {
                    // Post-filter emptiness (review #10): distinguish "no sessions
                    // anywhere" from "this project has no matching sessions". An
                    // all-devices-failed fetch ALSO lands here (hasLoaded flips true
                    // with the error) — it must show the retryable error, not a
                    // lying empty state, and still pull-to-refresh (R1-3).
                    PullToRefreshBox(
                        isRefreshing = ui.isRefreshing,
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            when {
                                ui.error != null -> InlineError(
                                    icon = Icons.Rounded.Forum,
                                    title = stringResource(R.string.sessions_load_failed),
                                    message = ui.error!!,
                                    onRetry = viewModel::refresh,
                                    retryLabel = stringResource(R.string.common_retry),
                                )
                                // Some devices failed (and none "all"): the truth is a
                                // connectivity problem, not an empty project (R2-3).
                                ui.failedDevices > 0 -> Text(
                                    text = stringResource(R.string.activity_partial_failed, ui.failedDevices),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.danger,
                                )
                                ui.totalRows == 0 -> EmptyState(
                                    icon = Icons.Rounded.Forum,
                                    title = stringResource(R.string.sessions_agg_empty_title),
                                    message = stringResource(R.string.sessions_agg_empty_message),
                                )
                                activeProjectId != null -> EmptyState(
                                    icon = Icons.Rounded.Bookmark,
                                    title = stringResource(R.string.sessions_project_empty_title),
                                    message = stringResource(R.string.sessions_project_empty_message),
                                )
                                // Sections land a frame behind a fresh fetch — a brief
                                // spinner beats lying with either empty state (R1-7).
                                else -> LoadingView(message = stringResource(R.string.common_loading))
                            }
                        }
                    }
                }

                else -> {
                    PullToRefreshBox(
                        isRefreshing = ui.isRefreshing,
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 24.dp),
                        ) {
                            items(
                                items = items,
                                key = { it.key },
                                contentType = { it.contentType },
                            ) { item ->
                                when (item) {
                                    AggListItem.Banner -> RefreshErrorBanner(
                                        message = ui.error.orEmpty(),
                                        onRetry = viewModel::refresh,
                                        onDismiss = viewModel::dismissAggregateError,
                                        modifier = Modifier.animateItem().padding(horizontal = 4.dp, vertical = 6.dp),
                                    )

                                    // One offline device must not silently swallow its
                                    // sessions (R1-11) — same signal as Activity's banner.
                                    AggListItem.PartialBanner -> Text(
                                        text = stringResource(R.string.activity_partial_failed, ui.failedDevices),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colors.danger,
                                        modifier = Modifier.animateItem().padding(horizontal = 4.dp, vertical = 6.dp),
                                    )

                                    is AggListItem.Header -> AggregateHeader(
                                        section = item.section,
                                        collapsed = item.section.id in collapsed,
                                        onToggle = {
                                            collapsed = if (item.section.id in collapsed) {
                                                collapsed - item.section.id
                                            } else {
                                                collapsed + item.section.id
                                            }
                                        },
                                        modifier = Modifier.animateItem(),
                                    )

                                    is AggListItem.RowItem -> SessionRow(
                                        conversation = item.row.conversation,
                                        onClick = { onOpenRow(item.row.serverId, item.row.conversation.id) },
                                        modifier = Modifier.animateItem(),
                                        serverName = item.row.serverName,
                                        unread = item.row.unread,
                                        highlighted = highlight?.takeIf { it.owner == CHATS_FOCUS_OWNER }
                                            ?.item?.ref?.key == item.row.refKey,
                                        onTogglePin = { viewModel.setPinnedAggregate(item.row, !item.row.conversation.isPinned) },
                                        onHandoff = { ref -> onHandoff(item.row.serverId, item.row.conversation.folderId, ref) },
                                        onDelete = { pendingDelete = item.row },
                                        onAddToProject = { addToTarget = item.row },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { row ->
        SessionDeleteDialog(
            onConfirm = {
                viewModel.deleteAggregate(row)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    addToTarget?.let { row ->
        val ref = SessionRef(row.serverId, row.conversation.id)
        AddToProjectSheet(
            targetTitle = row.conversation.trimmedTitle ?: "#${row.conversation.id}",
            projects = projects,
            memberOf = members.filterValues { refs -> refs.any { it.key == ref.key } }.keys,
            memberCount = { project -> members[project.id]?.size ?: 0 },
            onToggle = { projectId, member -> viewModel.toggleProjectMembership(projectId, ref, member) },
            onCreate = viewModel::createProject,
            onManage = {
                addToTarget = null
                onManageProjects()
            },
            onDismiss = { addToTarget = null },
        )
    }
}

/** Pinned (accent, pushpin) or per-device (stable color dot + name) header. */
@Composable
private fun AggregateHeader(
    section: AggregateSection,
    collapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    when (section.kind) {
        AggregateKind.PINNED -> CollapsibleSectionHeader(
            icon = Icons.Rounded.PushPin,
            tint = colors.accent,
            label = stringResource(R.string.sessions_pinned),
            count = section.count,
            collapsed = collapsed,
            onToggle = onToggle,
            collapsible = section.count > 0,
            modifier = modifier,
        )

        AggregateKind.DEVICE -> CollapsibleSectionHeader(
            icon = Icons.Rounded.Dns,
            tint = deviceBadgeColor(section.title),
            label = section.title,
            count = section.count,
            collapsed = collapsed,
            onToggle = onToggle,
            collapsible = section.count > 0,
            modifier = modifier,
        )
    }
}
