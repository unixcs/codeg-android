package app.codeg.android.feature.sessions

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.codeg.android.R
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * Confirm-then-delete dialog for the conversation list rows' long-press menu
 * (Chats / Activity / Search). Reuses the session-detail delete copy — the
 * warning text is identical because the operation is identical.
 */
@Composable
fun SessionDeleteDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = CodegTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.bgElevated,
        title = { Text(stringResource(R.string.sessionactions_delete_session_confirm), color = colors.textPrimary) },
        text = { Text(stringResource(R.string.sessionactions_delete_session_message), color = colors.textSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.common_delete), color = colors.danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel), color = colors.textSecondary)
            }
        },
    )
}
