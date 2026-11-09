package app.codeg.android.feature.activity

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Assignment
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.data.InteractionKind
import app.codeg.android.core.data.PendingInteraction
import app.codeg.android.core.data.TaskSegment
import app.codeg.android.core.data.TaskStatusTone
import app.codeg.android.core.data.taskStatusUi
import app.codeg.android.core.designsystem.theme.CodegMono
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * 批v150 工作台头三块（统计行 / 快捷入口行 / 需要处理预览）——全部真实数据：
 * 数字来自 [WorkbenchViewModel] 的任务板同源投影，快捷入口只做导航，预览行
 * 跳任务板。放在 feed 上方的固定区，feed 的滚动/高亮机制（v132-v137）零改动。
 * 批v151：总览（统计+快捷）与需要处理两个区可独立折叠，状态由
 * [WorkbenchViewModel.display] 持久化。
 */

/**
 * 总览折叠轨：展开时是右端「收起」的细条（不再占正文空间之外的高度），
 * 折叠时显示一行摘要 + 展开箭头。整条可点（Role.Button + onClickLabel），
 * 两个方向的触达目标都是全宽——与 v150 面板 2×2 卡的触达口径一致。
 */
@Composable
private fun WorkbenchOverviewRail(
    stats: WorkbenchStats,
    collapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    val summary = if (stats.hasLoaded) {
        stringResource(
            R.string.workbench_overview_summary,
            stats.runningTasks,
            stats.attentionTotal,
            "${stats.onlineDevices}/${stats.totalDevices}",
        )
    } else {
        "…"
    }
    // 批v153（第3轮可及性审查）：与「需要处理」卡同款状态朗读——读屏要能听出
    // 当前是收起还是展开（复用同义文案，zh/en 均为「已收起/已展开」口径）。
    val stateLabel = stringResource(
        if (collapsed) R.string.workbench_attention_collapsed else R.string.workbench_attention_expanded,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                onClickLabel = stringResource(
                    if (collapsed) R.string.workbench_overview_expand else R.string.workbench_overview_collapse
                ),
                role = Role.Button,
                onClick = onToggle,
            )
            // 触达修正（第1轮可及性审查）：细条全宽但仅 ~28dp 高——clickable 之后
            // 撑到 48dp（padding 在内，触达区含 padding），不改变视觉密度。
            .heightIn(min = 48.dp)
            .semantics { stateDescription = stateLabel }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        if (collapsed) {
            Text(
                text = summary,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.workbench_overview_collapse),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
            )
            Icon(
                Icons.Rounded.ExpandLess,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Collapsible content slide: opens downward from the rail, closes upward. */
private fun headerExpand() = expandVertically(expandFrom = Alignment.Top, animationSpec = tween(220)) + fadeIn(tween(220))

private fun headerCollapse() = shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = tween(180)) + fadeOut(tween(150))

/** One stat card: value + label. Value "…" until the first round lands.
 *  批v152：卡是入口，不只是展示——运行中/需要处理跳任务板对应段，在线设备跳
 *  设备管理；onClickLabel 让读屏用户知道点了会去哪。 */
@Composable
private fun StatCard(
    label: String,
    value: String,
    onClickLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.bgElevated)
            .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            fontFamily = CodegMono,
            color = colors.textPrimary,
            maxLines = 1,
        )
        LabelSystemScale {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 批v172 D2: workbench card labels ignore the in-app font multiplier (global /
 * per-interface) — they hold a fixed geometry, and at 115-130% the four-across
 * rows truncate to 1-2 glyphs. The SYSTEM font scale still passes through so
 * OS-level accessibility sizing keeps working.
 */
@Composable
private fun LabelSystemScale(content: @Composable () -> Unit) {
    val d = LocalDensity.current
    val systemScale = LocalConfiguration.current.fontScale
    CompositionLocalProvider(
        LocalDensity provides Density(d.density, systemScale),
        content = content,
    )
}

/** 统计行：运行中 / 需要处理 / 在线设备（真实计数，首round未落地前显示 …）。
 *  批v152：三张卡都可点，跳转目标见 [StatCard]。 */
@Composable
internal fun WorkbenchStatsRow(
    stats: WorkbenchStats,
    onOpenRunning: () -> Unit,
    onOpenAttention: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenUsage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val value = { n: Int -> if (stats.hasLoaded) n.toString() else "…" }
    Row(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCard(
            stringResource(R.string.workbench_stat_running),
            value(stats.runningTasks),
            stringResource(R.string.workbench_stat_running_action),
            onOpenRunning,
            Modifier.weight(1f),
        )
        StatCard(
            stringResource(R.string.workbench_stat_attention),
            // 批v160 W7: blocked tasks + agents waiting on a confirmation.
            value(stats.attentionTotal),
            stringResource(R.string.workbench_stat_attention_action),
            onOpenAttention,
            Modifier.weight(1f),
        )
        StatCard(
            stringResource(R.string.workbench_stat_online),
            if (stats.hasLoaded) "${stats.onlineDevices}/${stats.totalDevices}" else "…",
            stringResource(R.string.workbench_stat_online_action),
            onOpenDevices,
            Modifier.weight(1f),
        )
        // 批v163（M3）：今日 token 用量——全设备已知才出数字，未知降级"…"；
        // 点卡进设置页的用量 leaf（UsageOpenBus 一次性跳转）。
        StatCard(
            stringResource(R.string.workbench_stat_usage),
            if (stats.usageTodayKnown) app.codeg.android.feature.usage.formatTokenCount(stats.usageTodayTokens) else "…",
            stringResource(R.string.workbench_stat_usage_action),
            onOpenUsage,
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun QuickCard(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.bgElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
    ) {
        // 克制化：快捷入口图标退到中性色，本屏唯一 accent 实心留给「新建会话」主卡。
        Icon(icon, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(22.dp))
        LabelSystemScale {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 批v172 补充1: the quick-row catalog — id + label res, fixed display order.
 * Shared with 设置→工作台's per-card toggles so the two can never drift.
 */
internal val WORKBENCH_QUICK_CARDS: List<Pair<String, Int>> = listOf(
    "quickTask" to R.string.workbench_quick_task,
    "folders" to R.string.workbench_quick_folders,
    "search" to R.string.workbench_quick_search,
    "quickSave" to R.string.workbench_quick_save,
    "newSession" to R.string.workbench_new_session,
)

/** 快捷入口行（批v153：会话卡与底栏重复，工作台补「创建任务」直达；批v172
 *  补充1：每张卡可在设置里单独隐藏，[visibleCards] 只认上面的 catalog id）。 */
@Composable
internal fun WorkbenchQuickRow(
    visibleCards: List<String>,
    onQuickAction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if ("quickTask" in visibleCards) QuickCard(Icons.Rounded.Assignment, stringResource(R.string.workbench_quick_task), { onQuickAction("quickTask") }, Modifier.weight(1f))
        if ("folders" in visibleCards) QuickCard(Icons.Rounded.FolderOpen, stringResource(R.string.workbench_quick_folders), { onQuickAction("folders") }, Modifier.weight(1f))
        if ("search" in visibleCards) QuickCard(Icons.Rounded.Search, stringResource(R.string.workbench_quick_search), { onQuickAction("search") }, Modifier.weight(1f))
        if ("quickSave" in visibleCards) QuickCard(Icons.Rounded.Save, stringResource(R.string.workbench_quick_save), { onQuickAction("quickSave") }, Modifier.weight(1f))
        if ("newSession" in visibleCards) {
            // Primary action: same frosted-chip language as the ball size chips.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .weight(1.4f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.accent)
                    .clickable { onQuickAction("newSession") }
                    .padding(horizontal = 8.dp, vertical = 14.dp),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(20.dp))
                LabelSystemScale {
                    Text(
                        text = stringResource(R.string.workbench_new_session),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onAccent,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        // 批v153（第3轮可及性审查）：同屏 QuickCard 全带省略，主按钮漏网
                        // ——EN/fontScale 2.0 下会在字符中间硬切。
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 列表状态点（克制化）：6dp 语义色小圆点 + 中性文字。语义色
 * （warning/danger/accentDim）只允许出现在圆点上，不再用彩底胶囊。
 */
@Composable
private fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Spacer(modifier.size(6.dp).background(color, CircleShape))
}

/** One 需要处理 preview row: status tag + title + device. Tap → task board. */
@Composable
private fun AttentionRow(entry: app.codeg.android.core.data.TaskBoardEntry, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    val status = taskStatusUi(entry.task)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            // 触达修正（第1轮可及性审查）：预览行 ~34dp → 48dp 触达。
            .heightIn(min = 48.dp)
            .padding(horizontal = 6.dp, vertical = 7.dp),
    ) {
        StatusDot(
            when (status.tone) {
                TaskStatusTone.WARN, TaskStatusTone.DANGER -> colors.warning
                TaskStatusTone.RUN, TaskStatusTone.INFO -> colors.accentDim
                else -> colors.textTertiary
            },
        )
        Text(
            text = if (status.labelRes != 0) stringResource(status.labelRes) else status.raw.orEmpty(),
            fontSize = 11.sp,
            color = colors.textSecondary,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = entry.task.title,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = entry.serverName,
            style = MaterialTheme.typography.labelSmall,
            color = colors.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Chip label per interaction kind (批v160 W7). */
private fun InteractionKind.labelRes(): Int = when (this) {
    InteractionKind.PERMISSION -> R.string.workbench_kind_permission
    InteractionKind.QUESTION -> R.string.workbench_kind_question
    InteractionKind.PLAN -> R.string.workbench_kind_plan
}

/**
 * 批v160 W7: one 「等待你确认」 row — amber dot marks the attention card's task
 * rows' loudest signal (克制化：琥珀只落在 6dp 圆点上，不再整行彩底).
 * Dot + kind label + title on the first line, device + how long it has been
 * waiting on the second. Tap → the conversation itself ([onOpenInteraction]
 * hands a [PendingInteraction] to the shell, which handoffs to the right device
 * and opens the session).
 */
@Composable
private fun InteractiveRow(item: PendingInteraction, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.workbench_interactive_open),
                onClick = onClick,
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = 6.dp, vertical = 7.dp),
    ) {
        StatusDot(colors.warning)
        Text(
            text = stringResource(item.kind.labelRes()),
            fontSize = 11.sp,
            color = colors.textSecondary,
            fontWeight = FontWeight.Medium,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = item.title.ifBlank { stringResource(item.kind.labelRes()) },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = item.serverName,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = DateUtils.getRelativeTimeSpanString(item.since).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 需要处理卡：≤3 条真实任务预览。空态整块不渲染。
 * 批v151：可折叠；批v152：整行任意位置点击折叠/展开（「去任务板」按钮已删，
 * 预览行/「还有 N 条」仍跳任务板「等你处理」段）。
 * 批v160 W7：顶部「等待你确认」节（amber 行）置顶于任务行之上——智能体卡在
 * 等用户确认（权限/提问/计划）的会话，点行直达会话本身；隐藏条件加上
 * interactiveSessions 为空。
 */
@Composable
internal fun WorkbenchAttentionCard(
    stats: WorkbenchStats,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenInteraction: (PendingInteraction) -> Unit,
    onOpenAutomations: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    if (stats.attentionTasks == 0 && stats.interactiveSessions.isEmpty() &&
        (!stats.automationFailuresKnown || stats.automationFailures == 0)
    ) {
        return
    }
    val stateText = stringResource(
        if (collapsed) R.string.workbench_attention_collapsed else R.string.workbench_attention_expanded
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.bgElevated)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(
                    // 需要处理卡自己的标签（审查修正）：复用总览的 expand/collapse
                    // 文案会让读屏用户以为在操作总览区。
                    onClickLabel = stringResource(
                        if (collapsed) R.string.workbench_attention_expand else R.string.workbench_attention_collapse
                    ),
                    role = Role.Button,
                    onClick = onToggleCollapse,
                )
                // 触达修正（第1轮可及性审查）：标题行 ~26dp → 48dp 触达。
                .heightIn(min = 48.dp)
                .semantics { stateDescription = stateText }
                .padding(horizontal = 4.dp, vertical = 3.dp),
        ) {
            Text(
                text = stringResource(R.string.workbench_stat_attention),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
            // 批v152: chevron 推到最右，整行可点的视觉暗示。
            Spacer(Modifier.weight(1f))
            Icon(
                if (collapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(16.dp),
            )
        }
        AnimatedVisibility(visible = !collapsed, enter = headerExpand(), exit = headerCollapse()) {
            Column {
                Spacer(Modifier.size(2.dp))
                // 批v160 W7: the waiting-for-you section sits ABOVE the task rows —
                // an agent blocked mid-turn outranks a parked task.
                if (stats.interactiveSessions.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.workbench_interactive_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.warning,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                    stats.interactiveSessions.take(WorkbenchViewModel.INTERACTIVE_MAX)
                        .forEach { item -> InteractiveRow(item) { onOpenInteraction(item) } }
                }
                // 批v163（舰队 M2，Q3 拍板）：自动化失败进琥珀区——unseen_failures
                // 驱动，进运行历史 mark_seen 清零；整行跳任务板自动化段。
                if (stats.automationFailuresKnown && stats.automationFailures > 0) {
                    Text(
                        text = stringResource(R.string.workbench_automation_title, stats.automationFailures),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.danger,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                    stats.automationFailureDevices.forEach { device ->
                        AutomationFailureRow(device) { onOpenAutomations() }
                    }
                }
                stats.attentionPreview.forEach { entry -> AttentionRow(entry, onOpenTasks) }
                if (stats.attentionTasks > stats.attentionPreview.size) {
                    Text(
                        text = stringResource(R.string.workbench_attention_more, stats.attentionTasks - stats.attentionPreview.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary,
                        // 触达修正（第1轮可及性审查）：链接行 ~18dp → 48dp 触达
                        //（clickable 在前、padding 在内）。批v152 审查修正：补
                        // role/onClickLabel，读屏用户才能听出这是一条跳转动作。
                        modifier = Modifier
                            .clickable(
                                role = Role.Button,
                                onClickLabel = stringResource(R.string.workbench_stat_attention_action),
                                onClick = onOpenTasks,
                            )
                            .padding(horizontal = 6.dp, vertical = 16.dp),
                    )
                }
            }
        }
    }
}

/** 批v163（Q3）：一台设备的自动化失败行——琥珀区同款 48dp 触达，直达自动化段。 */
@Composable
private fun AutomationFailureRow(
    device: AutomationFailureDevice,
    onClick: () -> Unit,
) {
    val colors = CodegTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.workbench_automation_open),
                onClick = onClick,
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = 6.dp, vertical = 7.dp),
    ) {
        StatusDot(colors.danger)
        Text(
            text = stringResource(R.string.workbench_automation_chip, device.failures),
            fontSize = 11.sp,
            color = colors.textSecondary,
        )
        Text(
            text = device.deviceName,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The whole fixed header: collapse rail → stats → quick entry → attention preview. */
@Composable
internal fun WorkbenchHeader(
    stats: WorkbenchStats,
    overviewCollapsed: Boolean,
    attentionCollapsed: Boolean,
    onToggleOverview: () -> Unit,
    onToggleAttention: () -> Unit,
    quickCards: List<String>,
    onQuickAction: (String) -> Unit,
    onOpenTasks: (TaskSegment) -> Unit,
    onOpenInteraction: (PendingInteraction) -> Unit,
    onOpenAutomations: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenDevices: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 间距不靠 spacedBy 撑（审查修正：折叠后 AnimatedVisibility 仍是零高子项，
    // 两侧 spacing 会让 轨→卡 间距翻倍）：展开态间距放动画内容里，注意力卡的
    // 10dp 顶距恒定，两种折叠状态都是单倍间距。
    Column(modifier = modifier.fillMaxWidth()) {
        WorkbenchOverviewRail(stats, overviewCollapsed, onToggleOverview, modifier = Modifier.padding(top = 6.dp))
        AnimatedVisibility(visible = !overviewCollapsed, enter = headerExpand(), exit = headerCollapse()) {
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WorkbenchStatsRow(
                    stats,
                    onOpenRunning = { onOpenTasks(TaskSegment.RUNNING) },
                    onOpenAttention = { onOpenTasks(TaskSegment.ATTENTION) },
                    onOpenDevices = onOpenDevices,
                    onOpenUsage = onOpenUsage,
                )
                // 全隐藏时不渲染行：零高 Row 在 spacedBy(10) 里仍会撑出一段
                // 空隙（本文件 :637 注释的同款坑）。
                if (quickCards.isNotEmpty()) {
                    WorkbenchQuickRow(quickCards, onQuickAction)
                }
            }
        }
        WorkbenchAttentionCard(
            stats,
            attentionCollapsed,
            onToggleAttention,
            onOpenTasks = { onOpenTasks(TaskSegment.ATTENTION) },
            onOpenInteraction = onOpenInteraction,
            onOpenAutomations = onOpenAutomations,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}
