package app.codeg.android.feature.dispatch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.stringResource
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.OptionListSheet
import app.codeg.android.core.designsystem.component.PickerField
import app.codeg.android.core.designsystem.component.PickerOption
import app.codeg.android.core.model.AgentOptionsSnapshot
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ModelProviderInfo

/**
 * 批v166: submit surface + probe state for the 模型 / 推理强度 (and optionally
 * 权限模式) rows on the lightweight dispatch surfaces — idea quick-insert, the
 * task-board edit sheet, handoff. The same contract as DispatchForm's inline
 * rows: a pick is only ever submitted if the picker offered it (an unvalidated
 * id never rides a submit), a target change resets everything, and a pick
 * re-probes so the effort list matches the model. State lives as long as the
 * surface does — rotation keeps the composition alive, process death does not.
 *
 * [initialConfigValues] seeds [configPicks] (the task edit sheet prefills the
 * row display from the task's pinned config); a target change clears picks,
 * and the caller merges picks OVER its base config at submit time, so a
 * cleared sheet still re-sends the base untouched. Picks live only as long as
 * the surface does (plain remember, like DispatchForm's) — process death
 * loses them with the rest of the un-saveable form state.
 */
class AgentOptionsRowsState(initialConfigValues: Map<String, String> = emptyMap()) {
    val configPicks: SnapshotStateMap<String, String> =
        mutableStateMapOf<String, String>().apply { putAll(initialConfigValues) }

    /** Picked permission mode id (only meaningful with showPermissionMode). */
    var modeId: String? by mutableStateOf(null)

    /** Read-only copy for submit-time wiring. */
    fun picksSnapshot(): Map<String, String> = configPicks.toMap()

    internal var snapshot: AgentOptionsSnapshot? by mutableStateOf(null)
    internal var providers: List<ModelProviderInfo> by mutableStateOf(emptyList())
    internal var loading: Boolean by mutableStateOf(false)
    internal var failed: Boolean by mutableStateOf(false)
    /** False until the first target probe ran — its reset must not wipe the
     *  caller's prefill (the edit sheet seeds picks from the task's config). */
    internal var armed: Boolean = false
}

/**
 * The shared 模型/推理强度 rows (DispatchForm parity for the lightweight
 * forms). Emits rows into the caller's spaced column and owns its option
 * sheets. Probes [agent] — callers pass the EFFECTIVE agent (explicit pick,
 * else the project default) exactly like DispatchForm does, so the rows also
 * work under 自动 when the project has a default agent.
 *
 * DispatchForm keeps its own inline implementation this batch (its
 * session/task/new-task interplay is dense and already verified); converging
 * it onto this component is follow-up cleanup, not new behavior.
 */
@Composable
fun AgentOptionsRows(
    state: AgentOptionsRowsState,
    serverId: String?,
    agent: AgentType?,
    workingDir: String?,
    probe: suspend (String, AgentType, String?, Map<String, String>) -> AgentOptionsSnapshot?,
    providerModels: suspend (String, AgentType) -> List<ModelProviderInfo>,
    /** The 权限模式 row (plan/Build). Only forms whose submit sends mode_id. */
    showPermissionMode: Boolean = false,
) {
    var modeSheet by remember { mutableStateOf(false) }
    var configSheetId by remember { mutableStateOf<String?>(null) }

    // Target probe — same reset rules as DispatchForm: a device/agent/project
    // change invalidates the catalog AND every pick taken against it. The very
    // first run is not a change: the caller's prefill (edit sheet) survives it
    // and shapes the first probe.
    LaunchedEffect(serverId, agent, workingDir) {
        val wasArmed = state.armed
        state.snapshot = null
        state.providers = emptyList()
        state.loading = false
        state.failed = false
        if (wasArmed) {
            state.configPicks.clear()
            state.modeId = null
        }
        val d = serverId
        val a = agent
        // DispatchForm parity: the probe needs a project dir — before one is
        // picked the rows show the pending placeholder, never a fake empty.
        if (d == null || a == null || workingDir == null) return@LaunchedEffect
        // First run carries the caller's prefill picks (the edit sheet's pinned
        // model shapes the effort list in the same spawn); later runs probe the
        // just-cleared defaults, exactly like DispatchForm's target probe.
        val probePicks = if (wasArmed) emptyMap() else state.configPicks.toMap()
        state.armed = true
        state.loading = true
        val fresh = try {
            probe(d, a, workingDir, probePicks)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        state.loading = false
        if (fresh == null) {
            state.failed = true
        } else {
            state.snapshot = fresh
            val modes = fresh.modes?.availableModes.orEmpty()
            fresh.modes?.currentModeId
                ?.takeIf { current -> modes.any { it.id == current } }
                ?.let { state.modeId = it }
        }
    }
    // A picked model can change the reasoning-effort choices — re-probe with
    // the current picks. Deliberately silent (DispatchForm parity): the old
    // rows stay usable meanwhile, and a failed re-probe must not blank a
    // catalog the user is looking at.
    val configKey = state.configPicks.entries.sortedBy { it.key }
        .joinToString(",") { "${it.key}=${it.value}" }
    LaunchedEffect(configKey) {
        if (configKey.isEmpty()) return@LaunchedEffect
        val d = serverId ?: return@LaunchedEffect
        val a = agent ?: return@LaunchedEffect
        val dir = workingDir ?: return@LaunchedEffect
        val fresh = try {
            probe(d, a, dir, state.configPicks.toMap())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (fresh != null) state.snapshot = fresh
    }
    // Server truth per (device, agent): fresh on every target change (the
    // settings page may have edited the providers since). Never throws.
    LaunchedEffect(serverId, agent) {
        state.providers = emptyList()
        val d = serverId ?: return@LaunchedEffect
        val a = agent ?: return@LaunchedEffect
        state.providers = try {
            providerModels(d, a)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    val merged = remember(state.snapshot, state.providers) {
        mergeProviderModels(state.snapshot, state.providers)
    }
    val modes = state.snapshot?.modes?.availableModes.orEmpty()
    if (showPermissionMode && modes.isNotEmpty()) {
        PickerField(
            icon = Icons.Rounded.Tune,
            label = stringResource(R.string.dispatch_mode_permission),
            value = modes.firstOrNull { it.id == state.modeId }
                ?.let { it.name.ifBlank { it.id } },
            placeholder = "",
            onClick = { modeSheet = true },
        )
    }
    // M1 parity: the catalog row with the server's provider models merged in
    // (and, on a failed probe, carried by the provider models alone).
    val modelOption = merged?.configOptions?.firstOrNull {
        it.id == "model" || it.category == "model"
    }
    val effortOption = merged?.configOptions?.firstOrNull {
        it.id != modelOption?.id &&
            (it.category == "thought_level" || it.id == "reasoning_effort")
    }
    if (modelOption != null) {
        PickerField(
            icon = Icons.Rounded.Tune,
            label = stringResource(R.string.dispatch_model),
            value = modelOption.displayValue(state.configPicks),
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
                    state.loading -> R.string.dispatch_model_loading
                    state.failed -> R.string.dispatch_model_failed
                    // No working dir → the probe hasn't run; "无模型" reads as
                    // a verdict, not a pending state.
                    workingDir == null -> R.string.dispatch_model_pending
                    agent == null -> R.string.dispatch_model_needs_agent
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
            value = effortOption.displayValue(state.configPicks),
            placeholder = "",
            onClick = { configSheetId = effortOption.id },
        )
    }
    if (state.failed && !state.loading && modelOption == null) {
        Text(
            stringResource(R.string.dispatch_model_failed_note),
            style = MaterialTheme.typography.labelSmall,
            color = app.codeg.android.core.designsystem.theme.CodegTheme.colors.textTertiary,
        )
    }

    if (showPermissionMode && modeSheet) {
        OptionListSheet(
            title = stringResource(R.string.dispatch_mode_permission),
            options = modes.map { PickerOption(id = it.id, label = it.name.ifBlank { it.id }) },
            selectedId = state.modeId,
            onSelect = { option -> state.modeId = option.id },
            onDismiss = { modeSheet = false },
        )
    }
    // One generic list for whichever row opened it (model or reasoning
    // effort); grouped catalogs are flattened in declaration order.
    merged?.configOptions?.firstOrNull { it.id == configSheetId }?.let { option ->
        OptionListSheet(
            title = option.name.ifBlank { stringResource(R.string.dispatch_model) },
            options = option.selectValues().map {
                PickerOption(id = it.value, label = it.name.ifBlank { it.value }, note = it.description)
            },
            selectedId = state.configPicks[option.id] ?: option.kind.currentValue,
            onSelect = { picked ->
                state.configPicks[option.id] = picked.id
                configSheetId = null
            },
            onDismiss = { configSheetId = null },
        )
    }
}

/** Values of a select option: the flat list first, else the grouped one.
 *  DispatchForm's private helper, mirrored here for the lightweight forms. */
private fun app.codeg.android.core.model.SessionConfigOption.selectValues():
    List<app.codeg.android.core.model.SessionConfigSelectOption> =
    kind.options.ifEmpty { kind.groups.flatMap { it.options } }

/** The row label: the user's pick, else the agent's own current value,
 *  resolved to a display name and falling back to the raw value id (never
 *  fabricated — same rule as DispatchForm / the session screen). */
private fun app.codeg.android.core.model.SessionConfigOption.displayValue(
    picks: Map<String, String>,
): String? {
    val current = picks[id] ?: kind.currentValue?.takeIf { it.isNotBlank() } ?: return null
    return selectValues().firstOrNull { it.value == current }?.name?.takeIf { it.isNotBlank() }
        ?: current
}
