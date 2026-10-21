package app.codeg.android.feature.activity

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Dns
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.component.InlineError
import app.codeg.android.core.designsystem.component.LoadingView
import app.codeg.android.core.designsystem.component.ServerSwitcher
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.feature.sessions.CollapsibleSectionHeader
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
    viewModel: ActivityViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    var collapsed by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val colors = CodegTheme.colors
    val selectedName = servers.firstOrNull { it.id == selectedServerId }?.name
        ?: stringResource(R.string.app_name)

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    ServerSwitcher(
                        name = selectedName,
                        servers = servers,
                        selectedId = selectedServerId,
                        onSelect = onSelectServer,
                        onManageServers = onManageServers,
                    )
                },
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

                ui.hasLoaded && ui.isEmptyFeed ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(Icons.Rounded.Bedtime, stringResource(R.string.activity_empty_title), stringResource(R.string.activity_empty_message))
                    }

                else -> PullToRefreshBox(isRefreshing = ui.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
                    val toggle: (String) -> Unit = { id ->
                        collapsed = if (id in collapsed) collapsed - id else collapsed + id
                    }
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                        if (servers.size > 1) {
                            item(key = "server-chips", contentType = "chips") {
                                ServerQuickSwitchChips(
                                    servers = servers,
                                    selectedId = selectedServerId,
                                    onSelect = onSelectServer,
                                )
                            }
                        }
                        sections.forEach { section ->
                            val isCollapsed = section.id in collapsed
                            item(key = "h-${section.id}", contentType = "header") {
                                val style = activitySectionStyle(section.kind)
                                CollapsibleSectionHeader(
                                    icon = style.icon,
                                    tint = style.tint,
                                    label = style.label,
                                    count = section.count,
                                    collapsed = isCollapsed,
                                    onToggle = { toggle(section.id) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                            if (!isCollapsed) {
                                items(section.rows, key = { "row-${it.conversation.id}" }, contentType = { "row" }) { row ->
                                    SessionRow(
                                        row.conversation,
                                        onClick = { onOpenConversation(row.conversation.id) },
                                        modifier = Modifier.animateItem(),
                                        folderName = row.folderName,
                                    )
                                }
                            }
                        }
                        ui.lastRefreshed?.let { ts ->
                            item("updated", contentType = "footer") { UpdatedFooter(ts) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One chip per saved server — the fastest switcher for the three-server routine:
 * tap and the whole feed (Running + Last 24h) refetches for that server. Single-server
 * setups don't render the row at all (nothing to switch to).
 */
@Composable
private fun ServerQuickSwitchChips(
    servers: List<ServerProfile>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    val colors = CodegTheme.colors
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
    ) {
        items(servers, key = { it.id }) { server ->
            val selected = server.id == selectedId
            val shape = RoundedCornerShape(50)
            Row(
                modifier = Modifier
                    .clip(shape)
                    .background(if (selected) colors.accent.copy(alpha = 0.18f) else Color.Transparent)
                    .border(
                        width = 1.dp,
                        color = if (selected) colors.accent.copy(alpha = 0.5f) else colors.surfaceStroke,
                        shape = shape,
                    )
                    .clickable { if (!selected) onSelect(server.id) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Rounded.Dns,
                    contentDescription = null,
                    tint = if (selected) colors.accent else colors.textTertiary,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) colors.accent else colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

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
