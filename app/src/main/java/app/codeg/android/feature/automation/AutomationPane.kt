package app.codeg.android.feature.automation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.data.DeviceLoadStatus
import app.codeg.android.core.designsystem.component.AgentChipRow
import app.codeg.android.core.designsystem.component.CodegFilterChip
import app.codeg.android.core.designsystem.component.CodegFormTextField
import app.codeg.android.core.designsystem.component.CodegSegmented
import app.codeg.android.core.designsystem.component.DeviceChipRow
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.component.FullscreenTextEditorDialog
import app.codeg.android.core.designsystem.component.InlineError
import app.codeg.android.core.designsystem.component.LoadingView
import app.codeg.android.core.designsystem.component.OptionListSheet
import app.codeg.android.core.designsystem.component.PickerField
import app.codeg.android.core.designsystem.component.PickerOption
import app.codeg.android.core.designsystem.component.holdDialogWindowStill
import app.codeg.android.core.designsystem.component.rememberSheetDragLock
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.AutomationInfo
import app.codeg.android.core.model.AutomationRunInfo
import java.time.Instant

/**
 * Automations 面板（舰队 M2，Q2 拍板）：任务板页内「自动化」段。列表按设备
 * 分节扇出，行内启停/立即运行/运行历史/编辑/删除；编辑器七档 cron 预设 +
 * IANA 时区（服务端求值，客户端绝不换算）+ 隔离方式。能力闸：老服务端
 * automation_list 404 → 该设备只留一行"暂不支持"，不出假按钮。
 */
@Composable
fun AutomationPane(
    viewModel: AutomationViewModel,
    onOpenSession: (serverId: String, conversationId: Int) -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val runs by viewModel.runs.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    // 删除是破坏性动作：走确认弹层（菜单项只装载目标）。
    // (serverId, automation)：行携带 device scope 的 serverId——automation id
    // 是设备内自增，跨设备反查必撞号（误删另一台的同 id 条目）。
    var deleteTarget by remember { mutableStateOf<Pair<String, AutomationInfo>?>(null) }

    Column(Modifier.fillMaxSize()) {
        ui.actionError?.let { error ->
            Text(
                text = stringResource(R.string.task_action_failed) + " · " + error,
                color = colors.danger,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable { viewModel.clearActionError() },
            )
        }
        Box(Modifier.fillMaxSize()) {
            when {
                !ui.hasLoaded && ui.devices.isEmpty() -> LoadingView(
                    message = stringResource(R.string.automation_loading),
                    modifier = Modifier.fillMaxWidth(),
                )
                ui.devices.isEmpty() -> EmptyState(
                    icon = Icons.Rounded.History,
                    title = stringResource(R.string.automation_empty_title),
                    message = stringResource(R.string.automation_empty_hint),
                    modifier = Modifier.padding(top = 48.dp),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ui.devices.forEach { device ->
                        item(key = "hdr-${device.serverId}") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = device.deviceName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = colors.textPrimary,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (device.automations.any { it.hasUnseenFailures }) {
                                    Text(
                                        text = stringResource(R.string.automation_failures_badge),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colors.danger,
                                    )
                                }
                            }
                        }
                        when {
                            device.status == DeviceLoadStatus.FAILED -> item(key = "err-${device.serverId}") {
                                InlineError(
                                    icon = Icons.Rounded.History,
                                    title = stringResource(R.string.automation_device_failed),
                                    message = device.error.orEmpty(),
                                    onRetry = viewModel::refresh,
                                )
                            }
                            !device.supported -> item(key = "unsup-${device.serverId}") {
                                Text(
                                    text = stringResource(R.string.automation_unsupported),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textTertiary,
                                    modifier = Modifier.padding(vertical = 6.dp),
                                )
                            }
                            device.automations.isEmpty() -> item(key = "none-${device.serverId}") {
                                Text(
                                    text = stringResource(R.string.automation_none_on_device),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textTertiary,
                                    modifier = Modifier.padding(vertical = 6.dp),
                                )
                            }
                            else -> items(
                                device.automations,
                                key = { "${device.serverId}:${it.id}" },
                            ) { automation ->
                                AutomationRow(
                                    automation = automation,
                                    busy = ui.busyKey != null,
                                    busyKey = ui.busyKey,
                                    onToggle = { viewModel.setEnabled(automation, device.serverId, it) },
                                    onRunNow = { viewModel.runNow(automation, device.serverId) },
                                    onOpenRuns = { viewModel.openRuns(automation, device.serverId) },
                                    onEdit = { viewModel.openEditor(automation, device.serverId) },
                                    onDelete = { deleteTarget = device.serverId to automation },
                                )
                            }
                        }
                    }
                }
            }
            // 新建入口：只在至少一台设备支持时出现（能力闸不出假按钮）。
            if (ui.anySupported) {
                Button(
                    onClick = {
                        val target = ui.devices.firstOrNull { it.supported } ?: return@Button
                        viewModel.openEditor(target.serverId, target.deviceName)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = colors.onAccent,
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.automation_create))
                }
            }
        }
    }

    runs?.let { runState ->
        RunsSheet(
            state = runState,
            onClose = viewModel::closeRuns,
            onRetry = { viewModel.openRuns(runState.automation, runState.serverId) },
            onCancelRun = { viewModel.cancelRun(it, runState.serverId) },
            onOpenSession = { conversationId -> onOpenSession(runState.serverId, conversationId) },
        )
    }
    editor?.let { state ->
        AutomationEditorSheet(state = state, viewModel = viewModel)
    }
    deleteTarget?.let { (serverId, target) ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.automation_delete_confirm_title)) },
            text = {
                Text(stringResource(R.string.automation_delete_confirm_msg, target.name))
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    viewModel.delete(target, serverId)
                }) { Text(stringResource(R.string.automation_delete), color = colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun AutomationRow(
    automation: AutomationInfo,
    busy: Boolean,
    busyKey: String?,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onOpenRuns: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = CodegTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(CodegTheme.dimens.radiusMd),
        color = colors.bgElevated,
        border = BorderStroke(1.dp, colors.hairline),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenRuns),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = automation.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (automation.hasUnseenFailures) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = colors.danger.copy(alpha = 0.16f),
                    ) {
                        Text(
                            text = stringResource(R.string.automation_failure_count, automation.unseenFailures),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.danger,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                Switch(
                    checked = automation.enabled,
                    onCheckedChange = onToggle,
                    enabled = !busy,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp),
            ) {
                if (automation.isScheduled) {
                    Icon(
                        Icons.Rounded.AccessTime,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = automation.nextRunAt
                            ?.let { relativeLabel(it) }
                            ?.let { stringResource(R.string.automation_next_run, it) }
                            ?: stringResource(R.string.automation_next_run_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(start = 4.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = automation.cron.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(start = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.automation_trigger_manual),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                automation.lastRunStatus?.let { status ->
                    Box(
                        Modifier
                            .padding(end = 4.dp)
                            .size(8.dp)
                            .background(runStatusColor(status), RoundedCornerShape(50)),
                    )
                    Text(
                        text = runStatusLabel(status),
                        style = MaterialTheme.typography.labelSmall,
                        color = runStatusColor(status),
                    )
                    Text(
                        text = " · ",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary,
                    )
                }
                Text(
                    text = automation.agentType.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRunNow, enabled = !busy, modifier = Modifier.size(32.dp)) {
                    if (busyKey == "run:${automation.id}") {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = colors.accent,
                        )
                    } else {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(R.string.automation_run_now),
                            tint = colors.accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                IconButton(onClick = onOpenRuns, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Rounded.History,
                        contentDescription = stringResource(R.string.automation_runs_entry),
                        tint = colors.textSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.automation_edit)) },
                            leadingIcon = {
                                Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            onClick = { menuOpen = false; onEdit() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.automation_delete)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Delete, contentDescription = null,
                                    tint = colors.danger, modifier = Modifier.size(18.dp),
                                )
                            },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun RunsSheet(
    state: AutomationViewModel.RunsState,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onCancelRun: (Int) -> Unit,
    onOpenSession: (Int) -> Unit,
) {
    val colors = CodegTheme.colors
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .nestedScroll(rememberSheetDragLock()),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.automation_runs_title, state.automation.name),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_cancel))
                }
            }
            when {
                state.loading -> LoadingView(
                    message = stringResource(R.string.automation_loading),
                    modifier = Modifier.fillMaxWidth(),
                )
                state.error != null -> InlineError(
                    icon = Icons.Rounded.History,
                    title = stringResource(R.string.automation_runs_failed),
                    message = state.error.orEmpty(),
                    onRetry = onRetry,
                    modifier = Modifier.padding(bottom = 32.dp),
                )
                state.runs.isEmpty() -> EmptyState(
                    icon = Icons.Rounded.History,
                    title = stringResource(R.string.automation_runs_empty_title),
                    message = stringResource(R.string.automation_runs_empty_hint),
                    modifier = Modifier.padding(bottom = 32.dp),
                )
                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .navigationBarsPadding(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.runs, key = { it.id }) { run ->
                        RunRow(
                            run = run,
                            onCancel = if (!run.isSettled) {
                                { onCancelRun(run.id) }
                            } else null,
                            onOpenConversation = run.conversationId?.let { id ->
                                { onOpenSession(id) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RunRow(
    run: AutomationRunInfo,
    onCancel: (() -> Unit)?,
    onOpenConversation: (() -> Unit)?,
) {
    val colors = CodegTheme.colors
    Surface(
        shape = RoundedCornerShape(CodegTheme.dimens.radiusMd),
        color = colors.bgElevated,
        border = BorderStroke(1.dp, colors.hairline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = runStatusLabel(run.status),
                    style = MaterialTheme.typography.labelMedium,
                    color = runStatusColor(run.status),
                )
                Text(
                    text = run.startedAt?.let { relativeLabel(it) }
                        ?.let { " · $it" }
                        .orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                onCancel?.let {
                    TextButton(onClick = it) { Text(stringResource(R.string.automation_run_cancel)) }
                }
            }
            val detail = run.summary ?: run.error ?: run.stopReason
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (run.isFailed) colors.danger else colors.textSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            onOpenConversation?.let {
                TextButton(onClick = it) {
                    Icon(
                        Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.automation_open_session))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun AutomationEditorSheet(
    state: AutomationViewModel.EditorState,
    viewModel: AutomationViewModel,
) {
    val colors = CodegTheme.colors
    var showTime by rememberSaveable { mutableStateOf(false) }
    var fullscreenPrompt by rememberSaveable { mutableStateOf(false) }
    var folderSheetOpen by rememberSaveable { mutableStateOf(false) }

    Dialog(
        onDismissRequest = viewModel::closeEditor,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        holdDialogWindowStill()
        Box(
            Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                color = colors.bgElevated,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f),
            ) {
                Column(Modifier.fillMaxSize()) {
                    // 固定标题行（拖拽关闭契约的 Dialog 版：标题栏常驻不随滚动）。
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 16.dp),
                    ) {
                        Text(
                            text = stringResource(
                                if (state.titleIsEdit) R.string.automation_edit_title else R.string.automation_create_title,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = viewModel::closeEditor) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_cancel))
                        }
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CodegFormTextField(
                            value = state.name,
                            onValueChange = { value -> viewModel.updateEditor { it.copy(name = value) } },
                            label = stringResource(R.string.automation_field_name),
                            singleLine = true,
                        )
                        CodegFormTextField(
                            value = state.prompt,
                            onValueChange = { value -> viewModel.updateEditor { it.copy(prompt = value) } },
                            label = stringResource(R.string.automation_field_prompt),
                            minLines = 3,
                            maxLines = 8,
                            trailingIcon = {
                                IconButton(onClick = { fullscreenPrompt = true }) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.OpenInNew,
                                        contentDescription = stringResource(R.string.automation_expand_prompt),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            },
                        )
                        // 设备 → 项目 → Agent（派发表单同款三级联动；切设备清下游）。
                        DeviceChipRow(
                            devices = state.targets.profiles.map { PickerOption(id = it.id, label = it.name) },
                            selectedId = state.serverId,
                            loadingDeviceId = if (state.targets.foldersLoading) state.serverId else null,
                            errorDeviceIds = if (state.targets.error != null || state.targets.foldersError) {
                                setOfNotNull(state.serverId)
                            } else emptySet(),
                            onSelect = viewModel::editorSelectDevice,
                        )
                        val folderName = state.targets.folders
                            .firstOrNull { it.id == state.folderId }?.name
                        PickerField(
                            icon = Icons.Rounded.Public,
                            label = stringResource(R.string.automation_field_project),
                            value = folderName,
                            placeholder = stringResource(
                                when {
                                    state.serverId == null -> R.string.automation_pick_no_device
                                    state.targets.foldersLoading -> R.string.automation_pick_loading
                                    else -> R.string.automation_pick_project
                                },
                            ),
                            enabled = state.serverId != null && !state.targets.foldersLoading,
                            onClick = { folderSheetOpen = true },
                        )
                        AgentChipRow(
                            autoLabel = stringResource(R.string.automation_agent_auto),
                            agents = state.targets.agents.map {
                                PickerOption(id = it.name, label = it.displayName)
                            },
                            selectedId = state.agentType?.name,
                            onSelect = { name ->
                                viewModel.updateEditor {
                                    it.copy(agentType = AgentType.entries.firstOrNull { agent -> agent.name == name })
                                }
                            },
                        )
                        CodegSegmented(
                            options = listOf(
                                stringResource(R.string.automation_trigger_scheduled),
                                stringResource(R.string.automation_trigger_manual),
                            ),
                            selectedIndex = if (state.scheduled) 0 else 1,
                            onSelect = { index ->
                                viewModel.updateEditor { it.copy(scheduled = index == 0) }
                            },
                        )
                        if (!state.isManual) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CronPreset.entries.forEach { preset ->
                                    CodegFilterChip(
                                        label = presetLabel(preset),
                                        selected = state.preset == preset,
                                        onClick = {
                                            viewModel.updateEditor { it.copy(preset = preset) }
                                        },
                                    )
                                }
                            }
                            if (state.preset.needsTimeOfDay) {
                                Surface(
                                    shape = RoundedCornerShape(CodegTheme.dimens.radiusMd),
                                    color = colors.bg.copy(alpha = 0.42f),
                                    border = BorderStroke(1.dp, colors.hairline),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showTime = true },
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(12.dp),
                                    ) {
                                        Icon(
                                            Icons.Rounded.AccessTime,
                                            contentDescription = null,
                                            tint = colors.textSecondary,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Text(
                                            text = stringResource(
                                                R.string.automation_time_of_day,
                                                String.format(java.util.Locale.ROOT, "%02d:%02d", state.hour, state.minute),
                                            ),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = colors.textPrimary,
                                            modifier = Modifier.padding(start = 10.dp),
                                        )
                                    }
                                }
                            }
                            if (state.preset == CronPreset.CUSTOM) {
                                CodegFormTextField(
                                    value = state.customCron,
                                    onValueChange = { value ->
                                        viewModel.updateEditor { it.copy(customCron = value) }
                                    },
                                    label = stringResource(R.string.automation_field_custom_cron),
                                    placeholder = stringResource(R.string.automation_custom_cron_hint),
                                    singleLine = true,
                                )
                            }
                            // 预览唯一触发点：所有 cron 输入的 key 都在这里，
                            // 回调里不再直调（否则双触发，靠 sig 去重也只是兜底）。
                            LaunchedEffect(
                                state.scheduled, state.preset, state.minute, state.hour,
                                state.customCron, state.timezone,
                            ) {
                                // M6 审查：sig 去重只省重复结果不省请求——
                                // customCron 逐键都打一次 compute_next_run，慢链路
                                // 上预览在「计算中⇄无效」间闪。400ms 防抖再发。
                                kotlinx.coroutines.delay(400)
                                viewModel.scheduleNextRunPreview()
                            }
                            val previewText = when {
                                state.nextRunStale -> stringResource(R.string.automation_next_computing)
                                state.nextRun == null -> stringResource(R.string.automation_next_invalid)
                                else -> relativeLabel(state.nextRun)
                                    ?.let { stringResource(R.string.automation_next_run, it) }
                                    ?: state.nextRun
                            }
                            Text(
                                text = previewText,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.nextRun == null && !state.nextRunStale) {
                                    colors.warning
                                } else colors.textSecondary,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Public,
                                contentDescription = null,
                                tint = colors.textTertiary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = state.timezone,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                        Text(
                            text = stringResource(R.string.automation_timezone_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textTertiary,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CodegFilterChip(
                                label = stringResource(R.string.automation_isolation_worktree),
                                selected = state.isolation == "worktree_per_run",
                                onClick = {
                                    viewModel.updateEditor { it.copy(isolation = "worktree_per_run") }
                                },
                            )
                            CodegFilterChip(
                                label = stringResource(R.string.automation_isolation_shared),
                                selected = state.isolation == "shared_in_root",
                                onClick = {
                                    viewModel.updateEditor { it.copy(isolation = "shared_in_root") }
                                },
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(R.string.automation_field_enabled),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = state.enabled,
                                onCheckedChange = { value ->
                                    viewModel.updateEditor { it.copy(enabled = value) }
                                },
                            )
                        }
                        state.error?.let { error ->
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.danger,
                            )
                        }
                        Button(
                            onClick = viewModel::saveEditor,
                            enabled = !state.saving,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.accent,
                                contentColor = colors.onAccent,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                        ) {
                            Text(stringResource(if (state.titleIsEdit) R.string.automation_save else R.string.automation_create))
                        }
                    }
                }
            }
        }
    }

    if (showTime) {
        val timeState = rememberTimePickerState(
            initialHour = state.hour,
            initialMinute = state.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTime = false },
            title = { Text(stringResource(R.string.automation_pick_time)) },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateEditor {
                        it.copy(hour = timeState.hour, minute = timeState.minute)
                    }
                    showTime = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showTime = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
    if (fullscreenPrompt) {
        FullscreenTextEditorDialog(
            text = state.prompt,
            onTextChange = { value -> viewModel.updateEditor { it.copy(prompt = value) } },
            onDismiss = { fullscreenPrompt = false },
            label = stringResource(R.string.automation_field_prompt),
        )
    }
    if (folderSheetOpen) {
        OptionListSheet(
            title = stringResource(R.string.automation_field_project),
            options = state.targets.folders.map { PickerOption(id = it.id.toString(), label = it.name) },
            selectedId = state.folderId?.toString(),
            onSelect = { option ->
                viewModel.updateEditor { it.copy(folderId = option.id.toIntOrNull()) }
                folderSheetOpen = false
            },
            onDismiss = { folderSheetOpen = false },
        )
    }
}

@Composable
private fun presetLabel(preset: CronPreset): String = stringResource(
    when (preset) {
        CronPreset.DAILY -> R.string.automation_preset_daily
        CronPreset.WORKDAYS -> R.string.automation_preset_workdays
        CronPreset.MONDAYS -> R.string.automation_preset_mondays
        CronPreset.MONTHLY_FIRST -> R.string.automation_preset_monthly
        CronPreset.HOURLY -> R.string.automation_preset_hourly
        CronPreset.EVERY_30_MIN -> R.string.automation_preset_half_hour
        CronPreset.CUSTOM -> R.string.automation_preset_custom
    },
)

@Composable
private fun runStatusLabel(status: String): String = when (status) {
    "running" -> stringResource(R.string.automation_run_running)
    "succeeded" -> stringResource(R.string.automation_run_succeeded)
    "failed" -> stringResource(R.string.automation_run_failed)
    "cancelled", "canceled" -> stringResource(R.string.automation_run_cancelled)
    "skipped" -> stringResource(R.string.automation_run_skipped)
    else -> status
}

@Composable
private fun runStatusColor(status: String) = when (status) {
    "running" -> CodegTheme.colors.accent
    "succeeded" -> CodegTheme.colors.success
    "failed" -> CodegTheme.colors.danger
    else -> CodegTheme.colors.textTertiary
}

/** Offset RFC3339 (…+08:00) throws in Instant.parse — fall back to the wire
 *  layer's tolerant parser, then the raw string (same chain as the task board). */
private fun relativeLabel(iso: String?): String? {
    val raw = iso ?: return null
    val instant = runCatching { Instant.parse(raw) }
        .recoverCatching { app.codeg.android.core.model.wire.Rfc3339.parse(raw) }
        .getOrNull() ?: return raw
    return android.text.format.DateUtils.getRelativeTimeSpanString(
        instant.toEpochMilli(),
        System.currentTimeMillis(),
        android.text.format.DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}
