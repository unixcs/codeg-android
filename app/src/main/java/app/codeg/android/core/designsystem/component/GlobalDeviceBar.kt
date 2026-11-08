package app.codeg.android.core.designsystem.component

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.datastore.SessionProject
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * The app-global device bar, pinned above every tab's content: one chip per saved
 * server plus an aggregate "All" chip, and a trailing manage-servers shortcut.
 * Selecting a device re-points the whole app (all tabs refetch for that server);
 * the aggregate chip is hidden outright on tabs that don't support it (MainShell
 * passes [showAll] — batch v132: Activity AND Chats, where it opens the project
 * filter sheet), so it can never promise a view the current tab can't show.
 *
 * Rendered by [MainShell] only when the bottom bar is shown (tab routes) and more
 * than one server exists — single-device setups get nothing to switch.
 */
data class DeviceChipColor(val label: String, val color: Color)

/** One stable accent per server name — the same device keeps the same dot colour. */
@Composable
fun deviceBadgeColor(name: String): Color {
    val colors = CodegTheme.colors
    val palette = remember(colors.isDark) {
        listOf(
            Color(0xFF4C8DFF), Color(0xFF34B27B), Color(0xFFE0A63A),
            Color(0xFFE0666B), Color(0xFF9B7BE8), Color(0xFF2FB5C4),
        )
    }
    return palette[kotlin.math.abs(name.hashCode()) % palette.size]
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun GlobalDeviceBar(
    servers: List<ServerProfile>,
    selectedId: String?,
    allSelected: Boolean,
    showAll: Boolean,
    /** Active project name shown inside the All chip; null collapses the slot
     *  so the chip's rhythm is unchanged when no filter is active (review #18). */
    allSubtitle: String? = null,
    onSelectServer: (String) -> Unit,
    onSelectAll: () -> Unit,
    onManageServers: () -> Unit,
    /** Long-press a device chip → "skip in All" (batch v137). The device keeps its
     *  chip and stays individually openable; it just stops joining the aggregate. */
    onSetIncludeInAll: (String, Boolean) -> Unit = { _, _ -> },
    // ---- 批v160 W9 项目快切：the row's second dimension. All defaults keep the
    // pre-W9 contract (devices only) so non-session surfaces pass nothing. ----
    /** True = the chips after 全部 are projects + 未添加 instead of devices. */
    projectMode: Boolean = false,
    projects: List<SessionProject> = emptyList(),
    /** The active REAL project id (null = 全部项目 or a device focus). */
    selectedProjectId: String? = null,
    /** True = the 未添加 bucket is the active scope. */
    unassignedSelected: Boolean = false,
    onSelectProject: (String) -> Unit = {},
    onSelectUnassigned: () -> Unit = {},
    /** Long-press 全部 → the 会话项目 sheet (select/create/manage) — the old
     *  click behavior, moved to long-press by W9. Null = the surface has no
     *  project sheet (search/tasks), the chip loses the long-press. */
    onLongPressAll: (() -> Unit)? = null,
    /** Long-press a project chip → hide it from the row and from 全部. */
    onHideProject: (String) -> Unit = {},
    /** The tiny 设备/项目 mode card anchored under 全部 (W9's click). */
    showModeCard: Boolean = false,
    onDismissModeCard: () -> Unit = {},
    /** SESSION_CHIP_DEVICES | SESSION_CHIP_PROJECTS. */
    onChipModeChange: (String) -> Unit = {},
) {
    val colors = CodegTheme.colors
    // Keep the ID, not the profile snapshot: the switch must track the live list so
    // the toggle it performs is visible immediately, and deleting/hiding the device
    // while the sheet is open simply closes it (batch v137).
    var skipTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.bg.copy(alpha = 0.96f))
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (showAll) {
                item(key = "chip-all") {
                    Box {
                        DeviceChip(
                            isSelected = allSelected,
                            leading = { iconTint -> Icon(Icons.Rounded.Dns, null, tint = iconTint, modifier = Modifier.size(14.dp)) },
                            onClick = onSelectAll,
                            // W9: 长按全部 → 会话项目 sheet（原点击行为挪长按）。
                            onLongClick = onLongPressAll,
                            longClickLabel = stringResource(R.string.project_filter_title),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = stringResource(R.string.activity_all),
                                    fontSize = 15.sp,
                                    fontWeight = if (allSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (allSelected) colors.textPrimary else colors.textSecondary,
                                    maxLines = 1,
                                )
                                // Collapsed slot when no project filter is active.
                                if (allSubtitle != null) {
                                    Text(
                                        text = "· $allSubtitle",
                                        fontSize = 12.sp,
                                        color = if (allSelected) colors.textPrimary.copy(alpha = 0.85f) else colors.textTertiary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 88.dp),
                                    )
                                }
                            }
                        }
                        // W9: the 设备/项目 mode card — small, anchored, two rows.
                        DropdownMenu(
                            expanded = showModeCard,
                            onDismissRequest = onDismissModeCard,
                        ) {
                            ModeCardOption(
                                icon = Icons.Rounded.Dns,
                                label = stringResource(R.string.sessions_filter_devices),
                                selected = !projectMode,
                            ) {
                                onDismissModeCard()
                                onChipModeChange(app.codeg.android.core.datastore.SESSION_CHIP_DEVICES)
                            }
                            ModeCardOption(
                                icon = Icons.Rounded.Folder,
                                label = stringResource(R.string.sessions_filter_projects),
                                selected = projectMode,
                            ) {
                                onDismissModeCard()
                                onChipModeChange(app.codeg.android.core.datastore.SESSION_CHIP_PROJECTS)
                            }
                        }
                    }
                }
            }
            if (projectMode) {
                // W9: 全部 + 项目一..N + 未添加. Hidden projects are already
                // filtered out by the caller (the hide lives in the aggregate
                // filter store, shared with the W2 filter sheet's project column).
                items(projects.size, key = { projects[it].id }) { i ->
                    val project = projects[i]
                    val selected = !allSelected && !unassignedSelected && project.id == selectedProjectId
                    DeviceChip(
                        isSelected = selected,
                        leading = { iconTint ->
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(deviceBadgeColor(project.name)),
                            )
                        },
                        onClick = { onSelectProject(project.id) },
                        onLongClick = { onHideProject(project.id) },
                        longClickLabel = stringResource(R.string.project_chip_hide),
                    ) {
                        Text(
                            text = project.name,
                            fontSize = 15.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) colors.textPrimary else colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                item(key = "chip-unassigned") {
                    // 未添加：sessions with no project tag — the orphan-sweep bucket.
                    DeviceChip(
                        isSelected = unassignedSelected,
                        leading = { iconTint -> Icon(Icons.Rounded.Inbox, null, tint = iconTint, modifier = Modifier.size(14.dp)) },
                        onClick = onSelectUnassigned,
                    ) {
                        Text(
                            text = stringResource(R.string.sessions_unassigned),
                            fontSize = 15.sp,
                            fontWeight = if (unassignedSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (unassignedSelected) colors.textPrimary else colors.textSecondary,
                            maxLines = 1,
                        )
                    }
                }
            } else {
                items(servers.size, key = { servers[it].id }) { i ->
                    val server = servers[i]
                    val selected = !allSelected && server.id == selectedId
                    DeviceChip(
                        isSelected = selected,
                        leading = { iconTint ->
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(deviceBadgeColor(server.name)),
                            )
                        },
                        onClick = { onSelectServer(server.id) },
                        onLongClick = { skipTargetId = server.id },
                    ) {
                        Text(
                            text = server.name,
                            fontSize = 15.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = when {
                                selected -> colors.textPrimary
                                server.includeInAll -> colors.textSecondary
                                else -> colors.textTertiary
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!server.includeInAll) {
                            Text(
                                text = "· " + stringResource(R.string.device_skipped_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textTertiary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            item(key = "chip-manage") {
                // 触达修正（第1轮可及性审查）：30dp 低于 48dp 最小触达标准——回
                // 默认尺寸，视觉仍由 16dp 的齿轮图标定调。
                IconButton(onClick = onManageServers) {
                    Icon(
                        Icons.Rounded.Settings,
                        contentDescription = stringResource(R.string.devicebar_manage),
                        tint = colors.textTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        HorizontalHairline()
    }

    // Long-press target: one switch + the manage entry (batch v137). Kept as a
    // sheet (not a dialog) so the device bar's context stays visible behind it.
    val skipTarget = servers.firstOrNull { it.id == skipTargetId }
    skipTarget?.let { target ->
        ModalBottomSheet(onDismissRequest = { skipTargetId = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(
                    text = target.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = !target.includeInAll,
                            role = Role.Switch,
                            onValueChange = { skip -> onSetIncludeInAll(target.id, !skip) },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.device_skip_in_all),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textPrimary,
                        )
                        Text(
                            text = stringResource(R.string.device_skip_in_all_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                    }
                    // The row owns the toggle action (merged semantics: "Skip in All,
                    // switch, off"), so the switch itself is display-only.
                    Switch(
                        checked = !target.includeInAll,
                        onCheckedChange = null,
                    )
                }
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = {
                        skipTargetId = null
                        onManageServers()
                    },
                ) {
                    Text(stringResource(R.string.devicebar_manage))
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceChip(
    // 参数名刻意避开 selected：否则下方 semantics lambda 的左值会解析到
    // 参数（val）本身，报 "'val' cannot be reassigned"。
    isSelected: Boolean,
    leading: @Composable (Color) -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    /** TalkBack label for the long-press action; defaults to the device skip. */
    longClickLabel: String? = null,
    content: @Composable () -> Unit,
) {
    val colors = CodegTheme.colors
    val shape = RoundedCornerShape(50)
    val resolvedLongClickLabel = longClickLabel ?: stringResource(R.string.device_skip_in_all)
    // 批v151 polish (mandate 图二): a 40dp visual chip inside a ≥48dp touch box —
    // the old ~30dp pill read as cramped little cards. Selected state is the faint
    // neutral fill + trailing check (批v170 restraint) instead of a solid accent.
    Box(
        modifier = Modifier
            // R3-A P2: no extra alpha on the skipped state — textTertiary alone
            // already reads dimmer (4.62:1 light / 5.7:1 dark); stacking alpha on
            // top of the tertiary switch sank the label to 2.47:1, under WCAG AA.
            .clip(shape)
            .combinedClickable(
                onLongClickLabel = if (onLongClick != null) resolvedLongClickLabel else null,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            // TalkBack 读出选中态（第1轮可及性审查）：combinedClickable 不带
            // selected 语义，选中/未选中在无障碍里听起来一模一样。
            .semantics { selected = isSelected }
            // Padding INSIDE the clickable: expands the touch target to 48dp without
            // fattening the visual chip.
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 40.dp)
                .clip(shape)
                // 批v170 克制化：选中=浅灰低透明底+细边框（InsightKit
                // CapsuleChipRow 同口径），实心蓝块退役；设备色点仍在
                // leading 里保留身份色。
                .background(if (isSelected) colors.selectedBg else Color.Transparent)
                .border(
                    width = if (isSelected) 0.75.dp else 1.dp,
                    color = if (isSelected) colors.selectedBorder else colors.surfaceStroke,
                    shape = shape,
                )
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            leading(if (isSelected) colors.selectedText else colors.textTertiary)
            content()
            if (isSelected) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = colors.selectedText,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun HorizontalHairline() {
    androidx.compose.material3.HorizontalDivider(
        thickness = androidx.compose.ui.unit.Dp.Hairline,
        color = CodegTheme.colors.surfaceStroke,
    )
}

/** One row of the W9 mode card: icon + label, check on the active dimension. */
@Composable
private fun ModeCardOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = CodegTheme.colors
    DropdownMenuItem(
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(icon, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) colors.textPrimary else colors.textSecondary,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        },
        trailingIcon = if (selected) {
            { Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(16.dp)) }
        } else {
            null
        },
        onClick = onClick,
    )
}
