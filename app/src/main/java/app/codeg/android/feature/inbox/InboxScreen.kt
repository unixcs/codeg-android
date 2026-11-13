package app.codeg.android.feature.inbox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddTask
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.data.AttachmentStager
import app.codeg.android.core.data.DispatchTargets
import app.codeg.android.core.data.LocalAttachment
import app.codeg.android.core.datastore.DispatchRef
import app.codeg.android.core.datastore.DispatchState
import app.codeg.android.core.datastore.DraftEntry
import app.codeg.android.core.datastore.IdeaEntry
import app.codeg.android.core.datastore.INBOX_IDEA_STYLE_CENTER_WIDE
import app.codeg.android.core.designsystem.component.AgentChipRow
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Send
import app.codeg.android.core.designsystem.component.CodegAttachActions
import app.codeg.android.core.designsystem.component.CodegAttachmentRow
import app.codeg.android.core.designsystem.component.CodegFormTextField
import app.codeg.android.core.designsystem.component.DeviceChipRow
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.component.FullscreenTextEditorDialog
import app.codeg.android.core.designsystem.component.ImageViewerDialog
import app.codeg.android.core.designsystem.component.ImageViewerSource
import app.codeg.android.core.designsystem.component.LocalImageThumbnail
import app.codeg.android.core.designsystem.component.OptionListSheet
import app.codeg.android.core.designsystem.component.PickerField
import app.codeg.android.core.designsystem.component.PickerOption
import app.codeg.android.core.designsystem.component.PrimaryButton
import app.codeg.android.core.designsystem.component.rememberCameraCapture
import app.codeg.android.core.designsystem.component.SwipeActionBox
import app.codeg.android.core.designsystem.component.SwipeSpec
import app.codeg.android.core.designsystem.component.holdDialogWindowStill
import app.codeg.android.core.designsystem.component.rememberSheetDragLock
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.designsystem.theme.CardFontClamp
import app.codeg.android.core.model.AgentOptionsSnapshot
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ModelProviderInfo
import app.codeg.android.feature.dispatch.AgentOptionsRows
import app.codeg.android.feature.dispatch.AgentOptionsRowsState
import app.codeg.android.feature.quick.QuickSaveSheet
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The idea inbox + dispatch wizard (batch v140, rebuilt 批v146 阶段4+5). Ideas
 * are local drafts in a lightweight memo-style card (time + ⋯ menu + one 派发
 * action); dispatch runs the shared pipeline with the 任务书 §6.2 form order:
 * 设备与 Agent → 任务描述 → 目标（项目 + 分支） → 创建. An idea leaves the
 * inbox only when the server CONFIRMS the create; an UNKNOWN ref may only
 * re-dispatch after a probe verified "not created".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    viewModel: InboxViewModel = hiltViewModel(key = "inbox"),
    // 批v149 会话模式：派发确认后自动跳转会话（serverId 先落设备再推详情，
    // 会话详情读 selectedProfile 恰好一次，见 MainShell 的 onOpenSession 接线）。
    onOpenSession: (serverId: String, conversationId: Int) -> Unit = { _, _ -> },
    /** 批v153（UX 审查）：任务模式派发回执的「去任务板」直达。 */
    onOpenTasks: () -> Unit = {},
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    // Saveable (ids, not objects): a rotation must not close the editor/sheet —
    // the text inside is saveable too, and losing it mid-typing loses work.
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var dispatchingId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<IdeaEntry?>(null) }
    val editing = editingId?.let { id -> ui.ideas.firstOrNull { it.id == id } }
    val dispatching = dispatchingId?.let { id -> ui.ideas.firstOrNull { it.id == id } }
    // UNKNOWN cards: redispatch/discard are destructive or duplicating actions,
    // so they route through a confirmation dialog first.
    var confirmRef by remember { mutableStateOf<DispatchRef?>(null) }
    var confirmRedispatch by remember { mutableStateOf(false) }
    // Success dialog lives HERE, not in DispatchSheet: a confirmed dispatch
    // deletes the idea, which nulls [dispatching] and unmounts the whole
    // sheet — a dialog inside it would vanish before the user reads it.
    var dispatchResult by remember { mutableStateOf<DispatchRef?>(null) }
    // 批v147 收件箱净化：列表只放本地草稿。派发回执（在途/已确认）不再独占
    // 卡片刷屏；UNKNOWN 的恢复入口挪到对应草稿卡上，草稿已删的孤儿 UNKNOWN
    // 保留一张紧凑恢复卡——去重闸门必须始终可解析，不能变成看不见的账。
    val unresolvedRefs = remember(ui.inFlightRefs, ui.pendingRefs) { ui.inFlightRefs + ui.pendingRefs }
    val ideaIds = remember(ui.ideas) { ui.ideas.map { it.id }.toSet() }
    val refByIdeaId = remember(unresolvedRefs) {
        unresolvedRefs.filter { it.ideaId != null }.associateBy { it.ideaId!! }
    }
    val orphanRefs = remember(unresolvedRefs, ideaIds) {
        unresolvedRefs.filter { it.ideaId == null || it.ideaId !in ideaIds }
    }

    // 批v151: "new idea" button placement (设置→通用→外壳). "corner" = classic M3
    // FAB bottom-right; "centerWide" = half-screen-width pill bottom-center so
    // either thumb reaches it. Both open the same IdeaEditorDialog.
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val ideaStyle = settings.inboxNewIdeaStyle
    // 批v155 段2: the idea editor's 一键插待办 reuses the dispatch target surface
    // (device profiles + per-device folders/agents) and the model probe.
    val targets by viewModel.target.collectAsStateWithLifecycle()

    // 批v160 multi-select: ids only (saveable), cleared on exit; the destructive
    // confirm carries the snapshot so deleting can't race the selection.
    var selectMode by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var pendingBatchDelete by remember { mutableStateOf<List<String>?>(null) }

    // 批v162 反向快链：追加到已有会话。appendingId（saveable）只认想法，
    // appendTarget（对象）落在选择器选中之后——旋转丢 target 退回选择器，
    // 不丢"正在追加哪个想法"。
    var appendingId by rememberSaveable { mutableStateOf<String?>(null) }
    var appendTarget by remember { mutableStateOf<SessionPickerRow?>(null) }
    // 批v172 D7: 长按/⋯菜单的「保存为 Markdown」——同一卡片的 id（saveable），
    // 弹 QuickSaveSheet 时预填标题与正文。
    var quickSavingId by rememberSaveable { mutableStateOf<String?>(null) }
    val quickSaving = quickSavingId?.let { id -> ui.ideas.firstOrNull { it.id == id } }
    val appending = appendingId?.let { id -> ui.ideas.firstOrNull { it.id == id } }
    // 「已派进会话」小标的来源：已确认的会话型 DispatchRef（ideaId → 最近一笔）。
    val appendedSessionByIdeaId = remember(ui.confirmedRefs) {
        ui.confirmedRefs.filter { it.sessionMode && it.ideaId != null }.associateBy { it.ideaId!! }
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        floatingActionButton = {
            if (ideaStyle != INBOX_IDEA_STYLE_CENTER_WIDE && !selectMode) {
                FloatingActionButton(onClick = { creating = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.inbox_new))
                }
            }
        },
        bottomBar = {
            // Wide placement rides the bottomBar slot: Scaffold pads the list
            // content above it automatically, in both placements.
            if (ideaStyle == INBOX_IDEA_STYLE_CENTER_WIDE && !selectMode) {
                NewIdeaWideButton(onClick = { creating = true })
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            InboxToolbar(
                selectMode = selectMode,
                columns = settings.inboxColumns,
                selectedCount = selected.size,
                allSelected = ui.ideas.isNotEmpty() && ui.ideas.all { it.id in selected },
                hasIdeas = ui.ideas.isNotEmpty(),
                ideaCount = ui.ideas.size,
                onExitSelect = { selectMode = false; selected = emptySet() },
                onEnterSelect = { selectMode = true; selected = emptySet() },
                onToggleColumns = {
                    viewModel.setInboxColumns(if (settings.inboxColumns == 2) 1 else 2)
                },
                onToggleAll = {
                    selected = if (ui.ideas.isNotEmpty() && ui.ideas.all { it.id in selected }) {
                        emptySet()
                    } else {
                        ui.ideas.map { it.id }.toSet()
                    }
                },
                onDeleteSelected = { pendingBatchDelete = selected.toList() },
            )
            if (ui.ideas.isEmpty() && orphanRefs.isEmpty()) {
                EmptyState(
                    icon = Icons.Rounded.Add,
                    title = stringResource(R.string.inbox_empty),
                    message = "",
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            } else {
                // Shared per-idea card factory for both the single-column list and
                // the two-column grid (identical wiring, only the layout differs).
                val ideaCard: @Composable (IdeaEntry) -> Unit = { idea ->
                    val ref = refByIdeaId[idea.id]
                    val copyIdea: (Context) -> Unit = { ctx ->
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("idea", "${idea.title}\n${idea.description}"))
                    }
                    val context = LocalContext.current
                    // 批v171 N7: the idea card joins the session rows in the
                    // Gmail/Outlook swipe skeleton — the bindings mirror the
                    // long-press menu's handlers (派发/编辑/复制/追加/删除), the
                    // master switch (and select mode) unwraps to a plain Box.
                    @Composable
                    fun swipeSpec(actionId: String): SwipeSpec? = when (InboxSwipeAction.from(actionId)) {
                        null -> null
                        // unresolved（探测中/UNKNOWN）时与派发 pill 同门禁用。
                        InboxSwipeAction.DISPATCH -> if (ref != null) null else SwipeSpec(
                            label = stringResource(R.string.inbox_swipe_action_dispatch),
                            icon = Icons.Rounded.Send,
                            background = colors.accent,
                            fire = { dispatchingId = idea.id },
                        )
                        InboxSwipeAction.EDIT -> SwipeSpec(
                            label = stringResource(R.string.inbox_swipe_action_edit),
                            icon = Icons.Rounded.Edit,
                            background = colors.accent,
                            fire = { editingId = idea.id },
                        )
                        InboxSwipeAction.COPY -> SwipeSpec(
                            label = stringResource(R.string.inbox_swipe_action_copy),
                            icon = Icons.Rounded.ContentCopy,
                            background = colors.accent,
                            fire = { copyIdea(context) },
                        )
                        InboxSwipeAction.APPEND -> SwipeSpec(
                            label = stringResource(R.string.inbox_swipe_action_append),
                            icon = Icons.AutoMirrored.Rounded.CallMade,
                            background = colors.accent,
                            fire = { appendingId = idea.id },
                        )
                        // The swipe only opens the confirm dialog faster — same
                        // pending-state flow as the menu item.
                        InboxSwipeAction.DELETE -> SwipeSpec(
                            label = stringResource(R.string.common_delete),
                            icon = Icons.Outlined.DeleteOutline,
                            background = colors.danger,
                            // 批v171 R2: 浅色板 onAccent 是深字，压 danger 不达 AA；
                            // 白字在两板 danger 上都达标。
                            contentColor = if (colors.isDark) colors.onAccent else Color.White,
                            fire = { pendingDelete = idea },
                        )
                    }
                    val card: @Composable () -> Unit = {
                        IdeaCard(
                            idea = idea,
                            unresolved = ref,
                            probing = ref != null && ref.requestId in ui.probingIds,
                            canRedispatch = ref != null && ref.requestId in ui.probeClearedIds,
                            selectMode = selectMode,
                            selected = idea.id in selected,
                            onToggleSelect = {
                                selected = if (idea.id in selected) selected - idea.id else selected + idea.id
                            },
                            onEnterSelect = { selectMode = true; selected = setOf(idea.id) },
                            onDispatch = { dispatchingId = idea.id },
                            onEdit = { editingId = idea.id },
                            onCopy = copyIdea,
                            onDelete = { pendingDelete = idea },
                            // 批v162 反向快链入口（⋯菜单与长按菜单同一条 DropdownMenu）。
                            onAppendToSession = { appendingId = idea.id },
                            // 批v172 D7: 预填快速保存（标题=idea.title，正文=description）。
                            onSaveAsMarkdown = { quickSavingId = idea.id },
                            appendedToSession = idea.id in appendedSessionByIdeaId,
                            onProbe = { ref?.let { viewModel.probe(it.requestId) } },
                            onDiscard = { ref?.let { confirmRef = it; confirmRedispatch = false } },
                            onRedispatch = { ref?.let { confirmRef = it; confirmRedispatch = true } },
                        )
                    }
                    if (selectMode || !settings.inboxSwipeEnabled) {
                        card()
                    } else {
                        SwipeActionBox(
                            leftSpec = swipeSpec(settings.inboxSwipeLeftAction),
                            rightSpec = swipeSpec(settings.inboxSwipeRightAction),
                            cornerRadius = 16.dp,
                        ) {
                            card()
                        }
                    }
                }
                if (settings.inboxColumns == 2) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        state = rememberLazyGridState(),
                    ) {
                        // probe/redispatch failures must be visible with no sheet open.
                        ui.dispatchError?.takeIf { dispatching == null }?.let { error ->
                            item(key = "dispatch-error", span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    error,
                                    color = colors.danger,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        orphanRefs.forEach { ref ->
                            item(
                                key = "orphan-${ref.requestId}",
                                contentType = { "unknown-ref" },
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                UnknownRefCard(
                                    ref = ref,
                                    probing = ref.requestId in ui.probingIds,
                                    canRedispatch = ref.requestId in ui.probeClearedIds,
                                    onProbe = { viewModel.probe(ref.requestId) },
                                    onDiscard = { confirmRef = ref; confirmRedispatch = false },
                                    onRedispatch = { confirmRef = ref; confirmRedispatch = true },
                                )
                            }
                        }
                        gridItems(ui.ideas, key = { it.id }, contentType = { "idea" }) { idea ->
                            ideaCard(idea)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // probe/redispatch fire from the cards below with no sheet open —
                        // without this line their failures ("检查失败…勿直接重派") would
                        // be set on the VM but never rendered anywhere.
                        ui.dispatchError?.takeIf { dispatching == null }?.let { error ->
                            item(key = "dispatch-error") {
                                Text(
                                    error,
                                    color = colors.danger,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        orphanRefs.forEach { ref ->
                            item(key = "orphan-${ref.requestId}", contentType = { "unknown-ref" }) {
                                // 草稿已删（或旧版本记录没挂 ideaId）的 UNKNOWN 只能自立卡片：
                                // 去重闸门靠它保持可解析，绝不能无声消失。
                                UnknownRefCard(
                                    ref = ref,
                                    probing = ref.requestId in ui.probingIds,
                                    canRedispatch = ref.requestId in ui.probeClearedIds,
                                    onProbe = { viewModel.probe(ref.requestId) },
                                    onDiscard = { confirmRef = ref; confirmRedispatch = false },
                                    onRedispatch = { confirmRef = ref; confirmRedispatch = true },
                                )
                            }
                        }
                        items(ui.ideas, key = { it.id }, contentType = { "idea" }) { idea ->
                            ideaCard(idea)
                        }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        IdeaEditorDialog(
            initial = editing,
            stager = viewModel.attachmentStager,
            // 批v155 段2: 一键插待办 needs the ledger's in-flight flag (button
            // gating) and last error (inline in the insert section). The editor
            // stays open through Unknown/HardFailed — only a CONFIRMED onDone
            // closes it and shows the receipt dialog.
            dispatching = ui.dispatching,
            dispatchError = ui.dispatchError,
            targets = targets,
            loadTarget = viewModel::loadTarget,
            probeAgentOptions = viewModel::probeAgentOptions,
            modelProviderEntries = viewModel::modelProviderEntries,
            onDismiss = { creating = false; editingId = null },
            // 批v164: draft memory rides along only in new-idea mode — an
            // existing idea's text lives in its own row, not in a draft slot.
            loadDraft = if (editing == null) ({ viewModel.loadIdeaDraft() }) else null,
            saveDraft = if (editing == null) viewModel::saveIdeaDraft else null,
            onSave = { title, desc, attachments ->
                viewModel.saveIdea(editing?.id, title, desc, source = editing, attachments = attachments)
                creating = false
                editingId = null
            },
            onInsert = { payload ->
                viewModel.quickInsertTodo(
                    ideaId = payload.ideaId,
                    title = payload.title,
                    description = payload.description,
                    attachments = payload.attachments,
                    serverId = payload.serverId,
                    folderId = payload.folderId,
                    folderName = payload.folderName,
                    agentType = payload.agentType,
                    modeId = payload.modeId,
                    configValues = payload.configValues,
                    // 批v164 审查R1: only a NEW idea retires the composer draft.
                    clearComposerDraft = editing == null,
                    onDone = { ref ->
                        creating = false
                        editingId = null
                        dispatchResult = ref
                    },
                )
            },
        )
    }

    pendingDelete?.let { idea ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.inbox_delete)) },
            text = { Text(idea.title) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteIdea(idea.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.inbox_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }

    // 批v160 multi-select batch delete. The dialog carries the id snapshot:
    // exiting select mode clears the live set, the snapshot survives it.
    pendingBatchDelete?.let { ids ->
        AlertDialog(
            onDismissRequest = { pendingBatchDelete = null },
            title = { Text(stringResource(R.string.inbox_delete)) },
            text = { Text(stringResource(R.string.inbox_delete_selected_confirm, ids.size)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteIdeas(ids)
                    pendingBatchDelete = null
                    selectMode = false
                    selected = emptySet()
                }) { Text(stringResource(R.string.inbox_delete), color = colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { pendingBatchDelete = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }

    dispatching?.let { idea ->
        // Stale errors from a previous session must not greet the next dispatch.
        LaunchedEffect(idea.id) { viewModel.clearDispatchError() }
        DispatchSheet(
            idea = idea,
            viewModel = viewModel,
            onClose = { dispatchingId = null },
            // 批v149：会话模式确认后不弹回执框——直接关单跳会话（首条指令由
            // 会话详情页自动发送）；任务模式维持原回执对话框。
            onResult = { ref ->
                val convId = ref.remoteTodoId
                if (ref.sessionMode && ref.state == DispatchState.CONFIRMED && convId != null) {
                    dispatchingId = null
                    onOpenSession(ref.serverId, convId)
                } else {
                    dispatchResult = ref
                }
            },
        )
    }

    dispatchResult?.let { ref ->
        AlertDialog(
            onDismissRequest = { dispatchResult = null; dispatchingId = null },
            title = { Text(stringResource(R.string.dispatch_ok, ref.remoteTodoId ?: 0)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${ref.serverName} · ${ref.title}")
                    ui.dispatchError?.let { note ->
                        Text(note, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                    }
                }
            },
            confirmButton = {
                // 批v153（UX 审查）：回执直达任务板。R4 墨菲审查：能自动跳会话的
                // CONFIRMED（sessionMode + convId 非空）根本不会进本对话框，gate
                // 只看 state——会话模式 convId=null 的死路回执同样有去处。
                Row {
                    if (ref.state == DispatchState.CONFIRMED) {
                        TextButton(onClick = {
                            dispatchResult = null
                            dispatchingId = null
                            onOpenTasks()
                        }) {
                            Text(stringResource(R.string.receipt_open_task_board))
                        }
                    }
                    TextButton(onClick = { dispatchResult = null; dispatchingId = null }) {
                        Text(stringResource(android.R.string.ok))
                    }
                }
            },
        )
    }

    confirmRef?.let { ref ->
        AlertDialog(
            onDismissRequest = { confirmRef = null },
            title = { Text(stringResource(R.string.dispatch_unknown)) },
            text = {
                Text(
                    stringResource(
                        if (confirmRedispatch) R.string.dispatch_unknown_confirm_redispatch
                        else R.string.dispatch_unknown_confirm_discard,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (confirmRedispatch) viewModel.redispatch(ref, null)
                    else viewModel.discard(ref.requestId)
                    confirmRef = null
                }) { Text(stringResource(R.string.dispatch_unknown_confirm_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRef = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }

    // 批v162 反向快链状态机：选择器（挑目标会话）→ 预览确认（追加发送）→
    // 成功关双单并跳转目标会话；确认单的返回是回选择器，不是关整个流程。
    appending?.let { idea ->
        val target = appendTarget
        if (target == null) {
            SessionPickerSheet(
                viewModel = viewModel,
                onPick = { appendTarget = it },
                onDismiss = { appendingId = null },
            )
        } else {
            AppendToSessionSheet(
                idea = idea,
                target = target,
                viewModel = viewModel,
                onSent = { picked ->
                    appendTarget = null
                    appendingId = null
                    // 批v149 同款跳转：先落设备再推详情（MainShell 的 onOpenSession
                    // 接线里做 handoffTo 舞步），会话页 load 完自动发送追加的指令。
                    onOpenSession(picked.serverId, picked.conversationId)
                },
                onDismiss = { appendTarget = null },
            )
        }
    }

    // 批v172 D7: 快速保存宿主——想法卡长按/⋯菜单进来，标题与正文已预填。
    quickSaving?.let { idea ->
        QuickSaveSheet(
            initialTitle = idea.title,
            initialContent = idea.description,
            onDismiss = { quickSavingId = null },
        )
    }
}

/**
 * Memo-style idea card (任务书 §5.2): time top-left, ⋯ menu top-right
 * (编辑/复制/删除, delete confirmed by the caller), one prominent 派发 action
 * at the bottom. Description truncates at 3 lines.
 *
 * 批v147：派发回执不再独占列表卡片——本卡未决时（[unresolved] 非空）在
 * 描述与派发钮之间内联一行状态：PENDING 显示"提交中"，UNKNOWN 给出
 * 检查/重派/放弃入口；未决期间主派发钮禁用（去重闸门本来也会拦）。
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun IdeaCard(
    idea: IdeaEntry,
    unresolved: DispatchRef?,
    probing: Boolean,
    canRedispatch: Boolean,
    /** 批v160 multi-select: when on, whole-card taps toggle the checkbox and the
     *  ⋯ menu / dispatch action are suppressed. */
    selectMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    /** 批v160: the ⋯ menu's 多选 entry — enters select mode with this card picked. */
    onEnterSelect: () -> Unit,
    onDispatch: () -> Unit,
    onEdit: () -> Unit,
    onCopy: (Context) -> Unit,
    onDelete: () -> Unit,
    /** 批v162 反向快链：⋯菜单 / 长按菜单的「追加到已有会话」——打开目标选择器。 */
    onAppendToSession: () -> Unit,
    /** 批v172 D7: ⋯菜单 / 长按菜单的「保存为 Markdown」——预填打开快速保存。 */
    onSaveAsMarkdown: () -> Unit,
    /** 批v162: 这条想法已有确认入账的会话追加（读 DispatchRef）——出小标。 */
    appendedToSession: Boolean,
    onProbe: () -> Unit,
    onDiscard: () -> Unit,
    onRedispatch: () -> Unit,
) {
    // 批v171 N3e: card content ignores the user font-scale bump (see CardFontClamp).
    CardFontClamp {
    val colors = CodegTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.combinedClickable(
            onClick = { if (selectMode) onToggleSelect() },
            onLongClick = { if (!selectMode) menuOpen = true },
        ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.bgElevated),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectMode) {
                    Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
                }
                Text(
                    formatStamp(idea.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                if (!selectMode) {
                    Box {
                        IconButton(
                            onClick = { menuOpen = true },
                            // R3-A P2: the menu's only visible entry needs a name
                            // (TalkBack) and a thumb-sized hit area.
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                Icons.Rounded.MoreVert,
                                contentDescription = stringResource(R.string.card_more_actions),
                                tint = colors.textSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.inbox_edit)) },
                                onClick = { menuOpen = false; onEdit() },
                            )
                            val context = LocalContext.current
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.inbox_copy)) },
                                onClick = { menuOpen = false; onCopy(context) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.inbox_append_to_session)) },
                                onClick = { menuOpen = false; onAppendToSession() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.inbox_menu_save_md)) },
                                onClick = { menuOpen = false; onSaveAsMarkdown() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.inbox_menu_select)) },
                                onClick = { menuOpen = false; onEnterSelect() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.inbox_delete), color = colors.danger) },
                                onClick = { menuOpen = false; onDelete() },
                            )
                        }
                    }
                }
            }
            Text(
                idea.title.ifBlank { stringResource(R.string.inbox_untitled) },
                style = MaterialTheme.typography.titleSmall,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (idea.description.isNotBlank()) {
                Text(
                    idea.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // 批v154: attachments are invisible until the editor opens otherwise —
            // a small count keeps the card honest about what a dispatch will carry.
            // 批v155 段3: tapping it opens the idea's images in the viewer.
            // rememberSaveable: rotation no longer closes the viewer (R2D#4).
            var cardViewerIndex by rememberSaveable { mutableStateOf<Int?>(null) }
            if (idea.attachments.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .then(
                            if (idea.attachments.any { it.isImage }) {
                                Modifier.clickable { cardViewerIndex = 0 }
                            } else Modifier,
                        ),
                ) {
                    Icon(
                        Icons.Rounded.AttachFile,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.attach_count, idea.attachments.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary,
                    )
                }
            }
            cardViewerIndex?.let {
                ImageViewerDialog(
                    images = idea.attachments.filter { it.isImage }
                        .map { ImageViewerSource.File(it.localPath) },
                    onDismiss = { cardViewerIndex = null },
                )
            }
            // 批v162：已派进会话小标——这条想法的文字/附件确认追加进过某个会话
            //（DispatchRef CONFIRMED + sessionMode）。想法留在收件箱，标不挡派发。
            if (appendedToSession) {
                Text(
                    stringResource(R.string.inbox_appended_to_session),
                    style = MaterialTheme.typography.labelSmall,
                    // 克制化：状态小标退到中性色，accent 只留给本屏主 CTA。
                    color = colors.textTertiary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (unresolved != null) {
                val inFlight = unresolved.state == DispatchState.PENDING
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    if (probing) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(6.dp))
                    }
                    Text(
                        stringResource(if (inFlight) R.string.dispatch_pending_note else R.string.dispatch_unknown),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (inFlight) colors.textSecondary else colors.danger,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!inFlight) {
                        TextButton(onClick = onProbe, enabled = !probing, contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Text(stringResource(R.string.dispatch_unknown_check), style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(
                            onClick = onRedispatch,
                            enabled = !probing && canRedispatch,
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) {
                            Text(
                                stringResource(R.string.dispatch_redispatch),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (!probing && canRedispatch) colors.textPrimary else colors.textTertiary,
                            )
                        }
                        TextButton(onClick = onDiscard, enabled = !probing, contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Text(stringResource(R.string.dispatch_unknown_discard), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (!inFlight && !canRedispatch) {
                    Text(
                        stringResource(R.string.dispatch_unknown_need_probe),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            androidx.compose.foundation.layout.Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    // 克制化：派发是次级动作——透明底 + hairline 细边框（实心 accent
                    // 只留给编辑器「保存」），未决态文字仍用 textTertiary 表示禁用。
                    .border(0.75.dp, colors.hairline, RoundedCornerShape(8.dp))
                    .clickable(enabled = !selectMode && unresolved == null, onClick = onDispatch)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.inbox_dispatch),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (unresolved == null) colors.textPrimary else colors.textTertiary,
                )
            }
        }
    }
}
    }

/**
 * 批v160 inbox toolbar row. Normal mode: columns toggle (1⇄2) + enter-select on
 * the right. Select mode: the whole row becomes the batch toolbar — exit, 全选
 * toggle, "N selected" count and the destructive delete (confirm handled by the
 * caller's dialog).
 */
@Composable
private fun InboxToolbar(
    selectMode: Boolean,
    columns: Int,
    selectedCount: Int,
    allSelected: Boolean,
    hasIdeas: Boolean,
    ideaCount: Int,
    onExitSelect: () -> Unit,
    onEnterSelect: () -> Unit,
    onToggleColumns: () -> Unit,
    onToggleAll: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    val colors = CodegTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectMode) {
            IconButton(onClick = onExitSelect) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.common_close),
                    tint = colors.textSecondary,
                )
            }
            IconButton(onClick = onToggleAll, enabled = hasIdeas) {
                Icon(
                    Icons.Rounded.Checklist,
                    contentDescription = stringResource(
                        if (allSelected) R.string.inbox_select_none else R.string.inbox_select_all,
                    ),
                    tint = if (hasIdeas) colors.textSecondary else colors.textTertiary,
                )
            }
            Text(
                stringResource(R.string.inbox_selected_count, selectedCount),
                style = MaterialTheme.typography.labelLarge,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDeleteSelected, enabled = selectedCount > 0) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.inbox_delete),
                    tint = if (selectedCount > 0) colors.danger else colors.textTertiary,
                )
            }
        } else {
            // 批v171 N1：非选择模式这行左侧原本全空（设备条下一条 48dp 空带），
            // 填上想法计数——信息行代替留白（Keep/Telegram 顶栏惯例）。
            Text(
                stringResource(R.string.inbox_count, ideaCount),
                style = MaterialTheme.typography.labelLarge,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            IconButton(onClick = onToggleColumns) {
                Icon(
                    if (columns == 2) Icons.Rounded.ViewAgenda else Icons.Rounded.GridView,
                    contentDescription = stringResource(
                        if (columns == 2) R.string.inbox_columns_one else R.string.inbox_columns_two,
                    ),
                    tint = colors.textSecondary,
                )
            }
            IconButton(onClick = onEnterSelect, enabled = hasIdeas) {
                Icon(
                    Icons.Rounded.Checklist,
                    contentDescription = stringResource(R.string.inbox_menu_select),
                    tint = if (hasIdeas) colors.textSecondary else colors.textTertiary,
                )
            }
        }
    }
}

/**
 * UNKNOWN dispatch card: 检查服务器 always available; 重新派发 unlocks ONLY
 * after a probe verified "not created" (任务书 §5.4 未核实不能再次创建).
 */
@Composable
private fun UnknownRefCard(
    ref: DispatchRef,
    probing: Boolean,
    canRedispatch: Boolean,
    onProbe: () -> Unit,
    onDiscard: () -> Unit,
    onRedispatch: () -> Unit,
) {
    val colors = CodegTheme.colors
    // PENDING 的 ref 只是路过 UNKNOWN 视图（第1轮派发审查修正）：动作全禁 +
    // 标题换成"派发中"口径，镜像 IdeaCard 的 inFlight 门——服务端确认落地前
    // 重派会双建、放弃会丢真任务。
    val inFlight = ref.state == DispatchState.PENDING
    // 批v147 视觉规范：拒绝红底大卡，孤儿 UNKNOWN 也只允许柔和底色 + 红字标题。
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.bgElevated),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                stringResource(if (inFlight) R.string.dispatch_pending_note else R.string.dispatch_unknown),
                style = MaterialTheme.typography.titleSmall,
                color = if (inFlight) colors.textSecondary else colors.danger,
            )
            Text(
                "${ref.title} · ${ref.serverName.ifBlank { ref.serverId }} · ${ref.folderName.ifBlank { "#${ref.folderId}" }}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (probing || inFlight) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(6.dp))
                }
                if (!inFlight) {
                    TextButton(onClick = onProbe, enabled = !probing, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(stringResource(R.string.dispatch_unknown_check), style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(
                        onClick = onRedispatch,
                        enabled = !probing && canRedispatch,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(
                            stringResource(R.string.dispatch_redispatch),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (!probing && canRedispatch) colors.textPrimary else colors.textTertiary,
                        )
                    }
                    TextButton(onClick = onDiscard, enabled = !probing, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(stringResource(R.string.dispatch_unknown_discard), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (!inFlight && !canRedispatch) {
                Text(
                    stringResource(R.string.dispatch_unknown_need_probe),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                )
            }
        }
    }
}

@Composable
private fun IdeaEditorDialog(
    initial: IdeaEntry?,
    stager: AttachmentStager,
    /** 批v155 段2: ledger in-flight flag — gates the insert submit button. */
    dispatching: Boolean,
    /** Last dispatch error, shown inline in the insert section (the section is
     *  the only surface that can explain a failed insert while this dialog is up). */
    dispatchError: String?,
    targets: DispatchTargets,
    loadTarget: (String) -> Unit,
    probeAgentOptions: suspend (String, AgentType, String?, Map<String, String>) -> AgentOptionsSnapshot?,
    /** 批v166: server truth for the 模型 row's provider merge (DispatchForm M1). */
    modelProviderEntries: suspend (String, AgentType) -> List<ModelProviderInfo>,
    /** 批v164: 新想法 draft memory. Null in edit mode — an existing idea's text
     *  lives in its own row, so there is no draft to restore or persist. */
    loadDraft: (suspend () -> DraftEntry)?,
    saveDraft: ((String, String) -> Unit)?,
    onDismiss: () -> Unit,
    onSave: (String, String, List<LocalAttachment>) -> Unit,
    /** 批v155 段2: 一键插待办 — create the work task directly (待办, no auto
     *  start). The caller owns closing the dialog via onDone, which fires on
     *  CONFIRMED only. */
    onInsert: (IdeaInsertPayload) -> Unit,
) {
    var title by rememberSaveable(initial?.id ?: "new") { mutableStateOf(initial?.title.orEmpty()) }
    var description by rememberSaveable(initial?.id ?: "new") { mutableStateOf(initial?.description.orEmpty()) }
    // 批v164: half-typed 新想法 survives close/reopen — same recipe as the
    // dispatch form (DispatchForm.kt): restore once from DraftStore, then
    // debounce-persist every change. The VM clears the slot only after the
    // idea is durably saved, so a failed save keeps the user's text.
    var draftRestored by rememberSaveable { mutableStateOf(false) }
    if (initial == null && loadDraft != null) {
        LaunchedEffect(Unit) {
            // 批v164 审查R4: a failed draft READ must only downgrade this
            // session to no-prefill — returning before draftRestored would
            // leave the saver permanently off, silently dropping everything
            // the user types from then on.
            val stored = runCatching { loadDraft() }.getOrNull()
            if (stored != null) {
                if (title.isBlank()) title = stored.title
                if (description.isBlank()) description = stored.text
            }
            draftRestored = true
        }
        LaunchedEffect(draftRestored) {
            val persist = saveDraft ?: return@LaunchedEffect
            if (!draftRestored) return@LaunchedEffect
            snapshotFlow { title to description }
                .distinctUntilChanged()
                .collectLatest { (t, d) ->
                    kotlinx.coroutines.delay(500)
                    persist(t, d)
                }
        }
    }
    // 批v154: attachments live in app-private storage; the editor owns only the
    // list. Copies staged HERE are deleted on cancel — the idea's own are not.
    val attachments = remember {
        androidx.compose.runtime.mutableStateListOf<LocalAttachment>().apply {
            addAll(initial?.attachments.orEmpty())
        }
    }
    val stagedOwners = remember { mutableSetOf<String>() }
    var staging by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun stage(uris: List<android.net.Uri>, images: Boolean) {
        if (uris.isEmpty()) return
        staging = true
        scope.launch {
            val staged = uris.mapNotNull { uri -> stager.stage(uri, null, null, images) }
            val room = (MAX_IDEA_ATTACHMENTS - attachments.size).coerceAtLeast(0)
            val accepted = staged.take(room)
            staged.drop(room).forEach(stager::discard)
            attachments.addAll(accepted)
            accepted.forEach { stagedOwners += it.id }
            staging = false
        }
    }
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_IDEA_ATTACHMENTS),
    ) { uris -> stage(uris, images = true) }
    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> stage(uris, images = false) }
    // 批v178: the camera pill stages like picked images (same base64 chain).
    val cameraCapture = rememberCameraCapture { uri -> stage(listOf(uri), images = true) }

    fun close() {
        // Cancel drops only what this dialog staged — idea copies stay with the row.
        attachments.filter { it.id in stagedOwners }.forEach(stager::discard)
        onDismiss()
    }

    // 批v154: this used to be an AlertDialog. Its window pans under the IME, so
    // the description field (3 lines, near the bottom) went behind the keyboard
    // and the card could not scroll. Same recipe as the composer: a full-width
    // bottom sheet-like Dialog that DOESN'T move its window (ADJUST_NOTHING),
    // sized by imePadding alone, with a scroll container and a near-fullscreen
    // editor for long descriptions.
    Dialog(
        onDismissRequest = { close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        holdDialogWindowStill()
        val colors = CodegTheme.colors
        Box(
            Modifier
                .fillMaxSize()
                .imePadding(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            // R3-A P3: the handle used to be a lie — a static drawing on a Dialog.
            // Vertical drag now really dismisses: pull past ~96dp and the sheet
            // closes, release short and it springs back. Confined to the handle
            // so it never fights the scrolling form below.
            val dragY = remember { Animatable(0f) }
            val dismissPx = with(LocalDensity.current) { 96.dp.toPx() }
            // 批v171 N1：0.94 固定高让「新想法」下半屏全空（内容只有两个输入框
            // +附件区）；改内容自适应、仍以 0.94 封顶，描述框 minLines 6 撑住
            // 编辑空间——sheet 高度跟着内容走。
            Surface(
                color = colors.bgElevated,
                shape = RoundedCornerShape(
                    topStart = CodegTheme.dimens.radiusLg,
                    topEnd = CodegTheme.dimens.radiusLg,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.94f).dp)
                    .offset { IntOffset(0, dragY.value.roundToInt()) },
            ) {
                Column(Modifier.fillMaxWidth()) {
                    // W10: the bottom-sheet affordance (drag handle) + header — the
                    // handle reads as "this is a sheet" at a glance.
                    Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .size(width = 36.dp, height = 4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(colors.surfaceStroke)
                                // Touch target beyond the 4dp sliver; the drag lives
                                // here only — the form's scroll below is untouched.
                                .padding(horizontal = 24.dp, vertical = 14.dp)
                                .pointerInput(Unit) {
                                    detectVerticalDragGestures(
                                        onVerticalDrag = { change, dy ->
                                            change.consume()
                                            scope.launch { dragY.snapTo((dragY.value + dy).coerceAtLeast(0f)) }
                                        },
                                        onDragEnd = {
                                            scope.launch {
                                                if (dragY.value >= dismissPx) close()
                                                else dragY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                                            }
                                        },
                                        onDragCancel = {
                                            scope.launch { dragY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                                        },
                                    )
                                },
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(if (initial == null) R.string.inbox_new else R.string.inbox_edit),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { close() }) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.common_close),
                                tint = colors.textSecondary,
                            )
                        }
                    }
                    Column(
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp)
                            .padding(top = 4.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CodegFormTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = stringResource(R.string.inbox_title_hint),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        CodegFormTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = stringResource(R.string.inbox_desc_hint),
                            minLines = 6,
                            maxLines = 12,
                            trailingIcon = {
                                IconButton(onClick = { fullscreen = true }) {
                                    Icon(
                                        Icons.Rounded.OpenInFull,
                                        contentDescription = stringResource(R.string.compose_edit_fullscreen),
                                        tint = colors.textSecondary,
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        AttachmentChips(
                            attachments = attachments,
                            staging = staging,
                            onRemove = { att ->
                                attachments.remove(att)
                                if (att.id in stagedOwners) {
                                    stagedOwners -= att.id
                                    stager.discard(att)
                                }
                            },
                            onPickImages = {
                                pickImages.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            onPickFiles = { pickFiles.launch(arrayOf("*/*")) },
                            onCameraCapture = cameraCapture,
                        )
                        // 批v155 段2: 一键插待办 — skip the save→dispatch two-step.
                        // The content gate mirrors the save button: an empty idea
                        // has nothing to insert. R3 P2-1: the inserted TASK must
                        // also carry a prompt — validate_draft rejects an empty
                        // one, and attachments-only with no title would dead-end
                        // on the derived title (it comes from the description).
                        InsertTodoSection(
                            targets = targets,
                            dispatching = dispatching,
                            dispatchError = dispatchError,
                            contentReady = (description.isNotBlank() || attachments.isNotEmpty()) &&
                                (title.isNotBlank() || description.isNotBlank()),
                            title = title,
                            description = description,
                            attachments = attachments.toList(),
                            // Editing a draft reuses its row; a new idea gets a
                            // fresh ledger binding (R1A #5).
                            seedIdeaId = initial?.id,
                            loadTarget = loadTarget,
                            probeAgentOptions = probeAgentOptions,
                            modelProviderEntries = modelProviderEntries,
                            onInsert = { payload ->
                                // Ownership of locally staged copies moves to the
                                // dispatch pipeline (same rule as 保存): a confirmed
                                // create discards them with the draft; cancel after a
                                // failed insert must not delete files the upserted
                                // idea still references. (If the VM's upsert itself
                                // throws, the staged copies leak — accepted: a
                                // DataStore IO failure is the rare double-failure
                                // path, and discarding here could instead delete
                                // files an intact original draft still points at.)
                                stagedOwners.clear()
                                onInsert(payload)
                            },
                        )
                    }
                    // W10 footer: a hairline above, cancel as a ghost, 保存 as the
                    // accent action — the old twin-TextButton footer read as two
                    // equally-weighted escape hatches.
                    androidx.compose.material3.HorizontalDivider(
                        thickness = androidx.compose.ui.unit.Dp.Hairline,
                        color = colors.surfaceStroke,
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(colors.bgElevated)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Spacer keeps 保存/cancel right-aligned; no hint text here —
                        // the save gate is self-evident (title/description/attachment).
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = { close() },
                            // R3-A: parity with the 44dp 保存 beside it — twin
                            // footer actions at different heights read as broken.
                            modifier = Modifier.heightIn(min = 44.dp),
                        ) { Text(stringResource(android.R.string.cancel)) }
                        Button(
                            onClick = {
                                // Ownership moves to the idea row.
                                stagedOwners.clear()
                                onSave(title, description, attachments.toList())
                            },
                            enabled = !staging && (title.isNotBlank() || description.isNotBlank() || attachments.isNotEmpty()),
                            shape = RoundedCornerShape(CodegTheme.dimens.radiusMd),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.accent,
                                contentColor = colors.onAccent,
                            ),
                            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp),
                            modifier = Modifier.heightIn(min = 44.dp),
                        ) {
                            Text(
                                stringResource(R.string.common_save),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }

    if (fullscreen) {
        FullscreenTextEditorDialog(
            text = description,
            onTextChange = { description = it },
            onDismiss = { fullscreen = false },
            label = stringResource(R.string.inbox_desc_hint),
        )
    }
}

/** Everything the idea editor's 一键插待办 resolved — mapped 1:1 onto
 *  [InboxViewModel.quickInsertTodo] by the screen. */
private data class IdeaInsertPayload(
    val ideaId: String,
    val title: String,
    val description: String,
    val attachments: List<LocalAttachment>,
    val serverId: String,
    val folderId: Int,
    val folderName: String,
    val agentType: AgentType?,
    val modeId: String?,
    /** 批v166: 模型/推理强度 picks (config_values) — the old form had no way
     *  to pick a model, and the row it did have sent permission modes. */
    val configValues: Map<String, String>,
)

/**
 * 批v155 段2: the idea editor's 一键插待办. Collapsed it is one entry row;
 * expanded it reveals the dispatch form's target selectors — 设备 chips →
 * Agent chips → 项目 PickerField → 权限模式/模型/推理强度 rows (批v166: the
 * shared [AgentOptionsRows], same probe/merge contract as
 * [app.codeg.android.feature.dispatch.DispatchForm]) — and a submit button.
 * The task is created in 待办 (autoStart=false); the local
 * draft is upserted first by the VM so an UNKNOWN ledger ref always binds to
 * a persisted idea.
 *
 * State lives here (not saveable hoisted): the section dies with the dialog,
 * and selections lost on collapse/expand are re-picked in one tap. The idea id
 * reuses the draft's own id when editing one (R1A: a fresh UUID here would
 * upsert a SECOND draft row — and a CONFIRMED create then discards the shared
 * attachment directory out from under the original card); fresh per dialog
 * instance only for new ideas, where the ledger binds UNKNOWN refs to it at
 * submit time.
 */
@Composable
private fun InsertTodoSection(
    targets: DispatchTargets,
    dispatching: Boolean,
    dispatchError: String?,
    /** Insert gate (R3 P2-1): a prompt exists (description or attachments) AND
     *  a title that survives (given, or derivable from the description) —
     *  stricter than the save button, which only stores a local draft. */
    contentReady: Boolean,
    /** Editor content, carried verbatim into the payload (the VM derives a
     *  blank title from the description and upserts the draft before sending). */
    title: String,
    description: String,
    attachments: List<LocalAttachment>,
    /** The edited draft's id, or null for a brand-new idea. */
    seedIdeaId: String?,
    loadTarget: (String) -> Unit,
    probeAgentOptions: suspend (String, AgentType, String?, Map<String, String>) -> AgentOptionsSnapshot?,
    modelProviderEntries: suspend (String, AgentType) -> List<ModelProviderInfo>,
    onInsert: (IdeaInsertPayload) -> Unit,
) {
    val colors = CodegTheme.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    var device by rememberSaveable { mutableStateOf<String?>(null) }
    var folderId by rememberSaveable { mutableStateOf<Int?>(null) }
    var agent by rememberSaveable { mutableStateOf<AgentType?>(null) }
    // 批v166: 权限模式 + 模型 + 推理强度 ride the shared rows component —
    // same probe/merge/re-probe contract as DispatchForm (the old inline row
    // mislabeled the permission-mode list as 模型 and hid entirely under 自动).
    val optionsState = remember { AgentOptionsRowsState() }
    var folderSheet by remember { mutableStateOf(false) }
    val ideaId = remember { seedIdeaId ?: java.util.UUID.randomUUID().toString() }

    val folder = targets.folders.firstOrNull { it.id == folderId }

    // Process-death restore: saved ids come back but the loaded lists died —
    // re-request once (same rule as DispatchForm).
    LaunchedEffect(device) {
        val d = device ?: return@LaunchedEffect
        if (targets.folders.isEmpty() && !targets.foldersLoading && targets.error == null) {
            loadTarget(d)
        }
    }
    LaunchedEffect(targets.agents) {
        val a = agent
        if (a != null && targets.agents.isNotEmpty() && a !in targets.agents) agent = null
    }
    LaunchedEffect(targets.folders) {
        val fid = folderId
        if (fid != null && targets.folders.isNotEmpty() && targets.folders.none { it.id == fid }) {
            folderId = null
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(CodegTheme.dimens.radiusMd))
                .background(colors.bgElevated, RoundedCornerShape(CodegTheme.dimens.radiusMd))
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.AddTask,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.inbox_insert_toggle),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = colors.textTertiary,
            )
        }
        if (expanded) {
            Text(
                stringResource(R.string.inbox_insert_hint),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
            )
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
                        loadTarget(id)
                    }
                },
            )
            if (targets.foldersError || targets.error != null || targets.agentsError) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        targets.error
                            ?: stringResource(
                                if (targets.foldersError) R.string.dispatch_folders_failed
                                else R.string.dispatch_agents_failed,
                            ),
                        color = colors.danger,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { device?.let(loadTarget) }) {
                        Text(stringResource(R.string.dispatch_retry))
                    }
                }
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
                value = folder?.name,
                placeholder = stringResource(
                    when {
                        targets.foldersLoading -> R.string.dispatch_defaults_loading
                        targets.foldersError -> R.string.dispatch_folders_failed
                        device == null -> R.string.dispatch_no_device
                        else -> R.string.dispatch_no_folder
                    },
                ),
                onClick = { folderSheet = true },
                enabled = targets.folders.isNotEmpty(),
            )
            // 批v166: 权限模式 + 模型 + 推理强度 — the shared rows. Probe the
            // EFFECTIVE agent (explicit pick, else the project default) exactly
            // like DispatchForm, so 自动 with a project default still offers a
            // model; without one the row explains itself instead of hiding.
            AgentOptionsRows(
                state = optionsState,
                serverId = device,
                agent = agent ?: folder?.defaultAgentType?.takeIf { it != AgentType.UNKNOWN },
                workingDir = folder?.path,
                probe = probeAgentOptions,
                providerModels = modelProviderEntries,
                showPermissionMode = true,
            )
            dispatchError?.let { error ->
                Text(error, color = colors.danger, style = MaterialTheme.typography.bodySmall)
            }
            PrimaryButton(
                text = stringResource(R.string.inbox_insert_submit),
                onClick = {
                    val d = device
                    val f = folder
                    if (d != null && f != null) {
                        onInsert(
                            IdeaInsertPayload(
                                ideaId = ideaId,
                                title = title,
                                description = description,
                                attachments = attachments,
                                serverId = d,
                                folderId = f.id,
                                folderName = f.name,
                                agentType = agent,
                                modeId = optionsState.modeId,
                                configValues = optionsState.picksSnapshot(),
                            ),
                        )
                    }
                },
                enabled = contentReady && !dispatching && device != null && folder != null,
                loading = dispatching,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (folderSheet) {
        OptionListSheet(
            title = stringResource(R.string.dispatch_project),
            options = targets.folders.map { PickerOption(id = it.id.toString(), label = it.name, note = it.path) },
            selectedId = folderId?.toString(),
            onSelect = { option -> folderId = option.id.toIntOrNull() },
            onDismiss = { folderSheet = false },
        )
    }
}

/** Attachment rows + the two pickers inside the idea editor.
 *  批v155 段3: image rows show a small thumbnail and open the fullscreen
 *  viewer across the idea's images. */
@Composable
private fun AttachmentChips(
    attachments: List<LocalAttachment>,
    staging: Boolean,
    onRemove: (LocalAttachment) -> Unit,
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
    onCameraCapture: () -> Unit,
) {
    val colors = CodegTheme.colors
    // rememberSaveable: rotation no longer closes the viewer (R2D#4).
    var viewerIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    if (staging) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.common_loading),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
            )
        }
    }
    // W10: the shared attachment card row (44dp card, thumbnail, hairline) —
    // image rows still open the fullscreen viewer across the idea's images.
    if (attachments.isNotEmpty()) {
        androidx.compose.foundation.layout.Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            attachments.forEach { att ->
                CodegAttachmentRow(
                    attachment = att,
                    onRemove = onRemove,
                    onClick = if (att.isImage) {
                        {
                            viewerIndex = attachments.filter { it.isImage }
                                .indexOfFirst { it.id == att.id }
                                .takeIf { idx -> idx >= 0 }
                        }
                    } else null,
                )
            }
        }
    }
    CodegAttachActions(
        pickImagesLabel = stringResource(R.string.attach_add_image),
        pickCameraLabel = stringResource(R.string.compose_attach_camera),
        pickFilesLabel = stringResource(R.string.attach_add_file),
        enabled = !staging && attachments.size < MAX_IDEA_ATTACHMENTS,
        onPickImages = onPickImages,
        onCamera = onCameraCapture,
        onPickFiles = onPickFiles,
    )
    viewerIndex?.let { idx ->
        ImageViewerDialog(
            images = attachments.filter { it.isImage }.map { ImageViewerSource.File(it.localPath) },
            initialIndex = idx,
            onDismiss = { viewerIndex = null },
        )
    }
}

/** Cap per idea / per dispatch — 批v155 raised 10 → 20 (shared with DispatchForm). */
private val MAX_IDEA_ATTACHMENTS = app.codeg.android.core.data.AttachmentLimits.MAX_IMAGES

/**
 * The unified dispatch panel — now a THIN host (批v150 §1.5 宿主 A): the shared
 * [app.codeg.android.feature.dispatch.DispatchForm] carries the whole flow
 * (执行方式 → 设备/Agent chips → 项目/分支 → 标题/描述 → 定时 → 摘要 → 主按钮,
 * selectors = chips + BottomSheet) and reads its state through this screen's
 * [InboxViewModel] (the v146 ledger invariants live there, unchanged). The
 * sheet contributes the fixed title (drag-to-close area, v151 contract) and
 * the idea prefill.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DispatchSheet(
    idea: IdeaEntry,
    viewModel: InboxViewModel,
    onClose: () -> Unit,
    onResult: (DispatchRef) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        // 批v151 弹层契约：内容双向剩余全被锁吃掉，上下滚动两向都不再误触收起；
        // 拖拽关闭只走固定标题区（DispatchForm 自带内部滚动，标题在其外）。
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .nestedScroll(rememberSheetDragLock()),
        ) {
            // Fixed header outside DispatchForm's own scroll: the title row stays
            // the drag-to-close area (v151 sheet contract), same as QuickTaskSheet.
            // dragHandle=null → 标题条撑到 48dp（第1轮可及性审查）。
            Text(
                stringResource(R.string.inbox_dispatch),
                style = MaterialTheme.typography.titleMedium,
                color = CodegTheme.colors.textPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .wrapContentHeight(Alignment.CenterVertically)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
            app.codeg.android.feature.dispatch.DispatchForm(
                host = viewModel,
                defaultSessionMode = true,
                ideaId = idea.id,
                initialTitle = idea.title,
                initialDescription = idea.description,
                initialAttachments = idea.attachments,
                onResult = onResult,
            )
        }
    }
}

private fun formatStamp(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

/**
 * 批v151 "底部居中" new-idea placement: half the screen wide, the FAB's 56dp
 * height — an alternative to the corner FAB for either-thumb reach. 克制化：
 * 不再是 accent 实心块，改为 surfaceStroke 细边框 + 浅灰底的胶囊，文字与
 * 图标用 textPrimary（本屏唯一实心 accent CTA 是编辑器的「保存」）。
 */
@Composable
private fun NewIdeaWideButton(onClick: () -> Unit) {
    val colors = CodegTheme.colors
    val capsule = RoundedCornerShape(percent = 50)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(top = 8.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .height(56.dp)
                .clip(capsule)
                .background(colors.textPrimary.copy(alpha = 0.06f))
                .border(0.75.dp, colors.surfaceStroke, capsule)
                .clickable(onClickLabel = stringResource(R.string.inbox_new), onClick = onClick),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = null,
                tint = colors.textPrimary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.inbox_new),
                color = colors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
