package app.codeg.android.feature.activity

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.data.FocusItem
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.datastore.SessionRef
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.component.InlineError
import app.codeg.android.core.designsystem.component.LoadingView
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.feature.sessions.AddToProjectSheet
import app.codeg.android.feature.sessions.CollapsibleSectionHeader
import app.codeg.android.feature.sessions.SessionDeleteDialog
import app.codeg.android.feature.sessions.SessionRow
import java.time.Instant

/**
 * The Activity tab: a live feed split into **Running** and **Last 24 Hours** sections.
 * Mirrors the Chats list's polish — the grouping is computed off the main thread
 * ([ActivityViewModel.sections]), the sections share the prominent collapsible header,
 * and the list recycles per item type.
 *
 * The top bar reuses the same [ServerSwitcher] as the Chats tab and a quick-switch
 * chip row sits above the feed: switching here drives the app-global selected server,
 * so Home and Activity always look at the same server without a round-trip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    servers: List<ServerProfile>,
    selectedServerId: String?,
    onSelectServer: (String) -> Unit,
    onManageServers: () -> Unit,
    onOpenConversation: (Int) -> Unit,
    allMode: Boolean = false,
    onRefreshAll: () -> Unit = {},
    onOpenRow: (serverId: String, conversationId: Int) -> Unit = { _, id -> onOpenConversation(id) },
    onHandoff: (serverId: String, folderId: Int, reference: String) -> Unit = { _, _, _ -> },
    onManageProjects: () -> Unit = {},
    viewModel: ActivityViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val projects by viewModel.sessionProjects.collectAsStateWithLifecycle()
    val members by viewModel.projectMembers.collectAsStateWithLifecycle()
    val activeProjectId by viewModel.activeProjectId.collectAsStateWithLifecycle()
    val highlight by viewModel.focusHub.highlight.collectAsStateWithLifecycle()
    var collapsed by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var pendingDelete by remember { mutableStateOf<ActivityRow?>(null) }
    var addToTarget by remember { mutableStateOf<ActivityRow?>(null) }
    val colors = CodegTheme.colors
    val listState = rememberLazyListState()
    val selectedName = servers.firstOrNull { it.id == selectedServerId }?.name
        ?: stringResource(R.string.app_name)

    // Flattened lazy items — one source of truth for rendering, focus registration
    // and scroll indexing (aggregate mode only registers; single-device has no ball
    // cycling per the v132 requirement scope).
    data class ActItem(val key: String, val section: ActivitySection?, val row: ActivityRow?)

    val items = remember(sections, collapsed, allMode, ui.failedDevices, ui.reachableDevices, ui.lastRefreshed) {
        buildList {
            if (allMode && ui.failedDevices > 0 && ui.reachableDevices > 0) add(ActItem("partial-failure", null, null))
            sections.forEach { section ->
                add(ActItem("h-${section.id}", section, null))
                if (section.id !in collapsed) {
                    section.rows.forEach { row -> add(ActItem("row-${row.serverId}-${row.conversation.id}", null, row)) }
                }
            }
            ui.lastRefreshed?.let { add(ActItem("updated", null, null)) }
        }
    }

    // Register the visible rows for the ball cycle. Keyed on the ROW REFS +
    // TITLES, not `items` (R1-2/R2-4): the 25s poll rewrites lastRefreshed and
    // rebuilds `items`, but refs/titles don't change — restarting register()
    // would wipe a highlight the user is about to open; a rename re-registers
    // so the preview card stays honest.
    val focusRowKeys = remember(items) {
        items.mapNotNull { it.row }.map { "${it.serverId}:${it.conversation.id}|${it.conversation.trimmedTitle.orEmpty()}" }
    }
    DisposableEffect(allMode, focusRowKeys) {
        if (allMode) {
            viewModel.focusHub.register(
                ACTIVITY_FOCUS_OWNER,
                items.mapNotNull { it.row }.map { row ->
                    FocusItem(
                        ref = SessionRef(row.serverId, row.conversation.id),
                        title = row.conversation.trimmedTitle ?: "#${row.conversation.id}",
                        serverName = row.serverName,
                    )
                },
            )
        }
        onDispose { viewModel.focusHub.unregister(ACTIVITY_FOCUS_OWNER) }
    }

    // Dedupe on the highlight STAMP (R2-1/R3-3): the poll rebuilds `items` every
    // 25s, but only a NEW highlight may scroll — the same ref after a wrap-around
    // or filter change must re-scroll, which ref-dedupe ate.
    var lastScrolledStamp by remember { mutableStateOf(0L) }
    LaunchedEffect(highlight, items) {
        if (!allMode) return@LaunchedEffect
        val hl = highlight?.takeIf { it.owner == ACTIVITY_FOCUS_OWNER } ?: return@LaunchedEffect
        if (hl.stamp == lastScrolledStamp) return@LaunchedEffect
        val idx = items.indexOfFirst { it.row != null && it.row.refKey == hl.item.ref.key }
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
                title = { Text(if (allMode) stringResource(R.string.activity_all) else selectedName) },
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = colors.textPrimary),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                ui.loading && !ui.hasLoaded ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingView(stringResource(R.string.common_loading)) }

                // Initial fetch failed — surface it with retry instead of an ambiguous
                // empty feed (matches the Chats tab's full-screen error state).
                ui.hasLoaded.not() && ui.error != null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        InlineError(
                            icon = Icons.Rounded.GraphicEq,
                            title = stringResource(R.string.activity_load_failed),
                            message = ui.error!!,
                            onRetry = viewModel::refresh,
                            retryLabel = stringResource(R.string.common_retry),
                        )
                    }

                // Empty FEED is judged on ui.conversations — atomic with the data, so
                // a frame-stale empty `sections` can't flash the wrong empty state
                // (R1-7). The filtered-to-zero case keeps the section-layer check:
                // there sections IS the filtered truth — but when devices failed the
                // truth is connectivity, not an empty project (R2-3).
                allMode && activeProjectId != null && ui.hasLoaded && sections.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (ui.failedDevices > 0) {
                            Text(
                                text = stringResource(R.string.activity_partial_failed, ui.failedDevices),
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.danger,
                            )
                        } else {
                            EmptyState(
                                Icons.Rounded.Bookmark,
                                stringResource(R.string.sessions_project_empty_title),
                                stringResource(R.string.sessions_project_empty_message),
                            )
                        }
                    }

                ui.hasLoaded && ui.conversations.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(Icons.Rounded.Bedtime, stringResource(R.string.activity_empty_title), stringResource(R.string.activity_empty_message))
                    }

                else -> PullToRefreshBox(
                    isRefreshing = ui.refreshing,
                    onRefresh = { if (allMode) onRefreshAll() else viewModel.refresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                        items(
                            items = items,
                            key = { it.key },
                            contentType = { if (it.row != null) "row" else if (it.section != null) "header" else "banner" },
                        ) { item ->
                            when {
                                item.key == "partial-failure" -> Text(
                                    text = stringResource(R.string.activity_partial_failed, ui.failedDevices),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = CodegTheme.colors.danger,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                                )

                                item.section != null -> {
                                    val style = activitySectionStyle(item.section.kind)
                                    CollapsibleSectionHeader(
                                        icon = style.icon,
                                        tint = style.tint,
                                        label = style.label,
                                        count = item.section.count,
                                        collapsed = item.section.id in collapsed,
                                        onToggle = {
                                            collapsed = if (item.section.id in collapsed) collapsed - item.section.id else collapsed + item.section.id
                                        },
                                        modifier = Modifier.animateItem(),
                                    )
                                }

                                item.row != null -> {
                                    val row = item.row
                                    SessionRow(
                                        row.conversation,
                                        onClick = {
                                            if (allMode) onOpenRow(row.serverId, row.conversation.id)
                                            else onOpenConversation(row.conversation.id)
                                        },
                                        modifier = Modifier.animateItem(),
                                        folderName = row.folderName,
                                        serverName = if (allMode) row.serverName else null,
                                        onDelete = { pendingDelete = row },
                                        onAddToProject = { addToTarget = row },
                                        unread = row.unread,
                                        highlighted = allMode &&
                                            highlight?.takeIf { it.owner == ACTIVITY_FOCUS_OWNER }
                                                ?.item?.ref?.key == row.refKey,
                                        onHandoff = { ref -> onHandoff(row.serverId, row.conversation.folderId, ref) },
                                    )
                                }

                                else -> UpdatedFooter(ui.lastRefreshed!!)
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
                viewModel.delete(row)
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

/** Owner id the aggregate Activity list registers under ([HomeTab.ACTIVITY].route). */
private const val ACTIVITY_FOCUS_OWNER = "activity"

/** Resolved header presentation for an [ActivityKind] (icon + theme-aware tint + label). */
private data class ActivityStyle(val icon: ImageVector, val tint: Color, val label: String)

@Composable
private fun activitySectionStyle(kind: ActivityKind): ActivityStyle {
    val colors = CodegTheme.colors
    return when (kind) {
        ActivityKind.RUNNING -> ActivityStyle(
            icon = Icons.Rounded.GraphicEq,
            tint = colors.accent,
            label = stringResource(R.string.activity_running),
        )
        ActivityKind.RECENT -> ActivityStyle(
            icon = Icons.Rounded.History,
            tint = colors.textSecondary,
            label = stringResource(R.string.activity_recent),
        )
    }
}

/** "Updated 2 minutes ago" — a dim, centred footer echoing the iOS Activity feed. */
@Composable
private fun UpdatedFooter(timestamp: Instant) {
    val relative = DateUtils.getRelativeTimeSpanString(
        timestamp.toEpochMilli(),
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
    Text(
        text = stringResource(R.string.activity_updated, relative),
        style = MaterialTheme.typography.labelSmall,
        color = CodegTheme.colors.textTertiary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
    )
}
