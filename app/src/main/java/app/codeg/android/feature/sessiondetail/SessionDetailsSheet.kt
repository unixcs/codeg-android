package app.codeg.android.feature.sessiondetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AgentAvatar(ui.agent, size = 40.dp)
                Text(
                    summary?.trimmedTitle ?: stringResource(R.string.sessionactions_untitled),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
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
                OutlinedButton(
                    onClick = {
                        // Dismiss first: both actions surface their result via the
                        // snackbar, which the open sheet would otherwise cover.
                        onDismiss()
                        viewModel.exportSessionJson()
                    },
                    enabled = !ui.isExporting && !workingDir.isNullOrBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        stringResource(
                            if (ui.isExporting) R.string.session_info_exporting else R.string.session_info_export,
                        ),
                        color = colors.textPrimary,
                    )
                }
                Button(
                    onClick = {
                        onDismiss()
                        viewModel.reconnectSession()
                    },
                    // Without a CLI session id there is nothing to re-attach: the button
                    // would silently start a brand-new CLI session instead.
                    enabled = !ui.isReconnecting && !workingDir.isNullOrBlank() && !cliId.isNullOrBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = colors.onAccent,
                        disabledContainerColor = colors.accent.copy(alpha = 0.4f),
                        disabledContentColor = colors.onAccent.copy(alpha = 0.8f),
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        stringResource(
                            if (ui.isReconnecting) R.string.session_info_reconnecting else R.string.session_info_reconnect,
                        ),
                    )
                }
            }
            Text(
                stringResource(R.string.session_info_export_hint),
                fontSize = 12.sp,
                color = colors.textTertiary,
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
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = valueColor ?: colors.textPrimary)
    }
}
