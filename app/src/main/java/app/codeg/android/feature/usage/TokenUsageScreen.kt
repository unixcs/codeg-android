package app.codeg.android.feature.usage

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.CodegSegmented
import app.codeg.android.core.designsystem.component.LoadingView
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.TokenUsageBreakdownItem
import app.codeg.android.core.model.TokenUsageReport

/**
 * Token 用量（舰队 M3 轻量版）：今日/7 天切换 + 每设备总量卡 + Agent/模型/
 * 项目三个 TopN。宿主是 Settings 的 USAGE leaf（顶栏/返回由 Settings 的
 * Scaffold 提供，本屏只画内容）；动态工作台的「今日用量」统计卡经
 * [UsageOpenBus] 跳进来。离线设备明示失败行；老服务端明示"暂不支持"。
 */
@Composable
fun TokenUsageScreen(viewModel: TokenUsageViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors

    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(ui.actionNote, ui.actionError) {
        if (ui.actionNote != null || ui.actionError != null) {
            kotlinx.coroutines.delay(4_000)
            viewModel.clearActionNote()
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        CodegSegmented(
                            options = listOf(
                                stringResource(R.string.usage_period_today),
                                stringResource(R.string.usage_period_week),
                            ),
                            selectedIndex = if (ui.period == UsagePeriod.TODAY) 0 else 1,
                            onSelect = { index ->
                                viewModel.setPeriod(if (index == 0) UsagePeriod.TODAY else UsagePeriod.WEEK)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        // Fixed 48dp slot so the spinner never swaps the row
                        // height / shifts the refresh button (M3 审查 M2).
                        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            if (ui.syncing) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            } else {
                                IconButton(onClick = viewModel::syncAll, enabled = ui.anySupported) {
                                    Icon(Icons.Rounded.Sync, contentDescription = stringResource(R.string.usage_sync), tint = colors.textSecondary)
                                }
                            }
                        }
                        IconButton(onClick = viewModel::refresh) {
                            Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.usage_refresh), tint = colors.textSecondary)
                        }
                    }
                    Text(
                        text = stringResource(R.string.usage_scope_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary,
                    )
                    if (ui.actionNote != null) {
                        Text(
                            text = ui.actionNote!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.success,
                        )
                    }
                    if (ui.actionError != null) {
                        Text(
                            text = ui.actionError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.danger,
                        )
                    }
                }
            }
            if (ui.devices.isEmpty() && ui.hasLoaded) {
                item {
                    Text(
                        text = stringResource(R.string.usage_no_devices),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(ui.devices, key = { it.serverId }) { device ->
                DeviceUsageCard(
                    device = device,
                    periodLabel = if (ui.period == UsagePeriod.TODAY) {
                        stringResource(R.string.usage_period_today)
                    } else {
                        stringResource(R.string.usage_period_week)
                    },
                    onRetry = { viewModel.retry(device.serverId) },
                )
            }
            item {
                Text(
                    text = stringResource(R.string.usage_q6_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun DeviceUsageCard(
    device: app.codeg.android.feature.usage.TokenUsageViewModel.DeviceUsage,
    periodLabel: String,
    onRetry: () -> Unit,
) {
    val colors = CodegTheme.colors
    Surface(
        shape = RoundedCornerShape(CodegTheme.dimens.radiusMd),
        color = colors.bgElevated,
        border = BorderStroke(1.dp, colors.hairline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = device.deviceName,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = periodLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                )
            }
            when {
                device.status == app.codeg.android.feature.usage.TokenUsageViewModel.DeviceStatus.LOADING ->
                    LoadingView(message = stringResource(R.string.usage_loading), modifier = Modifier.fillMaxWidth())
                !device.supported -> Text(
                    text = stringResource(R.string.usage_unsupported),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                )
                device.isFailed -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = device.error ?: stringResource(R.string.usage_load_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.danger,
                    )
                    Text(
                        text = stringResource(R.string.common_retry),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.accent,
                        // M6 审查：裸 clickable+4dp 触达 ~28dp，房规重试钮
                        // （DispatchForm）用 TextButton 48dp 保障——对齐。
                        modifier = Modifier
                            .clickable(role = Role.Button, onClick = onRetry)
                            .heightIn(min = 48.dp),
                    )
                }
                else -> {
                    val report = device.report
                    if (report == null) {
                        Text(
                            text = stringResource(R.string.usage_load_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.danger,
                        )
                    } else {
                        TotalsBlock(report)
                        device.syncStatus?.let { sync ->
                            val syncedLabel = stringResource(
                                R.string.usage_sync_status,
                                sync.syncedConversations,
                                sync.totalConversations,
                            )
                            Text(
                                text = syncedLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textTertiary,
                            )
                        }
                        if (report.truncated) {
                            Text(
                                text = stringResource(R.string.usage_truncated),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.warning,
                            )
                        }
                        TopNBlock(
                            title = stringResource(R.string.usage_top_agents),
                            items = report.byAgent,
                        )
                        TopNBlock(
                            title = stringResource(R.string.usage_top_models),
                            items = report.byModel,
                        )
                        TopNBlock(
                            title = stringResource(R.string.usage_top_projects),
                            items = report.byFolder,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TotalsBlock(report: TokenUsageReport) {
    val colors = CodegTheme.colors
    val totals = report.totals
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = formatTokenCount(totals.totalTokens),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
        )
        Text(
            text = stringResource(
                R.string.usage_totals_detail,
                formatTokenCount(totals.inputTokens),
                formatTokenCount(totals.outputTokens),
                formatTokenCount(totals.cacheReadTokens),
                totals.turnCount,
                totals.conversationCount,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun TopNBlock(title: String, items: List<TokenUsageBreakdownItem>) {
    val colors = CodegTheme.colors
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textTertiary,
        )
        val top = remember(items) { items.sortedByDescending { it.totalTokens }.take(5) }
        val maxTokens = remember(top) { top.firstOrNull()?.totalTokens ?: 0L }.coerceAtLeast(1L)
        top.forEach { item ->
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.label.ifBlank { item.key },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = formatTokenCount(item.totalTokens),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth((item.totalTokens.toFloat() / maxTokens).coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(colors.accent.copy(alpha = 0.55f), RoundedCornerShape(50)),
                )
            }
        }
    }
}
