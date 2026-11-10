package app.codeg.android.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.data.DeviceLoad
import app.codeg.android.core.data.DeviceLoadStatus
import app.codeg.android.core.datastore.SessionRef
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.component.InlineError
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.feature.sessions.AddToProjectSheet
import app.codeg.android.feature.sessions.SessionDeleteDialog
import app.codeg.android.feature.sessions.SessionRenameDialog
import app.codeg.android.feature.sessions.SessionRow
import app.codeg.android.feature.sessions.SessionSwipeAction
import app.codeg.android.feature.sessions.rememberBoardTrackMenu

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onOpenConversation: (Int) -> Unit,
    // 批v151 All mode: the device bar's 全部 chip enters the aggregate fan-out.
    // Rows render merged hits with their owning device; a row open rides the
    // owning-device handoff, exactly like the Chats aggregate.
    // 批v174 P3: the merged rows' actions are no longer withheld — every callback
    // routes to the row's OWNING device via the VM's At() trio.
    allMode: Boolean = false,
    onOpenAggregateRow: (serverId: String, conversationId: Int) -> Unit = { _, id -> onOpenConversation(id) },
    /** 批v168: swipe bindings from Settings, already resolved to null ("off").
     *  Each direction resolves against the row's own callbacks (all present
     *  since 批v174 P3), so the settings bindings act on both modes. */
    swipeLeft: SessionSwipeAction? = null,
    swipeRight: SessionSwipeAction? = null,
    // 批v174 P3: the row menu now carries the full 7-item catalog like the Chats
    // rows — 「添加到会话」 hands the reference to a NEW task on the owning device
    // (same single-flight dance as the Chats aggregate), and the project sheet's
    // 管理 jump routes to the real destination.
    onHandoff: (serverId: String, folderId: Int, reference: String) -> Unit = { _, _, _ -> },
    onManageProjects: () -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    // 批v174 P3: project passthroughs for the 添加到项目 sheet.
    val projects by viewModel.sessionProjects.collectAsStateWithLifecycle()
    val members by viewModel.projectMembers.collectAsStateWithLifecycle()
    // Keyed on the owning device: a floating-ball device switch while a dialog is
    // open must discard it — the held id is server-local and would hit the wrong device.
    // 批v174 P3: the held pair carries its own serverId now, so both modes' dialogs
    // route through the same At() actions; the remember key stays as a belt-and-braces
    // discard on a device switch.
    var pendingDelete by remember(ui.serverId) { mutableStateOf<Pair<String, ConversationSummary>?>(null) }
    var pendingRename by remember(ui.serverId) { mutableStateOf<Pair<String, ConversationSummary>?>(null) }
    // 批v174 P3: 添加到项目 target — (owning device, conversation), same shape
    // as the Chats screen's.
    var addToTarget by remember(ui.serverId) { mutableStateOf<Pair<String, ConversationSummary>?>(null) }
    // 批v162 P1: 「加入任务板」 from a search hit — the fastest path for an old
    // session the user just found by name.
    val boardMenu = rememberBoardTrackMenu()
    val colors = CodegTheme.colors

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        // Like every other tab, a transparent TopAppBar — it carries the title. On tab
        // routes the app-global device bar (MainShell) owns the status-bar inset, so
        // the bar here zeroes its own insets to keep the top gap single-height.
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_search)) },
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = colors.textPrimary,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Search field — a native Material text field with an IME "Search"
            // action, a clear button, and an inline progress spinner.
            TextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.search_placeholder)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    when {
                        ui.searching -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accent)
                        query.isNotEmpty() -> IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.search_clear))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.submit() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.codeSurface,
                    unfocusedContainerColor = colors.codeSurface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = colors.accent,
                    focusedLeadingIconColor = colors.textSecondary,
                    unfocusedLeadingIconColor = colors.textTertiary,
                    focusedTrailingIconColor = colors.textSecondary,
                    unfocusedTrailingIconColor = colors.textTertiary,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    focusedPlaceholderColor = colors.textTertiary,
                    unfocusedPlaceholderColor = colors.textTertiary,
                ),
            )

            Box(Modifier.fillMaxSize()) {
                when {
                    query.isBlank() && recent.isNotEmpty() -> RecentList(recent, viewModel::useRecent, viewModel::clearRecent)
                    query.isBlank() ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            EmptyState(Icons.Rounded.Search, stringResource(R.string.search_title), stringResource(R.string.search_hint))
                        }
                    ui.noDevice ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            EmptyState(
                                icon = Icons.Rounded.Search,
                                title = stringResource(R.string.server_none_selected),
                                message = stringResource(R.string.search_no_device_hint),
                                actionLabel = stringResource(R.string.common_retry),
                                onAction = viewModel::retry,
                            )
                        }
                    ui.error != null ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            InlineError(Icons.Rounded.ErrorOutline, stringResource(R.string.search_failed), ui.error!!, onRetry = viewModel::retry, retryLabel = stringResource(R.string.common_retry))
                        }
                    // 批v151 审查修正：空态门控必须读当前模式自己的结果列表——All
                    // 模式从不写 `results`，单模式的门控会把合并命中藏进"无结果"。
                    ((!allMode && ui.results.isEmpty()) || (allMode && ui.allResults.isEmpty())) && !ui.searching -> {
                        if (allMode && ui.deviceStates.isEmpty()) {
                            // 所有可见设备都退出了聚合（includeInAll=false）——明说，
                            // 而不是冒充"无匹配"（同 Chats 聚合 v137 的 allSkipped 文案）。
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                EmptyState(
                                    icon = Icons.Rounded.Dns,
                                    title = stringResource(R.string.all_devices_skipped_title),
                                    message = stringResource(R.string.all_devices_skipped_message),
                                )
                            }
                        } else {
                            // 部分失败但零命中（第1轮派发审查修正）：失败横幅置顶，
                            // 网络故障不再冒充"无匹配"——与列表分支同一文案口径。
                            val failedEmpty = if (allMode) ui.deviceStates.filter { it.status == DeviceLoadStatus.FAILED } else emptyList()
                            Column(Modifier.fillMaxSize()) {
                                if (failedEmpty.isNotEmpty()) UnreachableDevicesBanner(failedEmpty)
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    EmptyState(Icons.Rounded.Search, stringResource(R.string.search_no_results), stringResource(R.string.search_no_results_for, ui.query))
                                }
                            }
                        }
                    }
                    allMode && ui.allResults.isEmpty() && ui.searching -> {
                        // 进「全部」的扇出等待期（第3轮扫尾修正）：合并结果要等全部
                        // 设备落地才写 allResults，等待期渲染进度而不是空列表——
                        // 旧单设备结果已在切模式时让位，不能给用户 10s 白板。
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = colors.accent)
                                Text(
                                    stringResource(R.string.search_all_searching),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textSecondary,
                                )
                            }
                        }
                    }
                    else -> if (allMode) {
                        // 批v151 All mode: merged hits + a device-failure banner (same
                        // "devices unreachable" language as the Activity aggregate).
                        // Keys are serverId-scoped — conversation ids collide across
                        // devices and would crash LazyColumn's duplicate-key check.
                        val failed = ui.deviceStates.filter { it.status == DeviceLoadStatus.FAILED }
                        Column(Modifier.fillMaxSize()) {
                            if (failed.isNotEmpty()) {
                                UnreachableDevicesBanner(failed)
                            }
                            // 批v174 P3(审查必修): a row-action failure shows as a
                            // dismissable banner — the full-screen error gate reads
                            // `error`, which the aggregate round never writes; letting
                            // action failures land there would wipe the merged list.
                            ui.actionError?.let {
                                ActionErrorBanner(it, onDismiss = viewModel::dismissActionError)
                            }
                            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                                items(ui.allResults, key = { "${it.serverId}:${it.conversation.id}" }) { hit ->
                                    SessionRow(
                                        hit.conversation,
                                        onClick = { viewModel.submit(); onOpenAggregateRow(hit.serverId, hit.conversation.id) },
                                        modifier = Modifier.animateItem(),
                                        // Folder ids are server-local and the All view has
                                        // no per-device label map; the owning device name
                                        // is the identity that matters here.
                                        folderName = null,
                                        // 批v174 P3: the merged list no longer withholds
                                        // actions — every callback routes to the row's
                                        // OWNING device (ids are server-local, the At()
                                        // trio resolves the right client per row).
                                        onTogglePin = { viewModel.setPinnedAt(hit.serverId, hit.conversation, !hit.conversation.isPinned) },
                                        onHandoff = { ref -> onHandoff(hit.serverId, hit.conversation.folderId, ref) },
                                        onDelete = { pendingDelete = hit.serverId to hit.conversation },
                                        onAddToProject = { addToTarget = hit.serverId to hit.conversation },
                                        onRename = { pendingRename = hit.serverId to hit.conversation },
                                        serverName = hit.serverName,
                                        onBoard = boardMenu.isOnBoard(hit.serverId, hit.conversation.id),
                                        onToggleBoard = {
                                            boardMenu.toggle(hit.serverId, hit.conversation.id, hit.conversation.folderId)
                                        },
                                        swipeLeftAction = swipeLeft,
                                        swipeRightAction = swipeRight,
                                    )
                                }
                            }
                        }
                    } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        // 批v174 P3(审查必修): same banner slot as the All branch —
                        // single rows route through the same At() trio now.
                        ui.actionError?.let {
                            item(key = "action-error") { ActionErrorBanner(it, onDismiss = viewModel::dismissActionError) }
                        }
                        items(ui.results, key = { it.id }) { c ->
                            // 批v174 P3: the dialogs and the project sheet hold the
                            // owning device too (ui.serverId can be null only while
                            // no device is selected — then there are no rows anyway).
                            val sid = ui.serverId
                            SessionRow(
                                c,
                                onClick = { viewModel.submit(); onOpenConversation(c.id) },
                                modifier = Modifier.animateItem(),
                                folderName = ui.folderNames[c.folderId],
                                onTogglePin = sid?.let { s -> { viewModel.setPinnedAt(s, c, !c.isPinned) } },
                                onHandoff = sid?.let { s -> { ref -> onHandoff(s, c.folderId, ref) } },
                                onDelete = sid?.let { s -> { pendingDelete = s to c } },
                                onAddToProject = sid?.let { s -> { addToTarget = s to c } },
                                onRename = sid?.let { s -> { pendingRename = s to c } },
                                onBoard = boardMenu.isOnBoard(ui.serverId.orEmpty(), c.id),
                                onToggleBoard = ui.serverId?.let { sid2 ->
                                    { boardMenu.toggle(sid2, c.id, c.folderId) }
                                },
                                swipeLeftAction = swipeLeft,
                                swipeRightAction = swipeRight,
                            )
                        }
                    }
                }
            }
        }
    }

    // 批v174 P3: both modes' dialogs route through the same At() actions — the
    // held pair names the OWNING device, so no mode fork at the confirm site.
    pendingDelete?.let { (serverId, conversation) ->
        SessionDeleteDialog(
            onConfirm = {
                viewModel.deleteAt(serverId, conversation)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    pendingRename?.let { (serverId, conversation) ->
        SessionRenameDialog(
            initialTitle = conversation.trimmedTitle.orEmpty(),
            onConfirm = { title ->
                viewModel.renameAt(serverId, conversation, title)
                pendingRename = null
            },
            onDismiss = { pendingRename = null },
        )
    }

    // 批v174 P3: the 添加到项目 sheet — same wiring as the Chats screen's.
    addToTarget?.let { (serverId, conversation) ->
        val ref = SessionRef(serverId, conversation.id)
        AddToProjectSheet(
            targetTitle = conversation.trimmedTitle ?: "#${conversation.id}",
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

/** 设备失败横幅：All 模式的列表分支与空态分支共用（同 Activity 聚合口径）。
 *  批v153（第3轮可及性审查）：去掉 maxLines=1——多设备同时挂时设备名单被截
 *  用户看不到哪台挂了；Activity 聚合的同文案本就不限行。 */
@Composable
private fun UnreachableDevicesBanner(failed: List<DeviceLoad>) {
    Text(
        text = stringResource(R.string.activity_devices_unreachable, failed.joinToString { it.name }),
        fontSize = 12.sp,
        color = CodegTheme.colors.warning,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** 批v174 P3(审查必修): row-action failure banner — dismissable, list stays on
 *  screen (Chats-aggregate parity: the row itself carries the optimistic revert). */
@Composable
private fun ActionErrorBanner(message: String, onDismiss: () -> Unit) {
    val colors = CodegTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            message,
            fontSize = 12.sp,
            color = colors.warning,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
            // 批v175 审查: the banner's only dismissal needs a name — TalkBack read
            // "unlabeled" and the user couldn't close the error.
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_close), tint = colors.textTertiary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun RecentList(recent: List<String>, onUse: (String) -> Unit, onClear: () -> Unit) {
    val colors = CodegTheme.colors
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.search_recent), fontSize = 13.sp, color = colors.textSecondary, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.search_clear), fontSize = 13.sp, color = colors.accent, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onClear() }.padding(horizontal = 8.dp, vertical = 4.dp))
        }
        LazyColumn {
            items(recent, key = { it }) { term ->
                Row(
                    Modifier.fillMaxWidth().clickable { onUse(term) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Rounded.History, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(16.dp))
                    Text(term, fontSize = 14.sp, color = colors.textPrimary)
                }
            }
        }
    }
}
