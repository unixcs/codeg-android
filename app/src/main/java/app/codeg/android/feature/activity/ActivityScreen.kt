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
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.codeg.android.feature.sessions.SessionRenameDialog
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
    var pendingRename by remember { mutableStateOf<ActivityRow?>(null) }
    var addToTarget by remember { mutableStateOf<ActivityRow?>(null) }
    val colors = CodegTheme.colors
    val listState = rememberLazyListState()
    val selectedName = servers.firstOrNull { it.id == selectedServerId }?.name
        ?: stringResource(R.string.app_name)

    // Flattened lazy items — one source of truth for rendering, focus registration
    // and scroll indexing. Both modes register (v136): a single-device activity list
    // is a perfectly good ring, and leaving it out made the ball gesture dead there.
    data class ActItem(val key: String, val section: ActivitySection?, val row: ActivityRow?)

    val items = remember(sections, collapsed, allMode, ui.deviceStates, ui.error, ui.lastRefreshed, ui.conversations) {
        buildList {
            // A device going dark while we still show its last rows must stay
            // visible — the banner is the only cue those rows are stale (v137).
            if (allMode && ui.failedDevices > 0) add(ActItem("partial-failure", null, null))
            // Single-device 4s timeout with an older list on screen: keep the rows,
            // surface the failure above them instead of replacing the feed.
            if (!allMode && ui.error != null && ui.conversations.isNotEmpty()) {
                add(ActItem("refresh-error", null, null))
            }
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
        items.mapNotNull { it.row }
            .map { "${it.serverId}:${it.conversation.id}|${it.conversation.trimmedTitle.orEmpty()}|${it.serverName}" }
    }
    DisposableEffect(focusRowKeys) {
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
        onDispose { viewModel.focusHub.unregister(ACTIVITY_FOCUS_OWNER) }
    }

    // Dedupe on the highlight STAMP (R2-1/R3-3): the poll rebuilds `items` every
    // 25s, but only a NEW highlight may scroll — the same ref after a wrap-around
    // or filter change must re-scroll, which ref-dedupe ate.
    // Seeded from the CURRENT highlight (v136) — a highlight that survived while this
    // list was off-screen must not yank the list on return; only one that arrives later
    // (a ball gesture fired with this list visible) scrolls.
    var lastScrolledStamp by remember { mutableStateOf(highlight?.stamp ?: 0L) }
    LaunchedEffect(highlight, items) {
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

                // Any terminal failure with nothing to show: the initial fetch (hasLoaded
                // still false), the single-device 4s timeout, and the all-devices-failed
                // aggregate all land here. `!loading` keeps a fresh round from flashing
                // the previous error while it is being retried.
                !ui.loading && ui.error != null && ui.conversations.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        InlineError(
                            icon = Icons.Rounded.GraphicEq,
                            title = stringResource(R.string.activity_load_failed),
                            message = ui.error!!,
                            onRetry = { if (allMode) viewModel.retryFailedDevices() else viewModel.refresh() },
                            retryLabel = stringResource(R.string.common_retry),
                        )
                    }

                // Aggregate returned rows but every device that could answer is empty
                // and at least one is dark: the truth is connectivity, not "no
                // activity" (batch v137). Wait for stragglers first — another device
                // may still be inside its 3s window.
                allMode && ui.hasLoaded && ui.conversations.isEmpty() &&
                    ui.failedDevices > 0 && ui.pendingDeviceNames.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        InlineError(
                            icon = Icons.Rounded.GraphicEq,
                            title = stringResource(R.string.activity_load_failed),
                            message = stringResource(
                                R.string.activity_devices_unreachable,
                                ui.failedDeviceNames.joinToString(", "),
                            ),
                            onRetry = viewModel::retryFailedDevices,
                            retryLabel = stringResource(R.string.common_retry),
                        )
                    }

                // Skeleton phase: one device is still connecting, so an empty (or
                // project-filtered-to-empty) feed would be a lie (batch v137).
                allMode && ui.hasLoaded && sections.isEmpty() && ui.pendingDeviceNames.isNotEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingView(
                            stringResource(
                                R.string.device_connecting,
                                ui.pendingDeviceNames.joinToString(", "),
                            ),
                        )
                    }

                // Every visible device opted out of the aggregate — say so instead of
                // claiming there is no activity anywhere (batch v137).
                allMode && ui.hasLoaded && sections.isEmpty() &&
                    servers.isNotEmpty() && servers.none { it.includeInAll } ->
                    EmptyState(
                        Icons.Rounded.Dns,
                        stringResource(R.string.all_devices_skipped_title),
                        stringResource(R.string.all_devices_skipped_message),
                    )

                // A retry/refresh with nothing on screen yet: spinner, not "no activity".
                ui.refreshing && ui.conversations.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingView(stringResource(R.string.common_loading))
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
                                text = stringResource(
                                    R.string.activity_devices_unreachable,
                                    ui.failedDeviceNames.joinToString(", "),
                                ),
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
                                item.key == "partial-failure" -> Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = stringResource(
                                            R.string.activity_devices_unreachable,
                                            ui.failedDeviceNames.joinToString(", "),
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = CodegTheme.colors.danger,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = viewModel::retryFailedDevices) {
                                        Text(stringResource(R.string.common_retry), style = MaterialTheme.typography.labelMedium)
                                    }
                                }

                                item.key == "refresh-error" -> Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = ui.error.orEmpty(),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = CodegTheme.colors.danger,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = viewModel::refresh) {
                                        Text(stringResource(R.string.common_retry), style = MaterialTheme.typography.labelMedium)
                                    }
                                }

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
                                        onRename = { pendingRename = row },
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

    pendingRename?.let { row ->
        SessionRenameDialog(
            initialTitle = row.conversation.trimmedTitle.orEmpty(),
            onConfirm = { title ->
                viewModel.rename(row, title)
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
