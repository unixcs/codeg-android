package app.codeg.android.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.codeg.android.R
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * 统一选择器（ui重构 v2 §1.5，新任务书 §六）：单列 + Chips + BottomSheet，
 * 全 app 滚轮的唯一替代面。四个原语，全部真实数据驱动：
 *
 *  - [PickerField]  表单行：图标 + 标签 + 当前值 + chevron，点击弹出 [OptionListSheet]。
 *  - [OptionListSheet]  垂直列表 BottomSheet：搜索过滤 + 选中态。
 *  - [DeviceChipRow]  目标设备横滚 chips（选中态 + 目标加载转圈 + 预检失败提示点）。
 *  - [AgentChipRow]  横滚 chips，首位固定「自动」（无 Agent 覆写，项目默认生效）。
 */

/** One selectable option for [OptionListSheet]. */
data class PickerOption(
    val id: String,
    val label: String,
    /** Optional one-line secondary text (e.g. a repo path). */
    val note: String? = null,
)

/**
 * Form row: leading icon, label, current value, chevron. Value-less renders the
 * [placeholder] in secondary color. Purely presentational — the caller owns the
 * option sheet it opens.
 */
@Composable
fun PickerField(
    icon: ImageVector,
    label: String,
    value: String?,
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    valueNote: String? = null,
    /** R3-A P3: a dead click (ripple fires, nothing happens) is a lie — callers
     *  with loading/empty option states pass their real condition here. */
    enabled: Boolean = true,
) {
    val colors = CodegTheme.colors
    // 批v160 W10: card treatment — radiusMd corners, elevated fill, hairline
    // stroke and a 56dp row (the old 12dp-radius borderless fill read as a
    // cramped list row, not a form control).
    val shape = RoundedCornerShape(CodegTheme.dimens.radiusMd)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(colors.bg.copy(alpha = 0.42f), shape)
            .border(1.dp, colors.surfaceStroke, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) colors.textSecondary else colors.textTertiary,
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.textPrimary.copy(alpha = 0.06f))
                .padding(7.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
            Text(
                value ?: placeholder,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (value != null) FontWeight.Medium else FontWeight.Normal,
                color = if (value != null && enabled) colors.textPrimary else colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (valueNote != null) {
                Text(valueNote, style = MaterialTheme.typography.labelSmall, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(
            Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = colors.textTertiary.copy(alpha = if (enabled) 1f else 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Vertical option list in a modal bottom sheet, with a search filter (§六:
 * 搜索 + 选中态). [options] are already resolved by the caller; empty renders
 * the "nothing available" line instead of a blank sheet. [footer] renders
 * below the list (also when empty) — 批v178 device sheet's 管理设备 entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionListSheet(
    title: String,
    options: List<PickerOption>,
    selectedId: String?,
    onSelect: (PickerOption) -> Unit,
    onDismiss: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
) {
    val colors = CodegTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // v151 sheet contract: content drag-lock eats all bidirectional
                // scroll leftover; drag-to-close lives on the fixed title above
                // (the sheet itself sets dragHandle = null).
                .nestedScroll(rememberSheetDragLock())
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 20.dp),
        ) {
            // dragHandle=null：标题条就是拖拽关闭区——撑到 48dp（第3轮扫尾修正）。
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .wrapContentHeight(Alignment.CenterVertically),
            )
            Spacer(Modifier.size(10.dp))
            if (options.size > 6) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.textTertiary) },
                    placeholder = { Text(stringResource(R.string.option_sheet_search), style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
            }
            val filtered = remember(options, query) {
                if (query.isBlank()) options
                else options.filter { it.label.contains(query.trim(), ignoreCase = true) || it.note?.contains(query.trim(), ignoreCase = true) == true }
            }
            if (filtered.isEmpty()) {
                Text(
                    stringResource(R.string.picker_no_options),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(vertical = 18.dp),
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(filtered, key = { it.id }) { option ->
                        val selected = option.id == selectedId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) colors.selectedBg else Color.Transparent)
                                .clickable { onSelect(option); onDismiss() }
                                .padding(horizontal = 10.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    option.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) colors.selectedText else colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (option.note != null) {
                                    Text(option.note, style = MaterialTheme.typography.labelSmall, color = if (selected) colors.selectedSubText else colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (selected) {
                                Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.selectedText, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            footer?.let {
                Spacer(Modifier.size(8.dp))
                it()
            }
        }
    }
}

/**
 * Horizontal chip row of target devices. [errorDeviceIds] marks devices whose
 * health precheck / target load FAILED (real loader data — never a fake online
 * dot): the chip shows a warning tick instead of pretending liveness.
 */
@Composable
fun DeviceChipRow(
    devices: List<PickerOption>,
    selectedId: String?,
    loadingDeviceId: String? = null,
    errorDeviceIds: Set<String> = emptySet(),
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ChipRowBase(
        options = devices,
        selectedId = selectedId,
        modifier = modifier,
        trailing = { id ->
            when {
                id == loadingDeviceId -> CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                id in errorDeviceIds -> Box(Modifier.size(6.dp).clip(CircleShape).background(CodegTheme.colors.danger))
            }
        },
        onSelect = onSelect,
    )
}

/**
 * Horizontal chip row of agents with a fixed first "自动" chip (null id = no
 * override; the project default applies — same semantics as the old wheel).
 */
@Composable
fun AgentChipRow(
    autoLabel: String,
    agents: List<PickerOption>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    ChipRowBase(
        options = listOf(PickerOption(id = "", label = autoLabel)) + agents,
        selectedId = selectedId ?: "",
        modifier = modifier,
        onSelect = { id -> onSelect(id.ifEmpty { null }) },
    )
}

/** Shared horizontal chip strip — the single scroll container for chip rows. */
@Composable
private fun ChipRowBase(
    options: List<PickerOption>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (String) -> Unit = {},
) {
    val colors = CodegTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (options.isEmpty()) {
            Text(
                stringResource(R.string.picker_no_options),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
                modifier = Modifier.padding(vertical = 10.dp),
            )
        }
        options.forEach { option ->
            val selected = option.id == selectedId
            Surface(
                // onClick 形态：Material 的最小可点面积强制生效（审查轮6——
                // Modifier.clickable 会绕过 48dp 触达保障）。
                onClick = { onSelect(option.id) },
                shape = RoundedCornerShape(999.dp),
                // 批v170 克制化选中态 + 墨菲 R1 修正：0.10 浅灰底在浅色主题
                // 只有 ~1.2:1，必须留文字色差兜底（选中 textPrimary / 未选中
                // textSecondary，与 InsightKit.CapsuleChipRow 同口径），否则
                // 设备/智能体选择器等于盲选。
                color = if (selected) colors.selectedBg else colors.bgElevated,
                contentColor = if (selected) colors.selectedText else colors.textSecondary,
                border = BorderStroke(1.dp, if (selected) colors.selectedBorder else colors.hairline),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(option.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    trailing(option.id)
                }
            }
        }
    }
}
