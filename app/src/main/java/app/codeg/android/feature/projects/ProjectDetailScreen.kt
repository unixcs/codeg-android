package app.codeg.android.feature.projects

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.datastore.CommonDirsDefaults
import app.codeg.android.core.datastore.ServerCommonDir
import app.codeg.android.core.designsystem.component.CodegSegmented
import app.codeg.android.core.designsystem.component.InlineError
import app.codeg.android.core.designsystem.component.LoadingView
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.designsystem.theme.colorFromHex

private enum class ProjectTab(val labelRes: Int) {
    FILES(R.string.projects_tab_files),
    CHANGES(R.string.projects_tab_changes),
    COMMITS(R.string.projects_tab_commits),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    onBack: () -> Unit,
    // 批v176 jump menu: navigate to a registered project (folderId>0) or an
    // ephemeral absolute path (folderId=-1), optionally pinned to a device.
    onJump: (folderId: Int, path: String?, serverId: String?) -> Unit = { _, _, _ -> },
    onManageDirs: () -> Unit = {},
    viewModel: ProjectDetailViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val commonDirs by viewModel.commonDirs.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    var tab by remember { mutableStateOf(ProjectTab.FILES) }
    var showBranchPicker by remember { mutableStateOf(false) }
    var showCommit by remember { mutableStateOf(false) }
    var showBrowser by remember { mutableStateOf(false) }
    // Menu state lives here (not in the toolbar) so a device-switch landing page
    // can auto-open the jump menu once its data is in.
    var menuOpen by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (viewModel.autoOpenMenu) menuOpen = true
    }

    if (showBranchPicker) BranchPickerSheet(viewModel, onDismiss = { showBranchPicker = false })

    val commitClient = ui.client
    val commitFolder = ui.folder
    if (showCommit && commitClient != null && commitFolder != null) {
        CommitSheet(
            viewModel = viewModel,
            client = commitClient,
            rootPath = commitFolder.path,
            onDismiss = { showCommit = false },
            onCommitted = { push ->
                showCommit = false
                // Run the deferred push AFTER the commit sheet dismisses, so a
                // credential prompt doesn't stack on top of the composer.
                if (push) viewModel.push()
            },
        )
    }

    // One credential sheet for every push/pull/fetch, whichever tab initiated it.
    // key(id) gives each (re-)prompt fresh field/submitting state, like iOS's
    // item-keyed sheet — otherwise a retry would reuse the prior prompt's state.
    ui.credentialPrompt?.let { prompt -> key(prompt.id) { GitCredentialSheet(viewModel, prompt) } }

    val serverId = ui.profile?.id
    val homeLabel = stringResource(R.string.jump_default_home)
    // Built-in targets shown until the user pins custom dirs in settings.
    val defaultDirs = remember(ui.home, homeLabel) {
        listOfNotNull(ui.home?.let { ServerCommonDir(homeLabel, it) }) + listOf(
            ServerCommonDir(CommonDirsDefaults.MNT, CommonDirsDefaults.MNT),
            ServerCommonDir(CommonDirsDefaults.PROJECTS, CommonDirsDefaults.PROJECTS),
        )
    }
    val effectiveDirs = commonDirs.ifEmpty { defaultDirs }

    // Full-tree browser from the ⋯ menu — opens on the current location and
    // lands the pick as an ephemeral browse (never registers a folder).
    ui.client?.takeIf { showBrowser }?.let { c ->
        DirectoryBrowserSheet(
            title = stringResource(R.string.common_dirs_browse_title),
            confirmLabel = stringResource(R.string.common_dirs_browse_confirm),
            loadHome = { c.getHomeDirectory() },
            loadDirs = { path -> c.listDirectoryEntries(path) },
            onSelect = { path ->
                showBrowser = false
                onJump(-1, path, serverId)
            },
            onDismiss = { showBrowser = false },
            showRoot = true,
            initialPath = ui.folder?.path ?: ui.browsePath,
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val folder = ui.folder
            when {
                ui.loading && folder == null && ui.browsePath == null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingView(stringResource(R.string.common_loading)) }
                folder == null && ui.browsePath == null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        InlineError(Icons.Rounded.FolderOpen, stringResource(R.string.projects_load_failed), ui.error ?: stringResource(R.string.projects_folder_not_found), viewModel::load)
                    }
                else -> Column(Modifier.fillMaxSize()) {
                    // 批v176: the old TopAppBar (64dp) + Header (79dp) stacked ate a
                    // third of the viewport before the first file row. One 48dp bar
                    // carries everything the user actually needs here; the path lives
                    // in the Files tab's dir row + the ⋯ menu's copy action. Ephemeral
                    // browse mode (folder==null) shows only Files — git tabs make no
                    // sense for an unregistered path — so no tabs row at all.
                    val ephemeral = folder == null && ui.browsePath != null
                    CompactToolbar(
                        name = folder?.name ?: ui.browsePath?.substringAfterLast('/').orEmpty(),
                        color = folder?.let { colorFromHex(it.color) } ?: colors.accent,
                        branch = if (ephemeral) null else ui.branch,
                        onBack = onBack,
                        onBranchClick = { showBranchPicker = true },
                        menuOpen = menuOpen,
                        onMenuClick = { menuOpen = true },
                    ) { onClose ->
                        val clip = LocalClipboardManager.current
                        val context = LocalContext.current
                        val copiedMsg = stringResource(R.string.folders_action_copy_path)
                        if (folder != null) {
                            MenuHeader(stringResource(R.string.jump_section_project))
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.jump_root)) },
                                leadingIcon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                                onClick = { onJump(folder.id, null, serverId); onClose() },
                            )
                        } else {
                            MenuHeader(stringResource(R.string.jump_section_device_projects))
                            ui.folders.forEach { fd ->
                                DropdownMenuItem(
                                    text = { Text(fd.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    leadingIcon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                                    onClick = { onJump(fd.id, null, serverId); onClose() },
                                )
                            }
                        }
                        MenuHeader(stringResource(R.string.jump_section_common))
                        effectiveDirs.forEach { dir ->
                            DropdownMenuItem(
                                text = { Text(dir.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                                onClick = { onJump(-1, dir.path, serverId); onClose() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.jump_browse)) },
                            leadingIcon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                            onClick = {
                                showBrowser = true
                                onClose()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.folders_action_copy_path)) },
                            leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                            onClick = {
                                clip.setText(AnnotatedString(folder?.path ?: ui.browsePath.orEmpty()))
                                onClose()
                                Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.jump_manage_dirs)) },
                            leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                            onClick = {
                                onManageDirs()
                                onClose()
                            },
                        )
                        // Device switch: page-local navigation context — the global
                        // selection stays untouched. Lands on the device's home with
                        // this menu auto-opened for its quick-picks.
                        MenuHeader(stringResource(R.string.common_dirs_pick_device))
                        profiles.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { Icon(Icons.Rounded.PhoneAndroid, contentDescription = null) },
                                trailingIcon = if (p.id == serverId) {
                                    { Icon(Icons.Rounded.Check, contentDescription = null, tint = CodegTheme.colors.accent) }
                                } else null,
                                onClick = {
                                    if (p.id != serverId) onJump(-1, null, p.id)
                                    onClose()
                                },
                            )
                        }
                    }
                    if (!ephemeral) SegmentedTabs(tab) { tab = it }
                    Box(Modifier.fillMaxSize()) {
                        val client = ui.client
                        if (client != null) {
                            if (folder != null) {
                                when (tab) {
                                    ProjectTab.FILES -> FolderFilesView(client, folder.path)
                                    ProjectTab.CHANGES -> FolderChangesView(viewModel, client, folder.path, onCommit = { showCommit = true })
                                    ProjectTab.COMMITS -> FolderCommitsView(viewModel, client, folder.path)
                                }
                            } else {
                                // Ephemeral: the browsed path anchors writes too —
                                // ops beyond it fail with the clear outside-workspace error.
                                FolderFilesView(client, rootPath = ui.browsePath.orEmpty())
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 48dp single-row chrome: back · badge · name · branch · ⋯ jump menu (slot content). */
@Composable
private fun CompactToolbar(
    name: String,
    color: Color,
    branch: String?,
    onBack: () -> Unit,
    onBranchClick: () -> Unit,
    menuOpen: Boolean,
    onMenuClick: () -> Unit,
    menuContent: @Composable (onClose: () -> Unit) -> Unit,
) {
    val colors = CodegTheme.colors

    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = colors.textPrimary, modifier = Modifier.size(22.dp))
        }
        Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.22f)).border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(6.dp)))
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        branch?.let {
            Box(Modifier.clip(RoundedCornerShape(50)).clickable { onBranchClick() }) { BranchPill(it) }
        }
        Box {
            IconButton(onClick = onMenuClick, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.common_more), tint = colors.textPrimary)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                menuContent { menuOpen = false }
            }
        }
    }
}

/** Non-clickable section caption inside the jump menu. */
@Composable
private fun MenuHeader(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = CodegTheme.colors.textTertiary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun SegmentedTabs(selected: ProjectTab, onSelect: (ProjectTab) -> Unit) {
    val entries = ProjectTab.entries
    CodegSegmented(
        options = entries.map { stringResource(it.labelRes) },
        selectedIndex = entries.indexOf(selected),
        onSelect = { onSelect(entries[it]) },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}
