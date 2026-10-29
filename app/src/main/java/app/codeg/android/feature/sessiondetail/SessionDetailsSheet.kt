package app.codeg.android.feature.sessiondetail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.common.RelativeTime
import app.codeg.android.core.designsystem.component.AgentAvatar
import app.codeg.android.core.designsystem.component.statusColor
import app.codeg.android.core.designsystem.component.statusLabel
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * Session details: the read-only summary rows plus the handoff block — working directory,
 * CLI session id, Codeg id (each one-tap copy), a JSON export that lands in the project
 * directory, and a manual reconnect. Mirrors the web client's session-info card.
 *
 * Batch v133 (owner call): the whole content scrolls (a long session must never push
 * the actions off-screen), the title renders a single ellipsized line, and the three
 * actions became equal-width icon-over-label tiles — the old cramped pill row wrapped
 * its Chinese labels into three-line blobs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailsSheet(
    ui: SessionDetailUiState,
    viewModel: SessionDetailViewModel,
    onDismiss: () -> Unit,
) {
    val colors = CodegTheme.colors
    val summary = ui.summary
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val workingDir = ui.folderPath
    val cliId = ui.externalId
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.bgElevated,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AgentAvatar(ui.agent, size = 40.dp)
                Text(
                    summary?.trimmedTitle ?: stringResource(R.string.sessionactions_untitled),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (summary != null) {
                DetailRow(stringResource(R.string.sessionactions_detail_status), statusLabel(summary.status), statusColor(summary.status))
                ui.folderName?.let { DetailRow(stringResource(R.string.sessionactions_detail_folder), it) }
                DetailRow(stringResource(R.string.sessionactions_detail_messages), summary.messageCount.toString())
                summary.model?.takeIf { it.isNotBlank() }?.let { DetailRow(stringResource(R.string.sessionactions_detail_model), it) }
                summary.gitBranch?.takeIf { it.isNotBlank() }?.let { DetailRow(stringResource(R.string.sessionactions_detail_branch), it) }
                DetailRow(stringResource(R.string.sessionactions_detail_created), RelativeTime.compact(summary.createdAt))
                DetailRow(stringResource(R.string.sessionactions_detail_updated), RelativeTime.compact(summary.updatedAt))
            }

            HorizontalDivider(thickness = 0.5.dp, color = colors.hairline)

            CopyRow(
                label = stringResource(R.string.session_info_working_dir),
                value = workingDir ?: stringResource(R.string.session_info_unavailable),
                enabled = !workingDir.isNullOrBlank(),
                onCopy = viewModel::copyWorkingDir,
            )
            CopyRow(
                label = stringResource(R.string.session_info_cli_id),
                value = cliId ?: stringResource(R.string.session_info_no_id),
                enabled = !cliId.isNullOrBlank(),
                onCopy = viewModel::copySessionId,
            )
            val codegId = ui.summary?.id?.toString()
            CopyRow(
                label = stringResource(R.string.session_info_codeg_id),
                value = codegId ?: "—",
                enabled = codegId != null,
                onCopy = { codegId?.let(viewModel::copyText) },
            )

            Text(
                stringResource(R.string.session_info_note),
                fontSize = 12.sp,
                color = colors.textTertiary,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // One-tap copy of the alignment block (batch v123) — the primary
                // action; the JSON export moves to the secondary slot.
                ActionTile(
                    icon = Icons.Rounded.ContentCopy,
                    label = stringResource(R.string.session_info_action_handoff),
                    emphasized = true,
                    enabled = !workingDir.isNullOrBlank() || cliId != null,
                    onClick = {
                        onDismiss()
                        viewModel.copySessionSummary()
                    },
                    modifier = Modifier.weight(1f),
                )
                ActionTile(
                    icon = Icons.Rounded.FileDownload,
                    label = stringResource(
                        if (ui.isExporting) R.string.session_info_exporting else R.string.session_info_action_export,
                    ),
                    emphasized = false,
                    enabled = !ui.isExporting && !workingDir.isNullOrBlank(),
                    onClick = {
                        // Dismiss first: both actions surface their result via the
                        // snackbar, which the open sheet would otherwise cover.
                        onDismiss()
                        viewModel.exportSessionJson()
                    },
                    modifier = Modifier.weight(1f),
                )
                ActionTile(
                    icon = Icons.Rounded.Refresh,
                    label = stringResource(
                        if (ui.isReconnecting) R.string.session_info_reconnecting else R.string.session_info_action_reconnect,
                    ),
                    emphasized = false,
                    enabled = !ui.isReconnecting && !workingDir.isNullOrBlank() && !cliId.isNullOrBlank(),
                    onClick = {
                        onDismiss()
                        viewModel.reconnectSession()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                stringResource(R.string.session_info_copy_all_hint),
                fontSize = 12.sp,
                color = colors.textTertiary,
            )
        }
    }
}

/**
 * One of the three sheet actions: icon over a single-line label, fixed compact
 * height, no wrapping. [emphasized] tiles fill with the accent; the rest are
 * outlined secondaries.
 */
@Composable
private fun ActionTile(
    icon: ImageVector,
    label: String,
    emphasized: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = if (emphasized) colors.accent else Color.Transparent,
        contentColor = if (emphasized) colors.onAccent else colors.textPrimary,
        border = if (emphasized) null else BorderStroke(1.dp, colors.surfaceStroke),
        modifier = modifier,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.45f)
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CopyRow(label: String, value: String, enabled: Boolean, onCopy: () -> Unit) {
    val colors = CodegTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 12.sp, color = colors.textSecondary)
            Text(
                value,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = if (enabled) colors.textPrimary else colors.textTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onCopy, enabled = enabled) {
            Icon(
                Icons.Rounded.ContentCopy,
                contentDescription = stringResource(R.string.session_info_copy),
                tint = if (enabled) colors.textSecondary else colors.textTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color? = null) {
    val colors = CodegTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = colors.textSecondary)
        Text(
            value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = valueColor ?: colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
