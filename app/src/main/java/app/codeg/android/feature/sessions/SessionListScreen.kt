package app.codeg.android.feature.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.data.DeviceLoad
import app.codeg.android.core.data.FocusItem
import app.codeg.android.core.datastore.ChatListDisplayStore
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.datastore.SessionProject
import app.codeg.android.core.datastore.SessionRef
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.component.InlineError
import app.codeg.android.core.designsystem.component.LoadingView
import app.codeg.android.core.designsystem.component.deviceBadgeColor
import app.codeg.android.core.designsystem.component.sheetContentScrollLock
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
    /** 批v153（UX 审查）：页内搜索入口，MainShell push 搜索面。 */
    onOpenSearch: () -> Unit = {},
    onOpenConversation: (Int) -> Unit,
    onNewTask: () -> Unit,
    onHandoff: (folderId: Int, reference: String) -> Unit = { _, _ -> },
    /** Aggregate ("全部") mode row taps: MainShell performs the owning-device
     *  selection dance before pushing the detail (ids are server-local). */
    onOpenAggregateRow: (serverId: String, conversationId: Int) -> Unit = { _, _ -> },
    onHandoffAggregate: (serverId: String, folderId: Int, reference: String) -> Unit = { _, _, _ -> },
    onManageProjects: () -> Unit = {},
    /** 批v168: swipe bindings from Settings, already resolved to null ("off"). */
    swipeLeft: SessionSwipeAction? = null,
    swipeRight: SessionSwipeAction? = null,
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
            swipeLeft = swipeLeft,
            swipeRight = swipeRight,
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
    }.collectAsStateWithLifecycle(initialValue = ChatListDisplayDefaults)
    val scope = rememberCoroutineScope()
    // 批v162 P1: one subscription for the whole list; conversation ids are
    // server-local so the owning `selectedId` is part of every toggle.
    val boardMenu = rememberBoardTrackMenu()

    var collapsed by remember(selectedId, display) { mutableStateOf(display.collapsedSections) }
    var hiddenTarget by remember { mutableStateOf<SessionSection?>(null) }
    var showHiddenManager by remember { mutableStateOf(false) }
    // Keyed on the owning device: a floating-ball device switch while a dialog is
    // open must discard it — the held id is server-local and would hit the wrong device.
    var pendingDelete by remember(selectedId) { mutableStateOf<ConversationSummary?>(null) }
    var pendingRename by remember(selectedId) { mutableStateOf<ConversationSummary?>(null) }
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

    // Register the visible rows for the floating-ball cycle (v136). The single-device
    // branch used to register nothing, so `cycle session` pressed on the app's default
    // view had no ring to walk and silently fell back to the last aggregate list (or
    // did nothing). Collapsed sections are excluded: the ring must only walk rows the
    // user can actually see. Titles are part of the key so a rename refreshes the
    // preview card; refs alone would keep a stale title.
    val focusRows = remember(sections, collapsed, selectedId, selectedName) {
        sections.asSequence()
            .filter { it.id !in collapsed }
            .flatMap { it.rows.asSequence() }
            .mapNotNull { row ->
                val serverId = selectedId ?: return@mapNotNull null
                FocusItem(
                    ref = SessionRef(serverId, row.conversation.id),
                    title = row.conversation.trimmedTitle ?: "#${row.conversation.id}",
                    serverName = selectedName,
                )
            }
            .toList()
    }
    DisposableEffect(focusRows) {
        viewModel.focusHub.register(CHATS_FOCUS_OWNER, focusRows)
        onDispose { viewModel.focusHub.unregister(CHATS_FOCUS_OWNER) }
    }

    var showImportSheet by remember { mutableStateOf(false) }
    if (showImportSheet) {
        SessionImportSheet(
            onDismiss = { showImportSheet = false },
            // 导入改变了会话表 —— 立刻刷新列表，别等下一轮 25s 轮询。
            // 边界：sheet 内可切设备，跨设备导入只刷宿主列表——别台的变更
            // 等切设备（force 重拉）或聚合重扫时呈现，V1 不做定向刷新。
            onImported = viewModel::refresh,
        )
    }
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                // The app-global device bar (MainShell) owns server switching now —
                // the title is a plain label, and the Dns icon keeps the manage entry.
                title = { Text(selectedName) },
                actions = {
                    // 批v163（舰队 M4）：导入历史会话——扫当前设备的本机 CLI
                    // 会话文件，勾选导入（sheet 内可切设备）。
                    IconButton(onClick = { showImportSheet = true }) {
                        Icon(
                            Icons.Rounded.CloudDownload,
                            contentDescription = stringResource(R.string.session_import_title),
                            tint = colors.textSecondary,
                        )
                    }
                    // 批v153（UX 审查）：会话页补搜索入口——此前按名字找会话必须
                    // 先离开当前页去工作台的搜索卡。
                    IconButton(onClick = onOpenSearch) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = stringResource(R.string.workbench_quick_search),
                            tint = colors.textSecondary,
                        )
                    }
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

                // v137: the single-device 4s timeout sets hasLoaded=true + error so
                // the old infinite spinner becomes a retryable error page.
                ui.hasLoaded && ui.error != null && ui.isEmpty -> {
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

                // v137: a retry from the 4s-timeout page clears `error` and leaves the
                // list empty while it re-asks the device — "No sessions yet" would be a
                // lie (and the branch below is not pull-to-refreshable).
                ui.hasLoaded && ui.isEmpty && ui.isBusy -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingView(message = stringResource(R.string.common_loading))
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
                            onRename = { conv -> pendingRename = conv },
                            boardMenu = boardMenu,
                            serverId = selectedId,
                            onLongPressSection = { section -> hiddenTarget = section },
                            onOpenHiddenManager = { showHiddenManager = true },
                            // A refresh that failed over a still-populated list: surface
                            // it inline above the rows rather than swallowing it.
                            refreshError = ui.error,
                            onRetry = viewModel::refresh,
                            onDismissError = viewModel::dismissError,
                            swipeLeft = swipeLeft,
                            swipeRight = swipeRight,
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

    pendingRename?.let { conversation ->
        SessionRenameDialog(
            initialTitle = conversation.trimmedTitle.orEmpty(),
            onConfirm = { title ->
                viewModel.rename(conversation, title)
                pendingRename = null
            },
            onDismiss = { pendingRename = null },
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
    onRename: (ConversationSummary) -> Unit,
    /** 批v162 P1: task-board membership + toggle, shared with every other list. */
    boardMenu: BoardTrackMenu,
    /** Owning device of these rows; null disables the board action (no target). */
    serverId: String?,
    onLongPressSection: (SessionSection) -> Unit,
    onOpenHiddenManager: () -> Unit,
    refreshError: String?,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    swipeLeft: SessionSwipeAction?,
    swipeRight: SessionSwipeAction?,
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
                        onRename = { onRename(row.conversation) },
                        onBoard = boardMenu.isOnBoard(serverId.orEmpty(), row.conversation.id),
                        onToggleBoard = serverId?.let { sid ->
                            {
                                boardMenu.toggle(sid, row.conversation.id, row.conversation.folderId)
                            }
                        },
                        swipeLeftAction = swipeLeft,
                        swipeRightAction = swipeRight,
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
            // 克制化：置顶组图标退中性色，accent 只留给本屏「新任务」主 CTA。
            tint = colors.textSecondary,
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

/** Owner id the Chats list registers under in [SessionFocusHub] ("chats" — since 批v150 a push surface, not a tab). */
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
    swipeLeft: SessionSwipeAction?,
    swipeRight: SessionSwipeAction?,
) {
    val ui by viewModel.aggregateUi.collectAsStateWithLifecycle()
    val sections by viewModel.aggregateSections.collectAsStateWithLifecycle()
    val projects by viewModel.sessionProjects.collectAsStateWithLifecycle()
    val members by viewModel.projectMembers.collectAsStateWithLifecycle()
    val activeProjectId by viewModel.activeProjectId.collectAsStateWithLifecycle()
    // 批v154: multi-select device filter — a view filter, so toggling it never refetches.
    val hiddenDevices by viewModel.hiddenDevices.collectAsStateWithLifecycle()
    // 批v160: the project column of that filter sheet.
    val hiddenProjects by viewModel.hiddenProjects.collectAsStateWithLifecycle()
    val highlight by viewModel.focusHub.highlight.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    val listState = rememberLazyListState()
    // 批v162 P1: same ledger as the single-device list; each row carries its own
    // owning serverId, so this one needs no extra parameter.
    val boardMenu = rememberBoardTrackMenu()

    var showDeviceFilter by rememberSaveable { mutableStateOf(false) }

    var collapsed by rememberSaveable { mutableStateOf(setOf<String>()) }
    var pendingDelete by remember { mutableStateOf<AggregateRow?>(null) }
    var pendingRename by remember { mutableStateOf<AggregateRow?>(null) }
    var addToTarget by remember { mutableStateOf<AggregateRow?>(null) }

    // Failure counters are remember keys (R3-4): a device with no visible rows
    // going offline changes neither sections nor error — the banner must still appear.
    val items = remember(sections, collapsed, ui.error, ui.deviceStates) {
        buildList {
            if (ui.error != null) add(AggListItem.Banner)
            // Naming the dark devices must survive the all-failed case too: a stale
            // list can be on screen while every current fetch failed (v137).
            if (ui.failedDevices > 0 && ui.error == null) add(AggListItem.PartialBanner)
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
        .map { "${it.row.refKey}|${it.row.conversation.trimmedTitle.orEmpty()}|${it.row.serverName}" }
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
    // Seeded from the CURRENT highlight: v136's ball gestures enter the detail right
    // away, so a highlight surviving on the hub while this list was off-screen is
    // where the user just was — returning must not yank the list to it. Only a
    // highlight that arrives while the list is on screen scrolls.
    var lastScrolledStamp by remember { mutableStateOf(highlight?.stamp ?: 0L) }
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
                    // 批v154: device multi-select filter, next to the project
                    // bookmark. 批v160: the sheet gains a project column — the
                    // funnel shows whenever either column hides something (or
                    // there are ≥2 devices to filter at all). Accent tint = some
                    // device or project is unchecked; hidden with a single device
                    // (nothing to filter) UNLESS something is unchecked —
                    // otherwise the only way back would be a wipe.
                    if (hiddenDevices.isNotEmpty() || hiddenProjects.isNotEmpty() || ui.deviceStates.size > 1) {
                        IconButton(onClick = { showDeviceFilter = true }) {
                            Icon(
                                Icons.Rounded.FilterList,
                                contentDescription = stringResource(R.string.sessions_device_filter),
                                tint = if (hiddenDevices.isEmpty() && hiddenProjects.isEmpty()) colors.textSecondary else colors.accent,
                            )
                        }
                    }
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
                                // Skeleton phase (v137): at least one device is still
                                // inside its 3s window, so an empty state would be a
                                // lie — name the devices instead.
                                ui.pendingDeviceNames.isNotEmpty() -> LoadingView(
                                    message = stringResource(
                                        R.string.device_connecting,
                                        ui.pendingDeviceNames.joinToString(", "),
                                    ),
                                )
                                // Some devices failed (and none "all"): the truth is a
                                // connectivity problem, not an empty project (R2-3).
                                // v137: name them and offer a retry that only re-asks
                                // the dark devices.
                                ui.failedDevices > 0 -> InlineError(
                                    icon = Icons.Rounded.Forum,
                                    title = stringResource(R.string.sessions_load_failed),
                                    message = stringResource(
                                        R.string.activity_devices_unreachable,
                                        ui.failedDeviceNames.joinToString(", "),
                                    ),
                                    onRetry = viewModel::retryFailedDevices,
                                    retryLabel = stringResource(R.string.common_retry),
                                )
                                // Every visible device opted out of the aggregate — say
                                // so instead of claiming there are no sessions (v137).
                                ui.allSkipped -> EmptyState(
                                    icon = Icons.Rounded.Dns,
                                    title = stringResource(R.string.all_devices_skipped_title),
                                    message = stringResource(R.string.all_devices_skipped_message),
                                )
                                // 批v154: the device filter unchecked every device — say
                                // so instead of claiming there are no sessions anywhere.
                                ui.deviceStates.isNotEmpty() &&
                                    ui.deviceStates.all { it.serverId in hiddenDevices } -> EmptyState(
                                    icon = Icons.Rounded.FilterList,
                                    title = stringResource(R.string.sessions_devices_hidden_title),
                                    message = stringResource(R.string.sessions_devices_hidden_message),
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
                                    // sessions (R1-11) — same signal as Activity's banner,
                                    // now named + retryable (batch v137).
                                    AggListItem.PartialBanner -> Row(
                                        modifier = Modifier.animateItem().fillMaxWidth()
                                            .padding(horizontal = 4.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = stringResource(
                                                R.string.activity_devices_unreachable,
                                                ui.failedDeviceNames.joinToString(", "),
                                            ),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = colors.danger,
                                            modifier = Modifier.weight(1f),
                                        )
                                        TextButton(onClick = viewModel::retryFailedDevices) {
                                            Text(stringResource(R.string.common_retry), style = MaterialTheme.typography.labelMedium)
                                        }
                                    }

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
                                        onRename = { pendingRename = item.row },
                                        onBoard = boardMenu.isOnBoard(item.row.serverId, item.row.conversation.id),
                                        onToggleBoard = {
                                            boardMenu.toggle(
                                                item.row.serverId,
                                                item.row.conversation.id,
                                                item.row.conversation.folderId,
                                            )
                                        },
                                        swipeLeftAction = swipeLeft,
                                        swipeRightAction = swipeRight,
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

    pendingRename?.let { row ->
        SessionRenameDialog(
            initialTitle = row.conversation.trimmedTitle.orEmpty(),
            onConfirm = { title ->
                viewModel.renameAggregate(row, title)
                pendingRename = null
            },
            onDismiss = { pendingRename = null },
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

    if (showDeviceFilter) {
        SessionFilterSheet(
            devices = ui.deviceStates,
            projects = projects,
            projectCounts = members.mapValues { (_, refs) -> refs.size },
            hiddenDevices = hiddenDevices,
            hiddenProjects = hiddenProjects,
            onToggleDevice = viewModel::setDeviceVisible,
            onToggleProject = viewModel::setProjectVisible,
            onShowColumnDevices = viewModel::showAllDevices,
            onHideColumnDevices = { viewModel.hideAllDevices(ui.deviceStates.map { it.serverId }) },
            onShowColumnProjects = viewModel::showAllProjects,
            onHideColumnProjects = { viewModel.hideAllProjects(projects.map { it.id }) },
            onShowAll = viewModel::showAllFilters,
            onHideAll = {
                viewModel.hideAllFilters(
                    ui.deviceStates.map { it.serverId },
                    projects.map { it.id },
                )
            },
            onDismiss = { showDeviceFilter = false },
        )
    }
}

/**
 * Two-column multi-select filter for the 全部 aggregate (批v154 devices +
 * 批v160 projects). Left column = devices, right = session projects; a row is
 * hidden when its device is unchecked OR any project it belongs to is unchecked,
 * so the two dimensions stack (设备A + 项目A = that one conversation stream).
 * Every row is checked by default (both stores keep the HIDDEN ids).
 *
 * Three toggles, per the owner's spec: the sheet's global button flips EVERYTHING
 * (全部显示 ⇄ 全部隐藏, label derived from state; 全部隐藏 legitimately empties
 * the list); each column's own header flips just that column; the two interlock
 * without conflicting because they only ever write whole-column or whole-sheet
 * sets of the same hidden-id stores.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionFilterSheet(
    devices: List<DeviceLoad>,
    projects: List<SessionProject>,
    projectCounts: Map<String, Int>,
    hiddenDevices: Set<String>,
    hiddenProjects: Set<String>,
    onToggleDevice: (String, Boolean) -> Unit,
    onToggleProject: (String, Boolean) -> Unit,
    onShowColumnDevices: () -> Unit,
    onHideColumnDevices: () -> Unit,
    onShowColumnProjects: () -> Unit,
    onHideColumnProjects: () -> Unit,
    onShowAll: () -> Unit,
    onHideAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CodegTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .sheetContentScrollLock()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.sessions_device_filter),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                val anyHidden = hiddenDevices.isNotEmpty() || hiddenProjects.isNotEmpty()
                TextButton(onClick = { if (anyHidden) onShowAll() else onHideAll() }) {
                    Text(
                        stringResource(
                            if (anyHidden) R.string.sessions_device_filter_show_all else R.string.sessions_filter_hide_all,
                        ),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FilterColumn(
                    title = stringResource(R.string.sessions_filter_devices),
                    titleReveals = hiddenDevices.isNotEmpty(),
                    onTitleClick = { if (hiddenDevices.isNotEmpty()) onShowColumnDevices() else onHideColumnDevices() },
                    modifier = Modifier.weight(1f),
                ) {
                    if (devices.isEmpty()) {
                        Text(
                            stringResource(R.string.common_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textTertiary,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    devices.forEach { device ->
                        val checked = device.serverId !in hiddenDevices
                        FilterCheckRow(
                            label = device.name,
                            checked = checked,
                            onToggle = { onToggleDevice(device.serverId, !checked) },
                        )
                    }
                }
                FilterColumn(
                    title = stringResource(R.string.sessions_filter_projects),
                    titleReveals = hiddenProjects.isNotEmpty(),
                    onTitleClick = { if (hiddenProjects.isNotEmpty()) onShowColumnProjects() else onHideColumnProjects() },
                    modifier = Modifier.weight(1f),
                ) {
                    if (projects.isEmpty()) {
                        Text(
                            stringResource(R.string.projects_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textTertiary,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    projects.forEach { project ->
                        val checked = project.id !in hiddenProjects
                        FilterCheckRow(
                            label = project.name,
                            badge = projectCounts[project.id]?.takeIf { it > 0 }?.toString(),
                            icon = Icons.Rounded.Bookmark,
                            checked = checked,
                            onToggle = { onToggleProject(project.id, !checked) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** One filter column: a clickable header (flips the whole column) + its rows. */
@Composable
private fun FilterColumn(
    title: String,
    titleReveals: Boolean,
    onTitleClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CodegTheme.colors
    Column(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onTitleClick)
                .padding(vertical = 4.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (titleReveals) colors.accent else colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (titleReveals) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(16.dp),
            )
        }
        content()
    }
}

/** A single filter row: checkbox + (optional icon) + label + (optional count). */
@Composable
private fun FilterCheckRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    icon: ImageVector? = null,
    badge: String? = null,
) {
    val colors = CodegTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .heightIn(min = 44.dp)
            .padding(vertical = 2.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        icon?.let {
            Icon(
                it,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        badge?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/** Pinned (neutral pushpin) or per-device (stable color dot + name) header. */
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
            // 克制化：置顶组图标退中性色（与 sectionStyle(Pinned) 同口径）。
            tint = colors.textSecondary,
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
