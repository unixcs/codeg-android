package app.codeg.android.feature.projects

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.common.RelativeTime
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.component.FolderBadge
import app.codeg.android.core.designsystem.component.GlassCard
import app.codeg.android.core.designsystem.component.InlineError
import app.codeg.android.core.designsystem.component.LivePulse
import app.codeg.android.core.designsystem.component.LoadingView
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.designsystem.theme.colorFromHex
import app.codeg.android.core.model.FolderDetail
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(
    onOpenFolder: (Int) -> Unit,
    viewModel: ProjectsViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    var showBrowser by remember { mutableStateOf(false) }
    var showClone by remember { mutableStateOf(false) }
    // 批v129: long-press a folder row → action sheet (copy path / remove from workspace).
    var actionFolder by remember { mutableStateOf<FolderDetail?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_folders)) },
                windowInsets = WindowInsets(0, 0, 0, 0), // device bar in MainShell owns the status-bar inset
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = colors.textPrimary,
                ),
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(
                    onClick = { menuOpen = true },
                    containerColor = colors.accent,
                    contentColor = colors.onAccent,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.projects_add))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.projects_open_folder)) },
                        leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null) },
                        onClick = { menuOpen = false; showBrowser = true },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.projects_clone_repo)) },
                        leadingIcon = { Icon(Icons.Rounded.CloudDownload, null) },
                        onClick = { menuOpen = false; showClone = true },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                ui.loading && !ui.hasLoaded ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingView(stringResource(R.string.common_loading)) }
                !ui.hasLoaded && ui.error != null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        InlineError(Icons.Rounded.FolderOpen, stringResource(R.string.projects_load_failed), ui.error!!, viewModel::refresh)
                    }
                ui.hasLoaded && ui.folders.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(Icons.Rounded.FolderOpen, stringResource(R.string.projects_empty_title), stringResource(R.string.projects_empty_message))
                    }
                else -> PullToRefreshBox(isRefreshing = ui.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 88.dp),
                    ) {
                        // One continuous grouped surface holding every folder as a
                        // borderless row split by inset hairlines — the iOS grouped-list
                        // look, matching the Settings tab. Folder counts are small, so a
                        // single card inside the lazy list is fine.
                        item {
                            GlassCard(padding = 0.dp) {
                                val list = sortedFolders(ui.folders)
                                list.forEachIndexed { index, folder ->
                                    if (index > 0) {
                                        HorizontalDivider(color = colors.hairline, modifier = Modifier.padding(start = 66.dp))
                                    }
                                    FolderRow(folder, ui.runningCount(folder.id), onLongClick = { actionFolder = folder }) { onOpenFolder(folder.id) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showBrowser) {
        DirectoryBrowserSheet(
            title = stringResource(R.string.projects_open_folder),
            confirmLabel = stringResource(R.string.projects_open_this_folder),
            loadHome = viewModel::homeDirectory,
            loadDirs = viewModel::listDirectories,
            onSelect = { path -> showBrowser = false; viewModel.openFolder(path) {} },
            onDismiss = { showBrowser = false },
            showRoot = true,
        )
    }
    if (showClone) {
        CloneRepoSheet(
            loadHome = viewModel::homeDirectory,
            loadDirs = viewModel::listDirectories,
            clone = viewModel::clone,
            onCloned = { showClone = false; viewModel.refresh() },
            onDismiss = { showClone = false },
        )
    }
    actionFolder?.let { folder ->
        FolderActionSheet(
            folder = folder,
            onRemove = {
                actionFolder = null
                scope.launch {
                    // Surface the failure — a silent return leaves the row in place with
                    // zero feedback (review round 3).
                    viewModel.removeFolder(folder.id)?.let { err ->
                        android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDismiss = { actionFolder = null },
        )
    }
}

/**
 * One folder as a borderless grouped row: a colored [FolderBadge] tile, the name
 * with its full path + branch underneath, a trailing running-count pill or
 * relative last-opened time, then a drill-in chevron. The enclosing [GlassCard]
 * draws the surface; rows are split by inset hairlines. Long-press opens the
 * [FolderActionSheet] (copy path / remove from workspace) — batch v129.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(
    folder: FolderDetail,
    running: Int,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = CodegTheme.colors
    val tile = colorFromHex(folder.color) ?: colors.accent
    Row(
        modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FolderBadge(color = tile, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(folder.name, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(folder.path, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                folder.gitBranch?.let { BranchPill(it) }
            }
        }
        if (running > 0) {
            RunningPill(running)
        } else {
            folder.lastOpenedAt?.let { Text(RelativeTime.compact(it), fontSize = 11.sp, color = colors.textTertiary) }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
    }
}

/** Active-task indicator: a pulsing dot + count in an accent capsule (iOS folder row). */
@Composable
private fun RunningPill(count: Int) {
    val colors = CodegTheme.colors
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(colors.accentDim).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        LivePulse(dotSize = 7.dp)
        Text("$count", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colors.accent)
    }
}

@Composable
fun BranchPill(branch: String) {
    val colors = CodegTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.clip(RoundedCornerShape(50)).background(colors.textPrimary.copy(alpha = 0.06f)).padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Icon(Icons.AutoMirrored.Rounded.AltRoute, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(11.dp))
        Text(
            branch,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun sortedFolders(folders: List<FolderDetail>): List<FolderDetail> =
    folders.sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() }))

/** One action row in [FolderActionSheet]. */
@Composable
private fun FolderActionRow(icon: ImageVector, label: String, tint: Color = CodegTheme.colors.textPrimary, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 14.sp, color = tint)
    }
}

/**
 * Long-press actions for a workspace folder (batch v129): copy its absolute
 * path (hand it to the agent as context) and remove it from the workspace view
 * — the server only unregisters the folder, files stay on disk.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderActionSheet(
    folder: FolderDetail,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CodegTheme.colors
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var confirmRemove by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(folder.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(folder.path, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            FolderActionRow(Icons.Rounded.ContentCopy, stringResource(R.string.folders_action_copy_path)) {
                clipboard.setText(AnnotatedString(folder.path))
                // API 33+ shows its own clipboard overlay; only add inline feedback below it.
                if (android.os.Build.VERSION.SDK_INT < 33) copied = true
                scope.launch { kotlinx.coroutines.delay(1500); copied = false }
            }
            FolderActionRow(Icons.Rounded.LinkOff, stringResource(R.string.folders_action_remove), tint = colors.danger) { confirmRemove = true }
            if (copied) {
                Text(
                    stringResource(R.string.folders_path_copied),
                    fontSize = 12.sp,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.folders_remove_title, folder.name)) },
            text = { Text(stringResource(R.string.folders_remove_message), color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; onRemove() }) { Text(stringResource(R.string.folders_action_remove), color = colors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
