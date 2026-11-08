package app.codeg.android.feature.handoff

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.codeg.android.R
import app.codeg.android.core.data.DispatchTargets
import app.codeg.android.core.data.HandoffSource
import app.codeg.android.core.designsystem.component.AgentChipRow
import app.codeg.android.core.designsystem.component.DeviceChipRow
import app.codeg.android.core.designsystem.component.OptionListSheet
import app.codeg.android.core.designsystem.component.PickerField
import app.codeg.android.core.designsystem.component.PickerOption
import app.codeg.android.core.designsystem.component.sheetContentScrollLock
import app.codeg.android.core.designsystem.theme.CodegTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.core.model.AgentType

/**
 * Handoff composer (batch v141): edit the generated package, pick relay mode
 * (A context / B git), pick the target device/project/agent, dispatch via the
 * shared pipeline. Shown from the source session's detail screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandoffSheet(
    source: HandoffSource,
    viewModel: HandoffViewModel,
    onClose: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var device by rememberSaveable { mutableStateOf<String?>(null) }
    var folderId by rememberSaveable { mutableStateOf<Int?>(null) }
    var agent by rememberSaveable { mutableStateOf<AgentType?>(null) }
    var folderSheet by rememberSaveable { mutableStateOf(false) }
    // 批v166: 模型/推理强度 rows (shared component) — a handoff task no longer
    // silently runs on the agent default model.
    val optionsState = remember { app.codeg.android.feature.dispatch.AgentOptionsRowsState() }

    androidx.compose.runtime.LaunchedEffect(source.conversationId) {
        viewModel.generate(source)
        viewModel.loadTarget(source.serverId)
        // rememberSaveable restores the picked device on rotation — re-running
        // this effect must not silently snap the selection back to the source.
        if (device == null) device = source.serverId
    }

    ModalBottomSheet(
        onDismissRequest = { viewModel.reset(); onClose() },
        // 批v151：拖拽关闭只认固定头（标题+来源行），滚动区全锁——列表顶下拉
        // 不再误关（根修见 SheetGestures）。
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
    ) {
        val pkg = ui.pkg
        if (pkg == null) {
            Column(Modifier.padding(24.dp)) { CircularProgressIndicator(Modifier.size(20.dp)) }
            return@ModalBottomSheet
        }
        Column(
            Modifier
                .fillMaxWidth()
                .sheetContentScrollLock()
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // dragHandle=null：标题+来源行是拖拽关闭区——标题条撑到 48dp
            // （第1轮可及性审查），内容仍垂直居中。
            Text(
                stringResource(R.string.handoff_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .wrapContentHeight(Alignment.CenterVertically),
            )
            Text(
                stringResource(
                    R.string.handoff_source_line,
                    source.serverName, source.folderName, source.agent.displayName,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = CodegTheme.colors.textSecondary,
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HandoffField(
                    label = stringResource(R.string.handoff_goal),
                    value = pkg.goal,
                    onChange = { v -> viewModel.update { it.copy(goal = v) } },
                )
                HandoffField(
                    label = stringResource(R.string.handoff_completed),
                    value = pkg.completed,
                    onChange = { v -> viewModel.update { it.copy(completed = v) } },
                )
                HandoffField(
                    label = stringResource(R.string.handoff_progress),
                    value = pkg.progress,
                    onChange = { v -> viewModel.update { it.copy(progress = v) } },
                )
                HandoffField(
                    label = stringResource(R.string.handoff_decisions),
                    value = pkg.decisions,
                    onChange = { v -> viewModel.update { it.copy(decisions = v) } },
                )
                HandoffField(
                    label = stringResource(R.string.handoff_known_issues),
                    value = pkg.knownIssues,
                    onChange = { v -> viewModel.update { it.copy(knownIssues = v) } },
                )
                HandoffField(
                    label = stringResource(R.string.handoff_next_steps),
                    value = pkg.nextSteps,
                    onChange = { v -> viewModel.update { it.copy(nextSteps = v) } },
                )

                // Relay mode: A = context only; B = same + a git precheck note. The
                // package never carries code — B only attaches an honest status line.
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = !ui.modeB,
                        onClick = { viewModel.setModeB(false) },
                        label = { Text(stringResource(R.string.handoff_mode_a)) },
                    )
                    FilterChip(
                        selected = ui.modeB,
                        onClick = { viewModel.setModeB(true) },
                        label = { Text(stringResource(R.string.handoff_mode_b)) },
                    )
                    if (ui.gitChecking) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }
                if (ui.modeB) {
                    val pc = ui.gitPrecheck
                    Text(
                        when {
                            pc == null -> stringResource(R.string.handoff_git_checking)
                            pc.error != null -> pc.error
                            else -> stringResource(R.string.handoff_git_result, pc.branch ?: "?", pc.dirtyCount) +
                                (pc.dirtyPreview.takeIf { it.isNotEmpty() }?.let { " ${it.joinToString("、")}" } ?: "")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = CodegTheme.colors.textSecondary,
                    )
                }

                val targets: DispatchTargets = ui.targets
                // 派发默认预选（设置→通用→派发默认选项）：sheet 打开时若未选设备，
                // 自动落到默认设备/项目/Agent。
                val settings by viewModel.settings.collectAsStateWithLifecycle()
                LaunchedEffect(targets.profiles) {
                    if (device == null && settings.dispatchDefaultDevice.isNotBlank() &&
                        targets.profiles.any { it.id == settings.dispatchDefaultDevice }
                    ) {
                        device = settings.dispatchDefaultDevice
                        folderId = null
                        agent = null
                        viewModel.loadTarget(settings.dispatchDefaultDevice)
                    }
                }
                LaunchedEffect(targets.folders, targets.agents) {
                    if (device == null) return@LaunchedEffect
                    if (folderId == null && settings.dispatchDefaultFolderId > 0 &&
                        targets.folders.any { it.id == settings.dispatchDefaultFolderId }
                    ) {
                        folderId = settings.dispatchDefaultFolderId
                    }
                    if (agent == null && settings.dispatchDefaultAgent.isNotBlank()) {
                        val wire = AgentType.fromWire(settings.dispatchDefaultAgent)
                        if (wire != AgentType.UNKNOWN && (targets.agents.isEmpty() || wire in targets.agents)) {
                            agent = wire
                        }
                    }
                }
                targets.error?.let {
                    Text(
                        it,
                        color = CodegTheme.colors.danger,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clickable { device?.let { d -> viewModel.loadTarget(d) } },
                    )
                }
                // §6.3: a dead agent list is a degraded state, not a dead panel —
                // short inline hint + tappable retry, raw ACP error stays in the log.
                if (targets.agentsError) {
                    Text(
                        stringResource(R.string.dispatch_agents_failed) + " · " +
                            stringResource(R.string.dispatch_retry),
                        color = CodegTheme.colors.danger,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clickable { device?.let { d -> viewModel.loadTarget(d) } },
                    )
                }
                // 统一选择器（批v150）：设备 chips + 项目 PickerField→OptionListSheet
                // + Agent chips，与共享派发表单同款交互；三列滚轮在此退役。
                DeviceChipRow(
                    devices = targets.profiles.map { PickerOption(id = it.id, label = it.name) },
                    selectedId = device,
                    loadingDeviceId = if (targets.foldersLoading) device else null,
                    errorDeviceIds = if (targets.error != null || targets.agentsError || targets.foldersError) {
                        setOfNotNull(device)
                    } else emptySet(),
                    onSelect = { id ->
                        if (id != device) {
                            device = id
                            folderId = null
                            agent = null
                            viewModel.loadTarget(id)
                        }
                    },
                )
                if (targets.profiles.isEmpty()) {
                    Text(
                        stringResource(R.string.dispatch_no_device),
                        style = MaterialTheme.typography.bodySmall,
                        color = CodegTheme.colors.textTertiary,
                    )
                }
                val autoAgentLabel = stringResource(R.string.dispatch_agent_auto)
                AgentChipRow(
                    autoLabel = autoAgentLabel,
                    agents = targets.agents.map { PickerOption(id = it.name, label = it.displayName) },
                    selectedId = agent?.name,
                    onSelect = { name -> agent = AgentType.entries.firstOrNull { it.name == name } },
                )
                PickerField(
                    icon = Icons.Rounded.Folder,
                    label = stringResource(R.string.dispatch_project),
                    value = targets.folders.firstOrNull { it.id == folderId }?.name,
                    placeholder = stringResource(
                        when {
                            targets.foldersLoading -> R.string.dispatch_defaults_loading
                            targets.foldersError -> R.string.dispatch_folders_failed
                            else -> R.string.dispatch_no_folder
                        },
                    ),
                    onClick = { if (targets.folders.isNotEmpty()) folderSheet = true },
                )
                // 批v166: probe the EFFECTIVE agent (explicit pick, else the
                // project default) — same rule as the dispatch form.
                app.codeg.android.feature.dispatch.AgentOptionsRows(
                    state = optionsState,
                    serverId = device,
                    agent = agent ?: targets.folders.firstOrNull { it.id == folderId }
                        ?.defaultAgentType?.takeIf { it != AgentType.UNKNOWN },
                    workingDir = targets.folders.firstOrNull { it.id == folderId }?.path,
                    probe = viewModel::probeAgentOptions,
                    providerModels = viewModel::modelProviderEntries,
                )
                ui.error?.let {
                    Text(it, color = CodegTheme.colors.danger, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(
                    onClick = {
                        val dev = device ?: return@TextButton
                        val fid = folderId ?: return@TextButton
                        viewModel.dispatch(
                            source, dev, fid,
                            targets.folders.firstOrNull { it.id == fid }?.name.orEmpty(),
                            agent,
                            configValues = optionsState.picksSnapshot(),
                        )
                    },
                    enabled = device != null && folderId != null && !ui.dispatching && ui.unknownRef == null &&
                        (pkg.goal.isNotBlank() || pkg.nextSteps.isNotBlank()),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (ui.dispatching) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(stringResource(R.string.handoff_dispatch))
                }
                Text(
                    stringResource(R.string.handoff_no_code_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = CodegTheme.colors.textSecondary,
                )

                if (folderSheet) {
                    OptionListSheet(
                        title = stringResource(R.string.dispatch_project),
                        options = targets.folders.map { PickerOption(id = it.id.toString(), label = it.name, note = it.path) },
                        selectedId = folderId?.toString(),
                        onSelect = { opt -> folderId = opt.id.toIntOrNull() },
                        onDismiss = { folderSheet = false },
                    )
                }
            }
        }
    }

    if (ui.createdRef != null) {
        val ref = ui.createdRef!!.ref
        AlertDialog(
            onDismissRequest = { viewModel.reset(); onClose() },
            title = { Text(stringResource(R.string.dispatch_ok, ref.remoteTodoId ?: 0)) },
            text = { Text("${ref.serverName} · ${ref.title}") },
            confirmButton = {
                TextButton(onClick = { viewModel.reset(); onClose() }) { Text(stringResource(android.R.string.ok)) }
            },
        )
    }
}

@Composable
private fun HandoffField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
}

