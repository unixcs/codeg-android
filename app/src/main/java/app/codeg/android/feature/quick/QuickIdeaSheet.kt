package app.codeg.android.feature.quick

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.codeg.android.R
import app.codeg.android.core.datastore.DraftEntry
import app.codeg.android.core.datastore.DraftStore
import app.codeg.android.core.datastore.IdeaEntry
import app.codeg.android.core.datastore.IdeaStore
import app.codeg.android.core.designsystem.component.PrimaryButton
import app.codeg.android.core.designsystem.component.sheetContentScrollLock
import app.codeg.android.core.designsystem.theme.CodegTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The ball's 记录想法 sheet (批v150 §1.4): title optional (auto-named
 * 想法 MM-dd HH:mm), description free — no device/project required. Saves to
 * [IdeaStore]; the idea surfaces in the inbox tab like any other draft.
 */
@HiltViewModel
class QuickIdeaViewModel @Inject constructor(
    private val ideaStore: IdeaStore,
    private val drafts: DraftStore,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    /** 保存成功计数器（单调递增、永不清零）。sheet 用「打开时的基线」判断本次
     *  会话内是否发生了新保存：布尔位会在保存完成于 sheet 关闭之后残留 true，
     *  下次打开瞬间就把新 sheet 关掉（VM 是 activity 作用域，比 sheet 长寿）。 */
    private val _savedSeq = MutableStateFlow(0)
    val savedSeq: StateFlow<Int> = _savedSeq.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // 双击防抖：一次保存只落一条草稿（UUID 各异、内容相同的两条只会添乱）。
    private val saving = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 批v164: the sheet's half-typed text survives close/reopen (and process
     *  death) — the sheet's rememberSaveable dies with the composition, the
     *  DataStore draft does not. The composable restores once and saves through
     *  [saveDraft] on a debounce; [save] success clears it. */
    suspend fun loadDraft(): DraftEntry = drafts.draftNow(DraftStore.ideaKey(DRAFT_KEY))

    fun saveDraft(title: String, description: String) {
        viewModelScope.launch {
            runCatching {
                if (title.isBlank() && description.isBlank()) {
                    drafts.clearDraft(DraftStore.ideaKey(DRAFT_KEY))
                } else {
                    drafts.setDraft(DraftStore.ideaKey(DRAFT_KEY), title, description)
                }
            }
        }
    }

    fun save(title: String, description: String) {
        if (title.isBlank() && description.isBlank()) return
        if (!saving.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val autoTitle = appContext.getString(
                    R.string.quick_idea_auto_title,
                    DateTimeFormatter.ofPattern("MM-dd HH:mm").format(LocalDateTime.now()),
                )
                val entry = IdeaEntry(
                    id = UUID.randomUUID().toString(),
                    title = title.trim().ifBlank { autoTitle },
                    description = description.trim(),
                    createdAt = now,
                    updatedAt = now,
                )
                try {
                    ideaStore.upsert(entry)
                    // A saved idea must not come back as a draft — clear only on
                    // success so a failed save keeps the user's text.
                    runCatching { drafts.clearDraft(DraftStore.ideaKey(DRAFT_KEY)) }
                    _savedSeq.value += 1
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _error.value = "想法保存失败（本地存储异常）"
                }
            } finally {
                saving.set(false)
            }
        }
    }

    /** The VM is activity-scoped and outlives the sheet — clear last open's
     *  error so a fresh sheet doesn't greet the user with old news. */
    fun clearError() {
        _error.value = null
    }

    private companion object {
        const val DRAFT_KEY = "quick"
    }
}

/**
 * Quick idea editor. ≤2-tap acceptance path: ball tap → panel card → this sheet;
 * 保存 lands the idea in the inbox. Fixed-title + inner-scroll per the v151
 * sheet contract (drag-to-close on the title; content scrolls both ways).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickIdeaSheet(
    onDismiss: () -> Unit,
    viewModel: QuickIdeaViewModel = hiltViewModel(),
) {
    val savedSeq by viewModel.savedSeq.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    // 批v164 draft memory: restore once on open, then debounce-save every change
    // (DispatchForm's pattern). rememberSaveable only survives rotation — the
    // DataStore draft survives close/reopen, which is what users actually mean
    // by "记了一半回来还在".
    var draftRestored by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val stored = runCatching { viewModel.loadDraft() }.getOrNull() ?: return@LaunchedEffect
        if (title.isBlank()) title = stored.title
        if (description.isBlank()) description = stored.text
        draftRestored = true
    }
    LaunchedEffect(draftRestored) {
        if (!draftRestored) return@LaunchedEffect
        snapshotFlow { title to description }
            .distinctUntilChanged()
            .collectLatest { (t, d) ->
                kotlinx.coroutines.delay(500)
                viewModel.saveDraft(t, d)
            }
    }
    val emptyHint = stringResource(R.string.quick_idea_empty_hint)

    // 只有「本 sheet 里点了保存」才允许自动关闭：关闭后重开的新 sheet 不会被
    // 上一次在途保存的完成事件误关（保存是本次交互的事件，不是 VM 的常驻状态）。
    // 批v153: rememberSaveable — a plain remember lost the baseline on rotation,
    // so a save in flight during a config change never auto-closed the sheet and
    // a second tap produced a duplicate idea (no dedupe downstream).
    var pendingSaveAt by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(savedSeq) {
        pendingSaveAt?.let { at -> if (savedSeq > at) onDismiss() }
    }
    // VM 比 sheet 长寿（activity 作用域）——清掉上次打开残留的错误行。
    LaunchedEffect(Unit) { viewModel.clearError() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
    ) {
        // 批v151 全锁 + 固定头：标题在滚动区外（拖拽关闭只认它），滚动区吃掉
        // 双向剩余拖拽——列表顶下拉不再误关这张卡（根修见 SheetGestures）。
        Column(
            Modifier
                .fillMaxWidth()
                .sheetContentScrollLock(),
        ) {
            // dragHandle=null：标题条就是拖拽关闭区——撑到 48dp（第1轮可及性
            // 审查），视觉节奏不变。
            Text(
                stringResource(R.string.quick_idea_title),
                style = MaterialTheme.typography.titleMedium,
                color = CodegTheme.colors.textPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .wrapContentHeight(Alignment.CenterVertically)
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 10.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.quick_idea_title_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.quick_idea_desc_hint)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(error!!, color = CodegTheme.colors.danger, style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_cancel))
                    }
                    PrimaryButton(
                        text = stringResource(R.string.common_save),
                        onClick = {
                            pendingSaveAt = savedSeq
                            viewModel.save(title, description)
                        },
                        enabled = title.isNotBlank() || description.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (title.isBlank() && description.isBlank()) {
                    Text(emptyHint, style = MaterialTheme.typography.labelSmall, color = CodegTheme.colors.textTertiary)
                }
            }
        }
    }
}
