package app.codeg.android.feature.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.data.LocalAttachment
import app.codeg.android.core.datastore.IdeaEntry
import app.codeg.android.core.designsystem.component.CodegFilterChip
import app.codeg.android.core.designsystem.component.CodegFormTextField
import app.codeg.android.core.designsystem.component.LoadingView
import app.codeg.android.core.designsystem.component.LocalImageThumbnail
import app.codeg.android.core.designsystem.component.PrimaryButton
import app.codeg.android.core.designsystem.component.rememberSheetDragLock
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.AgentType
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// 批v165 R4: was compiled per rendered row (every >24h stamp).
private val PICKER_DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")

/**
 * 批v162 反向快链的目标选择器（想法卡 →「追加到已有会话」）。数据由
 * [InboxViewModel.loadPickerSessions] 一次性拉取（每台可见设备一轮
 * listConversations，聚合超时族、单设备失败隔离），UI 只做 搜索 + 筛选 + 选行。
 * 选行后由调用方（InboxScreen）切到 [AppendToSessionSheet] 预览确认——
 * 发送前必看全文，这是"追加是往别人会话里塞内容"的礼貌。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionPickerSheet(
    viewModel: InboxViewModel,
    onPick: (SessionPickerRow) -> Unit,
    onDismiss: () -> Unit,
) {
    val picker by viewModel.picker.collectAsStateWithLifecycle()
    val targets by viewModel.target.collectAsStateWithLifecycle()
    // 筛选态全是 saveable 值：旋转不丢筛选（行数据 VM 持有，自动回填）。
    var query by rememberSaveable { mutableStateOf("") }
    var serverId by rememberSaveable { mutableStateOf<String?>(null) }
    var folderKey by rememberSaveable { mutableStateOf<String?>(null) }
    var boardOnly by rememberSaveable { mutableStateOf(false) }

    // 每次打开拉一轮（一次性数据，选择器不是浏览器）。
    LaunchedEffect(Unit) { viewModel.loadPickerSessions() }

    val filtered = remember(picker.rows, picker.trackedKeys, query, serverId, folderKey, boardOnly) {
        filterPickerRows(picker.rows, query, serverId, folderKey, boardOnly, picker.trackedKeys)
    }
    // 项目 chips 跟设备走（folderId 设备本地）：先按设备筛，再列剩余行里出现过的项目。
    val deviceRows = remember(picker.rows, serverId) {
        if (serverId == null) picker.rows else picker.rows.filter { it.serverId == serverId }
    }
    val folderOptions = remember(deviceRows) {
        deviceRows.mapNotNull { row ->
            row.folderName?.let { Triple(row.folderKey, it, row.serverId) }
        }.distinctBy { it.first }
    }

    val colors = CodegTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .nestedScroll(rememberSheetDragLock())
                .padding(bottom = 16.dp),
        ) {
            // dragHandle=null：标题条就是拖拽关闭区——撑到 48dp（v151 弹层契约）。
            Text(
                stringResource(R.string.picker_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .wrapContentHeight(Alignment.CenterVertically)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
            CodegFormTextField(
                value = query,
                onValueChange = { query = it },
                label = stringResource(R.string.picker_search_hint),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            // 设备 + 仅看板 chips（横滚）。
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CodegFilterChip(
                    label = stringResource(R.string.picker_filter_all_devices),
                    selected = serverId == null,
                    onClick = { serverId = null; folderKey = null },
                )
                targets.profiles.forEach { profile ->
                    CodegFilterChip(
                        label = profile.name,
                        selected = serverId == profile.id,
                        onClick = {
                            serverId = if (serverId == profile.id) null else profile.id
                            // 换设备时项目筛选必须跟着清：folderId 只在本设备内唯一。
                            folderKey = null
                        },
                    )
                }
                CodegFilterChip(
                    label = stringResource(R.string.picker_filter_board_only),
                    selected = boardOnly,
                    onClick = { boardOnly = !boardOnly },
                )
            }
            if (folderOptions.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CodegFilterChip(
                        label = stringResource(R.string.picker_filter_all_projects),
                        selected = folderKey == null,
                        onClick = { folderKey = null },
                    )
                    folderOptions.forEach { (key, name, _) ->
                        CodegFilterChip(
                            label = name,
                            selected = folderKey == key,
                            onClick = { folderKey = if (folderKey == key) null else key },
                        )
                    }
                }
            }
            when {
                picker.loading -> Column(Modifier.heightIn(min = 160.dp)) {
                    LoadingView(stringResource(R.string.common_loading))
                }
                picker.failed -> Text(
                    stringResource(R.string.picker_failed),
                    color = colors.danger,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.loadPickerSessions() }
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                )
                filtered.isEmpty() -> Text(
                    stringResource(R.string.picker_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                )
                else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(filtered, key = { it.key }) { row ->
                        PickerSessionRow(row = row, onClick = { onPick(row) })
                        HorizontalDivider(color = colors.hairline)
                    }
                }
            }
        }
    }
}

/** 选择器的一行：标题 + live 圆点 + 相对时间，第二行 设备 · 项目 · agent。 */
@Composable
private fun PickerSessionRow(row: SessionPickerRow, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    Column(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (row.live) {
                // live 圆点：与 Activity 行同口径的状态旗标。
                Box(Modifier.size(6.dp).clip(CircleShape).background(colors.accent))
                Spacer(Modifier.size(6.dp))
            }
            Text(
                row.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                when (val stamp = relativeStamp(row.updatedAt, Instant.now())) {
                    RelativeStamp.JustNow -> stringResource(R.string.picker_time_just_now)
                    is RelativeStamp.Minutes -> stringResource(R.string.picker_time_minutes_ago, stamp.value)
                    is RelativeStamp.Hours -> stringResource(R.string.picker_time_hours_ago, stamp.value)
                    is RelativeStamp.Dated -> stamp.label
                },
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
            )
        }
        Text(
            listOfNotNull(
                row.serverName,
                row.folderName,
                row.agent.shortName,
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 追加前的预览确认（反向第三步）：目标会话 + 想法全文 + 附件缩略图，一个
 * 「追加发送」出口。发送走 [InboxViewModel.sendIntoSession]；失败停在原地
 * 显错误（不武装不跳转——干净失败），成功由调用方关单并跳转目标会话。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppendToSessionSheet(
    idea: IdeaEntry,
    target: SessionPickerRow,
    viewModel: InboxViewModel,
    onSent: (SessionPickerRow) -> Unit,
    onDismiss: () -> Unit,
) {
    val picker by viewModel.picker.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    // 与 VM 的闸门同口径：全空的想法没东西可追加。
    val contentReady = idea.title.isNotBlank() || idea.description.isNotBlank() || idea.attachments.isNotEmpty()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .nestedScroll(rememberSheetDragLock())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.inbox_append_to_session),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .wrapContentHeight(Alignment.CenterVertically)
                    .padding(vertical = 10.dp),
            )
            Text(stringResource(R.string.append_target_label), fontSize = 12.sp, color = colors.textTertiary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (target.live) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(colors.accent))
                    Spacer(Modifier.size(6.dp))
                }
                Text(
                    target.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                listOfNotNull(target.serverName, target.folderName, target.agent.shortName).joinToString(" · "),
                fontSize = 12.sp,
                color = colors.textSecondary,
            )
            HorizontalDivider(color = colors.hairline)
            Text(
                idea.title.ifBlank { stringResource(R.string.inbox_untitled) },
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
            if (idea.description.isNotBlank()) {
                Text(
                    idea.description,
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                    modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                )
            }
            IdeaAttachmentStrip(idea.attachments)
            picker.error?.let { error ->
                Text(error, color = colors.danger, style = MaterialTheme.typography.bodySmall)
            }
            PrimaryButton(
                text = stringResource(R.string.append_send),
                onClick = { viewModel.sendIntoSession(idea, target) { onSent(target) } },
                enabled = contentReady && !picker.sending,
                loading = picker.sending,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 想法附件的缩略图条（图片横排 + 非图片文件名行）。空附件不占位。 */
@Composable
private fun IdeaAttachmentStrip(attachments: List<LocalAttachment>) {
    val colors = CodegTheme.colors
    if (attachments.isEmpty()) return
    val images = attachments.filter { it.isImage }
    if (images.isNotEmpty()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(images, key = { it.id }) { att ->
                LocalImageThumbnail(path = att.localPath, modifier = Modifier.size(56.dp))
            }
        }
    }
    attachments.filterNot { it.isImage }.forEach { att ->
        Text(
            att.name,
            fontSize = 12.sp,
            color = colors.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---- 批v162 纯函数（单测锁定在 app/src/test/.../feature/inbox/）----

/** 选择器的一行数据：跨设备聚合的最近活跃会话（VM 组装，UI 只读）。 */
data class SessionPickerRow(
    val serverId: String,
    val serverName: String,
    val conversationId: Int,
    val title: String,
    val agent: AgentType,
    val folderId: Int,
    val folderName: String?,
    val updatedAt: Instant,
    val live: Boolean,
) {
    /** 跨设备身份（会话 id 只在本设备内唯一）。 */
    val key: String get() = "$serverId:$conversationId"

    /** 项目的跨设备身份：folderId 设备本地，筛选键必须带上设备。 */
    val folderKey: String get() = "$serverId:$folderId"
}

/** 相对时间的四档口径：刚刚 / N 分钟前 / N 小时前 / 具体日期（MM-dd）。 */
sealed interface RelativeStamp {
    data object JustNow : RelativeStamp
    data class Minutes(val value: Long) : RelativeStamp
    data class Hours(val value: Long) : RelativeStamp
    /** 超过一天：不再玩相对时间，直接给 MM-dd（今年以前的也给 MM-dd——选择器是快筛，不是账本）。 */
    data class Dated(val label: String) : RelativeStamp
}

/** 纯函数：updatedAt → 相对时间口径。负年龄（时钟偏差）按"刚刚"处理。 */
fun relativeStamp(
    updatedAt: Instant,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): RelativeStamp {
    val age = Duration.between(updatedAt, now)
    if (age.isNegative) return RelativeStamp.JustNow
    val minutes = age.toMinutes()
    return when {
        minutes < 1 -> RelativeStamp.JustNow
        minutes < 60 -> RelativeStamp.Minutes(minutes)
        age.toHours() < 24 -> RelativeStamp.Hours(age.toHours())
        else -> RelativeStamp.Dated(PICKER_DAY_FORMAT.format(updatedAt.atZone(zone)))
    }
}

/**
 * 纯函数：选择器的过滤——设备 → 项目（跨设备键）→ 仅看板 → 关键词
 * （标题/设备名/项目名/agent，大小写不敏感的包含匹配）。入参行已按活跃
 * 倒序，出参保持同序。folderKey 与 boardOnly 同时生效（交集，不是二选一）。
 */
fun filterPickerRows(
    rows: List<SessionPickerRow>,
    query: String,
    serverId: String?,
    folderKey: String?,
    boardOnly: Boolean,
    trackedKeys: Set<String>,
): List<SessionPickerRow> {
    val q = query.trim()
    return rows.filter { row ->
        if (serverId != null && row.serverId != serverId) return@filter false
        if (folderKey != null && row.folderKey != folderKey) return@filter false
        if (boardOnly && row.key !in trackedKeys) return@filter false
        if (q.isNotEmpty()) {
            val hay = "${row.title} ${row.serverName} ${row.folderName.orEmpty()} ${row.agent.displayName}"
            if (!hay.contains(q, ignoreCase = true)) return@filter false
        }
        true
    }
}
