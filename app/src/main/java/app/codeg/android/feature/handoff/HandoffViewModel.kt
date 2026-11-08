package app.codeg.android.feature.handoff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.DispatchOutcome
import app.codeg.android.core.data.DispatchService
import app.codeg.android.core.data.DispatchTargetLoader
import app.codeg.android.core.data.DispatchTargets
import app.codeg.android.core.data.HandoffPackage
import app.codeg.android.core.data.HandoffPackageFactory
import app.codeg.android.core.data.HandoffSource
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.network.ApiError
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.displayMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Cross-device session handoff (batch v141). Generates an editable handoff
 * package from the source session's transcript, prechecks git state for mode B,
 * and dispatches the package as a native work_task via the shared v140 pipeline
 * (same ledger, same anti-double-create rules).
 */
@HiltViewModel
class HandoffViewModel @Inject constructor(
    private val dispatchService: DispatchService,
    private val targetLoader: DispatchTargetLoader,
    private val repository: app.codeg.android.core.data.ServerRepository,
    private val agentOptionsProber: app.codeg.android.core.data.AgentOptionsProber,
    appSettingsStore: app.codeg.android.core.datastore.AppSettingsStore,
) : ViewModel() {

    /** 批v166: the model/effort rows' probe + provider catalog (shared rows
     *  component). Cache-first on the prober; never throws. */
    suspend fun probeAgentOptions(
        serverId: String,
        agentType: AgentType,
        workingDir: String?,
        configValues: Map<String, String> = emptyMap(),
    ): app.codeg.android.core.model.AgentOptionsSnapshot? =
        agentOptionsProber.probe(serverId, agentType, workingDir, configValues)

    suspend fun modelProviderEntries(
        serverId: String,
        agentType: AgentType,
    ): List<app.codeg.android.core.model.ModelProviderInfo> =
        agentOptionsProber.modelProviderEntries(serverId, agentType)

    /** App-wide settings — the handoff sheet reads its preselected defaults
     *  (device/project/agent) from this flow. */
    val settings = appSettingsStore.settings
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly,
            app.codeg.android.core.datastore.AppSettings.INITIAL)

    /** Git 接力 (mode B) precheck result for the SOURCE project. */
    data class GitPrecheck(
        val branch: String?,
        val dirtyCount: Int,
        val dirtyPreview: List<String>,
        val error: String?,
    )

    data class UiState(
        val pkg: HandoffPackage? = null,
        val targets: DispatchTargets = DispatchTargets(),
        /** Null = mode A (context relay), true/false = mode B checked/unchecked. */
        val modeB: Boolean = false,
        val gitPrecheck: GitPrecheck? = null,
        val gitChecking: Boolean = false,
        val dispatching: Boolean = false,
        val error: String? = null,
        val createdRef: DispatchOutcome.Confirmed? = null,
        /** Set when the result came back UNKNOWN — dispatch stays blocked. */
        val unknownRef: app.codeg.android.core.datastore.DispatchRef? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()

    init {
        // Device chips are owned here (collected from the profile store), never
        // written by the target loader — a failed health check can't blank them.
        viewModelScope.launch {
            try {
                repository.visibleProfiles.collect { profiles ->
                    _ui.value = _ui.value.copy(targets = _ui.value.targets.copy(profiles = profiles))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Profiles unreadable: the sheet shows an empty device list and
                // dispatch refuses ("设备不可用") — never a crash from init.
            }
        }
        // Crash mid-dispatch: surface any PENDING ledger entry as resolvable.
        viewModelScope.launch { dispatchService.sweepPendingToUnknown() }
    }

    fun generate(source: HandoffSource) {
        if (_ui.value.pkg != null) return
        _ui.value = _ui.value.copy(pkg = HandoffPackageFactory.fromSource(source))
    }

    fun update(transform: (HandoffPackage) -> HandoffPackage) {
        _ui.value = _ui.value.copy(pkg = _ui.value.pkg?.let(transform))
    }

    fun setModeB(enabled: Boolean) {
        _ui.value = _ui.value.copy(modeB = enabled)
        if (enabled) checkGit()
    }

    fun loadTarget(serverId: String) {
        val seq = ++targetSeq
        viewModelScope.launch {
            _ui.value = _ui.value.copy(targets = _ui.value.targets.copy(folders = emptyList(), agents = emptyList(), foldersLoading = true, error = null))
            val loaded = targetLoader.load(serverId)
            if (seq != targetSeq) return@launch
                _ui.value = _ui.value.copy(
                    targets = _ui.value.targets.copy(
                        folders = loaded.folders,
                        agents = loaded.agents,
                        foldersLoading = loaded.foldersLoading,
                        error = loaded.error,
                        agentsError = loaded.agentsError,
                    ),
                )
        }
    }

    private var targetSeq = 0

    /**
     * Mode B precheck: the source project's git state via the server's
     * `git_status`/`get_git_branch` (they take a filesystem path, not a folder
     * id — PRD §6.3). Read-only; never touches the working tree.
     */
    fun checkGit() {
        val pkg = _ui.value.pkg ?: return
        val seq = ++gitSeq
        viewModelScope.launch {
            _ui.value = _ui.value.copy(gitChecking = true, gitPrecheck = null)
            val precheck = runPrecheck(pkg.sourceServerId, pkg)
            if (seq != gitSeq) return@launch
            _ui.value = _ui.value.copy(gitChecking = false, gitPrecheck = precheck)
        }
    }

    private var gitSeq = 0

    private suspend fun runPrecheck(serverId: String, pkg: HandoffPackage): GitPrecheck {
        val profile = try {
            repository.profiles.first().firstOrNull { it.id == serverId }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val client = profile?.let { repository.client(it) }
            ?: return GitPrecheck(null, 0, emptyList(), "设备不可用，无法检查 Git 状态")
        return precheckGit(client, pkg)
    }

    private suspend fun precheckGit(client: CodegClient, pkg: HandoffPackage): GitPrecheck {
        val path = pkg.sourceFolderPath
        if (path.isNullOrBlank()) {
            // Unverifiable must never masquerade as "0 dirty files" — say so.
            return GitPrecheck(pkg.gitBranch, 0, emptyList(), "项目路径未知，Git 状态未验证")
        }
        // `git status --porcelain` only lists dirty files, so every entry counts.
        val branch = try { client.getGitBranch(path) } catch (e: CancellationException) { throw e } catch (e: Exception) { null } ?: pkg.gitBranch
        val status = try { client.gitStatus(path) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            ?: return GitPrecheck(branch, 0, emptyList(), "Git 状态检查失败（未验证）")
        return GitPrecheck(
            branch = branch,
            dirtyCount = status.size,
            dirtyPreview = status.take(5).map { it.path },
            error = null,
        )
    }

    fun dispatch(
        source: HandoffSource,
        targetServerId: String,
        folderId: Int,
        folderName: String,
        agentType: AgentType?,
        /** 批v166: pinned model/effort picks from the shared rows (config_values). */
        configValues: Map<String, String> = emptyMap(),
    ) {
        val pkg = _ui.value.pkg ?: return
        if (_ui.value.dispatching) {
            _ui.value = _ui.value.copy(error = "正在派发，请稍候")
            return
        }
        if (pkg.goal.isBlank() && pkg.nextSteps.isBlank()) {
            _ui.value = _ui.value.copy(error = "交接包为空：请至少填写工作目标或下一步")
            return
        }
        val gitNote = if (_ui.value.modeB) {
            val pc = _ui.value.gitPrecheck
            when {
                pc == null -> "Git 状态未验证：派发前请自行确认已提交并推送。"
                pc.error != null -> "${pc.error} 代码不会随交接包传输，请自行确认已提交并推送。"
                else -> "Git 预检：分支 ${pc.branch ?: "未知"}，${pc.dirtyCount} 个未提交文件" +
                    (pc.dirtyPreview.takeIf { it.isNotEmpty() }?.let { "（${it.joinToString("、")}…）" } ?: "") +
                    "。交接包只含上下文，代码需先提交/推送并由目标设备拉取后再执行。"
            }
        } else null
        _ui.value = _ui.value.copy(dispatching = true, error = null)
        viewModelScope.launch {
            try {
                val title = "交接：${pkg.sourceSessionTitle.ifBlank { "会话#${pkg.sourceSessionId}" }}"
                // Ledger gate: the sheet's unknownRef guard dies with the sheet
                // (reset on close). An unresolved UNKNOWN with the same
                // server+folder+title means the previous attempt's fate is still
                // unobservable — re-sending here would double-create. Refuse and
                // point at the resolvable card instead.
                if (dispatchService.hasUnresolvedDuplicate(targetServerId, folderId, title)) {
                    _ui.value = _ui.value.copy(
                        dispatching = false,
                        error = "检测到一条同目标的「提交结果未知」记录尚未处理：请先到 动态页「想法」页签的派发记录中检查/放弃后再派发，否则可能重复创建任务",
                    )
                    return@launch
                }
                // The folder list reloads when the target device changes; a stale
                // selection (rotation restore) could carry another device's
                // folder id into this dispatch — task landing in the wrong project.
                val folders = _ui.value.targets.folders
                if (folders.isNotEmpty() && folders.none { it.id == folderId }) {
                    _ui.value = _ui.value.copy(dispatching = false, error = "目标项目已失效，请重新选择项目后再派发")
                    return@launch
                }
                val outcome = dispatchService.send(
                    DispatchService.SendRequest(
                        serverId = targetServerId,
                        folderId = folderId,
                        folderName = folderName,
                        title = title,
                        description = pkg.toTaskDescription(gitNote),
                        agentTypeWire = agentType?.wire,
                        // 批v166: the handoff task runs with the picked model
                        // instead of silently falling back to the agent default.
                        configValues = configValues,
                        // Handoff: starting the task is the receiving side's
                        // decision — never auto-start from the sender.
                        autoStart = false,
                    ),
                )
                when (outcome) {
                    is DispatchOutcome.Confirmed -> _ui.value = _ui.value.copy(dispatching = false, createdRef = outcome)
                    is DispatchOutcome.Unknown -> _ui.value = _ui.value.copy(
                        dispatching = false,
                        unknownRef = outcome.ref,
                        error = "提交结果未知：请到 动态页「想法」页签的派发记录回查（检查/放弃），面板已禁止直接重发",
                    )
                    is DispatchOutcome.HardFailed -> _ui.value = _ui.value.copy(dispatching = false, error = outcome.message)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Last-resort guard: a DataStore IO failure inside send() must
                // never crash the process — degrade to an error message.
                _ui.value = _ui.value.copy(dispatching = false, error = e.message ?: "派发失败")
            }
        }
    }

    fun clearError() {
        _ui.value = _ui.value.copy(error = null)
    }

    /** Full reset when the sheet closes, so a reopened sheet starts clean.
     * Refused while a dispatch is in flight: the send coroutine belongs to this
     * VM, and wiping its state would drop the dispatching/UNKNOWN guards and let
     * a reopened sheet fire a SECOND create for the same intent. */
    fun reset() {
        if (_ui.value.dispatching) return
        _ui.value = UiState()
    }
}
