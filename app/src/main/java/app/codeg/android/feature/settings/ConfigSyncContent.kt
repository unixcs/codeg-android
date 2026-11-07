package app.codeg.android.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.codeg.android.R
import app.codeg.android.core.designsystem.theme.CodegTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Config sync leaf (batch v142): export = password → AES-256-GCM ciphertext →
 * clipboard; import = paste ciphertext + password → preview/confirm → merge.
 * Zero network by design — PRD §10.8.
 */
@Composable
fun ConfigSyncContent(viewModel: ConfigSyncViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showExportDialog by rememberSaveable { mutableStateOf(false) }
    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    // Servers skipped at export time (no token); shown in the copied dialog.
    var exportSkipped by rememberSaveable { mutableStateOf(0) }
    // Fresh state on entry: a stale exportText would drop the dialog into the
    // "copied" branch, and a stale preview keeps plaintext tokens in memory.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.resetExport()
        viewModel.resetImport()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.configsync_intro),
            style = MaterialTheme.typography.bodySmall,
            color = CodegTheme.colors.textSecondary,
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.configsync_export_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.configsync_export_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = CodegTheme.colors.textSecondary,
                )
                TextButton(onClick = { showExportDialog = true }) {
                    Text(stringResource(R.string.configsync_export_button))
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.configsync_import_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.configsync_import_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = CodegTheme.colors.textSecondary,
                )
                TextButton(onClick = { showImportDialog = true }) {
                    Text(stringResource(R.string.configsync_import_button))
                }
            }
        }
        Text(
            stringResource(R.string.configsync_clipboard_note),
            style = MaterialTheme.typography.labelSmall,
            color = CodegTheme.colors.textSecondary,
        )
    }

    if (showExportDialog) {
        ExportDialog(
            busy = ui.exportBusy,
            error = ui.exportError,
            onConfirm = { pw ->
                viewModel.export(pw.toCharArray()) { text, skipped ->
                    val copied = copyToClipboard(context, text)
                    if (!copied) viewModel.copyFailed()
                    exportSkipped = skipped
                }
            },
            onDismiss = {
                showExportDialog = false
                exportSkipped = 0
                viewModel.resetExport()
            },
            copied = ui.exportText != null,
            skipped = exportSkipped,
        )
    }
    if (showImportDialog) {
        ImportDialog(
            ui = ui,
            onPreview = { text, pw -> viewModel.previewImport(text, pw.toCharArray()) },
            onToggle = viewModel::toggleUse,
            onApply = viewModel::applyImport,
            onDismiss = {
                showImportDialog = false
                viewModel.resetImport()
            },
        )
    }
}

@Composable
private fun ExportDialog(busy: Boolean, error: String?, onConfirm: (String) -> Unit, copied: Boolean, skipped: Int, onDismiss: () -> Unit) {
    // Plain `remember`: passwords must NOT ride the saved-instance Bundle.
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CodegTheme.colors.bgElevated,
        title = { Text(stringResource(R.string.configsync_export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (copied) {
                    Text(stringResource(R.string.configsync_export_done), color = CodegTheme.colors.textPrimary)
                    if (skipped > 0) {
                        Text(
                            stringResource(R.string.configsync_export_skipped, skipped),
                            color = CodegTheme.colors.danger,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    // Clipboard may have failed (rare) — the success text alone
                    // would be a lie.
                    error?.let {
                        Text(it, color = CodegTheme.colors.danger, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text(
                        stringResource(R.string.configsync_password_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = CodegTheme.colors.textSecondary,
                    )
                    OutlinedTextField(
                        value = pw, onValueChange = { pw = it },
                        label = { Text(stringResource(R.string.configsync_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = pw2, onValueChange = { pw2 = it },
                        label = { Text(stringResource(R.string.configsync_password_confirm)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        isError = pw2.isNotEmpty() && pw != pw2,
                    )
                    error?.let { Text(it, color = CodegTheme.colors.danger, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            if (copied) {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp))
                    TextButton(
                        enabled = pw.length >= 12 && pw == pw2 && !busy,
                        onClick = { onConfirm(pw) },
                    ) { Text(stringResource(R.string.configsync_export_button)) }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun ImportDialog(
    ui: ConfigSyncViewModel.UiState,
    onPreview: (String, String) -> Unit,
    onToggle: (String) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Plain `remember` (NOT saveable): the pasted blob is ciphertext, but it is
    // still a credential-bearing secret — it must never ride the saved-instance
    // Bundle the same way the password must not.
    var text by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    val preview = ui.preview
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CodegTheme.colors.bgElevated,
        title = { Text(stringResource(R.string.configsync_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (preview == null) {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        label = { Text(stringResource(R.string.configsync_paste_hint)) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = pw, onValueChange = { pw = it },
                        label = { Text(stringResource(R.string.configsync_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ui.importError?.let {
                        Text(it, color = CodegTheme.colors.danger, style = MaterialTheme.typography.bodySmall)
                    }
                    // applyImport returns here with preview=null: without this,
                    // the user sees the blank paste form again and cannot tell
                    // whether the import worked.
                    ui.importResult?.let {
                        Text(it, color = CodegTheme.colors.accent, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text(
                        stringResource(R.string.configsync_preview_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        for (c in preview) {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(c.payload.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        c.payload.base_url,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CodegTheme.colors.textSecondary,
                                    )
                                    Text(
                                        if (c.existsLocally) {
                                            stringResource(R.string.configsync_conflict, c.localName ?: "")
                                        } else {
                                            stringResource(R.string.configsync_new_server)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CodegTheme.colors.accent,
                                    )
                                }
                                Checkbox(checked = c.useImported, onCheckedChange = { onToggle(c.payload.server_id) })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ui.importBusy) CircularProgressIndicator(Modifier.padding(end = 8.dp))
                when {
                    ui.importResult != null -> TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
                    preview != null -> TextButton(enabled = !ui.importBusy, onClick = onApply) {
                        Text(stringResource(R.string.configsync_apply))
                    }
                    else -> TextButton(
                        enabled = text.isNotBlank() && pw.isNotEmpty() && !ui.importBusy,
                        onClick = { onPreview(text, pw) },
                    ) { Text(stringResource(R.string.configsync_decrypt)) }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

private fun copyToClipboard(context: Context, text: String): Boolean {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
    val clip = ClipData.newPlainText("codeg-config-sync", text)
    // API 33+: keep the ciphertext out of clipboard previews / cloud clipboards.
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    }
    manager.setPrimaryClip(clip)
    return true
}
