package app.codeg.android.feature.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FilterAltOff
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TriStateCheckbox
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.common.RelativeTime
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.designsystem.component.DeviceChipRow
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.component.InlineError
import app.codeg.android.core.designsystem.component.LoadingView
import app.codeg.android.core.designsystem.component.PickerOption
import app.codeg.android.core.designsystem.component.rememberSheetDragLock
import app.codeg.android.core.model.ScanResult
import app.codeg.android.core.model.ScanSession
import app.codeg.android.core.model.ScanSessionStatus
import java.time.Instant
import java.time.OffsetDateTime

/**
 * 「导入历史会话」sheet（fleet M4）: device chips → scan that device's local
 * CLI sessions grouped by project folder → check what to import → receipt.
 * Imported rows show a badge and sink to the bottom of their group; soft-
 * deleted rows are opt-in (checking one restores the row in place).
 */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun SessionImportSheet(
    onDismiss: () -> Unit,
    onImported: () -> Unit,
    viewModel: SessionImportViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors

    // The host refreshes its list once an import actually changed conversations.
    // An epoch (not a Boolean) keeps firing for repeat imports in one sitting.
    LaunchedEffect(ui.importEpoch) {
        if (ui.importEpoch > 0) onImported()
    }
    // The VM is backstack-scoped and outlives the sheet — every open (including
    // a reopen) resets to a fresh round instead of resurrecting the last one.
    LaunchedEffect(Unit) { viewModel.onSheetShown() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .nestedScroll(rememberSheetDragLock()),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.session_import_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_cancel))
                }
            }
            if (ui.devices.size > 1) {
                DeviceChipRow(
                    devices = ui.devices.map { PickerOption(id = it.id, label = it.name) },
                    selectedId = ui.selectedDeviceId,
                    loadingDeviceId = if (ui.phase == SessionImportViewModel.Phase.LOADING) ui.selectedDeviceId else null,
                    errorDeviceIds = if (ui.phase == SessionImportViewModel.Phase.FAILED) setOfNotNull(ui.selectedDeviceId) else emptySet(),
                    onSelect = viewModel::selectDevice,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Spacer(Modifier.size(4.dp))
            when {
                ui.receipt != null -> ReceiptPanel(
                    receipt = ui.receipt!!,
                    onDone = {
                        viewModel.closeReceipt()
                        onDismiss()
                    },
                )
                ui.phase == SessionImportViewModel.Phase.LOADING -> LoadingView(
                    message = stringResource(R.string.session_import_scanning),
                    modifier = Modifier.fillMaxWidth(),
                )
                ui.phase == SessionImportViewModel.Phase.UNSUPPORTED -> Text(
                    text = stringResource(R.string.session_import_unsupported),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                )
                ui.phase == SessionImportViewModel.Phase.FAILED -> InlineError(
                    icon = Icons.Rounded.CloudDownload,
                    title = stringResource(R.string.session_import_load_failed),
                    message = ui.error.orEmpty(),
                    onRetry = viewModel::rescan,
                    modifier = Modifier.padding(bottom = 32.dp),
                )
                else -> PickerBody(ui = ui, viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun PickerBody(
    ui: SessionImportViewModel.UiState,
    viewModel: SessionImportViewModel,
) {
    val colors = CodegTheme.colors
    val scan = ui.scan
    if (scan == null) return
    if (scan.folders.isEmpty()) {
        EmptyState(
            icon = Icons.Rounded.CloudDownload,
            title = stringResource(R.string.session_import_empty_title),
            message = stringResource(R.string.session_import_empty_hint),
            modifier = Modifier.padding(bottom = 32.dp),
        )
        return
    }
    // In-memory derivation over one list — microseconds on a phone, so no
    // debounce; recomputed only when a filter input actually changes.
    val visibleFolders = remember(
        scan,
        ui.query,
        ui.hiddenFolderPaths,
        ui.hiddenAgents,
        ui.showImported,
        ui.includeDeleted,
    ) {
        computeVisibleFolders(
            scan,
            ui.query,
            ui.hiddenFolderPaths,
            ui.hiddenAgents,
            ui.showImported,
            ui.includeDeleted,
        )
    }
    val visibleLegalKeys = remember(visibleFolders) {
        visibleFolders.fold(mutableSetOf<String>()) { acc, group -> acc.addAll(group.legalKeys); acc }
    }
    val visibleTotal = visibleFolders.sumOf { it.visibleSessions.size }
    val visibleImportable = visibleFolders.sumOf { it.legalKeys.size }
    val filterActive = ui.hiddenFolderPaths.isNotEmpty() || ui.hiddenAgents.isNotEmpty() ||
        ui.showImported || ui.includeDeleted
    var showFilterSheet by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            ImportSearchField(ui = ui, viewModel = viewModel, modifier = Modifier.weight(1f))
            IconButton(onClick = { showFilterSheet = true }) {
                Icon(
                    imageVector = Icons.Rounded.FilterList,
                    contentDescription = stringResource(R.string.session_import_filter),
                    tint = if (filterActive) colors.accent else colors.textSecondary,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
        ) {
            Text(
                text = stringResource(R.string.session_import_selected_count, ui.selection.size),
                style = MaterialTheme.typography.labelLarge,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = viewModel::toggleAllVisible,
                // Gmail scope: select-all only touches what is visible right now.
                enabled = visibleLegalKeys.isNotEmpty(),
            ) {
                Text(
                    text = stringResource(
                        if (allVisibleSelected(visibleLegalKeys, ui.selection)) {
                            R.string.session_import_select_none
                        } else {
                            R.string.session_import_select_all
                        },
                    ),
                )
            }
        }
        // Visible scope: with no filters AND no imported/deleted sessions these
        // equal the server's importableCount/totalSessions — otherwise the
        // numbers are deliberately smaller (D3: those rows are hidden, so the
        // summary describes what the list actually shows).
        Text(
            text = stringResource(R.string.session_import_summary, visibleImportable, visibleTotal),
            style = MaterialTheme.typography.labelMedium,
            color = colors.textTertiary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
        if (scan.noFolderCount > 0) {
            Text(
                text = stringResource(R.string.session_import_no_folder, scan.noFolderCount),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (visibleFolders.isEmpty()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (ui.query.isNotBlank()) {
                    EmptyState(
                        icon = Icons.Rounded.SearchOff,
                        title = stringResource(R.string.session_import_no_results, ui.query.trim()),
                        message = stringResource(R.string.session_import_no_results_hint),
                    )
                } else {
                    EmptyState(
                        icon = Icons.Rounded.FilterAltOff,
                        title = stringResource(R.string.session_import_all_hidden_title),
                        message = stringResource(R.string.session_import_all_hidden_hint),
                    )
                }
                TextButton(onClick = viewModel::clearSearchAndFilters) {
                    Text(text = stringResource(R.string.session_import_filter_show_all))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visibleFolders, key = { it.folder.path }) { group ->
                    FolderCard(group = group, ui = ui, viewModel = viewModel)
                }
            }
        }
        // Import failure used to be invisible (phase stays OK) — the CTA just
        // snapped back with no feedback. Show it above the button; retrying is
        // re-tapping the CTA (selection is untouched on failure).
        ui.error?.let { err ->
            Text(
                text = err,
                style = MaterialTheme.typography.bodySmall,
                color = colors.danger,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Button(
                onClick = viewModel::importSelected,
                enabled = ui.selection.isNotEmpty() && !ui.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (ui.busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(text = stringResource(R.string.session_import_cta, ui.selection.size))
            }
        }
    }
    if (showFilterSheet) {
        ImportFilterSheet(scan = scan, ui = ui, viewModel = viewModel, onDismiss = { showFilterSheet = false })
    }
}

@Composable
private fun ImportSearchField(
    ui: SessionImportViewModel.UiState,
    viewModel: SessionImportViewModel,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    TextField(
        value = ui.query,
        onValueChange = viewModel::onQueryChange,
        placeholder = { Text(text = stringResource(R.string.session_import_search)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = colors.textTertiary,
            )
        },
        trailingIcon = {
            if (ui.query.isNotEmpty()) {
                IconButton(onClick = { viewModel.onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.session_import_search_clear),
                        tint = colors.textTertiary,
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.codeSurface,
            unfocusedContainerColor = colors.codeSurface,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            cursorColor = colors.accent,
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
            focusedPlaceholderColor = colors.textTertiary,
            unfocusedPlaceholderColor = colors.textTertiary,
            focusedLeadingIconColor = colors.textSecondary,
            unfocusedLeadingIconColor = colors.textTertiary,
        ),
        modifier = modifier,
    )
}

/** Server order is startedAt desc; the client's only reorder is the PRD's
 *  "已导入沉底": new → deleted → imported, tiers keep their recency order. */
private fun tiered(sessions: List<ScanSession>): List<ScanSession> =
    sessions.filter { it.status == ScanSessionStatus.NEW } +
        sessions.filter { it.status == ScanSessionStatus.DELETED } +
        sessions.filter { it.status == ScanSessionStatus.IMPORTED }

@Composable
private fun FolderCard(
    group: VisibleFolder,
    ui: SessionImportViewModel.UiState,
    viewModel: SessionImportViewModel,
) {
    val folder = group.folder
    val colors = CodegTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.bgElevated, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Group header = tri-state checkbox + the whole info block is one
        // checkbox click target: tap anywhere to complete/clear this group
        // (scoped to visible legal rows — search/filter aware).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(
                    role = Role.Checkbox,
                    onClickLabel = stringResource(R.string.session_import_folder_toggle, folder.name),
                    onClick = { viewModel.toggleFolderGroup(group) },
                )
                .padding(vertical = 2.dp),
        ) {
            TriStateCheckbox(
                state = when (folderToggleState(group.legalKeys, ui.selection)) {
                    ImportSelectState.ALL -> ToggleableState.On
                    ImportSelectState.SOME -> ToggleableState.Indeterminate
                    ImportSelectState.NONE -> ToggleableState.Off
                },
                onClick = null,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = folder.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Visible scope: the meta line reflects what the group toggle
                // would actually touch (agent list shrinks with the filter too).
                val visibleAgents = group.visibleSessions.map { it.agentType }.distinct()
                Text(
                    text = stringResource(
                        R.string.session_import_folder_meta,
                        group.legalKeys.size,
                        group.visibleSessions.size,
                    ) + visibleAgents.joinToString(prefix = " · ") { it.shortName },
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        tiered(group.visibleSessions).forEach { session ->
            SessionImportRow(session = session, ui = ui, onToggle = { viewModel.toggleSession(session) })
        }
    }
}

/** Filter sheet for the import picker (SessionFilterSheet parity): hidden sets
 *  stored in reverse so rescan-discovered folders show up by default, one
 *  header button flips the whole list, toggles apply live with no "done" step. */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun ImportFilterSheet(
    scan: ScanResult,
    ui: SessionImportViewModel.UiState,
    viewModel: SessionImportViewModel,
    onDismiss: () -> Unit,
) {
    val colors = CodegTheme.colors
    val agents = remember(scan) {
        scan.folders.asSequence()
            .flatMap { it.agentTypes.asSequence() }
            .distinct()
            .sortedBy { it.displayName }
            .toList()
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "header") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.session_import_filter_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::toggleEverythingHidden) {
                        val anyHidden = ui.hiddenFolderPaths.isNotEmpty() || ui.hiddenAgents.isNotEmpty()
                        Text(
                            text = stringResource(
                                if (anyHidden) {
                                    R.string.session_import_filter_show_all
                                } else {
                                    R.string.session_import_filter_hide_all
                                },
                            ),
                        )
                    }
                }
            }
            item(key = "folders_header") {
                FilterSectionHeader(
                    title = stringResource(R.string.session_import_filter_folders),
                    revealed = ui.hiddenFolderPaths.isNotEmpty(),
                    onToggle = {
                        if (ui.hiddenFolderPaths.isNotEmpty()) viewModel.showAllFolderPaths() else viewModel.hideAllFolderPaths()
                    },
                )
            }
            items(scan.folders, key = { "folder:${it.path}" }) { folder ->
                ImportFilterCheckRow(
                    label = folder.name,
                    badge = folder.sessions.size.toString(),
                    checked = folder.path !in ui.hiddenFolderPaths,
                    onToggle = { viewModel.toggleFolderVisible(folder.path) },
                )
            }
            item(key = "agents_header") {
                FilterSectionHeader(
                    title = stringResource(R.string.session_import_filter_agents),
                    revealed = ui.hiddenAgents.isNotEmpty(),
                    onToggle = {
                        if (ui.hiddenAgents.isNotEmpty()) viewModel.showAllAgents() else viewModel.hideAllAgents()
                    },
                )
            }
            items(agents, key = { "agent:${it.wire}" }) { agent ->
                ImportFilterCheckRow(
                    label = agent.displayName,
                    badge = scan.folders.sumOf { f -> f.sessions.count { it.agentType == agent } }.toString(),
                    checked = agent !in ui.hiddenAgents,
                    onToggle = { viewModel.toggleAgent(agent) },
                )
            }
            item(key = "status_header") {
                FilterSectionHeader(title = stringResource(R.string.session_import_filter_status))
            }
            item(key = "status_imported") {
                ImportFilterCheckRow(
                    label = stringResource(R.string.session_import_show_imported),
                    badge = scan.folders.sumOf { it.importedCount }.toString(),
                    checked = ui.showImported,
                    onToggle = viewModel::toggleShowImported,
                )
            }
            item(key = "status_deleted") {
                ImportFilterCheckRow(
                    label = stringResource(R.string.session_import_include_deleted),
                    badge = scan.folders.sumOf { it.deletedCount }.toString(),
                    checked = ui.includeDeleted,
                    onToggle = viewModel::toggleIncludeDeleted,
                )
            }
            item(key = "bottom_spacer") { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/** Column header (SessionFilterSheet parity): accent + open eye while the
 *  section is hiding anything, tap toggles the whole section. */
@Composable
private fun FilterSectionHeader(
    title: String,
    revealed: Boolean = false,
    onToggle: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onToggle != null) {
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggle)
                } else {
                    Modifier
                },
            )
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = if (revealed) colors.accent else colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (onToggle != null) {
            Icon(
                imageVector = if (revealed) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun ImportFilterCheckRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    badge: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .heightIn(min = 44.dp)
            .padding(vertical = 2.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        badge?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
            )
        }
    }
}

@Composable
private fun SessionImportRow(
    session: ScanSession,
    ui: SessionImportViewModel.UiState,
    onToggle: () -> Unit,
) {
    val colors = CodegTheme.colors
    val imported = session.status == ScanSessionStatus.IMPORTED
    val deleted = session.status == ScanSessionStatus.DELETED
    val checked = SessionImportViewModel.sessionKey(session) in ui.selection
    // UNKNOWN agents are never sent (wire contract) — keep them unselectable so
    // the CTA count matches what actually rides the request.
    val selectable = !imported && (!deleted || ui.includeDeleted) &&
        session.agentType != AgentType.UNKNOWN
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selectable) {
                    Modifier.clickable(role = Role.Checkbox, onClick = onToggle)
                } else {
                    Modifier
                },
            ),
    ) {
        Checkbox(checked = checked, onCheckedChange = { if (selectable) onToggle() }, enabled = selectable)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = session.title.orEmpty().ifBlank { session.externalId },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (imported) FontWeight.Normal else FontWeight.Medium,
                color = if (imported) colors.textTertiary else colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = sessionCaption(session),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (imported) {
            Text(
                text = stringResource(R.string.session_import_badge_imported),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
                modifier = Modifier
                    .background(colors.bg, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        } else if (deleted) {
            Text(
                text = stringResource(R.string.session_import_badge_deleted),
                style = MaterialTheme.typography.labelSmall,
                color = colors.warning,
                modifier = Modifier
                    .background(colors.bg, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun sessionCaption(session: ScanSession): String {
    val msgs = stringResource(R.string.session_import_msg_count, session.messageCount)
    return buildList {
        add(session.agentType.shortName)
        add(msgs)
        val at = session.endedAt ?: session.startedAt
        parseIso(at)?.let { add(RelativeTime.compact(it)) }
        session.gitBranch?.takeIf { it.isNotBlank() }?.let { add(it) }
    }.joinToString(" · ")
}

private fun parseIso(raw: String): Instant? = runCatching {
    OffsetDateTime.parse(raw).toInstant()
}.recoverCatching {
    Instant.parse(raw)
}.getOrNull()

@Composable
private fun ReceiptPanel(
    receipt: SessionImportViewModel.Receipt,
    onDone: () -> Unit,
) {
    val colors = CodegTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // All-failed / all-not-found gets the alarm icon, not a green check.
        val anyChanged = receipt.imported > 0 || receipt.updated > 0 || receipt.restored > 0
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                if (anyChanged) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = if (anyChanged) colors.success else colors.danger,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = stringResource(
                    R.string.session_import_receipt,
                    receipt.imported,
                    receipt.updated,
                    receipt.restored,
                ),
                style = MaterialTheme.typography.titleSmall,
                color = colors.textPrimary,
            )
        }
        val issues = listOf(receipt.skipped, receipt.notFound, receipt.failed).any { it > 0 } || receipt.errors.isNotEmpty()
        if (issues) {
            Text(
                text = stringResource(
                    R.string.session_import_receipt_issue,
                    receipt.skipped,
                    receipt.notFound,
                    receipt.failed,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            receipt.errors.take(3).forEach { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.danger,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.session_import_done))
        }
    }
}
