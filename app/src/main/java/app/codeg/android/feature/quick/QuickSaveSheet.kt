package app.codeg.android.feature.quick

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.FolderOpen
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.codeg.android.R
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.AppSettingsStore
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.designsystem.component.OptionListSheet
import app.codeg.android.core.designsystem.component.PickerOption
import app.codeg.android.core.designsystem.component.PickerField
import app.codeg.android.core.designsystem.component.PrimaryButton
import app.codeg.android.core.designsystem.component.sheetContentScrollLock
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.network.CodegClient
import app.codeg.android.feature.projects.DirectoryBrowserSheet
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One finished save run, consumed by the sheet via [QuickSaveViewModel.saveSeq]. */
sealed interface QuickSaveOutcome {
    /** [relPath] is workspace-relative ("notes/idea.md"); [absPath] is the
     *  device-absolute path the sheet copies to the clipboard (批v174 N5 —
     *  paste it into a conversation so the agent can load the file directly). */
    data class Saved(val deviceName: String, val relPath: String, val absPath: String) : QuickSaveOutcome

    /** [message] is the server/client text when there is one (kept for diagnosis). */
    data class Failed(val message: String?) : QuickSaveOutcome
}

@HiltViewModel
class QuickSaveViewModel @Inject constructor(
    private val repository: ServerRepository,
    private val settingsStore: AppSettingsStore,
) : ViewModel() {

    val profiles: StateFlow<List<ServerProfile>> =
        repository.visibleProfiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 批v172 D7: last-used device + dir, re-prefilled when the sheet reopens. */
    val lastServerId: StateFlow<String> =
        settingsStore.settings.map { it.quickSaveLastServer }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    val lastDir: StateFlow<String> =
        settingsStore.settings.map { it.quickSaveLastDir }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    // Backing fields BEFORE their public exposures — Kotlin class-body
    // initializers run in declaration order, so a forward reference is the
    // "Variable '_x' must be initialized" compile error (cloud Verify caught
    // this; reviews read past it).
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /** 保存成功计数器（单调递增、永不清零）——sheet 用「打开时的基线」判断
     *  本次会话内是否发生了新保存。布尔位会在保存完成于 sheet 关闭之后残留，
     *  下次打开瞬间就误关新 sheet（VM 是 activity 作用域，比 sheet 长寿，
     *  QuickIdeaSheet 同款教训）。 */
    private val _saveSeq = MutableStateFlow(0)
    val saveSeq: StateFlow<Int> = _saveSeq.asStateFlow()
    private val _saveResult = MutableStateFlow<QuickSaveOutcome?>(null)
    val saveResult: StateFlow<QuickSaveOutcome?> = _saveResult.asStateFlow()

    // 双击防抖：一次保存只落一个文件。
    private val inFlight = AtomicBoolean(false)

    /** The client for a saved profile; null when its token is missing or the
     *  backing stores can't be read (批v175 审查: cancellation still propagates —
     *  the old unguarded copy turned a sheet close mid-read into a crash). */
    suspend fun clientFor(profile: ServerProfile): CodegClient? = try {
        repository.client(profile)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * 批v172 D7: the full chain runs on [viewModelScope] so sliding the sheet
     * away mid-save can't strand a half-written file — the sheet reads the
     * outcome via [saveSeq]/[saveResult] (seq guard, see QuickIdeaSheet).
     *
     * Chain: unique name against the target dir's listing → create the empty
     * entry → read it back for a fresh etag → write content. `create` can't
     * carry content and `save_file_content` with a null etag force-overwrites,
     * so the read-back is the optimistic-lock leg; collisions can't happen
     * because [uniqueName] already picked a free name.
     */
    fun save(serverId: String, relDir: String, title: String, content: String) {
        if (!inFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            _saveResult.value = null
            _saving.value = true
            val outcome = try {
                val profile = profiles.value.firstOrNull { it.id == serverId }
                val client = profile?.let { repository.client(it) }
                if (client == null) {
                    QuickSaveOutcome.Failed(null)
                } else {
                    val rootPath = client.getHomeDirectory()
                    val relNew = saveTo(client, rootPath, relDir, title, content)
                    // 批v175 审查: remembering the last dir is best-effort — inside
                    // this try a DataStore failure used to report an ALREADY-WRITTEN
                    // file as failed, and the retry then landed name-2.md next to it.
                    try {
                        settingsStore.setQuickSaveLast(serverId, relDir)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                    // 批v174 N5: hand the sheet the absolute path (same join as the
                    // SessionDetailViewModel upload path — trim the double slash).
                    QuickSaveOutcome.Saved(
                        profile.name,
                        relNew,
                        "${rootPath.trimEnd('/')}/${relNew.trimStart('/')}",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                QuickSaveOutcome.Failed(e.message?.takeIf { it.isNotBlank() })
            } finally {
                _saving.value = false
                inFlight.set(false)
            }
            _saveResult.value = outcome
            _saveSeq.value += 1
        }
    }

    private suspend fun saveTo(
        client: CodegClient,
        rootPath: String,
        relDir: String,
        title: String,
        content: String,
    ): String {
        val base = sanitizeFileName(title)
        val dirAbs = if (relDir.isEmpty()) rootPath else "$rootPath/$relDir"
        val taken = client.listDirectoryWithFiles(dirAbs)
            .filter { !it.isDir }
            .mapTo(mutableSetOf()) { it.name }
        val name = uniqueName(base, taken)
        client.createFileTreeEntry(rootPath, relDir, name, "file")
        val relNew = if (relDir.isEmpty()) name else "$relDir/$name"
        val edit = try {
            client.readFileForEdit(rootPath, relNew)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 批v175 审查: the empty entry is already on the server — surfacing
            // the failure used to leave a 0-byte orphan named after the title,
            // and the retry then picked name-2.md (two files, the emptier one
            // holding the right name). Best-effort cleanup before rethrowing.
            try {
                client.deleteFileTreeEntry(rootPath, relNew)
            } catch (_: Exception) {
            }
            throw e
        }
        check(!edit.readOnly) { "read-only file" }
        client.saveFileContent(rootPath, relNew, content, edit.etag)
        return relNew
    }

    private companion object {
        /** Filesystem-hostile characters out, hard length cap, never blank. */
        fun sanitizeFileName(raw: String): String = raw
            .trim()
            .replace(Regex("[/\\\\:*?\"<>|]"), "-")
            .trim('.', ' ')
            .take(80)
            .ifBlank { "untitled" }

        /** 批v172 D7 拍板口径：重名自动加序号（标题-2.md、-3.md …）。 */
        fun uniqueName(base: String, taken: Set<String>): String {
            val first = "$base.md"
            if (first !in taken) return first
            for (i in 2..99) {
                val candidate = "$base-$i.md"
                if (candidate !in taken) return candidate
            }
            return "$base-${System.currentTimeMillis()}.md"
        }
    }
}

/**
 * 批v172 D7: the quick-save sheet — title + body, pick a device and a folder,
 * land a `<标题>.md` on that device's workspace. Entries: the workbench quick
 * card (opt-in), a floating-ball binding, and the inbox idea card's
 * 「保存为 Markdown」 long-press entry (title/body prefilled).
 *
 * Fixed-title + inner-scroll per the v151 sheet contract (drag-to-close on the
 * title; content scrolls both ways).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickSaveSheet(
    initialTitle: String = "",
    initialContent: String = "",
    onDismiss: () -> Unit,
    viewModel: QuickSaveViewModel = hiltViewModel(),
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val lastServerId by viewModel.lastServerId.collectAsStateWithLifecycle()
    val lastDir by viewModel.lastDir.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val saveSeq by viewModel.saveSeq.collectAsStateWithLifecycle()
    val saveResult by viewModel.saveResult.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    val context = LocalContext.current
    // 批v174 N5: the save success copies the file's absolute path (clipboard
    // precedent: FolderFilesView's 复制绝对路径).
    val clipboard = LocalClipboardManager.current

    val pickDevice = stringResource(R.string.quick_save_pick_device)
    val loadingLabel = stringResource(R.string.common_loading)
    val rootFolderLabel = stringResource(R.string.quick_save_folder_root)
    // Hoisted with the labels above: the (serverId, …) LaunchedEffect below
    // consumes rootErrorLabel, and Kotlin locals must be declared before use
    // (the old spot under the saveSeq effect was a forward reference —
    // 终审必修, cloud Verify would have failed the build).
    val savedToastTemplate = stringResource(R.string.quick_save_saved)
    val genericError = stringResource(R.string.quick_save_err_generic)
    val rootErrorLabel = stringResource(R.string.quick_save_err_root)
    // 批v174 N4: an out-of-workspace browser pick used to fall back silently
    // to the root dir — now it says so (FolderFilesView 同款口径).
    val outsideErrorLabel = stringResource(R.string.quick_save_err_outside)
    // 批v174 审查建议③: a Retry against a dead profile/token must not land in
    // a silent dead state (error gone, save still disabled, zero explanation).
    val missingTokenLabel = stringResource(R.string.sessions_missing_token)
    val retryLabel = stringResource(R.string.common_retry)
    // 批v175 审查: the body field had no length cap — a >2MB paste froze the
    // text field (Compose chokes far below the server's 50 MB limit; the file
    // editor caps at 2M chars for the same reason) and rememberSaveable had to
    // serialize the whole thing on rotation.
    val tooLargeError = stringResource(R.string.quick_save_err_too_large)

    var title by rememberSaveable { mutableStateOf(initialTitle) }
    var content by rememberSaveable { mutableStateOf(initialContent) }
    // 批v174 N4: the two failure classes are separate — the ROOT state (home
    // resolution failed; shows with a retry) and the SAVE state (save failed /
    // bad dir pick; the message is kept). Neither clears the other; a successful
    // root re-resolve clears the root state.
    var rootError by rememberSaveable { mutableStateOf<String?>(null) }
    var saveError by rememberSaveable { mutableStateOf<String?>(null) }
    var serverId by rememberSaveable { mutableStateOf("") }
    var relDir by rememberSaveable { mutableStateOf("") }
    // 批v174 N4: bump to re-resolve root after a failure (the Retry button).
    var rootRetry by rememberSaveable { mutableStateOf(0) }
    // Per-open link state: remember (not saveable) — a rotation re-resolves
    // via the LaunchedEffect below instead of resurrecting a stale client.
    var client by remember { mutableStateOf<CodegClient?>(null) }
    var rootPath by remember { mutableStateOf<String?>(null) }
    var showDeviceSheet by rememberSaveable { mutableStateOf(false) }
    var showDirBrowser by rememberSaveable { mutableStateOf(false) }
    // 只在首次打开时预填一次；旋转恢复（serverId 已非空）不重跑。
    var hydrated by rememberSaveable { mutableStateOf(false) }

    // 预填：上次用的设备（仍在才用；否则单设备直选）+ 上次的目录（仅同设备）。
    LaunchedEffect(hydrated, profiles, lastServerId, lastDir) {
        if (!hydrated && profiles.isNotEmpty()) {
            hydrated = true
            if (serverId.isBlank()) {
                val pre = when {
                    lastServerId.isNotBlank() && profiles.any { it.id == lastServerId } -> lastServerId
                    profiles.size == 1 -> profiles.first().id
                    else -> ""
                }
                if (pre.isNotBlank()) {
                    serverId = pre
                    if (pre == lastServerId && lastDir.isNotBlank()) relDir = lastDir
                }
            }
        }
    }
    // 设备选定 → 解析 client + home 根（旋转后 remember 丢失也走这里重取）。
    // 批v174 N4: keys drop the raw profiles list — profile-object re-emissions
    // (token edits, settings writes) used to re-run the effect and BLIP the
    // resolved link (client/root wiped → re-fetched → loading flash). A boolean
    // "profiles arrived" gate still re-runs once on the rotation/late-arrival
    // path, and [rootRetry] lets the Retry button drive a re-resolve.
    val haveProfiles = profiles.isNotEmpty()
    LaunchedEffect(serverId, rootRetry, haveProfiles) {
        if (serverId.isBlank() || !haveProfiles) return@LaunchedEffect
        val profile = profiles.firstOrNull { it.id == serverId } ?: return@LaunchedEffect
        client = null
        rootPath = null
        rootError = null
        // 批v175 审查: a device switch must not keep the PREVIOUS device's save
        // failure banner up — the new device never failed anything.
        saveError = null
        val c = viewModel.clientFor(profile)
        client = c
        if (c != null) {
            // 批v175 审查: runCatching swallowed CancellationException — a retry/
            // device-switch cancelling the old effect let its tail write a root
            // error AFTER the new effect had cleared it (a permanent fake
            // 「无法获取根目录」banner next to a working save button). Cancelled
            // coroutines must stop, not report failure.
            rootPath = try {
                c.getHomeDirectory()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (rootPath == null) rootError = rootErrorLabel
        } else {
            rootError = missingTokenLabel
        }
    }

    // 只有「本 sheet 里点了保存」才允许自动关闭（seq 基线，QuickIdeaSheet 同款：
    // 布尔位会在保存完成于 sheet 关闭之后残留，误关重开的新 sheet）。
    var pendingSaveAt by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(saveSeq) {
        val at = pendingSaveAt ?: return@LaunchedEffect
        if (saveSeq > at) {
            pendingSaveAt = null
            when (val outcome = saveResult) {
                is QuickSaveOutcome.Saved -> {
                    // 批v174 N5: copy the ABSOLUTE path first — the entire point is
                    // to paste it into a conversation and have the agent load the
                    // file as context (FolderFilesView's copy-path precedent).
                    clipboard.setText(AnnotatedString(outcome.absPath))
                    // 批v175 审查: the long absolute path wraps 2-3 lines and
                    // LENGTH_SHORT (~2s) died before the conclusion was readable.
                    Toast.makeText(
                        context,
                        savedToastTemplate.format(outcome.absPath),
                        Toast.LENGTH_LONG,
                    ).show()
                    onDismiss()
                }
                is QuickSaveOutcome.Failed -> {
                    // 无 message 的失败（设备/token 缺失）给本地化兜底文案；
                    // 有 message 的原样展示（FolderFilesView 同口径，可诊断）。
                    saveError = outcome.message ?: genericError
                }
                null -> Unit
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .sheetContentScrollLock(),
        ) {
            // dragHandle=null：标题条就是拖拽关闭区——撑到 48dp（第1轮可及性审查）。
            Text(
                stringResource(R.string.quick_save_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
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
                PickerField(
                    icon = Icons.Rounded.Devices,
                    label = stringResource(R.string.quick_save_device),
                    value = profiles.firstOrNull { it.id == serverId }?.name,
                    placeholder = pickDevice,
                    onClick = { showDeviceSheet = true },
                    enabled = profiles.isNotEmpty() && !saving,
                )
                PickerField(
                    icon = Icons.Rounded.FolderOpen,
                    label = stringResource(R.string.quick_save_folder),
                    value = when {
                        rootPath == null -> null
                        relDir.isEmpty() -> rootFolderLabel
                        else -> "$rootPath/$relDir"
                    },
                    placeholder = loadingLabel,
                    valueNote = null,
                    onClick = { if (client != null && rootPath != null) showDirBrowser = true },
                    enabled = client != null && rootPath != null && !saving,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.quick_save_name)) },
                    placeholder = { Text(stringResource(R.string.quick_save_name_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.quick_save_content)) },
                    minLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                )
                if (profiles.isEmpty()) {
                    Text(
                        stringResource(R.string.quick_save_no_device),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textTertiary,
                    )
                }
                if (rootError != null) {
                    // 批v174 N4: the root state shows WITH a retry — a transient
                    // acp_connect hiccup no longer dead-ends the sheet (the old
                    // single error text had no way back except closing and reopening).
                    Text(rootError!!, color = colors.danger, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { rootRetry++ }) {
                        Text(retryLabel)
                    }
                }
                if (saveError != null) {
                    Text(saveError!!, color = colors.danger, style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_cancel))
                    }
                    PrimaryButton(
                        text = stringResource(R.string.quick_save_save),
                        onClick = {
                            if (content.length > MAX_QUICK_SAVE_CHARS) {
                                saveError = tooLargeError
                            } else {
                                pendingSaveAt = saveSeq
                                saveError = null
                                viewModel.save(serverId, relDir, title, content)
                            }
                        },
                        enabled = title.isNotBlank() && client != null && rootPath != null && !saving,
                        loading = saving,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (showDeviceSheet) {
        OptionListSheet(
            title = stringResource(R.string.quick_save_device),
            options = profiles.map { PickerOption(id = it.id, label = it.name, note = it.baseUrl) },
            selectedId = serverId.ifBlank { null },
            onSelect = { option ->
                if (option.id != serverId) relDir = ""  // 换设备，旧目录不再有意义
                serverId = option.id
                showDeviceSheet = false
            },
            onDismiss = { showDeviceSheet = false },
        )
    }
    // client/root 还没就绪时不弹浏览器（也不在 composition 里写 state——
    // 残留的 showDirBrowser 等就绪后自然弹出，正是用户点的那一下）。
    LaunchedEffect(client, rootPath) {
        if ((client == null || rootPath == null) && showDirBrowser) showDirBrowser = false
    }
    if (showDirBrowser) {
        val c = client
        val root = rootPath
        if (c != null && root != null) {
            DirectoryBrowserSheet(
                title = stringResource(R.string.quick_save_folder),
                confirmLabel = stringResource(R.string.quick_save_choose_dir),
                showRoot = false,
                initialPath = if (relDir.isEmpty()) null else "$root/$relDir",
                loadHome = { c.getHomeDirectory() },
                loadDirs = { p -> c.listDirectoryEntries(p) },
                onSelect = { abs ->
                    // 批v174 N4: an out-of-workspace pick now SAYS so and keeps the
                    // previous dir — the old `?: ""` silently reset the picker to
                    // the root, which was the "目录总被重置" complaint's other half.
                    val rel = relPathOrNull(root, abs)
                    if (rel == null) {
                        saveError = outsideErrorLabel
                    } else {
                        saveError = null
                        relDir = rel
                    }
                    showDirBrowser = false
                },
                onDismiss = { showDirBrowser = false },
            )
        }
    }
}

/** FolderFilesView 同款换算（批v172 D7 复制版，浏览器选点 → root 相对目录）。
 *  服务端对绝对路径会静默 re-root，浏览器给的是绝对路径，必须先落到工作区
 *  坐标；根本身映射为空串，出界返回 null。 */
private fun relPathOrNull(rootPath: String, absPath: String): String? = when {
    absPath == rootPath -> ""
    absPath.startsWith("$rootPath/") -> absPath.removePrefix("$rootPath/").trim('/')
    else -> null
}

/** 批v175 审查: Compose text fields choke far below the server's 50 MB limit —
 *  FolderFilesView's editor enforces the same 2M-char ceiling (MAX_EDIT_CHARS). */
private const val MAX_QUICK_SAVE_CHARS = 2_000_000
