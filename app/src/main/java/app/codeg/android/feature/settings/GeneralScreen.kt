package app.codeg.android.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropUp
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.SwipeLeft
import androidx.compose.material.icons.rounded.SwipeRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.OptionListSheet
import app.codeg.android.core.designsystem.component.PickerField
import app.codeg.android.core.designsystem.component.PickerOption
import app.codeg.android.core.designsystem.theme.AppFont
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.designsystem.theme.LightSurface
import app.codeg.android.core.model.AgentType
import app.codeg.android.feature.activity.WORKBENCH_QUICK_CARDS
import app.codeg.android.feature.floatingball.FONT_STEPS
import app.codeg.android.feature.floatingball.stepFontScale
import app.codeg.android.feature.main.HomeTab
import app.codeg.android.feature.main.resolveStartTab
import app.codeg.android.feature.main.visibleTabs
import app.codeg.android.feature.inbox.InboxSwipeAction
import app.codeg.android.feature.inbox.inboxSwipeActionLabelRes
import app.codeg.android.feature.sessions.SessionSwipeAction
import app.codeg.android.feature.sessions.swipeActionLabelRes
import kotlin.math.roundToInt

@Composable
fun GeneralContent(
    viewModel: GeneralViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val appSettings by viewModel.settings.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Section(stringResource(R.string.general_shell_section)) {
            // 批v152: the start marker must follow the EFFECTIVE start tab — a
            // persisted value that is currently hidden falls back to the first
            // visible tab, so showing the raw saved route would mark an
            // unreachable row.
            val effectiveStart = remember(appSettings.startTab, appSettings.homeTabOrder, appSettings.hiddenHomeTabs) {
                resolveStartTab(
                    appSettings.startTab,
                    visibleTabs(appSettings.homeTabOrder, appSettings.hiddenHomeTabs),
                ).route
            }
            TabOrderEditor(
                savedOrder = appSettings.homeTabOrder,
                startTab = effectiveStart,
                hidden = appSettings.hiddenHomeTabs,
                onReorder = viewModel::setHomeTabOrder,
                onStartTab = viewModel::setStartTab,
            )
            TabVisibilityEditor(
                hidden = appSettings.hiddenHomeTabs,
                onHiddenChange = viewModel::setTabHidden,
            )
            // 批v161: task-board shape (kanban vs list) and its per-layout column
            // count. Only the count belonging to the active layout is offered —
            // two count rows would let the user tune a setting that does nothing.
            ChipRow(
                title = stringResource(R.string.general_task_layout_title),
                subtitle = stringResource(R.string.general_task_layout_subtitle),
                options = listOf(
                    app.codeg.android.core.datastore.TASK_BOARD_KANBAN to R.string.general_task_layout_kanban,
                    app.codeg.android.core.datastore.TASK_BOARD_LIST to R.string.general_task_layout_list,
                ),
                selectedId = appSettings.taskBoardLayout,
                onSelect = viewModel::setTaskBoardLayout,
            )
            if (appSettings.taskBoardLayout == app.codeg.android.core.datastore.TASK_BOARD_LIST) {
                ChipRow(
                    title = stringResource(R.string.general_task_list_columns_title),
                    subtitle = stringResource(R.string.general_task_list_columns_subtitle),
                    options = listOf(1 to R.string.general_columns_one, 2 to R.string.general_columns_two),
                    selectedId = appSettings.taskBoardListColumns,
                    onSelect = viewModel::setTaskBoardListColumns,
                )
            } else {
                ChipRow(
                    title = stringResource(R.string.general_task_kanban_columns_title),
                    subtitle = stringResource(R.string.general_task_kanban_columns_subtitle),
                    options = listOf(1 to R.string.general_columns_one, 2 to R.string.general_columns_two),
                    selectedId = appSettings.taskBoardKanbanColumns,
                    onSelect = viewModel::setTaskBoardKanbanColumns,
                )
            }
            // 批v151: inbox "new idea" button placement (corner FAB vs bottom-center
            // half-width pill) — same chip idiom as the ball-size row above.
            ListItem(
                headlineContent = { Text(stringResource(R.string.inbox_idea_style_title)) },
                supportingContent = { Text(stringResource(R.string.inbox_idea_style_subtitle)) },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "corner" to R.string.inbox_idea_style_corner,
                            "centerWide" to R.string.inbox_idea_style_center,
                        ).forEach { (id, labelRes) ->
                            // 局部变量刻意叫 isSelected：若叫 selected，下方
                            // semantics lambda 的左值会解析到局部变量报编译错。
                            val isSelected = appSettings.inboxNewIdeaStyle == id
                            Text(
                                stringResource(labelRes),
                                fontSize = 13.sp,
                                color = if (isSelected) colors.selectedText else colors.textSecondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) colors.selectedBg else colors.textPrimary.copy(alpha = 0.08f),
                                    )
                                    .clickable { viewModel.setInboxNewIdeaStyle(id) }
                                    // 触达对齐第1轮标准（第2轮审查修正）：同批新 chip
                                    // 也撑 48dp，padding 留在触达区内。TalkBack 读出
                                    // 当前选中样式（第3轮扫尾修正）。
                                    .semantics { selected = isSelected }
                                    .heightIn(min = 48.dp)
                                    .wrapContentHeight(Alignment.CenterVertically)
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    headlineColor = colors.textPrimary,
                    supportingColor = colors.textTertiary,
                ),
            )
        }
        // 批v160 W9: the session chip row's project dimension — sort by member
        // count (descending), or drag-free manual order via ↑/↓. The manual
        // editor lists every project including hidden ones: it edits the row's
        // display order, not chip visibility (that lives on the bar's long-press
        // and the 全部 filter sheet).
        Section(stringResource(R.string.general_project_section)) {
            ToggleRow(
                stringResource(R.string.general_project_sort_title),
                stringResource(R.string.general_project_sort_subtitle),
                appSettings.projectChipSortByCount,
                viewModel::setProjectChipSortByCount,
            )
            if (!appSettings.projectChipSortByCount) {
                // Display order = the row's effective order (saved ids first,
                // then the never-moved rest) — the same order the chips render.
                val repoProjects by viewModel.sessionProjectRepo.projects.collectAsStateWithLifecycle()
                val projects = app.codeg.android.core.data.orderedProjectChips(
                    repoProjects, appSettings.projectChipOrder, sortByCount = false, counts = emptyMap(),
                )
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp)) {
                    Text(
                        stringResource(R.string.general_project_order_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.textSecondary,
                    )
                    Text(
                        stringResource(R.string.general_project_order_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textTertiary,
                    )
                }
                if (projects.isEmpty()) {
                    Text(
                        stringResource(R.string.general_project_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                    )
                } else {
                    projects.forEachIndexed { index, project ->
                        ListItem(
                            headlineContent = { Text(project.name) },
                            trailingContent = {
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    FilledTonalIconButton(
                                        onClick = { viewModel.moveProjectChip(project.id, -1) },
                                        enabled = index > 0,
                                        modifier = Modifier.size(48.dp),
                                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                                            containerColor = colors.textPrimary.copy(alpha = 0.08f),
                                            contentColor = colors.textPrimary,
                                        ),
                                    ) {
                                        Icon(Icons.Rounded.ArrowDropUp, contentDescription = stringResource(R.string.common_move_up))
                                    }
                                    FilledTonalIconButton(
                                        onClick = { viewModel.moveProjectChip(project.id, 1) },
                                        enabled = index < projects.lastIndex,
                                        modifier = Modifier.size(48.dp),
                                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                                            containerColor = colors.textPrimary.copy(alpha = 0.08f),
                                            contentColor = colors.textPrimary,
                                        ),
                                    ) {
                                        Icon(Icons.Rounded.ArrowDropDown, contentDescription = stringResource(R.string.common_move_down))
                                    }
                                }
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent,
                                headlineColor = colors.textPrimary,
                                supportingColor = colors.textTertiary,
                            ),
                        )
                    }
                }
            }
        }
        // 批v152: cold-start collapse state for the two Activity workbench blocks.
        // The user asked for "the sessions below to be visible most of the time",
        // so both defaults can be turned off here; an in-session tap still wins.
        Section(stringResource(R.string.general_workbench_section)) {
            ToggleRow(
                title = stringResource(R.string.general_workbench_overview_default_title),
                subtitle = stringResource(R.string.general_workbench_overview_default_subtitle),
                checked = appSettings.workbenchOverviewExpandedDefault,
                onChange = viewModel::setWorkbenchOverviewDefaultExpanded,
            )
            ToggleRow(
                title = stringResource(R.string.general_workbench_attention_default_title),
                subtitle = stringResource(R.string.general_workbench_attention_default_subtitle),
                checked = appSettings.workbenchAttentionExpandedDefault,
                onChange = viewModel::setWorkbenchAttentionDefaultExpanded,
            )
            // 批v172 补充1: 快捷卡管理——5 张卡（含新「快速保存」）逐卡开关，
            // 与 WorkbenchWidgets.WORKBENCH_QUICK_CARDS 同一 catalog，不会漂移。
            WORKBENCH_QUICK_CARDS.forEach { (cardId, labelRes) ->
                ToggleRow(
                    title = stringResource(labelRes),
                    subtitle = stringResource(R.string.workbench_card_toggle_hint),
                    checked = cardId in appSettings.workbenchQuickCards,
                    onChange = { on ->
                        val current = appSettings.workbenchQuickCards
                        viewModel.setWorkbenchQuickCards(
                            if (on) (current + cardId).distinct() else current - cardId,
                        )
                    },
                )
            }
        }
        // 批v160 W8: 会话交互提醒——智能体等确认（权限/提问/计划）时发系统通知；
        // 可选的前台服务把监听带进后台；电池白名单引导给国产 ROM 兜底。
        Section(stringResource(R.string.general_conversation_tools)) {
            ToggleRow(stringResource(R.string.general_live_feedback_title), stringResource(R.string.general_live_feedback_subtitle), ui.feedbackEnabled, viewModel::setFeedback)
            ToggleRow(stringResource(R.string.general_ask_questions_title), stringResource(R.string.general_ask_questions_subtitle), ui.questionEnabled, viewModel::setQuestion)
        }
        Section(stringResource(R.string.general_multi_agent_delegation)) {
            ToggleRow(stringResource(R.string.general_enable_delegation_title), stringResource(R.string.general_enable_delegation_subtitle), ui.delegationEnabled, viewModel::setDelegationEnabled)
            if (ui.delegationEnabled) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.general_max_depth_title)) },
                    supportingContent = { Text(stringResource(R.string.general_max_depth_subtitle)) },
                    trailingContent = {
                        Stepper(ui.depthLimit, onDec = { viewModel.setDepth(ui.depthLimit - 1) }, onInc = { viewModel.setDepth(ui.depthLimit + 1) })
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                        headlineColor = colors.textPrimary,
                        supportingColor = colors.textTertiary,
                    ),
                )
            }
        }
    }
}

/** 批v168: display name for a stored swipe-binding id ("none" included). */
@Composable
private fun swipeActionLabel(actionId: String): String =
    // 批v171: label 表下沉到 sessions 域共享（悬浮球 cycle toast 同口径）。
    stringResource(swipeActionLabelRes(actionId) ?: R.string.swipe_action_none)

@Composable
private fun PickerRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, fontSize = 15.sp, color = colors.textPrimary, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val colors = CodegTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = colors.textTertiary, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.bgElevated.copy(alpha = 0.5f))) { content() }
    }
}

@Composable
private fun <T> ChipRow(
    title: String,
    subtitle: String,
    options: List<Pair<T, Int>>,
    selectedId: T,
    onSelect: (T) -> Unit,
) {
    val colors = CodegTheme.colors
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { (id, labelRes) ->
                    // 局部变量刻意叫 isSelected：若叫 selected，下方 semantics
                    // lambda 的左值会解析到局部变量报编译错（v151 教训）。
                    val isSelected = selectedId == id
                    Text(
                        stringResource(labelRes),
                        fontSize = 13.sp,
                        color = if (isSelected) colors.selectedText else colors.textSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) colors.selectedBg else colors.textPrimary.copy(alpha = 0.08f),
                            )
                            .clickable { onSelect(id) }
                            .semantics { selected = isSelected }
                            .heightIn(min = 48.dp)
                            .wrapContentHeight(Alignment.CenterVertically)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = colors.textPrimary,
            supportingColor = colors.textTertiary,
        ),
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val colors = CodegTheme.colors
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = if (enabled) onChange else null,
                enabled = enabled,
                colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = colors.onAccent),
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = if (enabled) colors.textPrimary else colors.textTertiary,
            supportingColor = colors.textTertiary,
        ),
        modifier = if (enabled) Modifier.clickable { onChange(!checked) } else Modifier,
    )
}

/**
 * 批v152: per-tab visibility for the bottom bar / rail. Settings is rendered
 * with its switch disabled — it is the only way back to this screen, and the
 * store also refuses to persist its route as hidden, so an enabled switch here
 * would be a lie.
 */
@Composable
private fun TabVisibilityEditor(
    hidden: List<String>,
    onHiddenChange: (String, Boolean) -> Unit,
) {
    val colors = CodegTheme.colors
    val hiddenSet = hidden.toSet()
    Column {
        Text(
            stringResource(R.string.general_tab_visibility_subtitle),
            fontSize = 12.sp,
            color = colors.textTertiary,
            modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
        )
        HomeTab.entries.forEachIndexed { index, tab ->
            val locked = tab == HomeTab.SETTINGS
            ToggleRow(
                title = stringResource(tab.label),
                subtitle = stringResource(if (locked) R.string.general_tab_settings_locked else R.string.general_tab_visible_subtitle),
                checked = locked || tab.route !in hiddenSet,
                onChange = { visible -> onHiddenChange(tab.route, !visible) },
                enabled = !locked,
            )
            if (index < HomeTab.entries.lastIndex) {
                HorizontalDivider(color = colors.hairline, modifier = Modifier.padding(start = 56.dp))
            }
        }
    }
}

@Composable
private fun Stepper(value: Int, onDec: () -> Unit, onInc: () -> Unit) {
    val colors = CodegTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        StepButton(Icons.Rounded.Remove, modifier = Modifier.size(36.dp), onClick = onDec)
        Text("$value", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, modifier = Modifier.padding(horizontal = 8.dp))
        StepButton(Icons.Rounded.Add, modifier = Modifier.size(36.dp), onClick = onInc)
    }
}

@Composable
private fun StepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = CodegTheme.colors
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = colors.textPrimary.copy(alpha = 0.06f),
            contentColor = colors.textPrimary,
        ),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(18.dp))
    }
}

/**
 * Bottom-tab order + cold-start tab editor. Rows render in the user's saved order
 * (enum order as fallback); ▲▼ swap adjacent rows and persist immediately. The
 * start-tab row is marked with a check — tapping a row's name (not the arrows)
 * sets it as the tab the app opens on.
 */
@Composable
private fun TabOrderEditor(
    savedOrder: List<String>,
    startTab: String,
    hidden: List<String>,
    onReorder: (List<String>) -> Unit,
    onStartTab: (String) -> Unit,
) {
    val colors = CodegTheme.colors
    // R4-B A3: mirror of GeneralViewModel.chipOrderOverride — rapid ▲▼ taps
    // must chain off the last SWAP, not the last persisted snapshot whose
    // DataStore echo lags (a quick shuffle of two rows would lose a move).
    // Scoped to this editor, the tab order's only writer.
    var orderOverride by remember { mutableStateOf<List<String>?>(null) }
    val effectiveOrder = orderOverride ?: savedOrder
    val tabs = remember(effectiveOrder) { HomeTab.orderedBy(effectiveOrder) }
    val hiddenSet = hidden.toSet()
    Column {
        Text(stringResource(R.string.general_tab_order_subtitle), fontSize = 12.sp, color = colors.textTertiary, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
        tabs.forEachIndexed { index, tab ->
            // 批v152: a hidden tab cannot be the start tab — the cold-start
            // resolver would silently fall back, so the tap must not pretend
            // otherwise.
            val canStart = tab.route !in hiddenSet
            val isStart = tab.route == startTab
            ListItem(
                headlineContent = { Text(stringResource(tab.label)) },
                leadingContent = {
                    Icon(
                        if (isStart) Icons.Rounded.Check else tab.icon,
                        contentDescription = null,
                        tint = when {
                            isStart -> colors.accent
                            canStart -> colors.textTertiary
                            else -> colors.textTertiary.copy(alpha = 0.5f)
                        },
                        modifier = Modifier.size(20.dp),
                    )
                },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                        // 批v153（第3轮可及性审查）：36dp 无标签双胞胎按钮——读屏
                        // 用户无法区分上下、也低于 48dp 触达标准；补朗读文案并撑到
                        // 48dp（同屏 Stepper 的 ± 键保持 36dp 视觉不动）。
                        StepButton(
                            Icons.Rounded.ArrowDropUp,
                            enabled = index > 0,
                            contentDescription = stringResource(R.string.common_move_up),
                            modifier = Modifier.size(48.dp),
                            onClick = {
                                val next = tabs.toMutableList()
                                next[index - 1] = tabs[index].also { next[index] = next[index - 1] }
                                orderOverride = next.map { it.route }
                                onReorder(next.map { it.route })
                            },
                        )
                        StepButton(
                            Icons.Rounded.ArrowDropDown,
                            enabled = index < tabs.lastIndex,
                            contentDescription = stringResource(R.string.common_move_down),
                            modifier = Modifier.size(48.dp),
                            onClick = {
                                val next = tabs.toMutableList()
                                next[index + 1] = tabs[index].also { next[index] = next[index + 1] }
                                orderOverride = next.map { it.route }
                                onReorder(next.map { it.route })
                            },
                        )
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    headlineColor = when {
                        isStart -> colors.textPrimary
                        canStart -> colors.textPrimary
                        else -> colors.textTertiary.copy(alpha = 0.6f)
                    },
                ),
                modifier = if (canStart) Modifier.clickable { onStartTab(tab.route) } else Modifier,
            )
            if (index < tabs.lastIndex) {
                HorizontalDivider(color = colors.hairline, modifier = Modifier.padding(start = 56.dp))
            }
        }
    }
}

/** 派发默认 section: pick the device/project/agent every dispatch sheet opens
 *  preselected with (v1.0.1-fengx.34). Plain radio dialogs here — the wheel
 *  picker is the dispatch sheet's interaction, this screen just stores defaults. */
@Composable
private fun DispatchDefaultsSection(viewModel: DispatchDefaultsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    var showDevice by rememberSaveable { mutableStateOf(false) }
    var showFolder by rememberSaveable { mutableStateOf(false) }
    var showAgent by rememberSaveable { mutableStateOf(false) }

    val deviceName = ui.profiles.firstOrNull { it.id == settings.dispatchDefaultDevice }?.name
        ?: settings.dispatchDefaultDevice.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.dispatch_defaults_unset)
    val folderName = settings.dispatchDefaultFolderName.takeIf { settings.dispatchDefaultFolderId > 0 }
        ?: stringResource(R.string.dispatch_defaults_unset)
    val agentName = AgentType.fromWire(settings.dispatchDefaultAgent)
        .takeIf { settings.dispatchDefaultAgent.isNotBlank() && it != AgentType.UNKNOWN }?.displayName
        ?: stringResource(R.string.dispatch_defaults_any)

    ListItem(
        headlineContent = { Text(stringResource(R.string.dispatch_defaults_device)) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(deviceName, fontSize = 14.sp, color = colors.textSecondary)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = colors.textPrimary),
        modifier = Modifier.clickable { showDevice = true },
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.dispatch_defaults_folder)) },
        supportingContent = {
            if (settings.dispatchDefaultDevice.isBlank()) {
                Text(stringResource(R.string.dispatch_defaults_pick_device_first), fontSize = 12.sp)
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(folderName, fontSize = 14.sp, color = colors.textSecondary)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = colors.textPrimary, supportingColor = colors.textTertiary),
        modifier = Modifier.clickable(enabled = settings.dispatchDefaultDevice.isNotBlank()) {
            viewModel.loadTargets(settings.dispatchDefaultDevice)
            showFolder = true
        },
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.dispatch_defaults_agent)) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(agentName, fontSize = 14.sp, color = colors.textSecondary)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = colors.textPrimary),
        modifier = Modifier.clickable(enabled = settings.dispatchDefaultDevice.isNotBlank()) {
            viewModel.loadTargets(settings.dispatchDefaultDevice)
            showAgent = true
        },
    )
    Text(
        stringResource(R.string.dispatch_defaults_clear),
        fontSize = 13.sp,
        color = colors.danger,
        modifier = Modifier
            .padding(start = 16.dp, top = 2.dp)
            .clickable { viewModel.clear() },
    )

    if (showDevice) {
        RadioOptionDialog(
            title = stringResource(R.string.dispatch_defaults_device),
            options = ui.profiles.map { it.id to it.name },
            selectedId = settings.dispatchDefaultDevice,
            onSelect = { id ->
                showDevice = false
                ui.profiles.firstOrNull { it.id == id }?.let { viewModel.saveDeviceOnly(it.id, it.name) }
            },
            onDismiss = { showDevice = false },
        )
    }
    if (showFolder) {
        RadioOptionDialog(
            title = stringResource(R.string.dispatch_defaults_folder),
            options = ui.folders.map { it.id.toString() to it.name },
            selectedId = settings.dispatchDefaultFolderId.takeIf { it > 0 }?.toString(),
            loading = ui.loading,
            onSelect = { id ->
                showFolder = false
                id.toIntOrNull()?.let { fid -> ui.folders.firstOrNull { it.id == fid }?.let(viewModel::saveFolder) }
            },
            onDismiss = { showFolder = false },
        )
    }
    if (showAgent) {
        // First option = 不指定 (agent omitted, server uses the folder default).
        RadioOptionDialog(
            title = stringResource(R.string.dispatch_defaults_agent),
            options = listOf("" to stringResource(R.string.dispatch_defaults_any)) +
                ui.agents.map { it.wire to it.displayName },
            selectedId = settings.dispatchDefaultAgent.takeIf { it.isNotBlank() } ?: "",
            loading = ui.loading,
            onSelect = { wire ->
                showAgent = false
                viewModel.saveAgent(wire.takeIf { it.isNotBlank() }?.let(AgentType::fromWire))
            },
            onDismiss = { showAgent = false },
        )
    }
}

@Composable
private fun RadioOptionDialog(
    title: String,
    options: List<Pair<String, String>>,
    selectedId: String?,
    loading: Boolean = false,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CodegTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (loading) {
                    Text(stringResource(R.string.dispatch_defaults_loading), fontSize = 14.sp, color = colors.textSecondary)
                } else if (options.isEmpty()) {
                    Text(stringResource(R.string.dispatch_defaults_empty), fontSize = 14.sp, color = colors.textSecondary)
                }
                options.forEach { (id, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(id) }
                            .padding(vertical = 10.dp),
                    ) {
                        RadioButton(selected = id == selectedId, onClick = { onSelect(id) })
                        Text(label, fontSize = 15.sp, color = colors.textPrimary, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        },
    )
}

/**
 * 批v171 N8: 「手势与滑动」 — split out of the crowded General leaf (settings
 * IA research: one theme per screen). Hosts the shell's horizontal-swipe
 * toggles, the session-row bindings (批v168), the NEW inbox-card bindings
 * (批v171 N7) and the floating-ball block; the action-picker sheet below
 * generalizes over the four directions instead of one sheet per domain.
 */
@Composable
fun HandsContent(
    viewModel: GeneralViewModel = hiltViewModel(),
    onOpenBallSettings: () -> Unit = {},
) {
    val appSettings by viewModel.settings.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    // Which binding the picker edits: "session_left" | "session_right" |
    // "inbox_left" | "inbox_right" (null = closed). rememberSaveable survives.
    var swipeEditor by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Section(stringResource(R.string.hands_gesture_section)) {
            ToggleRow(
                stringResource(R.string.general_activity_swipe_title),
                stringResource(R.string.general_activity_swipe_subtitle),
                appSettings.activityDeviceSwipe,
                viewModel::setActivityDeviceSwipe,
            )
            // 批v160 W5: task-board horizontal swipe semantics — step the four
            // status columns (default) or switch devices like on Activity. Same
            // chip idiom as the inbox-idea-style row below.
            ListItem(
                headlineContent = { Text(stringResource(R.string.general_task_swipe_title)) },
                supportingContent = { Text(stringResource(R.string.general_task_swipe_subtitle)) },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            app.codeg.android.core.datastore.TASK_SWIPE_SEGMENTS to R.string.general_task_swipe_segments,
                            app.codeg.android.core.datastore.TASK_SWIPE_DEVICES to R.string.general_task_swipe_devices,
                        ).forEach { (id, labelRes) ->
                            // 局部变量刻意叫 isSelected：若叫 selected，下方
                            // semantics lambda 的左值会解析到局部变量报编译错。
                            val isSelected = appSettings.taskSwipeMode == id
                            Text(
                                stringResource(labelRes),
                                fontSize = 13.sp,
                                color = if (isSelected) colors.selectedText else colors.textSecondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) colors.selectedBg else colors.textPrimary.copy(alpha = 0.08f),
                                    )
                                    .clickable { viewModel.setTaskSwipeMode(id) }
                                    .semantics { selected = isSelected }
                                    .heightIn(min = 48.dp)
                                    .wrapContentHeight(Alignment.CenterVertically)
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    headlineColor = colors.textPrimary,
                    supportingColor = colors.textTertiary,
                ),
            )
        }
        Section(stringResource(R.string.swipe_session_section)) {
            // 批v171 N7: the master switch — off means the rows unwrap to a
            // plain Box (no gesture cost, no reveal panes).
            ToggleRow(
                stringResource(R.string.session_swipe_enabled_title),
                stringResource(R.string.session_swipe_enabled_subtitle),
                appSettings.sessionSwipeEnabled,
                viewModel::setSessionSwipeEnabled,
            )
            // 批v168: the session row's left/right swipe bindings (Gmail/Outlook
            // model). A swipe is a shortcut into the long-press menu's handlers;
            // 「无动作」turns that direction off.
            PickerField(
                icon = Icons.Rounded.SwipeLeft,
                label = stringResource(R.string.general_swipe_left_title),
                value = swipeActionLabel(appSettings.sessionSwipeLeftAction),
                placeholder = stringResource(R.string.swipe_action_none),
                onClick = { swipeEditor = "session_left" },
            )
            PickerField(
                icon = Icons.Rounded.SwipeRight,
                label = stringResource(R.string.general_swipe_right_title),
                value = swipeActionLabel(appSettings.sessionSwipeRightAction),
                placeholder = stringResource(R.string.swipe_action_none),
                onClick = { swipeEditor = "session_right" },
            )
        }
        Section(stringResource(R.string.swipe_inbox_section)) {
            ToggleRow(
                stringResource(R.string.inbox_swipe_enabled_title),
                stringResource(R.string.inbox_swipe_enabled_subtitle),
                appSettings.inboxSwipeEnabled,
                viewModel::setInboxSwipeEnabled,
            )
            PickerField(
                icon = Icons.Rounded.SwipeLeft,
                label = stringResource(R.string.inbox_swipe_left_title),
                value = inboxSwipeActionLabel(appSettings.inboxSwipeLeftAction),
                placeholder = stringResource(R.string.swipe_action_none),
                onClick = { swipeEditor = "inbox_left" },
            )
            PickerField(
                icon = Icons.Rounded.SwipeRight,
                label = stringResource(R.string.inbox_swipe_right_title),
                value = inboxSwipeActionLabel(appSettings.inboxSwipeRightAction),
                placeholder = stringResource(R.string.swipe_action_none),
                onClick = { swipeEditor = "inbox_right" },
            )
        }
        Section(stringResource(R.string.general_floating_ball_section)) {
            ToggleRow(
                stringResource(R.string.ball_enable_title),
                stringResource(R.string.ball_enable_subtitle),
                appSettings.floatBallEnabled,
                viewModel::setFloatBallEnabled,
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.ball_size_title)) },
                supportingContent = { Text(stringResource(R.string.ball_size_subtitle)) },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "small" to R.string.ball_size_small,
                            "medium" to R.string.ball_size_medium,
                            "large" to R.string.ball_size_large,
                        ).forEach { (id, labelRes) ->
                            // 局部变量刻意叫 isSelected：若叫 selected，下方
                            // semantics lambda 的左值会解析到局部变量报编译错
                            // （v151 教训，同新想法样式 chip）。
                            val isSelected = appSettings.floatBallSize == id
                            Text(
                                stringResource(labelRes),
                                fontSize = 13.sp,
                                color = if (isSelected) colors.selectedText else colors.textSecondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) colors.selectedBg else colors.textPrimary.copy(alpha = 0.08f),
                                    )
                                    .clickable { viewModel.setFloatBallSize(id) }
                                    // 批v153（第3轮可及性审查）：对齐新想法样式 chip 的
                                    // 三件套——48dp 触达 + selected 朗读（同族同法）。
                                    .semantics { selected = isSelected }
                                    .heightIn(min = 48.dp)
                                    .wrapContentHeight(Alignment.CenterVertically)
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    headlineColor = colors.textPrimary,
                    supportingColor = colors.textTertiary,
                ),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.ball_bindings_title)) },
                supportingContent = { Text(stringResource(R.string.ball_bindings_subtitle)) },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    headlineColor = colors.textPrimary,
                    supportingColor = colors.textTertiary,
                ),
                modifier = Modifier.clickable { onOpenBallSettings() },
            )
        }
        swipeEditor?.let { direction ->
            val session = direction.startsWith("session")
            val isLeft = direction.endsWith("left")
            OptionListSheet(
                title = stringResource(
                    when {
                        session && isLeft -> R.string.general_swipe_left_title
                        session -> R.string.general_swipe_right_title
                        isLeft -> R.string.inbox_swipe_left_title
                        else -> R.string.inbox_swipe_right_title
                    },
                ),
                options = if (session) {
                    (listOf(SessionSwipeAction.NONE_ID) + SessionSwipeAction.entries.map { it.id })
                        .map { id -> PickerOption(id = id, label = swipeActionLabel(id)) }
                } else {
                    InboxSwipeAction.RING.map { id -> PickerOption(id = id, label = inboxSwipeActionLabel(id)) }
                },
                selectedId = when (direction) {
                    "session_left" -> appSettings.sessionSwipeLeftAction
                    "session_right" -> appSettings.sessionSwipeRightAction
                    "inbox_left" -> appSettings.inboxSwipeLeftAction
                    else -> appSettings.inboxSwipeRightAction
                },
                onSelect = { option ->
                    when (direction) {
                        "session_left" -> viewModel.setSessionSwipeLeft(option.id)
                        "session_right" -> viewModel.setSessionSwipeRight(option.id)
                        "inbox_left" -> viewModel.setInboxSwipeLeft(option.id)
                        else -> viewModel.setInboxSwipeRight(option.id)
                    }
                },
                onDismiss = { swipeEditor = null },
            )
        }
    }
}

/**
 * 批v171 N8: 「通知」 — the 会话交互提醒 block moved verbatim out of General.
 */
@Composable
fun NotifyContent(viewModel: GeneralViewModel = hiltViewModel()) {
    val appSettings by viewModel.settings.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Section(stringResource(R.string.general_notify_section)) {
            val context = androidx.compose.ui.platform.LocalContext.current
            // POST_NOTIFICATIONS is a runtime permission on 33+; below that a
            // granted state is implicit and the toggle just persists.
            var permissionGranted by remember {
                mutableStateOf(
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context, android.Manifest.permission.POST_NOTIFICATIONS,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
                )
            }
            // 批v161.3 R1: all three notify toggles share ONE launcher — the
            // pending persist records WHICH switch asked, so the grant callback
            // flips the right pref and a denial snaps every switch back off.
            var pendingEnable by remember { mutableStateOf<(() -> Unit)?>(null) }
            val requestPermission = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
            ) { granted ->
                permissionGranted = granted
                // Deny stays un-persisted: the switch snaps back to off and the
                // user can retry (or grant from the system settings).
                if (granted) pendingEnable?.invoke()
                pendingEnable = null
            }
            // The shared gate (批v161.3 R1): turning a switch ON with
            // POST_NOTIFICATIONS ungranted (33+) first launches the request —
            // only a grant persists, a denial leaves the pref untouched. Turning
            // OFF always persists immediately.
            fun gatedChange(on: Boolean, persist: (Boolean) -> Unit) {
                if (!on) {
                    persist(false)
                    return
                }
                val needsRequest =
                    android.os.Build.VERSION.SDK_INT >= 33 && !permissionGranted
                if (needsRequest) {
                    pendingEnable = { persist(true) }
                    requestPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    persist(true)
                }
            }
            val notifyOn = appSettings.notifyInteractiveEnabled
            ListItem(
                headlineContent = { Text(stringResource(R.string.notify_interactive_toggle_title)) },
                supportingContent = { Text(stringResource(R.string.notify_interactive_toggle_subtitle)) },
                trailingContent = {
                    Switch(
                        checked = notifyOn,
                        onCheckedChange = { on ->
                            gatedChange(on, viewModel::setNotifyInteractiveEnabled)
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = colors.onAccent),
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    headlineColor = colors.textPrimary,
                    supportingColor = colors.textTertiary,
                ),
                modifier = Modifier.clickable {
                    gatedChange(!notifyOn, viewModel::setNotifyInteractiveEnabled)
                },
            )
            // 系统级通知体系: per-channel switches — task-board outcomes (and
            // turn completions share the channel) + silent device-offline alerts.
            // Same runtime-permission gate as the interactive switch (批v161.3
            // R1): persisting first used to let these two bypass the 33+ prompt
            // entirely — the notifications then never showed.
            ToggleRow(
                title = stringResource(R.string.settings_notify_task_results),
                subtitle = stringResource(R.string.settings_notify_task_results_subtitle),
                checked = appSettings.notifyTaskResultsEnabled,
                onChange = { on -> gatedChange(on, viewModel::setNotifyTaskResultsEnabled) },
            )
            ToggleRow(
                title = stringResource(R.string.settings_notify_device_events),
                subtitle = stringResource(R.string.settings_notify_device_events_subtitle),
                checked = appSettings.notifyDeviceEventsEnabled,
                onChange = { on -> gatedChange(on, viewModel::setNotifyDeviceEventsEnabled) },
            )
            ToggleRow(
                title = stringResource(R.string.notify_keepalive_title),
                subtitle = stringResource(
                    if (notifyOn) R.string.notify_keepalive_subtitle else R.string.notify_keepalive_disabled_subtitle,
                ),
                checked = appSettings.keepAliveEnabled,
                onChange = viewModel::setKeepAliveEnabled,
                // Without notifications there is nothing to keep alive.
                enabled = notifyOn,
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.notify_battery_title)) },
                supportingContent = { Text(stringResource(R.string.notify_battery_subtitle)) },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    headlineColor = colors.textPrimary,
                    supportingColor = colors.textTertiary,
                ),
                modifier = Modifier.clickable {
                    // The dedicated screen needs no special permission; some ROMs
                    // drop it — fall back to the app details page.
                    val intents = listOf(
                        android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    )
                    intents.firstNotNullOfOrNull { action ->
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    action,
                                    android.net.Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }.getOrNull()
                    }
                },
            )
            // 系统级通知体系: point at the system notification settings for the
            // per-channel sound/style knobs (importance, vibration, sound).
            Text(
                stringResource(R.string.notify_channels_hint),
                fontSize = 12.sp,
                color = colors.textTertiary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            )
            // Android 14+ gates full-screen (lock-screen floating) alerts behind
            // a separate "Display over other apps" grant (批v161.3 R1) — without
            // it the P0 alert silently degrades to a heads-up, so surface the
            // missing permission right where the notification toggles live.
            val canFullScreen = remember {
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    (context.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                        as? android.app.NotificationManager)
                        ?.canUseFullScreenIntent() ?: false
                } else {
                    true
                }
            }
            if (!canFullScreen) {
                Text(
                    stringResource(R.string.notify_fsi_hint),
                    fontSize = 12.sp,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                )
            }
        }
    }
}

/**
 * 批v171 N8: 「任务与派发」 — dispatch defaults + the new-session pin, verbatim
 * out of General.
 */
@Composable
fun DispatchContent(viewModel: GeneralViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Section(stringResource(R.string.general_dispatch_defaults_section)) {
            DispatchDefaultsSection()
        }
        Section(stringResource(R.string.general_new_session_section)) {
            var showFolderPicker by rememberSaveable { mutableStateOf(false) }
            // Summary line: the pin's name, or the last-used folder's name when
            // following memory, so the row reads as a fact not a mode switch.
            val lastUsedName = ui.lastUsedFolderId?.let { id ->
                ui.draftFolders.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() }
            }
            val followLabel = lastUsedName
                ?.let { stringResource(R.string.general_default_folder_follow_last, it) }
                ?: stringResource(R.string.general_default_folder_follow)
            // A pin whose folder was closed/deleted server-side is NOT a pin any more:
            // the new-task resolver skips it, so showing "#12" (and an option list with
            // nothing selected) would describe behaviour that no longer happens.
            val activePin = ui.pinnedFolderId?.takeIf { pin -> ui.draftFolders.any { it.id == pin } }
            val currentLabel = when (val pin = activePin) {
                null -> followLabel
                else -> ui.draftFolders.firstOrNull { it.id == pin }?.name ?: followLabel
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.general_default_folder_title)) },
                supportingContent = { Text(currentLabel) },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    headlineColor = colors.textPrimary,
                    supportingColor = colors.textTertiary,
                ),
                // No server loaded (or its load failed): the list is empty and every
                // option would be a no-op — don't open a dead dialog.
                modifier = Modifier.clickable { if (ui.serverId != null) showFolderPicker = true },
            )
            ui.error?.let { message ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.danger,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.load() }) {
                        Text(stringResource(R.string.common_retry), color = colors.accent)
                    }
                }
            }
            if (showFolderPicker) {
                AlertDialog(
                    onDismissRequest = { showFolderPicker = false },
                    title = { Text(stringResource(R.string.general_default_folder_title)) },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            PickerRow(
                                label = followLabel,
                                selected = activePin == null,
                                onClick = {
                                    viewModel.setPinnedFolder(null)
                                    showFolderPicker = false
                                },
                            )
                            ui.draftFolders.forEach { f ->
                                PickerRow(
                                    label = f.name.ifBlank { f.path },
                                    selected = activePin == f.id,
                                    onClick = {
                                        viewModel.setPinnedFolder(f.id)
                                        showFolderPicker = false
                                    },
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showFolderPicker = false }) {
                            Text(stringResource(R.string.common_ok), color = colors.accent)
                        }
                    },
                )
            }
        }
    }
}

/**
 * 批v171 N3/N8: the appearance page's 经典外观 + 字体 block — the font scale
 * lives with the look (it used to sit at the bottom of General), and the
 * classic-look switch flips the selected-role skin app-wide: accent-filled
 * (pre-v169) vs the restrained neutral style.
 */
@Composable
fun FontAndLookContent(viewModel: GeneralViewModel = hiltViewModel()) {
    val appSettings by viewModel.settings.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        ToggleRow(
            stringResource(R.string.classic_look_title),
            stringResource(R.string.classic_look_subtitle),
            appSettings.classicLook,
            viewModel::setClassicLook,
        )
        Section(stringResource(R.string.general_font_section)) {
            val stepIndex = FONT_STEPS.indexOfFirst { kotlin.math.abs(it - appSettings.fontScale) < 0.01f }
            // 批v172 D3: per-interface scale sheets. "session" covers the dynamic
            // tab family (activity/chats/search/session detail); 0 = follow global.
            var scaleSheet by rememberSaveable { mutableStateOf<String?>(null) }
            var fontSheet by rememberSaveable { mutableStateOf(false) }
            var surfaceSheet by rememberSaveable { mutableStateOf(false) }
            val followGlobal = stringResource(R.string.general_font_follow_global)
            val sheetOptions = remember(followGlobal) {
                listOf(PickerOption("0", followGlobal)) +
                    FONT_STEPS.map { PickerOption(it.toString(), "${(it * 100).roundToInt()}%") }
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.general_font_scale_title)) },
                supportingContent = { Text(stringResource(R.string.general_font_scale_subtitle)) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        StepButton(
                            Icons.Rounded.Remove,
                            enabled = stepIndex > 0,
                            onClick = { viewModel.setFontScale(stepFontScale(appSettings.fontScale, -1)) },
                        )
                        Text(
                            "${(appSettings.fontScale * 100).roundToInt()}%",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        StepButton(
                            Icons.Rounded.Add,
                            enabled = stepIndex in 0 until FONT_STEPS.lastIndex,
                            onClick = { viewModel.setFontScale(stepFontScale(appSettings.fontScale, +1)) },
                        )
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    headlineColor = colors.textPrimary,
                    supportingColor = colors.textTertiary,
                ),
            )
            // 批v172 D3: 动态会话 / 收件箱 / 任务板 各自的字号档——选中档位
            // 直接替换全局倍率（不是叠乘），「跟随全局」回到 v171 行为。
            InterfaceScaleRow(
                title = stringResource(R.string.general_font_session_title),
                scale = appSettings.sessionFontScale,
                onClick = { scaleSheet = "session" },
            )
            InterfaceScaleRow(
                title = stringResource(R.string.general_font_inbox_title),
                scale = appSettings.inboxFontScale,
                onClick = { scaleSheet = "inbox" },
            )
            InterfaceScaleRow(
                title = stringResource(R.string.general_font_taskhub_title),
                scale = appSettings.taskhubFontScale,
                onClick = { scaleSheet = "taskhub" },
            )
            // 批v172 D5: 正文字体三选（数字/代码保持等宽，CodegMono 不受影响）。
            ChoiceRow(
                title = stringResource(R.string.general_font_family_title),
                value = stringResource(
                    when (AppFont.from(appSettings.appFontId)) {
                        AppFont.WENKAI -> R.string.general_font_family_wenkai
                        AppFont.SERIF -> R.string.general_font_family_serif
                        AppFont.SYSTEM -> R.string.general_font_family_system
                    },
                ),
                onClick = { fontSheet = true },
            )
            // 批v172 D4: 浅色纸面四选——行的 note 注明深色模式忽略该设置。
            ChoiceRow(
                title = stringResource(R.string.general_surface_title),
                value = stringResource(
                    when (LightSurface.from(appSettings.lightSurfaceId)) {
                        LightSurface.PARCHMENT -> R.string.general_surface_parchment
                        LightSurface.SAGE -> R.string.general_surface_sage
                        LightSurface.PAPER_GRAY -> R.string.general_surface_gray
                        LightSurface.DEFAULT -> R.string.general_surface_default
                    },
                ),
                note = stringResource(R.string.general_surface_hint),
                onClick = { surfaceSheet = true },
            )
            scaleSheet?.let { which ->
                OptionListSheet(
                    title = stringResource(
                        when (which) {
                            "inbox" -> R.string.general_font_inbox_title
                            "taskhub" -> R.string.general_font_taskhub_title
                            else -> R.string.general_font_session_title
                        },
                    ),
                    options = sheetOptions,
                    // 0 = follow-global sentinel; 0f.toString() is "0.0" and would
                    // never match the option id "0", so normalize before comparing.
                    selectedId = when (which) {
                        "inbox" -> appSettings.inboxFontScale
                        "taskhub" -> appSettings.taskhubFontScale
                        else -> appSettings.sessionFontScale
                    }.let { if (it <= 0f) "0" else it.toString() },
                    onSelect = { option ->
                        val v = option.id.toFloatOrNull() ?: 0f
                        when (which) {
                            "inbox" -> viewModel.setInboxFontScale(v)
                            "taskhub" -> viewModel.setTaskhubFontScale(v)
                            else -> viewModel.setSessionFontScale(v)
                        }
                        scaleSheet = null
                    },
                    onDismiss = { scaleSheet = null },
                )
            }
            // 批v172 D5: 字体 sheet——stringResource 先提出来再拼 options
            // （R1 的教训：@DisallowComposableCalls 禁 remember 内取）。
            if (fontSheet) {
                OptionListSheet(
                    title = stringResource(R.string.general_font_family_title),
                    options = listOf(
                        PickerOption(AppFont.SYSTEM.id, stringResource(R.string.general_font_family_system)),
                        PickerOption(AppFont.WENKAI.id, stringResource(R.string.general_font_family_wenkai)),
                        PickerOption(AppFont.SERIF.id, stringResource(R.string.general_font_family_serif)),
                    ),
                    selectedId = AppFont.from(appSettings.appFontId).id,
                    onSelect = { option ->
                        viewModel.setAppFont(option.id)
                        fontSheet = false
                    },
                    onDismiss = { fontSheet = false },
                )
            }
            // 批v172 D4: 纸面 sheet。
            if (surfaceSheet) {
                OptionListSheet(
                    title = stringResource(R.string.general_surface_title),
                    options = listOf(
                        PickerOption(LightSurface.DEFAULT.id, stringResource(R.string.general_surface_default)),
                        PickerOption(LightSurface.PARCHMENT.id, stringResource(R.string.general_surface_parchment)),
                        PickerOption(LightSurface.SAGE.id, stringResource(R.string.general_surface_sage)),
                        PickerOption(LightSurface.PAPER_GRAY.id, stringResource(R.string.general_surface_gray)),
                    ),
                    selectedId = LightSurface.from(appSettings.lightSurfaceId).id,
                    onSelect = { option ->
                        viewModel.setLightSurface(option.id)
                        surfaceSheet = false
                    },
                    onDismiss = { surfaceSheet = false },
                )
            }
        }
    }
}

/** 批v172 D3: one per-interface font-scale row (value + chevron → OptionListSheet). */
@Composable
private fun InterfaceScaleRow(title: String, scale: Float, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    val valueText = if (scale <= 0f) stringResource(R.string.general_font_follow_global)
    else "${(scale * 100).roundToInt()}%"
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    valueText,
                    fontSize = 14.sp,
                    color = if (scale <= 0f) colors.textTertiary else colors.accent,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = colors.textPrimary,
        ),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** 批v172 D4/D5: generic choice row — same language as [InterfaceScaleRow]
 *  (transparent ListItem, right value + chevron), optional supporting note. */
@Composable
private fun ChoiceRow(title: String, value: String, note: String? = null, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (note != null) {
            { Text(note) }
        } else {
            null
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    value,
                    fontSize = 14.sp,
                    color = colors.textSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = colors.textPrimary,
            supportingColor = colors.textTertiary,
        ),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** 批v171 N7: display name for a stored inbox swipe-binding id ("none" included). */
@Composable
private fun inboxSwipeActionLabel(actionId: String): String =
    stringResource(inboxSwipeActionLabelRes(actionId) ?: R.string.swipe_action_none)
