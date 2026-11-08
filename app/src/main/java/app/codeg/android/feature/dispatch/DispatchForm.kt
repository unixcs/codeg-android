package app.codeg.android.feature.dispatch

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Assignment
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.datastore.AppSettings
import app.codeg.android.core.datastore.DispatchRef
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.data.DispatchTargets
import app.codeg.android.core.data.LocalAttachment
import app.codeg.android.core.designsystem.component.AgentChipRow
import app.codeg.android.core.designsystem.component.CodegAttachActions
import app.codeg.android.core.designsystem.component.CodegAttachmentRow
import app.codeg.android.core.designsystem.component.CodegFormTextField
import app.codeg.android.core.designsystem.component.CodegSectionLabel
import app.codeg.android.core.designsystem.component.FullscreenTextEditorDialog
import app.codeg.android.core.designsystem.component.DeviceChipRow
import app.codeg.android.core.designsystem.component.OptionListSheet
import app.codeg.android.core.designsystem.component.PickerField
import app.codeg.android.core.designsystem.component.PickerOption
import app.codeg.android.core.designsystem.component.PrimaryButton
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.core.model.WorkTaskTemplateInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What [DispatchForm] reads from its host, beyond [DispatchTargets]. Hosts are
 * ViewModels with DIFFERENT dispatch pipelines (§1.5 双宿主):
 *
 *  - 宿主 A [app.codeg.android.feature.inbox.InboxViewModel] — idea dispatch with
 *    the UNKNOWN ledger (probe / redispatch machinery stays there).
 *  - 宿主 B [app.codeg.android.feature.quick.QuickActionViewModel] — the ball's
 *    quick task path; an ambiguous submit lands in the SAME global ledger and is
 *    resolved from the inbox tab's cards.
 *
 * The form is state+callback driven and holds NO pipeline of its own: it never
 * talks to [app.codeg.android.core.data.DispatchService] directly.
 */
interface DispatchFormHost {
    val targets: StateFlow<DispatchTargets>
    val formUi: StateFlow<DispatchFormUi>
    val settings: StateFlow<AppSettings>

    /** 批v154: copies picked files into app-private storage. The form only stages
     *  LOCAL copies; [DispatchService] uploads them to the chosen device. */
    val attachmentStager: app.codeg.android.core.data.AttachmentStager

    /** P0: dispatch-form drafts (title + body) — the same store the composer uses. */
    val drafts: app.codeg.android.core.datastore.DraftStore

    fun loadTarget(serverId: String)
    fun loadBranches(serverId: String, folder: FolderDetail)

    /** 批v155: the agent's mode list for the 模型 row — cache-first probe via
     *  [app.codeg.android.core.data.AgentOptionsProber] (one CLI spawn per
     *  server+agent+dir, shared across forms). null = probe failed. */
    suspend fun probeAgentOptions(
        serverId: String,
        agentType: AgentType,
        workingDir: String?,
        configValues: Map<String, String> = emptyMap(),
    ): app.codeg.android.core.model.AgentOptionsSnapshot?

    /** M1: the server's configured model providers for one agent (`list_model_
     *  providers`) — the 模型 row's server truth. Never throws: empty list on
     *  any failure (old server, no token), and the form falls back to the
     *  probe-only picker. */
    suspend fun modelProviderEntries(
        serverId: String,
        agentType: AgentType,
    ): List<app.codeg.android.core.model.ModelProviderInfo>

    /** P0-5: remember the 「创建后立即开始」 choice for the next time the form opens. */
    fun setTaskAutoStartDefault(value: Boolean)

    /** Fire the pipeline; [onDone] delivers the ledger ref (PENDING included). */
    fun dispatch(submit: DispatchFormSubmit, onDone: (DispatchRef) -> Unit)

    // ── Fleet M5 任务模板 (newTaskMode only) ────────────────────────────────
    // Only host C (task board composer) renders the 模板 chip row; hosts A/B
    // keep these defaults and never see it. Templates are PER-DEVICE — the form
    // reloads them on every device change and prefill never touches network.

    /** The device's templates (server returns them name-asc). */
    val taskTemplates: StateFlow<List<WorkTaskTemplateInfo>>
        get() = EMPTY_TEMPLATES

    /** Fetch templates for one device. Never throws: a failed/old-server load
     *  degrades to an empty list (the row hides). */
    fun loadTaskTemplates(serverId: String) {}

    /** Chip ✕ delete — the confirm dialog lives in the form, the feedback toast
     *  in the host. */
    fun deleteTaskTemplate(serverId: String, id: Int) {}

    companion object {
        /** Single shared empty for defaulting hosts — a fresh MutableStateFlow
         *  per getter read would reset collectors on every recomposition. */
        private val EMPTY_TEMPLATES: StateFlow<List<WorkTaskTemplateInfo>> =
            MutableStateFlow(emptyList())
    }
}

/** Form-level host state: dispatch progress, errors, and the branch list. */
data class DispatchFormUi(
    val dispatching: Boolean = false,
    val error: String? = null,
    val branches: List<String> = emptyList(),
    val branchesLoading: Boolean = false,
    val branchesError: Boolean = false,
    /** serverId:folderPath key the branch list belongs to — a stale list must
     *  never be offered for a different project (v146 invariant). */
    val branchFolderKey: String? = null,
)

/** Cap per dispatch (image + file picks share it) — raised 10 → 20 in 批v155. */
private val MAX_DISPATCH_ATTACHMENTS = app.codeg.android.core.data.AttachmentLimits.MAX_IMAGES

/** Everything the form submits, resolved. The host owns guards beyond this. */
data class DispatchFormSubmit(
    val ideaId: String? = null,
    val serverId: String,
    val folderId: Int,
    val folderName: String,
    val agentType: AgentType?,
    val title: String,
    val description: String,
    val baseBranch: String?,
    /** 批v155: the picked agent mode (执行模式 row), list-validated by the form. */
    val modeId: String? = null,
    /** P0: picked config options (model / reasoning effort), id → value. Sent as
     *  `config_values` on a task; for a session they pin the conversation's first
     *  connect. Empty = the agent's own defaults. */
    val configValues: Map<String, String> = emptyMap(),
    val scheduleLocalMillis: Long?,
    val sessionMode: Boolean,
    val autoStart: Boolean = true,
    /** 批v162 P1: session mode only — track the created conversation on the
     *  task board. Ignored (and forced off) in task mode: a task is already a
     *  board row. */
    val addToBoard: Boolean = false,
    /** 批v154 — files to upload to the target device before the create. */
    val attachments: List<app.codeg.android.core.data.LocalAttachment> = emptyList(),
)

/**
 * The unified dispatch form (ui重构 v2 §1.5, doc1 §6.1 field order):
 * 执行方式 → 设备 chips → Agent chips → 项目 → 分支(任务) → 标题 → 描述 →
 * 定时(任务) → 派发摘要卡 → 底部主按钮（创建会话 / 创建任务）。
 *
 * All selectors are chips + BottomSheet (§六); the wheel era is gone. The form
 * is self-contained but the SHEET hosting it must keep the v148 drag lock, and
 * the RESULT dialog renders at screen level (v146 B-7 — it must survive the
 * sheet unmounting when a confirmed dispatch deletes the idea).
 *
 * v146 invariant carry-over (each marked at its site below): process-death
 * restore LaunchedEffects, branchFresh keying, past-schedule 30s ticker,
 * rememberSaveable keys, defaults preseed, canDispatch gate. The stale-folder
 * dispatch check and the UNKNOWN triple gate live in the HOSTS (both re-check
 * against real state at send time).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DispatchForm(
    host: DispatchFormHost,
    modifier: Modifier = Modifier,
    /** Host A (inbox) opens in session mode; host B (ball) opens in task mode. */
    defaultSessionMode: Boolean = true,
    /** 收件箱宿主传入草稿 id——CONFIRMED 后删草稿、UNKNOWN 卡片绑定都靠它（v140 契约）。 */
    ideaId: String? = null,
    /** Seed values for the title/description fields (host A prefills from the
     *  idea being dispatched; host B starts blank). Sheet-lifetime state — the
     *  sheet is disposed between opens, so a plain init matches the old sheet. */
    initialTitle: String = "",
    initialDescription: String = "",
    /** 批v155 任务板新建模式 (方案 A): task mode locked (no 执行方式 toggle),
     *  branch row dropped — the server bases the worktree on the repo's current
     *  HEAD and the APP never surfaces the worktree — and schedule row dropped
     *  (定时 lives in the long-press menu after creation). */
    newTaskMode: Boolean = false,
    /** Ledger ref of an accepted submit — the screen renders the result dialog. */
    onResult: (DispatchRef) -> Unit = {},
    /** 批v154: files already owned by the source (an idea's attachments). The form
     *  shows them as removable chips; it only DELETES copies it staged itself. */
    initialAttachments: List<app.codeg.android.core.data.LocalAttachment> = emptyList(),
    /** P0 草稿: non-null persists title/body per (server, entry, project). The inbox
     *  idea path passes null — its fields are seeded from the idea and must not be
     *  overwritten by an older draft. */
    draftEntry: String? = null,
) {
    val colors = CodegTheme.colors
    val targets by host.targets.collectAsStateWithLifecycle()
    val ui by host.formUi.collectAsStateWithLifecycle()
    val settings by host.settings.collectAsStateWithLifecycle()

    // rememberSaveable ids only (never objects): a rotation/process death keeps
    // the selection while the target lists reload from the host (v146).
    var device by rememberSaveable { mutableStateOf<String?>(null) }
    var folderId by rememberSaveable { mutableStateOf<Int?>(null) }
    var agent by rememberSaveable { mutableStateOf<AgentType?>(null) }
    var branch by rememberSaveable { mutableStateOf<String?>(null) }
    var sessionMode by rememberSaveable { mutableStateOf(!newTaskMode && defaultSessionMode) }
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    var description by rememberSaveable { mutableStateOf(initialDescription) }
    // P0 草稿: saving stays OFF until the stored draft was read back — otherwise the
    // first (blank) frame would erase it. [draftClosed] stops the saver after a
    // confirmed submit so the just-sent text can't come back.
    var draftRestored by remember(draftEntry) { mutableStateOf(false) }
    var draftClosed by remember(draftEntry) { mutableStateOf(false) }
    // P0-5 创建后立即开始: seeded from the remembered choice; the switch writes it
    // back on change, so the next open starts where the user left it.
    var taskAutoStart by rememberSaveable { mutableStateOf(settings.taskAutoStartDefault) }
    // 批v162 P1: session mode's 「加入任务板」. Deliberately NOT persisted: it is
    // a per-dispatch intent ("watch this one"), not a standing preference — a
    // remembered ON would silently clutter the board with every later session.
    var addToBoard by rememberSaveable { mutableStateOf(false) }
    var autoStartTouched by rememberSaveable { mutableStateOf(false) }
    var scheduled by rememberSaveable { mutableStateOf(false) }
    var scheduleMillis by rememberSaveable { mutableStateOf(0L) }
    // 批v155/P0-2 权限模式行 (plan/Build): these were mislabelled 「模型」 — they
    // are the ACP session modes. modeId survives process death, the OPTIONS list
    // does not: a restored id must revalidate against the fresh probe.
    var modeId by rememberSaveable { mutableStateOf<String?>(null) }
    var modeOptions by remember { mutableStateOf<List<app.codeg.android.core.model.SessionModeInfo>>(emptyList()) }
    var modeSheet by remember { mutableStateOf(false) }
    // P0-2 model / reasoning effort: the same probe returns the agent's config
    // catalog. Picks are USER-ONLY — an absent entry means "the agent's own
    // current value" (what the row displays), so a probe never writes its own
    // output back into the next probe (no feedback loop) and an untouched form
    // sends no config_values at all.
    var agentOptions by remember { mutableStateOf<app.codeg.android.core.model.AgentOptionsSnapshot?>(null) }
    // M1: the server's configured provider models (list_model_providers) — the
    // 模型 row's server truth. Merged into the display snapshot below: they top
    // up a live probe catalog and carry the row alone when the probe fails.
    var providerModels by remember {
        mutableStateOf<List<app.codeg.android.core.model.ModelProviderInfo>>(emptyList())
    }
    val configPicks = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    var configSheetId by remember { mutableStateOf<String?>(null) }
    var optionsLoading by remember { mutableStateOf(false) }
    var optionsFailed by remember { mutableStateOf(false) }
    // 批v154 attachments. A plain remember (not saveable): the staged model is not
    // Bundle-able, and the sheet closes on rotation anyway — the private copies
    // simply outlive the chip list. Locally staged copies are ours to delete on
    // removal; the ones handed in by the host (an idea's attachments) are not.
    val attachments = remember {
        androidx.compose.runtime.mutableStateListOf<LocalAttachment>().apply {
            addAll(initialAttachments)
        }
    }
    var stagingAttachments by remember { mutableStateOf(false) }
    var showDescFullscreen by rememberSaveable { mutableStateOf(false) }
    // 批v155 段3: which image attachment the fullscreen viewer shows (null = closed).
    // rememberSaveable: rotation no longer closes the viewer (R2D#4).
    var imageViewerIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val stagedOwners = remember { mutableSetOf<String>() }
    val attachmentScope = rememberCoroutineScope()
    // Stage picked uris off the main thread; the private copy is what the idea /
    // dispatch persists (a SAF grant dies with the screen).
    fun stage(uris: List<android.net.Uri>, images: Boolean) {
        if (uris.isEmpty()) return
        stagingAttachments = true
        attachmentScope.launch {
            val staged = uris.mapNotNull { uri ->
                host.attachmentStager.stage(uri, null, null, images)
            }
            val room = (MAX_DISPATCH_ATTACHMENTS - attachments.size).coerceAtLeast(0)
            val accepted = staged.take(room)
            staged.drop(room).forEach(host.attachmentStager::discard)
            attachments.addAll(accepted)
            accepted.forEach { stagedOwners += it.id }
            stagingAttachments = false
        }
    }
    val pickDispatchImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_DISPATCH_ATTACHMENTS),
    ) { uris -> stage(uris, images = true) }
    val pickDispatchFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> stage(uris, images = false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }
    var folderSheet by remember { mutableStateOf(false) }
    var branchSheet by remember { mutableStateOf(false) }
    // Result of THIS composition's submit; suppresses the error line once the
    // screen-level dialog is up (same rule as the pre-rebuild sheet).
    var result by remember { mutableStateOf<DispatchRef?>(null) }
    // Fleet M5: chip awaiting its delete confirmation (plain remember — the
    // dialog dies with the sheet like every other transient here).
    var templatePendingDelete by remember { mutableStateOf<WorkTaskTemplateInfo?>(null) }
    // Fleet M5: chip tapped while the form already has content — applying a
    // template overwrites 标题/描述 wholesale, so an existing draft of user text
    // gets a confirm first (a blank form applies immediately: the 3-second
    // acceptance path stays one tap).
    var templateConfirmApply by remember { mutableStateOf<WorkTaskTemplateInfo?>(null) }

    // 30s ticker (v146 invariant): "schedule in the past" goes stale as the wall
    // clock advances with no state change — the button must not stay enabled.
    var clockTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            clockTick = System.currentTimeMillis()
        }
    }

    val folder = targets.folders.firstOrNull { it.id == folderId }
    // branchFresh (v146 invariant): a picked branch only counts while it belongs
    // to the CURRENT device+repo — function-scope so the branch sheet sees it too.
    val branchFresh = ui.branchFolderKey == (device?.let { d -> folder?.let { f -> "$d:${f.path}" } })
    // Project (or device) switched → the picked branch no longer describes the
    // new repo; reset and reload. Session mode never sends base_branch — reload
    // only on the task path so toggling the mode leaks no stale list either way.
    LaunchedEffect(device, folderId, sessionMode) {
        branch = null
        val d = device
        if (!sessionMode && !newTaskMode && d != null && folder != null) host.loadBranches(d, folder)
    }
    // P0-2 探测: agent + project → describeAgentOptions (modes + config catalog).
    // Session mode probes the EFFECTIVE agent (explicit pick, else the project
    // default) so the model row also works when creating a conversation. The
    // prober is cache-first; a target change resets picks, a config change
    // re-probes with the picked values so the effort list matches the model.
    val probeAgent = agent
        ?: folder?.defaultAgentType?.takeIf { it != AgentType.UNKNOWN }
    // One place that folds a probe result into the rows (target probe + re-probe).
    fun applyProbe(snapshot: app.codeg.android.core.model.AgentOptionsSnapshot) {
        agentOptions = snapshot
        val modes = snapshot.modes?.availableModes.orEmpty()
        modeOptions = modes
        modeId = snapshot.modes?.currentModeId?.takeIf { cur -> modes.any { it.id == cur } }
    }
    // M1: what the 模型/推理 rows actually render — the probe catalog with the
    // server's provider models merged in (and, on a failed probe, carried by
    // the provider models alone). The config sheet resolves options from THIS
    // snapshot too, so a synthesized model row opens its picker like any other.
    val displayOptions = remember(agentOptions, providerModels) {
        mergeProviderModels(agentOptions, providerModels)
    }
    LaunchedEffect(device, probeAgent, folder?.path) {
        modeId = null
        modeOptions = emptyList()
        agentOptions = null
        configPicks.clear()
        optionsLoading = false
        optionsFailed = false
        val d = device
        val a = probeAgent
        val f = folder
        if (d == null || a == null || f == null) return@LaunchedEffect
        optionsLoading = true
        val snapshot = try {
            host.probeAgentOptions(d, a, f.path)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        optionsLoading = false
        if (snapshot == null) {
            optionsFailed = true
        } else {
            applyProbe(snapshot)
        }
    }
    // A picked model can change the reasoning-effort choices — re-probe with the
    // current picks. Deliberately silent: the old rows stay usable meanwhile, and
    // a failed re-probe must not blank a catalog the user is looking at.
    val configKey = configPicks.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" }
    LaunchedEffect(configKey) {
        if (configKey.isEmpty()) return@LaunchedEffect
        val d = device ?: return@LaunchedEffect
        val a = probeAgent ?: return@LaunchedEffect
        val f = folder ?: return@LaunchedEffect
        val snapshot = try {
            host.probeAgentOptions(d, a, f.path, configPicks.toMap())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (snapshot != null) agentOptions = snapshot
    }
    // M1: provider models per (device, agent) — fresh on every target change
    // (the settings page may have edited them since). The host never throws;
    // the belt-and-suspenders catch keeps a stray failure from killing the form.
    LaunchedEffect(device, probeAgent) {
        providerModels = emptyList()
        val d = device ?: return@LaunchedEffect
        val a = probeAgent ?: return@LaunchedEffect
        providerModels = try {
            host.modelProviderEntries(d, a)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }
    // Process-death restore (v146 invariant): saved device/folder/agent come back
    // but the loaded folders/agents died with the process — re-request once.
    LaunchedEffect(device) {
        val d = device ?: return@LaunchedEffect
        if (targets.folders.isEmpty() && !targets.foldersLoading && targets.error == null) {
            host.loadTarget(d)
        }
    }
    // A restored/default agent must actually exist on the device: once the real
    // agent list lands, drop a selection it doesn't contain.
    LaunchedEffect(targets.agents) {
        val a = agent
        if (a != null && targets.agents.isNotEmpty() && a !in targets.agents) agent = null
    }
    // Same for a restored folderId the loaded project list doesn't contain.
    LaunchedEffect(targets.folders) {
        val fid = folderId
        if (fid != null && targets.folders.isNotEmpty() && targets.folders.none { it.id == fid }) {
            folderId = null
        }
    }

    // 派发默认预选 (v146 invariant): settings-stored device/project/agent
    // (设置→通用→派发默认选项) auto-select when the form opens; switching
    // devices resets project/agent to the defaults.
    LaunchedEffect(targets.profiles) {
        if (device == null && settings.dispatchDefaultDevice.isNotBlank() &&
            targets.profiles.any { it.id == settings.dispatchDefaultDevice }
        ) {
            device = settings.dispatchDefaultDevice
            folderId = null
            agent = null
            host.loadTarget(settings.dispatchDefaultDevice)
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

    // Adopt the remembered choice until the user flips the switch this session.
    LaunchedEffect(settings.taskAutoStartDefault) {
        if (!autoStartTouched) taskAutoStart = settings.taskAutoStartDefault
    }

    // P0 草稿: per server + entry + project. Nothing to key on until the defaults
    // have settled (device/folder null → no draft, no save).
    val draftKey = remember(draftEntry, device, folderId) {
        val e = draftEntry
        val d = device
        val f = folderId
        if (e == null || d == null || f == null) null
        else app.codeg.android.core.datastore.DraftStore.dispatchKey(d, e, f)
    }
    val draftScope = rememberCoroutineScope()
    LaunchedEffect(draftKey) {
        draftRestored = false
        val key = draftKey ?: return@LaunchedEffect
        val stored = host.drafts.draftNow(key)
        // Only fill blanks: a host-seeded value (or a restored saveable) wins.
        if (title.isBlank()) title = stored.title
        if (description.isBlank()) description = stored.text
        draftRestored = true
    }
    LaunchedEffect(draftKey, draftRestored) {
        val key = draftKey ?: return@LaunchedEffect
        if (!draftRestored) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { title to description }
            .distinctUntilChanged()
            .collectLatest { (t, b) ->
                kotlinx.coroutines.delay(500)
                if (draftClosed) return@collectLatest
                if (t.isBlank() && b.isBlank()) host.drafts.clearDraft(key)
                else host.drafts.setDraft(key, t, b)
            }
    }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ① 执行方式（会话 / 任务卡）— doc1 §6.1 first field. 任务板新建模式
        // (批v155) locks task mode: no toggle, no session note.
        if (!newTaskMode) {
            FormSectionLabel(stringResource(R.string.dispatch_mode))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeCard(
                    label = stringResource(R.string.dispatch_mode_session),
                    note = stringResource(R.string.dispatch_mode_session_note),
                    selected = sessionMode,
                    icon = Icons.Rounded.Forum,
                    onClick = { sessionMode = true },
                    modifier = Modifier.weight(1f),
                )
                ModeCard(
                    label = stringResource(R.string.dispatch_mode_task),
                    note = stringResource(R.string.dispatch_mode_task_note),
                    selected = !sessionMode,
                    icon = Icons.Rounded.Assignment,
                    onClick = { sessionMode = false },
                    modifier = Modifier.weight(1f),
                )
            }
            if (sessionMode) {
                Text(
                    stringResource(R.string.dispatch_session_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                )
            }
        }

        // ② 设备 chips — real profiles; load errors mark the chip (never fake
        // online dots — liveness comes only from the host's health precheck).
        FormSectionLabel(stringResource(R.string.dispatch_section_device_agent))
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
                    host.loadTarget(id)
                }
            },
        )
        if (targets.foldersError || targets.error != null || targets.agentsError) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    targets.error
                        ?: stringResource(if (targets.foldersError) R.string.dispatch_folders_failed else R.string.dispatch_agents_failed),
                    color = colors.danger,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { device?.let { host.loadTarget(it) } },
                    modifier = Modifier.heightIn(min = 44.dp),
                ) {
                    Text(stringResource(R.string.dispatch_retry))
                }
            }
        }

        // ③ Agent chips — first chip is 自动 (no override; project default).
        val autoAgentLabel = stringResource(R.string.dispatch_agent_auto)
        AgentChipRow(
            autoLabel = autoAgentLabel,
            agents = targets.agents.map { PickerOption(id = it.name, label = it.displayName) },
            selectedId = agent?.name,
            onSelect = { name -> agent = AgentType.entries.firstOrNull { it.name == name } },
        )
        if (agent == null) {
            Text(
                stringResource(R.string.dispatch_agent_auto_note),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
            )
        }

        // ④ 项目 PickerField → OptionListSheet.
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

        // ⑤b 权限模式 (plan/Build) — the agent's live MODE list. P0-2: this
        // row used to be labelled 「模型」, which is why the picker only ever
        // offered plan/Build. It now sits under its own name, in both modes.
        if (modeOptions.isNotEmpty()) {
            PickerField(
                icon = Icons.Rounded.Tune,
                label = stringResource(R.string.dispatch_mode_permission),
                value = modeOptions.firstOrNull { it.id == modeId }
                    ?.let { it.name.ifBlank { it.id } },
                placeholder = "",
                onClick = { modeSheet = true },
            )
        }

        // ⑤c 模型 / ⑤d 推理强度 — the agent's config catalog. Shown in BOTH
        // modes: a conversation pins them through its first acp_connect, a task
        // sends them as config_values. Untouched rows = the agent's own default.
        // M1: reads the MERGED snapshot (probe + server provider models).
        val modelOption = displayOptions?.configOptions?.firstOrNull {
            it.id == "model" || it.category == "model"
        }
        val effortOption = displayOptions?.configOptions?.firstOrNull {
            it.id != modelOption?.id &&
                (it.category == "thought_level" || it.id == "reasoning_effort")
        }
        if (modelOption != null) {
            PickerField(
                icon = Icons.Rounded.Tune,
                label = stringResource(R.string.dispatch_model),
                value = modelOption.displayValue(configPicks),
                // A provider-carried row has no agent-reported current value —
                // a blank placeholder reads as a dead row; hint that it taps.
                placeholder = stringResource(R.string.dispatch_model_pick_hint),
                onClick = { configSheetId = modelOption.id },
            )
        } else {
            // No model selector yet: say which reason, never fake a list.
            PickerField(
                icon = Icons.Rounded.Tune,
                label = stringResource(R.string.dispatch_model),
                value = null,
                placeholder = stringResource(
                    when {
                        optionsLoading -> R.string.dispatch_model_loading
                        optionsFailed -> R.string.dispatch_model_failed
                        // R3 P3-6: no project selected → the probe hasn't run;
                        // "无模型" reads as a verdict, not a pending state.
                        folder == null -> R.string.dispatch_model_pending
                        probeAgent == null -> R.string.dispatch_model_needs_agent
                        else -> R.string.dispatch_model_none
                    },
                ),
                onClick = {},
                enabled = false,
            )
        }
        if (effortOption != null) {
            PickerField(
                icon = Icons.Rounded.Tune,
                label = stringResource(R.string.dispatch_reasoning),
                value = effortOption.displayValue(configPicks),
                placeholder = "",
                onClick = { configSheetId = effortOption.id },
            )
        }
        if (optionsFailed && !optionsLoading && modelOption == null) {
            Text(
                stringResource(R.string.dispatch_model_failed_note),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
            )
        }

        // ⑤ 分支（仅任务模式）— real git_list_all_branches; failure keeps the
        // project default dispatchable (上游不支持的字段绝不假装支持).
        // 任务板新建模式不显示 (批v155 方案 A): base follows HEAD, worktree invisible.
        if (!sessionMode && !newTaskMode) {
            PickerField(
                icon = Icons.Rounded.AccountTree,
                label = stringResource(R.string.dispatch_branch),
                value = if (branch != null && branchFresh) branch else null,
                valueNote = if (branch == null || !branchFresh) {
                    folder?.gitBranch
                        ?.let { stringResource(R.string.dispatch_branch_follow, it) }
                        ?: stringResource(R.string.dispatch_branch_follow_unknown)
                } else null,
                placeholder = stringResource(
                    when {
                        ui.branchesLoading -> R.string.dispatch_branch_loading
                        else -> R.string.dispatch_branch_follow_unknown
                    },
                ),
                onClick = {
                    val f = folder
                    val d = device
                    when {
                        ui.branchesLoading -> Unit
                        ui.branchesError && d != null && f != null -> host.loadBranches(d, f)
                        else -> branchSheet = true
                    }
                },
            )
            if (ui.branchesError && !ui.branchesLoading) {
                Text(
                    stringResource(R.string.dispatch_branch_failed),
                    color = colors.danger,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        // Fleet M5 模板 chips (new-task composer only): tap prefills 标题/描述
        // (+ agent when the snapshot's agent_type maps and the device still
        // offers it); the trailing ✕ deletes after a confirm. Templates are
        // PER-DEVICE — reloaded on every device change, and the row renders only
        // once a device is picked (the VM's list survives across sheet opens, so
        // a deviceless composer must not show the previous device's chips).
        if (newTaskMode) {
            val templates by host.taskTemplates.collectAsStateWithLifecycle()
            LaunchedEffect(device) {
                // A dialog pinned to device A's chip must never fire against
                // device B (ids are per-device spaces — cross-device deletes hit
                // lookalike rows).
                templatePendingDelete = null
                templateConfirmApply = null
                device?.let { host.loadTaskTemplates(it) }
            }

            // V1 prefill scope: full prompt text (prompt_blocks joined, falling
            // back to display_text — same precedence as WorkTaskInfo.
            // configPromptText) + the agent chip. mode_id/config_values are
            // deliberately NOT restored — they keep "the agent's own defaults".
            // 标题/描述/agent 三字段都无条件按模板重置（空文本即清空）——套用
            // 的承诺与确认框文案逐字一致，不留半套用状态。
            fun applyTaskTemplate(tpl: WorkTaskTemplateInfo) {
                title = tpl.title
                val cfg = tpl.config
                val blocks = (cfg["prompt_blocks"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonObject)?.get("text") as? JsonPrimitive }
                    ?.mapNotNull { it.contentOrNull }
                    ?.joinToString("\n\n")
                    .orEmpty()
                val display = (cfg["display_text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                description = blocks.ifBlank { display }
                (cfg["agent_type"] as? JsonPrimitive)?.contentOrNull?.let { wire ->
                    // Only preselect an agent this device actually offers — a
                    // snapshot from an uninstalled agent must not jam the form.
                    AgentType.entries.firstOrNull { it.wire == wire }
                        ?.takeIf { picked -> targets.agents.isEmpty() || targets.agents.any { it == picked } }
                        ?.let { agent = it }
                }
            }

            if (device != null && templates.isNotEmpty()) {
                FormSectionLabel(stringResource(R.string.task_template_row_label))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                ) {
                    items(templates, key = { it.id }) { tpl ->
                        // House chip look (Pickers ChipRowBase): Surface(onClick)
                        // keeps Material's minimum-touch guarantee (review round 6
                        // — bare clickable modifiers bypass the 48dp rule); the
                        // trailing ✕ is the delete entry (discoverable and
                        // TalkBack-labelled, unlike a long-press).
                        Surface(
                            onClick = {
                                // 已有任何内容（含仅选了 agent）就先确认——
                                // 套用是全字段替换，不是只补空位。
                                if (title.isNotBlank() || description.isNotBlank() || agent != null) {
                                    templateConfirmApply = tpl
                                } else {
                                    applyTaskTemplate(tpl)
                                }
                            },
                            shape = RoundedCornerShape(999.dp),
                            color = colors.bgElevated,
                            contentColor = colors.textPrimary,
                            border = BorderStroke(1.dp, colors.surfaceStroke),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 12.dp),
                            ) {
                                Text(
                                    tpl.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .padding(vertical = 7.dp)
                                        .widthIn(max = 200.dp),
                                )
                                IconButton(
                                    onClick = { templatePendingDelete = tpl },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        // M6 审查：破坏性操作前 TalkBack 用户必须
                                        // 听得到删的是哪个模板。
                                        contentDescription = stringResource(
                                            R.string.task_template_delete_named, tpl.name,
                                        ),
                                        tint = colors.textTertiary,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            templateConfirmApply?.let { tpl ->
                AlertDialog(
                    onDismissRequest = { templateConfirmApply = null },
                    title = { Text(stringResource(R.string.task_template_apply_title)) },
                    text = { Text(stringResource(R.string.task_template_apply_msg, tpl.name)) },
                    confirmButton = {
                        TextButton(onClick = {
                            templateConfirmApply = null
                            applyTaskTemplate(tpl)
                        }) { Text(stringResource(R.string.task_template_apply_confirm)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { templateConfirmApply = null }) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    },
                )
            }
            templatePendingDelete?.let { tpl ->
                AlertDialog(
                    onDismissRequest = { templatePendingDelete = null },
                    title = { Text(stringResource(R.string.task_template_delete_title)) },
                    text = { Text(stringResource(R.string.task_template_delete_msg, tpl.name)) },
                    confirmButton = {
                        TextButton(onClick = {
                            templatePendingDelete = null
                            device?.let { host.deleteTaskTemplate(it, tpl.id) }
                        }) {
                            Text(
                                stringResource(R.string.common_delete),
                                color = colors.danger,
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { templatePendingDelete = null }) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    },
                )
            }
        }

        // ⑥ 标题 + 描述. 会话模式的描述就是首条指令，空描述派出去只会得到
        // 一个空会话 — the guard repeats at canDispatch.
        FormSectionLabel(stringResource(R.string.dispatch_section_description))
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
            minLines = 3,
            maxLines = 12,
            // 批v154: long descriptions get the same near-fullscreen editor as the
            // composer — a 3-line box on a phone hides the tail while typing.
            trailingIcon = {
                IconButton(onClick = { showDescFullscreen = true }) {
                    Icon(
                        Icons.Rounded.OpenInFull,
                        contentDescription = stringResource(R.string.compose_edit_fullscreen),
                        tint = colors.textSecondary,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        // 批v154 附件：图片 / 任意文件 → 应用私有目录落盘，派发时上传到目标设备。
        if (stagingAttachments) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.common_loading),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        if (attachments.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                attachments.forEach { att ->
                    // 批v155 段3: image rows open the fullscreen viewer across
                    // this form's images; W10 card styling via the shared row.
                    CodegAttachmentRow(
                        attachment = att,
                        onRemove = {
                            attachments.removeAll { it.id == att.id }
                            if (att.id in stagedOwners) {
                                stagedOwners -= att.id
                                host.attachmentStager.discard(att)
                            }
                        },
                        onClick = if (att.isImage) {
                            {
                                imageViewerIndex = attachments.filter { it.isImage }
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
            pickFilesLabel = stringResource(R.string.attach_add_file),
            enabled = !stagingAttachments && attachments.size < MAX_DISPATCH_ATTACHMENTS,
            onPickImages = {
                pickDispatchImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onPickFiles = { pickDispatchFiles.launch(arrayOf("*/*")) },
        )
        // R3 P2-1: task mode needs it too — the server's validate_draft rejects
        // an empty prompt with 422 ("prompt is required"); a title-only create
        // used to pass the gate and die server-side.
        if (description.isBlank() && attachments.isEmpty()) {
            Text(
                stringResource(
                    if (sessionMode) R.string.dispatch_session_desc_required
                    else R.string.dispatch_task_desc_required,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
            )
        }
        // R3-A P3: the title gate had no hint — the user who filled everything
        // else saw a grey 主按钮 with no reason why.
        if (title.isBlank()) {
            Text(
                stringResource(R.string.dispatch_title_required),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
            )
        }
        // 批v155 段3: the form's fullscreen image viewer (attachment rows above).
        imageViewerIndex?.let { idx ->
            app.codeg.android.core.designsystem.component.ImageViewerDialog(
                images = attachments.filter { it.isImage }
                    .map { app.codeg.android.core.designsystem.component.ImageViewerSource.File(it.localPath) },
                initialIndex = idx,
                onDismiss = { imageViewerIndex = null },
            )
        }

        // ⑥a 加入任务板（仅会话模式）: a session is not a board row by default —
        // the form is the one place the user can say "track this one".
        if (sessionMode) {
            FormSectionLabel(stringResource(R.string.dispatch_add_to_board))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = addToBoard, onCheckedChange = { addToBoard = it })
                Text(
                    stringResource(R.string.dispatch_add_to_board_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        // ⑥b 创建后立即开始（仅任务模式）: off → 待办, on → 立刻入队并显示在进行中。
        if (!sessionMode) {
            FormSectionLabel(stringResource(R.string.dispatch_task_autostart))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = taskAutoStart,
                    onCheckedChange = {
                        taskAutoStart = it
                        autoStartTouched = true
                        host.setTaskAutoStartDefault(it)
                    },
                )
                Text(
                    stringResource(R.string.dispatch_task_autostart_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        // ⑦ 定时（仅任务模式）— 会话模式无定时（定时拉起的是 work_task）。
        // 任务板新建模式同样无定时 (批v155)：定时走卡片长按菜单。
        if (!sessionMode && !newTaskMode) {
            FormSectionLabel(stringResource(R.string.dispatch_schedule))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = scheduled, onCheckedChange = { scheduled = it })
                Text(
                    stringResource(if (scheduled) R.string.dispatch_schedule_set else R.string.dispatch_schedule_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clickable(enabled = scheduled) { showDatePicker = true },
                )
                if (scheduled && scheduleMillis > 0) {
                    val pattern = stringResource(R.string.dispatch_schedule_pattern)
                    Text(
                        remember(scheduleMillis, pattern) {
                            Instant.ofEpochMilli(scheduleMillis).atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern(pattern))
                        } + " (" + stringResource(R.string.dispatch_schedule_note) + ")",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textPrimary,
                    )
                }
            }
            // Murphy guard: a schedule switch without a time would silently
            // dispatch IMMEDIATE, a past time would fire at once (v146).
            val scheduleNotSet = scheduled && scheduleMillis <= 0L
            val scheduleInPast = scheduled && scheduleMillis in 1..clockTick
            if (scheduleNotSet) {
                Text(
                    stringResource(R.string.dispatch_schedule_not_set),
                    color = colors.danger,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (scheduleInPast) {
                Text(
                    stringResource(R.string.dispatch_schedule_past),
                    color = colors.danger,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // ⑧ 派发摘要卡 — full echo of what will be sent (doc1 §6.6).
        DispatchSummaryCard(
            sessionMode = sessionMode,
            device = targets.profiles.firstOrNull { it.id == device },
            folderName = folder?.name,
            agent = agent,
            folderDefaultAgent = folder?.defaultAgentType,
            modelName = modelOption?.displayValue(configPicks),
            branch = if (!sessionMode && !newTaskMode && branch != null && branchFresh) branch else null,
            followBranch = if (!sessionMode && !newTaskMode) folder?.gitBranch else null,
            scheduledAt = if (!sessionMode && !newTaskMode && scheduled && scheduleMillis > 0) scheduleMillis else null,
        )

        // Suppressed once the result dialog is up: a schedule/auto-start note
        // rides the same field and would render in error styling behind it.
        if (result == null) ui.error?.let {
            Text(it, color = colors.danger, style = MaterialTheme.typography.bodySmall)
        }

        // ⑨ 底部主按钮 — 创建会话 / 创建任务 (doc1 §6.7). canDispatch gate (v146).
        val scheduleNotSetNow = !sessionMode && scheduled && scheduleMillis <= 0L
        val scheduleInPastNow = !sessionMode && scheduled && scheduleMillis in 1..clockTick
        // R3 P2-1: validate_draft rejects an empty prompt in BOTH modes — the
        // form must never dispatch a title-only request (session mode already
        // required this; task mode used to slip through to a 422).
        val canDispatch = device != null && folderId != null && title.isNotBlank() &&
            (description.isNotBlank() || attachments.isNotEmpty()) &&
            !ui.dispatching && !scheduleNotSetNow && !scheduleInPastNow
        PrimaryButton(
            text = stringResource(
                when {
                    sessionMode -> R.string.dispatch_button_session
                    taskAutoStart -> R.string.dispatch_button_task_start
                    else -> R.string.dispatch_button_task
                },
            ),
            onClick = {
                val dev = device ?: return@PrimaryButton
                val fid = folderId ?: return@PrimaryButton
                // A branch picked against a stale folder list must never ride
                // along — branchFresh pins it to the current repo.
                val effectiveBranch = if (branch != null && branchFresh) branch else null
                // 会话模式的"自动"落到项目默认 Agent（没有则客户端默认）——
                // 服务端 create_conversation 需要具体 agent_type。
                val effectiveAgent = if (sessionMode) {
                    agent ?: folder?.defaultAgentType?.takeIf { it != AgentType.UNKNOWN }
                } else agent
                // 权限模式：restored id 必须能在当前列表里找到才放行（probe
                // 未回来时宁可 mode-less，不发未校验的 mode_id）。会话模式也带：
                // CONFIRMED 后写入 ConversationSelectorStore，首次 acp_connect 消费。
                val effectiveModeId = modeOptions.firstOrNull { it.id == modeId }?.id
                host.dispatch(
                    DispatchFormSubmit(
                        ideaId = ideaId,
                        serverId = dev,
                        folderId = fid,
                        folderName = targets.folders.firstOrNull { it.id == fid }?.name.orEmpty(),
                        agentType = effectiveAgent,
                        title = title,
                        description = description,
                        baseBranch = if (sessionMode || newTaskMode) null else effectiveBranch,
                        modeId = effectiveModeId,
                        configValues = configPicks.toMap(),
                        scheduleLocalMillis = if (!sessionMode && !newTaskMode && scheduled && scheduleMillis > 0) scheduleMillis else null,
                        sessionMode = sessionMode,
                        autoStart = taskAutoStart,
                        addToBoard = addToBoard && sessionMode,
                        attachments = attachments.toList(),
                    ),
                ) { ref ->
                    // A confirmed submit consumes the draft; UNKNOWN/PENDING keep
                    // it — that text is exactly what a redispatch has to resend.
                    if (ref.state == app.codeg.android.core.datastore.DispatchState.CONFIRMED) {
                        draftClosed = true
                        draftKey?.let { k -> draftScope.launch { host.drafts.clearDraft(k) } }
                    }
                    result = ref
                    onResult(ref)
                }
            },
            enabled = canDispatch,
            loading = ui.dispatching,
        )
    }

    if (showDescFullscreen) {
        FullscreenTextEditorDialog(
            text = description,
            onTextChange = { description = it },
            onDismiss = { showDescFullscreen = false },
            label = stringResource(R.string.inbox_desc_hint),
        )
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
    if (branchSheet) {
        // First row is the project default (跟随分支); picked branches only
        // count while branchFresh — a stale list can never be submitted.
        // \u0000 不可能出现在 git 分支名里——防止真实同名分支把 LazyColumn key 撞重复。
        val defaultId = "\u0000default"
        OptionListSheet(
            title = stringResource(R.string.dispatch_pick_branch),
            options = listOf(
                PickerOption(
                    id = defaultId,
                    label = folder?.gitBranch
                        ?.let { stringResource(R.string.dispatch_branch_follow, it) }
                        ?: stringResource(R.string.dispatch_branch_follow_unknown),
                ),
            ) + ui.branches.map { PickerOption(id = it, label = it) },
            selectedId = if (branch != null && branchFresh) branch else defaultId,
            onSelect = { option -> branch = if (option.id == defaultId) null else option.id },
            onDismiss = { branchSheet = false },
        )
    }
    if (modeSheet) {
        OptionListSheet(
            title = stringResource(R.string.dispatch_mode_permission),
            options = modeOptions.map { PickerOption(id = it.id, label = it.name.ifBlank { it.id }) },
            selectedId = modeId,
            onSelect = { option -> modeId = option.id },
            onDismiss = { modeSheet = false },
        )
    }
    // P0-2 config sheet: one generic list for whichever row opened it (model or
    // reasoning effort). Grouped catalogs are flattened in declaration order.
    // M1: resolves from the MERGED snapshot so a provider-carried model row opens.
    displayOptions?.configOptions?.firstOrNull { it.id == configSheetId }?.let { option ->
        OptionListSheet(
            title = option.name.ifBlank { stringResource(R.string.dispatch_model) },
            options = option.selectValues().map {
                PickerOption(id = it.value, label = it.name.ifBlank { it.value }, note = it.description)
            },
            selectedId = configPicks[option.id] ?: option.kind.currentValue,
            onSelect = { picked ->
                configPicks[option.id] = picked.id
                configSheetId = null
            },
            onDismiss = { configSheetId = null },
        )
    }
    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        pickedDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
                        showDatePicker = false
                        showTimePicker = true
                    }
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        ) {
            DatePicker(state = state)
        }
    }
    if (showTimePicker) {
        val now = LocalTime.now()
        val state = rememberTimePickerState(initialHour = now.hour, initialMinute = now.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text(stringResource(R.string.dispatch_pick_time)) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    val date = pickedDate ?: return@TextButton
                    scheduleMillis = date.atTime(LocalTime.of(state.hour, state.minute))
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    showTimePicker = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

/** Small section label above a field group. */
@Composable
private fun FormSectionLabel(text: String) {
    CodegSectionLabel(text, Modifier.padding(top = 6.dp))
}

/** One selectable 执行方式 card (会话 / 任务). W10: a real selection card —
 *  leading icon chip, radiusLg corners, accent wash + accent stroke when
 *  selected, hairline otherwise. */
@Composable
private fun ModeCard(
    label: String,
    note: String,
    selected: Boolean,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    val shape = RoundedCornerShape(CodegTheme.dimens.radiusLg)
    Row(
        modifier
            .clip(shape)
            // 批v170 克制化 → 批v171 角色化：选中态走 selectedBg/selectedBorder
            // 四角色（classic 皮肤下=accent 实心，restrained=浅灰低透明）。
            .background(
                if (selected) colors.selectedBg else colors.bg.copy(alpha = 0.42f),
                shape,
            )
            .border(
                width = if (selected) 0.75.dp else 1.dp,
                color = if (selected) colors.selectedBorder else colors.surfaceStroke,
                shape = shape,
            )
            // R3-A P2: selectable (not bare clickable) carries the selected state
            // into the semantics tree — TalkBack announces which card is on.
            .selectable(
                selected = selected,
                role = androidx.compose.ui.semantics.Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) colors.selectedText else colors.textTertiary,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) colors.selectedText else colors.textPrimary,
            )
            Text(note, style = MaterialTheme.typography.labelSmall, color = if (selected) colors.selectedSubText else colors.textTertiary)
        }
        if (selected) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = colors.selectedText,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 派发摘要卡 (doc1 §6.6): every field echoed once, exactly what the host will
 * submit — no hidden defaults. Unknown optional fields show —.
 */
@Composable
private fun DispatchSummaryCard(
    sessionMode: Boolean,
    device: ServerProfile?,
    folderName: String?,
    agent: AgentType?,
    folderDefaultAgent: AgentType?,
    /** 批v155: the picked agent mode (模型 row), already resolved to a name. */
    modelName: String? = null,
    branch: String?,
    followBranch: String?,
    scheduledAt: Long?,
) {
    val colors = CodegTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CodegTheme.dimens.radiusLg))
            .background(colors.bg.copy(alpha = 0.42f), RoundedCornerShape(CodegTheme.dimens.radiusLg))
            .border(1.dp, colors.surfaceStroke, RoundedCornerShape(CodegTheme.dimens.radiusLg))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            stringResource(R.string.dispatch_summary_title),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            // 批v170：摘要卡标题降灰（数值行保持等宽在 SummaryRow）。
            color = colors.textTertiary,
        )
        SummaryRow(stringResource(R.string.dispatch_mode), stringResource(if (sessionMode) R.string.dispatch_mode_session else R.string.dispatch_mode_task))
        SummaryRow(stringResource(R.string.dispatch_device), device?.name)
        SummaryRow(stringResource(R.string.dispatch_project), folderName)
        SummaryRow(
            stringResource(R.string.dispatch_agent),
            agent?.displayName
                ?: folderDefaultAgent?.takeIf { it != AgentType.UNKNOWN }?.displayName,
        )
        if (modelName != null || !sessionMode) {
            SummaryRow(stringResource(R.string.dispatch_model), modelName)
        }
        if (!sessionMode) {
            SummaryRow(
                stringResource(R.string.dispatch_branch),
                branch ?: followBranch?.let { stringResource(R.string.dispatch_branch_follow, it) },
            )
            SummaryRow(
                stringResource(R.string.dispatch_schedule),
                scheduledAt?.let { millis ->
                    val pattern = java.time.format.DateTimeFormatter.ofPattern(
                        stringResource(R.string.dispatch_schedule_pattern),
                    )
                    remember(millis, pattern) {
                        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(pattern)
                    }
                },
            )
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String?) {
    val colors = CodegTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
        Text(
            value ?: "—",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textPrimary,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/** Values of a select option: the flat list first, else the grouped one. */
private fun app.codeg.android.core.model.SessionConfigOption.selectValues():
    List<app.codeg.android.core.model.SessionConfigSelectOption> =
    kind.options.ifEmpty { kind.groups.flatMap { it.options } }

/**
 * The label to show for a config option: the user's pick, else the agent's own
 * current value [picks] doesn't carry — resolved to a display name, falling back
 * to the raw value id (never fabricated, same rule as the session screen).
 */
private fun app.codeg.android.core.model.SessionConfigOption.displayValue(
    picks: Map<String, String>,
): String? {
    val current = picks[id] ?: kind.currentValue?.takeIf { it.isNotBlank() } ?: return null
    return selectValues().firstOrNull { it.value == current }?.name?.takeIf { it.isNotBlank() }
        ?: current
}

/**
 * M1: fold the server's configured provider models (`list_model_providers`,
 * agent-filtered by the prober) into the probed config catalog. They top up a
 * live model catalog (never duplicating ids) and carry the 模型 row alone when
 * the probe failed or hasn't run — server truth beats an unavailable CLI spawn.
 * Grouped catalogs get the providers as their own trailing group:
 * [selectValues] prefers a non-empty flat list, so appending there would
 * silently drop the probed groups.
 */
internal fun mergeProviderModels(
    probe: app.codeg.android.core.model.AgentOptionsSnapshot?,
    providers: List<app.codeg.android.core.model.ModelProviderInfo>,
): app.codeg.android.core.model.AgentOptionsSnapshot? {
    val choices = providers.mapNotNull { p ->
        val model = p.model?.trim().orEmpty()
        if (model.isEmpty()) null
        else app.codeg.android.core.model.SessionConfigSelectOption(
            value = model,
            name = model,
            description = p.name,
        )
    }
        // The picker keys rows by value — two providers exporting the same model
        // string must collapse to one row or the sheet's LazyColumn crashes on a
        // duplicate key (fleet M1 review blocker).
        .distinctBy { it.value }
    if (choices.isEmpty()) return probe
    fun carried() = app.codeg.android.core.model.SessionConfigOption(
        id = "model",
        name = "",
        category = "model",
        kind = app.codeg.android.core.model.SessionConfigKind(options = choices),
    )
    val probed = probe ?: return app.codeg.android.core.model.AgentOptionsSnapshot(
        configOptions = listOf(carried()),
    )
    val modelOption = probed.configOptions.firstOrNull { it.id == "model" || it.category == "model" }
        ?: return probed.copy(configOptions = probed.configOptions + carried())
    val known = modelOption.selectValues().map { it.value }.toSet()
    val missing = choices.filter { it.value !in known }
    if (missing.isEmpty()) return probed
    val merged = if (modelOption.kind.groups.isEmpty()) {
        modelOption.copy(kind = modelOption.kind.copy(options = modelOption.kind.options + missing))
    } else {
        modelOption.copy(
            kind = modelOption.kind.copy(
                groups = modelOption.kind.groups + app.codeg.android.core.model.SessionConfigSelectGroup(
                    group = "provider_models",
                    options = missing,
                ),
            ),
        )
    }
    return probed.copy(configOptions = probed.configOptions.map { if (it.id == modelOption.id) merged else it })
}
