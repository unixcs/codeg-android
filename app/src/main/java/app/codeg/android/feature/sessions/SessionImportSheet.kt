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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import app.codeg.android.core.model.ScanFolder
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
    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.session_import_summary,
                    scan.importableCount,
                    scan.totalSessions,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = colors.textTertiary,
                modifier = Modifier.weight(1f),
            )
            if (scan.folders.any { it.deletedCount > 0 }) {
                Text(
                    text = stringResource(R.string.session_import_include_deleted),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.clickable(role = Role.Switch) { viewModel.toggleIncludeDeleted() },
                )
                Spacer(Modifier.width(6.dp))
                Switch(checked = ui.includeDeleted, onCheckedChange = { viewModel.toggleIncludeDeleted() })
            }
        }
        if (scan.noFolderCount > 0) {
            Text(
                text = stringResource(R.string.session_import_no_folder, scan.noFolderCount),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(scan.folders, key = { it.path }) { folder ->
                FolderCard(folder = folder, ui = ui, viewModel = viewModel)
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
}

/** Server order is startedAt desc; the client's only reorder is the PRD's
 *  "已导入沉底": new → deleted → imported, tiers keep their recency order. */
private fun tiered(sessions: List<ScanSession>): List<ScanSession> =
    sessions.filter { it.status == ScanSessionStatus.NEW } +
        sessions.filter { it.status == ScanSessionStatus.DELETED } +
        sessions.filter { it.status == ScanSessionStatus.IMPORTED }

@Composable
private fun FolderCard(
    folder: ScanFolder,
    ui: SessionImportViewModel.UiState,
    viewModel: SessionImportViewModel,
) {
    val colors = CodegTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.bgElevated, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
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
        Text(
            text = stringResource(
                R.string.session_import_folder_meta,
                folder.newCount,
                folder.sessions.size,
            ) + folder.agentTypes.joinToString(prefix = " · ") { it.shortName },
            style = MaterialTheme.typography.labelSmall,
            color = colors.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        tiered(folder.sessions).forEach { session ->
            SessionImportRow(session = session, ui = ui, onToggle = { viewModel.toggleSession(session) })
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
