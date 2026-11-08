package app.codeg.android.feature.usage

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.CapsuleChipRow
import app.codeg.android.core.designsystem.component.DeltaBadge
import app.codeg.android.core.designsystem.component.HeatmapMatrix
import app.codeg.android.core.designsystem.component.HairlineBar
import app.codeg.android.core.designsystem.component.InsightSectionTitle
import app.codeg.android.core.designsystem.component.LoadingView
import app.codeg.android.core.designsystem.component.MetricTile
import app.codeg.android.core.designsystem.component.MiniBarChart
import app.codeg.android.core.designsystem.component.RankedBarList
import app.codeg.android.core.designsystem.component.RankedRow
import app.codeg.android.core.designsystem.component.RingProgress
import app.codeg.android.core.designsystem.component.BarPoint
import app.codeg.android.core.designsystem.theme.CodegMono
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.TokenUsageBreakdownItem
import app.codeg.android.core.model.TokenUsageHeatCell
import app.codeg.android.core.model.TokenUsageReport
import app.codeg.android.core.model.TokenUsageTotals
import kotlin.math.roundToInt

/**
 * Token 用量（批v169 Tremor 仪表盘重构）：今日/7/30/90 天胶囊筛选 + 每设备
 * 一张仪表盘卡——Hero 总量（等宽大字+环比徽标）→ 缓存效率环 + 夜猫子徽标 →
 * 2×2 指标格（会话/轮次/活跃天数/生成时长）→ 用量趋势圆角柱状 → 7×24 编码
 * 作息热力图 → 用量分布榜（按项目/智能体/模型子胶囊）→ 消耗最高会话。
 * 数据面 = web 契约全集（series/heatmap/topConversations/streak/previousTotals，
 * 批v169 起接住），服务端零改动。宿主仍是 Settings 的 USAGE leaf（顶栏/返回
 * 由宿主 Scaffold 提供）；动态工作台「今日用量」卡经 [UsageOpenBus] 跳进来。
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

    val periodOptions = listOf(
        stringResource(R.string.usage_period_today),
        stringResource(R.string.usage_period_week),
        stringResource(R.string.usage_period_month),
        stringResource(R.string.usage_period_quarter),
    )

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
                        CapsuleChipRow(
                            options = periodOptions,
                            selectedIndex = ui.period.ordinal,
                            onSelect = { index -> viewModel.setPeriod(UsagePeriod.entries[index]) },
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
                    periodLabel = periodOptions[ui.period.ordinal],
                    breakdownMode = ui.breakdownMode,
                    onBreakdownMode = viewModel::setBreakdownMode,
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
    device: TokenUsageViewModel.DeviceUsage,
    periodLabel: String,
    breakdownMode: UsageBreakdownMode,
    onBreakdownMode: (UsageBreakdownMode) -> Unit,
    onRetry: () -> Unit,
) {
    val colors = CodegTheme.colors
    Surface(
        shape = RoundedCornerShape(CodegTheme.dimens.radiusMd),
        color = colors.bgElevated,
        border = BorderStroke(CodegTheme.dimens.hairlineWidth, colors.hairline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                    fontFamily = CodegMono,
                    color = colors.textTertiary,
                )
            }
            when {
                device.status == TokenUsageViewModel.DeviceStatus.LOADING && device.report == null ->
                    LoadingView(message = stringResource(R.string.usage_loading), modifier = Modifier.fillMaxWidth())
                !device.supported -> Text(
                    text = stringResource(R.string.usage_unsupported),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                )
                // 墨菲 R1：失败且手里没有旧数据（冷加载失败）才整卡报错；
                // 有旧 report 时降级为「陈旧数据横幅 + 原仪表盘」，不让
                // 一次弱网刷新把刚看的数据蒸发掉。
                device.isFailed && device.report == null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                            .heightIn(min = 48.dp)
                            .defaultMinSize(minWidth = 48.dp),
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
                        if (device.isFailed) {
                            StaleUsageBanner(onRetry = onRetry)
                        }
                        DashboardContent(
                            report = report,
                            breakdownMode = breakdownMode,
                            onBreakdownMode = onBreakdownMode,
                        )
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
                    }
                }
            }
        }
    }
}

/**
 * 陈旧数据横幅（墨菲 R1）：刷新失败但手里还有上一份完整 report 时，
 * 顶部一条低透明琥珀提示 + 重试，正文照常渲染——弱网下不再「数据蒸发」。
 */
@Composable
private fun StaleUsageBanner(onRetry: () -> Unit) {
    val colors = CodegTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.warning.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.usage_stale_note),
            style = MaterialTheme.typography.bodySmall,
            color = colors.warning,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.common_retry),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.warning,
            modifier = Modifier
                .clickable(role = Role.Button, onClick = onRetry)
                .heightIn(min = 48.dp)
                .defaultMinSize(minWidth = 48.dp)
                .padding(vertical = 14.dp),
        )
    }
}

/** 仪表盘正文：report 非空时的全部区块（Hero/缓存/指标格/趋势/作息/分布/Top 会话）。 */
@Composable
private fun DashboardContent(
    report: TokenUsageReport,
    breakdownMode: UsageBreakdownMode,
    onBreakdownMode: (UsageBreakdownMode) -> Unit,
) {
    val totals = report.totals
    HeroBlock(totals, report.previousTotals)
    CacheBlock(report)
    MetricGrid(report)
    TrendBlock(report)
    HeatmapBlock(report)
    BreakdownBlock(report, breakdownMode, onBreakdownMode)
    TopConversationsBlock(report.topConversations)
}

/** Hero：小标签 → 超大等宽总量 + 环比徽标 → 次要拆分行。 */
@Composable
private fun HeroBlock(totals: TokenUsageTotals, previous: TokenUsageTotals?) {
    val colors = CodegTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(R.string.usage_hero_total),
            style = MaterialTheme.typography.labelMedium,
            color = colors.textTertiary,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = formatTokenCount(totals.totalTokens),
                style = MaterialTheme.typography.displaySmall,
                fontFamily = CodegMono,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            )
            DeltaBadge(delta = deltaPercent(totals.totalTokens, previous?.totalTokens ?: 0))
        }
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

/** 缓存效率：细描边命中率环 + 「上下文大多不必重发」文案 + 夜猫子徽标。 */
@Composable
private fun CacheBlock(report: TokenUsageReport) {
    val colors = CodegTheme.colors
    val totals = report.totals
    if (totals.totalTokens <= 0) return
    val hitRate = cacheHitRate(totals)
    val fresh = (totals.totalTokens - totals.cacheReadTokens).coerceAtLeast(0)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                RingProgress(fraction = hitRate, modifier = Modifier.size(72.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = String.format(java.util.Locale.ROOT, "%.1f%%", hitRate * 100),
                        // titleSmall：1.3× 大字时 "100.0%" 仍收在 72dp 环内
                        // （titleMedium 等宽 6 字 ≈74.9dp 会压过描边，R4B-B1）。
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = CodegMono,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = stringResource(R.string.usage_cache_hit_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary,
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = stringResource(R.string.usage_cache_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary,
                )
                Text(
                    text = stringResource(
                        R.string.usage_cache_body,
                        formatTokenCount(totals.cacheReadTokens),
                        formatTokenCount(fresh),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
        val night = nightOwlFraction(report.heatmap)
        if (night >= 0.02f) {
            Row(
                modifier = Modifier
                    .background(colors.textPrimary.copy(alpha = 0.06f), RoundedCornerShape(CodegTheme.dimens.radiusSm))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(text = "🌙", style = MaterialTheme.typography.bodyMedium)
                Text(
                    // % 需转义：字符串模板里的字面百分号
                    text = stringResource(R.string.usage_night_owl, (night * 100).roundToInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

/** 2×2 指标格：会话数 / 对话轮次 / 活跃天数 / 生成时长（各带一行副注）。 */
@Composable
private fun MetricGrid(report: TokenUsageReport) {
    val totals = report.totals
    if (totals.totalTokens <= 0) return
    val sessions = totals.conversationCount
    val avgTokensPerSession = if (sessions > 0) formatTokenCount(totals.totalTokens / sessions) else null
    val avgTurnsPerSession = if (sessions > 0) String.format(java.util.Locale.ROOT, "%.1f", totals.turnCount.toFloat() / sessions) else null
    val avgPerActiveDay = if (totals.activeDays > 0) formatDuration(totals.durationMs / totals.activeDays) else null
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile(
                label = stringResource(R.string.usage_metric_sessions),
                value = sessions.toString(),
                sub = avgTokensPerSession?.let { stringResource(R.string.usage_metric_sessions_sub, it) },
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = stringResource(R.string.usage_metric_turns),
                value = formatInt(totals.turnCount),
                sub = avgTurnsPerSession?.let { stringResource(R.string.usage_metric_turns_sub, it) },
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile(
                label = stringResource(R.string.usage_metric_active_days),
                value = totals.activeDays.toString(),
                sub = report.streak.longestDays.takeIf { it > 0 }?.let { stringResource(R.string.usage_metric_streak_sub, it) },
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = stringResource(R.string.usage_metric_duration),
                value = formatDuration(totals.durationMs),
                sub = avgPerActiveDay?.let { stringResource(R.string.usage_metric_duration_sub, it) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 千分位（轮次/会话等整型计数；Locale.ROOT 保持 ASCII 分隔符）。 */
private fun formatInt(n: Int): String = String.format(java.util.Locale.ROOT, "%,d", n)

/** 用量趋势：无网格圆角柱状（缓存读取+新算两段）+ 首尾日期 + 最高一天。 */
@Composable
private fun TrendBlock(report: TokenUsageReport) {
    val colors = CodegTheme.colors
    if (report.series.isEmpty()) return
    val points = remember(report.series) {
        report.series.map { p ->
            val cached = p.cacheReadTokens.toFloat().coerceAtLeast(0f)
            val fresh = (p.totalTokens - p.cacheReadTokens).coerceAtLeast(0L).toFloat()
            BarPoint(cached = cached, fresh = fresh)
        }
    }
    val peak = remember(report.series) { report.series.maxByOrNull { it.totalTokens } }
    val firstKey = report.series.first().bucketKey
    val lastKey = report.series.last().bucketKey
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InsightSectionTitle(
            title = stringResource(R.string.usage_trend_title),
            trailing = peak?.takeIf { it.totalTokens > 0 }
                ?.let { stringResource(R.string.usage_trend_peak, formatTokenCount(it.totalTokens)) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            LegendSwatch(colors.textTertiary.copy(alpha = 0.55f))
            Text(stringResource(R.string.usage_legend_cached), style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
            Spacer(Modifier.width(6.dp))
            LegendSwatch(colors.textPrimary)
            Text(stringResource(R.string.usage_legend_fresh), style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
        }
        // stringResource 是 @Composable，必须先求值再进 semantics 普通 lambda。
        val trendDesc = stringResource(
            R.string.usage_trend_a11y,
            report.series.size,
            formatTokenCount(report.series.sumOf { it.totalTokens }),
        )
        MiniBarChart(
            points = points,
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .semantics { contentDescription = trendDesc },
        )
        Row(Modifier.fillMaxWidth()) {
            Text(bucketLabel(firstKey), style = MaterialTheme.typography.labelSmall, fontFamily = CodegMono, color = colors.textTertiary)
            Spacer(Modifier.weight(1f))
            Text(bucketLabel(lastKey), style = MaterialTheme.typography.labelSmall, fontFamily = CodegMono, color = colors.textTertiary)
        }
    }
}

@Composable
private fun LegendSwatch(color: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier
            .size(8.dp)
            .background(color, RoundedCornerShape(2.dp)),
    )
}

/** `YYYY-MM-DD` → `MM-DD`（月日语义交给上下文；紧凑等宽小字用）。 */
private fun bucketLabel(bucketKey: String): String =
    if (bucketKey.length >= 10) bucketKey.substring(5) else bucketKey

/** 7×24 编码作息热力图 + 峰值时段 + 少→多图例。 */
@Composable
private fun HeatmapBlock(report: TokenUsageReport) {
    val colors = CodegTheme.colors
    if (report.heatmap.none { it.totalTokens > 0 }) return
    val values = remember(report.heatmap) { heatValues(report.heatmap) }
    val peak = remember(report.heatmap) { peakHour(report.heatmap) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InsightSectionTitle(
            title = stringResource(R.string.usage_heatmap_title),
            trailing = peak?.let { stringResource(R.string.usage_heatmap_peak, String.format(java.util.Locale.ROOT, "%02d:00", it)) },
        )
        val heatmapDesc = peak?.let {
            stringResource(R.string.usage_heatmap_a11y, String.format(java.util.Locale.ROOT, "%02d:00", it))
        }
        HeatmapMatrix(
            values = values,
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .then(
                    if (heatmapDesc != null) {
                        Modifier.semantics { contentDescription = heatmapDesc }
                    } else {
                        Modifier
                    },
                ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.usage_heatmap_less), style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
            HeatSwatch(colors.hairline)
            listOf(0.25f, 0.5f, 0.75f).forEach { v -> HeatSwatch(colors.textPrimary.copy(alpha = 0.18f + 0.82f * v * v)) }
            HeatSwatch(colors.textPrimary)
            Text(stringResource(R.string.usage_heatmap_more), style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
        }
    }
}

@Composable
private fun HeatSwatch(color: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier
            .size(9.dp)
            .background(color, RoundedCornerShape(2.dp)),
    )
}

/** heatmap cells → 168 槽强度数组（按全局最大值归一；平方加深高值对比）。 */
private fun heatValues(cells: List<TokenUsageHeatCell>): FloatArray {
    val max = cells.maxOfOrNull { it.totalTokens } ?: 0L
    val out = FloatArray(168)
    if (max <= 0) return out
    for (cell in cells) {
        val idx = cell.weekday * 24 + cell.hour
        if (idx in out.indices && cell.totalTokens > 0) {
            out[idx] = (cell.totalTokens.toFloat() / max).coerceIn(0f, 1f)
        }
    }
    return out
}

/** 用量分布：项目/智能体/模型三个子胶囊 + 名次榜（相对榜首的细条）。 */
@Composable
private fun BreakdownBlock(
    report: TokenUsageReport,
    breakdownMode: UsageBreakdownMode,
    onBreakdownMode: (UsageBreakdownMode) -> Unit,
) {
    val colors = CodegTheme.colors
    val items = when (breakdownMode) {
        UsageBreakdownMode.FOLDER -> report.byFolder
        UsageBreakdownMode.AGENT -> report.byAgent
        UsageBreakdownMode.MODEL -> report.byModel
    }
    val rows = remember(items, breakdownMode) { rankedRows(items) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InsightSectionTitle(
            title = stringResource(R.string.usage_breakdown_title),
            trailing = null,
        )
        CapsuleChipRow(
            options = listOf(
                stringResource(R.string.usage_breakdown_by_folder),
                stringResource(R.string.usage_breakdown_by_agent),
                stringResource(R.string.usage_breakdown_by_model),
            ),
            selectedIndex = breakdownMode.ordinal,
            onSelect = { index -> onBreakdownMode(UsageBreakdownMode.entries[index]) },
        )
        if (rows.isEmpty()) {
            Text(
                text = stringResource(R.string.usage_breakdown_empty),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary,
            )
        } else {
            RankedBarList(rows = rows)
        }
    }
}

/** Top6 + 相对榜首占比细条 + 维度内百分比。 */
private fun rankedRows(items: List<TokenUsageBreakdownItem>): List<RankedRow> {
    val top = items.filter { it.totalTokens > 0 }.sortedByDescending { it.totalTokens }.take(6)
    if (top.isEmpty()) return emptyList()
    val maxTokens = top.first().totalTokens.coerceAtLeast(1L)
    val dimensionTotal = items.sumOf { it.totalTokens }.coerceAtLeast(1L)
    return top.map { item ->
        RankedRow(
            name = item.label.ifBlank { item.key },
            valueText = formatTokenCount(item.totalTokens),
            fraction = item.totalTokens.toFloat() / maxTokens,
            percentText = String.format(java.util.Locale.ROOT, "%.1f%%", item.totalTokens * 100.0 / dimensionTotal),
        )
    }
}

/** 消耗最高的会话：名次 + 标题 + agent + 总量（服务端已排序，取前 6）。 */
@Composable
private fun TopConversationsBlock(items: List<app.codeg.android.core.model.TokenUsageConversationItem>) {
    val colors = CodegTheme.colors
    val top = remember(items) { items.filter { it.totalTokens > 0 }.take(6) }
    if (top.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        InsightSectionTitle(
            title = stringResource(R.string.usage_top_conversations),
            modifier = Modifier.padding(bottom = 8.dp),
        )
        top.forEachIndexed { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = (index + 1).toString().padStart(2, '0'),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = CodegMono,
                    color = colors.textTertiary,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        text = item.title ?: "#${item.conversationId}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.agentType.isNotBlank()) {
                        Text(
                            text = item.agentType,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = formatTokenCount(item.totalTokens),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = CodegMono,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    textAlign = TextAlign.End,
                )
            }
            if (index != top.lastIndex) HorizontalDivider(color = colors.hairline, thickness = 0.75.dp)
        }
    }
}
