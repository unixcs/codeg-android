package app.codeg.android.feature.projects

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.CodegTextField
import app.codeg.android.core.designsystem.component.CodegSegmented
import app.codeg.android.core.designsystem.component.CopyButton
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.component.InlineError
import app.codeg.android.core.designsystem.component.LoadingView
import app.codeg.android.core.designsystem.component.PrimaryButton
import app.codeg.android.core.designsystem.markdown.MarkdownContent
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.DirectoryItem
import app.codeg.android.core.network.ApiError
import app.codeg.android.core.network.CodegClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Recursive file browser with an internal directory stack + file preview. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderFilesView(client: CodegClient, rootPath: String) {
    val colors = CodegTheme.colors
    var stack by remember { mutableStateOf(listOf(rootPath)) }
    // Saveable: preview/edit survive rotation.
    var previewPath by rememberSaveable { mutableStateOf<String?>(null) }
    var editPath by rememberSaveable { mutableStateOf<String?>(null) }
    // Content recovered from a failed create-with-content; consumed once by the editor.
    var pastedRecovery by remember { mutableStateOf<String?>(null) }
    var editorDirty by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    BackHandler(editPath, editorDirty, previewPath, stack) {
        when {
            // Same path as the in-app back arrow: dirty editors ask first.
            editPath != null -> if (editorDirty) confirmDiscard = true else editPath = null
            previewPath != null -> previewPath = null
            stack.size > 1 -> stack = stack.dropLast(1)
        }
    }

    val editing = editPath
    if (editing != null) {
        FileEditorScreen(
            client, rootPath, editing,
            onDirtyChange = { editorDirty = it },
            onClose = { editPath = null },
            onDirtyClose = { editPath = null; confirmDiscard = true },
            initialText = pastedRecovery,
        )
        // One-shot seed: once consumed (or the user backs out without the editor taking
        // it), the paste must not reappear in a later unrelated edit.
        pastedRecovery = null
        return
    }

    val preview = previewPath
    if (preview != null) {
        FilePreview(client, rootPath, preview, onEdit = { editPath = preview })
        return
    }
    val dir = stack.last()
    var result by remember(dir) { mutableStateOf<Result<List<DirectoryItem>>?>(null) }
    var refreshTick by remember(dir) { mutableStateOf(0) }
    LaunchedEffect(dir, refreshTick) { result = runCatching { client.listDirectoryWithFiles(dir) } }
    val refresh: () -> Unit = { refreshTick++ }

    var actionTarget by remember { mutableStateOf<DirectoryItem?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var showNewSheet by remember { mutableStateOf(false) }
    var showUploadBrowser by remember { mutableStateOf(false) }
    var uploadTargetDir by remember { mutableStateOf<String?>(null) }
    var uploadBusy by remember { mutableStateOf(false) }
    var uploadMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val outsideWorkspaceMsg = stringResource(R.string.files_outside_workspace)
    val genericErrorMsg = stringResource(R.string.files_error)
    val tooLargeMsg = stringResource(R.string.files_too_large_edit)

    // SAF multi-pick. The target dir is chosen first (server browser, stored relative),
    // then files. Per-file failures are counted, not aborting the batch.
    val pickUpload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val target = uploadTargetDir
        if (uris.isEmpty() || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            uploadBusy = true
            actionError = null
            uploadMessage = context.getString(R.string.files_upload_started)
            var ok = 0
            var failed = 0
            try {
                for (uri in uris) {
                    try {
                        val resolver = context.contentResolver
                        val name = queryDisplayName(resolver, uri) ?: uri.lastPathSegment ?: continue
                        val size = querySize(resolver, uri) ?: 0L
                        withContext(Dispatchers.IO) {
                            client.uploadWorkspaceFile(rootPath, target, name, size) { resolver.openInputStream(uri)!! }
                        }
                        ok++
                    } catch (_: Exception) {
                        // Keep uploading the rest; the summary reports both counts.
                        failed++
                    }
                }
                uploadMessage = buildString {
                    append(context.getString(R.string.files_upload_done, ok))
                    if (failed > 0) append(" · ").append(context.getString(R.string.files_upload_failed_count, failed))
                }
                refresh()
            } finally {
                uploadBusy = false
            }
        }
    }

    val r = result
    when {
        r == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingView(message = stringResource(R.string.common_loading)) }
        r.isFailure -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            InlineError(Icons.AutoMirrored.Rounded.InsertDriveFile, stringResource(R.string.files_load_failed), r.exceptionOrNull()?.message ?: stringResource(R.string.files_error), onRetry = refresh)
        }
        else -> {
            val items = sortItems(r.getOrDefault(emptyList()))
            val dirName = dir.substringAfterLast('/')
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        dirName,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    FilesToolbarMenu(
                        onNewFile = { showNewSheet = true },
                        onUpload = { showUploadBrowser = true },
                    )
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    if (stack.size > 1) {
                        item("up") {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { stack = stack.dropLast(1) }.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(Icons.Rounded.ArrowUpward, null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                                Text("..", fontFamily = FontFamily.Monospace, color = colors.textSecondary)
                            }
                        }
                    }
                    if (items.isEmpty()) {
                        item("empty") { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { EmptyState(Icons.Rounded.Folder, stringResource(R.string.files_empty_folder), stringResource(R.string.files_empty_folder_message)) } }
                    }
                    items(items, key = { it.path }) { item ->
                        FileRow(item, onLongClick = { actionTarget = item }) {
                            if (item.isDir) stack = stack + item.path else previewPath = item.path
                        }
                    }
                }
            }
        }
    }

    actionTarget?.let { target ->
        FileActionSheet(
            client = client,
            rootPath = rootPath,
            target = target,
            outsideWorkspaceMsg = outsideWorkspaceMsg,
            onDismiss = { actionTarget = null },
            onOpen = {
                actionTarget = null
                if (target.isDir) stack = stack + target.path else previewPath = target.path
            },
            onEdit = {
                actionTarget = null
                previewPath = target.path
            },
            onDone = {
                actionTarget = null
                refresh()
            },
        )
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.files_discard_title),
            message = stringResource(R.string.files_discard_message),
            confirmLabel = stringResource(R.string.files_discard_confirm),
            onConfirm = {
                confirmDiscard = false
                editPath = null
            },
            onDismiss = { confirmDiscard = false },
        )
    }

    if (showNewSheet) {
        NewEntrySheet(
            parentPath = dir,
            onCreate = { name, isDir, content ->
                showNewSheet = false
                // path param is the parent dir, workspace-relative ("" = root).
                runFileAction(scope, refresh, { actionError = it }) {
                    // Same 2MB ceiling the editor enforces — a multi-MB paste goes to the
                    // editor (re-opened with the text) rather than a doomed save.
                    if (!isDir && content.length > MAX_EDIT_CHARS) {
                        actionError = tooLargeMsg
                        editPath = dir.let { d -> if (d.endsWith("/")) "$d$name" else "$d/$name" }
                        return@runFileAction
                    }
                    client.createFileTreeEntry(rootPath, relPathOrNull(rootPath, dir) ?: "", name, if (isDir) "dir" else "file")
                    // A file created WITH content: create returns no etag context, so chain a
                    // read-for-edit (fetches a valid etag for the 0-byte file) then a save.
                    // A save failure auto-opens the editor seeded with the pasted text.
                    if (!isDir && content.isNotEmpty()) {
                        val relNew = (relPathOrNull(rootPath, dir) ?: "").let { if (it.isEmpty()) name else "$it/$name" }
                        try {
                            val edit = client.readFileForEdit(rootPath, relNew)
                            client.saveFileContent(rootPath, relNew, content, edit.etag)
                        } catch (e: Exception) {
                            actionError = e.message ?: genericErrorMsg
                            editPath = dir.let { d -> if (d.endsWith("/")) "$d$name" else "$d/$name" }
                            pastedRecovery = content
                        }
                    }
                }
            },
            onDismiss = { showNewSheet = false },
        )
    }

    if (showUploadBrowser) {
        // Pick the destination directory with the server browser, then SAF-pick files.
        DirectoryBrowserSheet(
            title = stringResource(R.string.files_upload_title),
            confirmLabel = stringResource(R.string.files_upload_pick_target),
            loadHome = { client.getHomeDirectory() },
            loadDirs = { p -> client.listDirectoryEntries(p) },
            onSelect = { selected ->
                showUploadBrowser = false
                // Server expects a workspace-relative target ("" = root); the browser
                // hands out absolute paths, and an absolute one would be silently
                // re-rooted inside the workspace as junk directories.
                val relTarget = relPathOrNull(rootPath, selected)
                if (relTarget == null) {
                    actionError = outsideWorkspaceMsg
                } else {
                    uploadTargetDir = relTarget
                    pickUpload.launch(emptyArray())
                }
            },
            onDismiss = { showUploadBrowser = false },
        )
    }

    if (uploadBusy) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), color = colors.accent)
    }
    actionError?.let { err ->
        Text(
            err,
            fontSize = 11.sp,
            color = colors.danger,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        )
    }
    uploadMessage?.let { msg ->
        Text(
            msg,
            fontSize = 11.sp,
            color = colors.textSecondary,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        )
    }
}

/** Navigate back through the browser's three layers: editor → preview → directory stack. */
@Composable
private fun BackHandler(
    editing: String?,
    editorDirty: Boolean,
    preview: String?,
    stack: List<String>,
    onBack: () -> Unit,
) {
    androidx.activity.compose.BackHandler(
        enabled = editing != null || preview != null || stack.size > 1,
        onBack = onBack,
    )
}

/** Toolbar overflow: new file/folder + upload. Upload target chosen via server browser. */
@Composable
private fun FilesToolbarMenu(onNewFile: () -> Unit, onUpload: () -> Unit) {
    val colors = CodegTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.files_manage), tint = colors.textSecondary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.files_new_file)) },
                leadingIcon = { Icon(Icons.Rounded.NoteAdd, null, modifier = Modifier.size(18.dp)) },
                onClick = { expanded = false; onNewFile() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.files_upload)) },
                leadingIcon = { Icon(Icons.Rounded.UploadFile, null, modifier = Modifier.size(18.dp)) },
                onClick = { expanded = false; onUpload() },
            )
        }
    }
}

/** SAF display-name lookup for a picked document. */
private fun queryDisplayName(resolver: android.content.ContentResolver, uri: android.net.Uri): String? =
    runCatching {
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        }
    }.getOrNull()

private fun querySize(resolver: android.content.ContentResolver, uri: android.net.Uri): Long? =
    runCatching {
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else null
            } else null
        }
    }.getOrNull()

/** Run [block] on IO, then refresh; failures surface via [onError] instead of being swallowed. */
private fun runFileAction(
    scope: kotlinx.coroutines.CoroutineScope,
    refresh: () -> Unit,
    onError: (String) -> Unit,
    block: suspend () -> Unit,
) {
    scope.launch {
        try {
            withContext(Dispatchers.IO) { block() }
            refresh()
        } catch (e: Exception) {
            onError(e.message ?: e.javaClass.simpleName)
        }
    }
}

/** Per-file/folder actions, opened by long-press on a row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileActionSheet(
    client: CodegClient,
    rootPath: String,
    target: DirectoryItem,
    outsideWorkspaceMsg: String,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDone: () -> Unit,
) {
    val colors = CodegTheme.colors
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    var showDownloadBrowser by remember { mutableStateOf(false) }
    val rel = target.path.removePrefix("$rootPath/")

    fun run(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = null
            try {
                withContext(Dispatchers.IO) { block() }
                onDone()
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                busy = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(target.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(rel, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            error?.let {
                Text(it, color = colors.danger, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
            ActionRow(Icons.Rounded.Visibility, stringResource(R.string.files_action_open)) { onOpen() }
            if (!target.isDir) {
                ActionRow(Icons.Rounded.Edit, stringResource(R.string.files_action_edit)) { onEdit() }
                ActionRow(Icons.Rounded.Download, stringResource(R.string.files_action_download)) { showDownloadBrowser = true }
            }
            ActionRow(Icons.Rounded.DriveFileRenameOutline, stringResource(R.string.files_action_rename)) { showRename = true }
            ActionRow(Icons.Rounded.UploadFile, stringResource(R.string.files_action_move)) { showMove = true }
            ActionRow(Icons.Rounded.Delete, stringResource(R.string.files_action_delete), tint = colors.danger) { confirmDelete = true }

            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = colors.accent)
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.files_delete_title, target.name),
            message = if (target.isDir) stringResource(R.string.files_delete_dir_message) else stringResource(R.string.files_delete_file_message),
            confirmLabel = stringResource(R.string.files_action_delete),
            onConfirm = {
                confirmDelete = false
                run { client.deleteFileTreeEntry(rootPath, rel) }
            },
            onDismiss = { confirmDelete = false },
        )
    }

    if (showRename) {
        TextInputDialog(
            title = stringResource(R.string.files_rename_title, target.name),
            initial = target.name,
            confirmLabel = stringResource(R.string.files_action_rename),
            onConfirm = { newName ->
                showRename = false
                run { client.renameFileTreeEntry(rootPath, rel, newName) }
            },
            onDismiss = { showRename = false },
        )
    }

    if (showMove) {
        DirectoryBrowserSheet(
            title = stringResource(R.string.files_move_title, target.name),
            confirmLabel = stringResource(R.string.files_action_move),
            loadHome = { client.getHomeDirectory() },
            loadDirs = { p -> client.listDirectoryEntries(p) },
            onSelect = { destDir ->
                showMove = false
                val relDest = relPathOrNull(rootPath, destDir)
                if (relDest == null) {
                    error = outsideWorkspaceMsg
                } else {
                    run { client.moveFileTreeEntry(rootPath, rel, relDest) }
                }
            },
            onDismiss = { showMove = false },
        )
    }

    if (showDownloadBrowser) {
        DownloadTargetBrowser(
            client = client,
            rootPath = rootPath,
            rel = rel,
            onDone = { showDownloadBrowser = false; onDone() },
            onDismiss = { showDownloadBrowser = false },
        )
    }
}

/** SAF "create document" then stream the server file into it chunk-by-chunk. */
@Composable
private fun DownloadTargetBrowser(
    client: CodegClient,
    rootPath: String,
    rel: String,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                error = null
                try {
                    val out = context.contentResolver.openOutputStream(uri) ?: throw ApiError.Transport("Cannot open download target")
                    withContext(Dispatchers.IO) {
                        out.use { stream ->
                            client.downloadWorkspace(rootPath, rel, isDir = false) { chunk -> stream.write(chunk) }
                        }
                    }
                    onDone()
                } catch (e: Exception) {
                    error = e.message ?: e.javaClass.simpleName
                } finally {
                    busy = false
                }
            }
        } else {
            onDismiss()
        }
    }
    LaunchedEffect(Unit) { launcher.launch(rel.substringAfterLast('/')) }

    if (busy || error != null) {
        AlertDialog(error, busy, onDismiss)
    }
}

@Composable
private fun AlertDialog(error: String?, busy: Boolean, onDismiss: () -> Unit) {
    val colors = CodegTheme.colors
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (busy) R.string.files_downloading else R.string.files_download_failed)) },
        text = { Text(error ?: stringResource(R.string.files_download_in_progress)) },
        confirmButton = {
            if (!busy) TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) }
        },
    )
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, tint: Color = CodegTheme.colors.textPrimary, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 14.sp, color = tint)
    }
}

/** New file / new folder prompt. For files, an optional content box (hidden while the
 *  folder kind is selected — the typed text is kept, not cleared, on kind switch). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewEntrySheet(parentPath: String, onCreate: (String, Boolean, String) -> Unit, onDismiss: () -> Unit) {
    val colors = CodegTheme.colors
    var name by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var isDir by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(
                stringResource(R.string.files_new_title, parentPath.substringAfterLast('/')),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
            )
            CodegSegmented(
                options = listOf(stringResource(R.string.files_new_file), stringResource(R.string.files_new_folder)),
                selectedIndex = if (isDir) 1 else 0,
                onSelect = { isDir = it == 1 },
                modifier = Modifier.padding(top = 12.dp),
            )
            CodegTextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.files_new_name),
                mono = true,
                modifier = Modifier.padding(top = 12.dp),
            )
            androidx.compose.animation.AnimatedVisibility(visible = !isDir) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.files_new_content_label)) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = colors.textPrimary),
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            }
            PrimaryButton(
                text = stringResource(R.string.common_create),
                onClick = { onCreate(name, isDir, content) },
                enabled = name.isNotBlank() && !name.contains('/') && name != "." && name != "..",
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

/** Destructive confirm with an explicit message. */
@Composable
private fun ConfirmDialog(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = CodegTheme.colors
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message, color = colors.textSecondary) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = colors.danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** Single-line text prompt (rename, etc.). */
@Composable
private fun TextInputDialog(title: String, initial: String, confirmLabel: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = CodegTheme.colors
    var value by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            CodegTextField(value = value, onValueChange = { value = it }, label = stringResource(R.string.files_new_name), mono = true)
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(item: DirectoryItem, onLongClick: () -> Unit, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    val sizeLabel = item.size?.takeIf { !item.isDir }?.let { formatBytes(it) }
    ListItem(
        headlineContent = { Text(item.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            Icon(
                if (item.isDir) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile,
                contentDescription = null,
                tint = if (item.isDir) colors.accent else colors.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        },
        trailingContent = sizeLabel?.let { { Text(it, fontSize = 11.sp, color = colors.textTertiary) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = colors.textPrimary),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilePreview(client: CodegClient, rootPath: String, absPath: String, onEdit: () -> Unit) {
    val colors = CodegTheme.colors
    val rel = if (absPath.startsWith("$rootPath/")) absPath.removePrefix("$rootPath/") else absPath.substringAfterLast('/')
    var result by remember(absPath) { mutableStateOf<Result<String>?>(null) }
    var loadTick by remember(absPath) { mutableStateOf(0) }
    val reload: () -> Unit = { loadTick++ }
    LaunchedEffect(absPath, loadTick) {
        result = runCatching { client.readFilePreview(rootPath, rel).content }
    }
    // Rendered vs raw: Markdown files default to rendered; everything else is raw-only.
    val kind = remember(absPath) { previewKindOf(absPath) }
    var rendered by remember(absPath, kind) { mutableStateOf(kind == PreviewKind.MARKDOWN) }
    // Fullscreen toggle. The fetched [result] / [rendered] state lives here so both the
    // inline and fullscreen renderings share one source of truth — and only ONE of them
    // mounts at a time (the inline branch renders an empty shell while fullscreen is up),
    // so there are never two WebViews alive for the same file.
    var fullscreen by rememberSaveable(absPath) { mutableStateOf(false) }

    val header: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(rel, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (kind == PreviewKind.TEXT && result?.getOrNull()?.isNotEmpty() == true) {
                IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.files_action_edit), tint = colors.textSecondary, modifier = Modifier.size(18.dp)) }
            }
            if (kind == PreviewKind.MARKDOWN && result?.getOrNull()?.isNotEmpty() == true) {
                FilePreviewModeToggle(rendered = rendered, onToggle = { rendered = it })
            }
            if (kind != PreviewKind.IMAGE && result?.getOrNull()?.isNotEmpty() == true) {
                IconButton(onClick = { fullscreen = true }) { Icon(Icons.Rounded.Fullscreen, contentDescription = stringResource(R.string.files_preview_fullscreen), tint = colors.textSecondary, modifier = Modifier.size(20.dp)) }
            }
            result?.getOrNull()?.let { if (it.isNotEmpty()) CopyButton(it) }
        }
    }
    // The body render is shared verbatim by the inline pane and the fullscreen dialog.
    val body: @Composable () -> Unit = {
        val r = result
        when {
            r == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingView(stringResource(R.string.common_loading)) }
            r.isFailure -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                InlineError(Icons.AutoMirrored.Rounded.InsertDriveFile, stringResource(R.string.files_read_failed), r.exceptionOrNull()?.message ?: stringResource(R.string.files_error), onRetry = reload)
            }
            else -> {
                val content = r.getOrDefault("")
                when {
                    content.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EmptyState(Icons.AutoMirrored.Rounded.InsertDriveFile, stringResource(R.string.files_empty_file), stringResource(R.string.files_empty_file_message)) }
                    rendered && kind == PreviewKind.MARKDOWN -> MarkdownScrollContent(content)
                    kind == PreviewKind.IMAGE -> ImagePreview(client, rootPath, rel)
                    kind == PreviewKind.HTML -> HtmlPreview(absPath.substringAfterLast('/'), content)
                    else -> FileContent(content)
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        header()
        if (!fullscreen) body()
    }

    if (fullscreen) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { fullscreen = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(colors.bg)
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                header()
                Box(Modifier.weight(1f)) { body() }
            }
        }
    }
}

private enum class PreviewKind { TEXT, MARKDOWN, IMAGE, HTML, OTHER }

/** Map a file name to how the preview should try to show it. */
private fun previewKindOf(name: String): PreviewKind {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "md", "markdown" -> PreviewKind.MARKDOWN
        "html", "htm" -> PreviewKind.HTML
        "png", "jpg", "jpeg", "gif", "webp", "bmp" -> PreviewKind.IMAGE
        "json", "xml", "yml", "yaml", "toml", "ini", "conf", "sh", "bash", "py", "rs", "kt", "java", "js", "ts", "tsx", "jsx", "css", "c", "h", "cpp", "go", "rb", "php", "sql", "txt", "log", "env", "gitignore", "lock", "gradle", "kts", "properties", "csv" -> PreviewKind.TEXT
        // No extension is common (Makefile, Dockerfile, LICENSE); give text a try.
        "" -> PreviewKind.TEXT
        else -> PreviewKind.OTHER
    }
}

/** Rendered/Source segmented toggle for Markdown file previews. */
@Composable
private fun FilePreviewModeToggle(rendered: Boolean, onToggle: (Boolean) -> Unit) {
    CodegSegmented(
        options = listOf(
            stringResource(R.string.files_preview_rendered),
            stringResource(R.string.files_preview_source),
        ),
        selectedIndex = if (rendered) 0 else 1,
        onSelect = { onToggle(it == 0) },
        modifier = Modifier.padding(end = 8.dp),
    )
}

/**
 * Rendered Markdown in a scrollable container. The file preview is a plain Column
 * (not the session timeline's LazyColumn), so the block list scrolls as one unit;
 * `MarkdownContent` already re-parses only when the content changes.
 */
@Composable
private fun MarkdownScrollContent(content: String) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
    ) {
        MarkdownContent(content, modifier = Modifier.padding(vertical = 8.dp))
    }
}

/** Image preview via `read_workspace_file_base64`, decoded on IO. */
@Composable
private fun ImagePreview(client: CodegClient, rootPath: String, rel: String) {
    val colors = CodegTheme.colors
    var bitmap by remember(rel) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var error by remember(rel) { mutableStateOf<String?>(null) }
    var loadTick by remember(rel) { mutableStateOf(0) }
    LaunchedEffect(rel, loadTick) {
        bitmap = null
        error = null
        withContext(Dispatchers.IO) {
            try {
                val b64 = client.readWorkspaceFileBase64(rootPath, rel, maxBytes = 10 * 1024 * 1024)
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw ApiError.Decoding("Unsupported image format")
            } catch (e: Exception) {
                error = e.message ?: "Failed to load image"
            }
        }
    }
    val bmp = bitmap
    when {
        bmp != null -> Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = rel,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)),
            )
        }
        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            InlineError(Icons.Rounded.Image, stringResource(R.string.files_read_failed), error!!, onRetry = { loadTick++ })
        }
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingView(stringResource(R.string.common_loading)) }
    }
}

/**
 * Local HTML preview in a locked-down WebView: JS off, no file/content access,
 * network loads blocked (remote <img>/beacons too). The raw HTML string is loaded
 * via `loadDataWithBaseURL` so nothing touches the local filesystem.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun HtmlPreview(name: String, html: String) {
    val colors = CodegTheme.colors
    var showSource by remember(name) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CodegSegmented(
                options = listOf(stringResource(R.string.files_preview_rendered), stringResource(R.string.files_preview_source)),
                selectedIndex = if (showSource) 1 else 0,
                onSelect = { showSource = it == 1 },
                modifier = Modifier.weight(1f),
            )
        }
        if (showSource) {
            FileContent(html)
        } else {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    android.webkit.WebView(ctx).apply {
                        // Hardened defaults: the preview is for reading, not executing
                        // or phoning home.
                        settings.javaScriptEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.blockNetworkLoads = true
                        settings.mediaPlaybackRequiresUserGesture = true
                        webViewClient = object : android.webkit.WebViewClient() {
                            // Block navigation away from the inlined document.
                            override fun shouldOverrideUrlLoading(view: android.webkit.WebView, request: android.webkit.WebResourceRequest): Boolean = true
                        }
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                    }
                },
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun FileContent(content: String) {
    val colors = CodegTheme.colors
    val truncationNote = stringResource(R.string.files_truncated_note, 4000)
    val lines = remember(content, truncationNote) {
        val all = content.split("\n")
        if (all.size > 4000) all.take(4000) + truncationNote else all
    }
    val gutterWidth = (lines.size.toString().length * 8 + 12).dp
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp).clip(RoundedCornerShape(10.dp)).background(colors.codeSurface).border(0.5.dp, colors.hairline, RoundedCornerShape(10.dp)),
    ) {
        items(lines.size) { i ->
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Text("${i + 1}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.textTertiary, modifier = Modifier.width(gutterWidth).padding(start = 6.dp, end = 6.dp))
                Text(lines[i].ifEmpty { " " }, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = colors.textSecondary)
            }
        }
    }
}

/**
 * Full-screen text editor: reads with an etag, saves with an optimistic lock, and
 * offers "overwrite anyway" when the file changed on disk since it was opened.
 * [onClose] is the clean exit; [onDirtyClose] runs instead when there are unsaved
 * edits, so the caller can show a discard confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileEditorScreen(
    client: CodegClient,
    rootPath: String,
    absPath: String,
    onDirtyChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    onDirtyClose: () -> Unit,
    initialText: String? = null,
) {
    val colors = CodegTheme.colors
    val scope = rememberCoroutineScope()
    val rel = if (absPath.startsWith("$rootPath/")) absPath.removePrefix("$rootPath/") else absPath.substringAfterLast('/')
    val tooLargeMsg = stringResource(R.string.files_too_large_edit)

    var loading by remember { mutableStateOf(true) }
    var readOnly by remember { mutableStateOf(false) }
    var etag by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var conflict by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var savedFlash by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            loading = true
            error = null
            try {
                val edit = withContext(Dispatchers.IO) { client.readFileForEdit(rootPath, rel) }
                if (edit.content.length > MAX_EDIT_CHARS) {
                    error = tooLargeMsg
                } else {
                    text = edit.content
                    etag = edit.etag
                    readOnly = edit.readOnly
                    dirty = false
                    onDirtyChange(false)
                    conflict = false
                    // A failed create-with-content handed us the pasted text: seed the
                    // editor with it (marking dirty so a save is one tap away) instead of
                    // showing the server's empty file and losing the paste.
                    if (!initialText.isNullOrEmpty() && text.isEmpty() && !readOnly) {
                        text = initialText.take(MAX_EDIT_CHARS)
                        dirty = true
                        onDirtyChange(true)
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(absPath) { load() }

    fun save(force: Boolean) {
        val currentEtag = etag ?: return
        scope.launch {
            saving = true
            error = null
            try {
                val result = withContext(Dispatchers.IO) {
                    client.saveFileContent(rootPath, rel, text, if (force) null else currentEtag)
                }
                etag = result.etag
                dirty = false
                onDirtyChange(false)
                conflict = false
                savedFlash = true
            } catch (e: Exception) {
                if (client.isEtagConflict(e)) {
                    conflict = true
                } else {
                    error = e.message ?: e.javaClass.simpleName
                }
            } finally {
                saving = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(colors.bg).imePadding()) {
        TopAppBar(
            title = { Text(rel, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, fontFamily = FontFamily.Monospace) },
            navigationIcon = {
                IconButton(onClick = { if (dirty) onDirtyClose() else onClose() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = colors.textPrimary)
                }
            },
            actions = {
                if (savedFlash) {
                    Text(stringResource(R.string.files_saved), fontSize = 12.sp, color = colors.accent, modifier = Modifier.padding(end = 8.dp))
                }
                IconButton(
                    onClick = { save(force = false) },
                    enabled = !loading && !readOnly && dirty && !saving && etag != null,
                ) {
                    Icon(Icons.Rounded.Save, contentDescription = stringResource(R.string.files_save), tint = if (dirty && !readOnly) colors.accent else colors.textTertiary)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = colors.textPrimary),
        )
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingView(stringResource(R.string.common_loading)) }
            error != null && text.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                InlineError(Icons.Rounded.Edit, stringResource(R.string.files_read_failed), error!!, onRetry = { load() })
            }
            else -> Column(Modifier.fillMaxSize()) {
                error?.let {
                    Text(it, color = colors.danger, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        if (!dirty) {
                            dirty = true
                            onDirtyChange(true)
                        }
                        savedFlash = false
                    },
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                    readOnly = readOnly,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = colors.textPrimary),
                )
            }
        }
    }

    if (conflict) {
        ConfirmDialog(
            title = stringResource(R.string.files_conflict_title),
            message = stringResource(R.string.files_conflict_message),
            confirmLabel = stringResource(R.string.files_conflict_overwrite),
            onConfirm = { conflict = false; save(force = true) },
            onDismiss = {
                conflict = false
                // Non-forced path out: reload to see what changed on disk.
                load()
            },
        )
    }
}

/**
 * Server tree APIs (create/rename/move, upload `target_path`) take workspace-relative
 * paths with `""` meaning the root — the directory browser hands out absolute paths.
 * Returns `null` when the picked path is outside this workspace.
 */
private fun relPathOrNull(rootPath: String, absPath: String): String? = when {
    absPath == rootPath -> ""
    absPath.startsWith("$rootPath/") -> absPath.removePrefix("$rootPath/")
    else -> null
}

/** Client cap for the on-device editor — Compose text fields choke far below the server's 50 MB limit. */
private const val MAX_EDIT_CHARS = 2_000_000

private fun sortItems(items: List<DirectoryItem>): List<DirectoryItem> =
    items.sortedWith(compareByDescending<DirectoryItem> { it.isDir }.thenBy { it.name.lowercase() })

private fun formatBytes(bytes: Long): String {
    val loc = java.util.Locale.US
    return when {
        bytes >= 1_073_741_824 -> String.format(loc, "%.1f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> String.format(loc, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> String.format(loc, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
