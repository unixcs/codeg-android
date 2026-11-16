package app.codeg.android.feature.projects

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.component.FolderBadge
import app.codeg.android.core.designsystem.component.GlassCard
import app.codeg.android.core.designsystem.component.LivePulse
import app.codeg.android.core.designsystem.component.deviceBadgeColor
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.designsystem.theme.colorFromHex
import app.codeg.android.core.model.FolderDetail
import kotlinx.coroutines.launch

/**
 * 批v180-A: the projects tab IS the devices overview now. One section per
 * visible device — a probe-status header (identity dot + name + host), that
 * device's workspace folders as tappable chips, and an "all files" row that
 * opens the ephemeral full-disk browser pinned to the device. Folder taps
 * navigate with the owning device's serverId so the detail page is page-pinned
 * (批v176), not a silent re-selection of the global device.
 *
 * The FAB still talks to the globally-selected device (open-folder / clone are
 * "add something on the current device" operations, unchanged from 批v129/174).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(
    onOpenFolder: (Int, String?) -> Unit,
    onBrowseAll: (String, String) -> Unit,
    viewModel: ProjectsViewModel = hiltViewModel(),
    overviewViewModel: DevicesOverviewViewModel = hiltViewModel(),
) {
    val devices by overviewViewModel.devices.collectAsStateWithLifecycle()
    val refreshing by overviewViewModel.refreshing.collectAsStateWithLifecycle()
    val selectedServerId by overviewViewModel.selectedServerId.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    var showBrowser by remember { mutableStateOf(false) }
    var showClone by remember { mutableStateOf(false) }
    // 批v129: long-press a folder chip → action sheet (copy path / remove from
    // workspace). 批v180-A: removal targets the OWNING device, so the sheet is
    // keyed by (serverId, folder) instead of the folder alone.
    var actionTarget by remember { mutableStateOf<Pair<String, FolderDetail>?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Selected device first, then alphabetical — the section the FAB writes to
    // leads the page.
    val devicesSorted = remember(devices, selectedServerId) {
        devices.sortedWith(
            compareByDescending<DeviceOverviewState> { it.profile.id == selectedServerId }
                .thenBy { it.profile.name.lowercase() },
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.devices_title)) },
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
            if (devicesSorted.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(Icons.Rounded.FolderOpen, stringResource(R.string.projects_empty_title), stringResource(R.string.projects_empty_message))
                }
            } else {
                PullToRefreshBox(isRefreshing = refreshing, onRefresh = overviewViewModel::refresh, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(
                            count = devicesSorted.size,
                            key = { devicesSorted[it].profile.id },
                        ) { index ->
                            val state = devicesSorted[index]
                            DeviceSection(
                                state = state,
                                onOpenFolder = { folder -> onOpenFolder(folder.id, state.profile.id) },
                                onBrowseAll = {
                                    val home = state.home
                                    if (home != null) onBrowseAll(state.profile.id, home)
                                },
                                onRetry = { overviewViewModel.retry(state.profile.id) },
                                onLongPressFolder = { folder -> actionTarget = state.profile.id to folder },
                            )
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
            // 批v180-A: clone registers the folder, ProjectsViewModel broadcasts
            // foldersChanged, and the overview refetches on its own.
            onCloned = { showClone = false },
            onDismiss = { showClone = false },
        )
    }
    actionTarget?.let { (serverId, folder) ->
        FolderActionSheet(
            folder = folder,
            onRemove = {
                actionTarget = null
                scope.launch {
                    // Surface the failure — a silent return leaves the chip in place
                    // with zero feedback (review round 3, still true per-device).
                    overviewViewModel.removeFolder(serverId, folder.id)?.let { err ->
                        android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDismiss = { actionTarget = null },
        )
    }
}

/**
 * One device as a glass section: probe-status header, then the device's
 * workspace folders as chips (long-press = action sheet), then the "all files"
 * row into the ephemeral browser. While probing the body shows a connecting
 * row; a failed probe collapses to an offline row whose whole area is tappable
 * for a retry.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun DeviceSection(
    state: DeviceOverviewState,
    onOpenFolder: (FolderDetail) -> Unit,
    onBrowseAll: () -> Unit,
    onRetry: () -> Unit,
    onLongPressFolder: (FolderDetail) -> Unit,
) {
    val colors = CodegTheme.colors
    GlassCard(padding = 0.dp) {
        Column(Modifier.fillMaxWidth()) {
            // Header: identity dot (stable per-device hue from the device bar),
            // name, and the host as the "what am I looking at" subtitle. The
            // trailing slot carries the probe status; tapping a failed header
            // retries the probe.
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (state.online) Modifier else Modifier.clickable(onClick = onRetry))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(deviceBadgeColor(state.profile.name)),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        state.profile.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        android.net.Uri.parse(state.profile.baseUrl).host ?: state.profile.baseUrl,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when {
                    state.probing -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            LivePulse(dotSize = 7.dp)
                            Text(stringResource(R.string.devices_probing), fontSize = 11.sp, color = colors.textTertiary)
                        }
                    }
                    !state.online -> Text(
                        stringResource(R.string.devices_offline),
                        fontSize = 11.sp,
                        color = colors.danger,
                    )
                }
            }

            HorizontalDivider(color = colors.hairline)

            when {
                state.probing -> SectionMessageRow(stringResource(R.string.devices_probing))
                !state.online -> Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        state.error ?: stringResource(R.string.devices_offline),
                        fontSize = 12.sp,
                        color = colors.textTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.devices_retry),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.accent,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                else -> {
                    if (state.folders.isEmpty()) {
                        SectionMessageRow(stringResource(R.string.devices_no_folders))
                    } else {
                        // Workspace folders as compact chips — the overview is a map
                        // across devices, so chips keep N devices scannable without
                        // each one eating a full-height row per folder.
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            state.folders.forEach { folder ->
                                val tile = colorFromHex(folder.color) ?: colors.accent
                                Row(
                                    Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(colors.textPrimary.copy(alpha = 0.05f))
                                        .combinedClickable(onClick = { onOpenFolder(folder) }, onLongClick = { onLongPressFolder(folder) })
                                        .padding(horizontal = 9.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    FolderBadge(color = tile, size = 16.dp)
                                    Text(
                                        folder.name,
                                        fontSize = 13.sp,
                                        color = colors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.size(width = 160.dp, height = 20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = colors.hairline, modifier = Modifier.padding(start = 34.dp))

            // "All files": the full-disk browser rooted at the device home,
            // pinned to this device (folderId = -1 + path + serverId).
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .then(if (state.online && state.home != null) Modifier.clickable(onClick = onBrowseAll) else Modifier)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    tint = if (state.online && state.home != null) colors.textSecondary else colors.textTertiary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    stringResource(R.string.devices_browse_all),
                    fontSize = 13.sp,
                    color = if (state.online && state.home != null) colors.textSecondary else colors.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                if (state.online && state.home != null) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** A full-width dim status line (connecting / nothing registered). */
@Composable
private fun SectionMessageRow(message: String) {
    val colors = CodegTheme.colors
    Text(
        message,
        fontSize = 12.sp,
        color = colors.textTertiary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
    )
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
 * 批v180-A: the remove call goes to the folder's OWN device via the overview VM.
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
