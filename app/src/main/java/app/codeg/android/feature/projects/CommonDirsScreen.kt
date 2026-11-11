package app.codeg.android.feature.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.codeg.android.R
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.CommonDirsDefaults
import app.codeg.android.core.datastore.CommonDirsStore
import app.codeg.android.core.datastore.ServerCommonDir
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.designsystem.component.OptionListSheet
import app.codeg.android.core.designsystem.component.PickerField
import app.codeg.android.core.designsystem.component.PickerOption
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.network.CodegClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 常用目录 management (batch v176): per-device jump-menu bindings for the file
 * screen. First visit (or first switch to an unconfigured device) seeds the
 * built-in defaults as ordinary editable rows, after which the stored list is
 * authoritative — deleting everything is an honest "no pinned dirs".
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class CommonDirsViewModel @Inject constructor(
    private val repository: ServerRepository,
    private val store: CommonDirsStore,
) : ViewModel() {

    val profiles: StateFlow<List<ServerProfile>> =
        repository.profiles.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Which device the editor is managing (defaults to the global selection). */
    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    val dirs: StateFlow<List<ServerCommonDir>> = _selectedId
        .flatMapLatest { store.dirsFor(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val seeding = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            _selectedId.value = repository.selectedProfile.first()?.id
            seedDefaultsIfEmpty()
        }
    }

    fun select(id: String) {
        _selectedId.value = id
        seedDefaultsIfEmpty()
    }

    /** Client for the currently-managed device (directory browser data source). */
    suspend fun currentClient(): CodegClient? {
        val id = _selectedId.value ?: return null
        val profile = repository.profiles.first().firstOrNull { it.id == id } ?: return null
        return repository.client(profile)
    }

    fun add(label: String, path: String) {
        val id = _selectedId.value ?: return
        viewModelScope.launch {
            store.setDirs(id, dirs.value + ServerCommonDir(label.trim(), path.trim()))
        }
    }

    fun remove(dir: ServerCommonDir) {
        val id = _selectedId.value ?: return
        viewModelScope.launch {
            store.setDirs(id, dirs.value.filterNot { it.path == dir.path && it.label == dir.label })
        }
    }

    fun rename(dir: ServerCommonDir, newLabel: String) {
        val id = _selectedId.value ?: return
        viewModelScope.launch {
            store.setDirs(id, dirs.value.map { if (it == dir) dir.copy(label = newLabel.trim()) else it })
        }
    }

    /**
     * Seed the built-in defaults as editable rows on first entry per device. The
     * home lookup is best-effort (an unreachable device still seeds the two
     * always-valid targets); CE is rethrown, never swallowed by runCatching.
     */
    private fun seedDefaultsIfEmpty() {
        val id = _selectedId.value ?: return
        if (id in seeding) return
        // Claim BEFORE launching: two rapid select(id) calls both pass the guard
        // if the claim rides inside the coroutine (批v176 R2).
        seeding += id
        viewModelScope.launch {
            try {
                if (store.dirsNow(id).isNotEmpty()) return@launch
                val profile = repository.profiles.first().firstOrNull { it.id == id }
                val home = profile?.let { p ->
                    repository.client(p)?.let { c ->
                        try {
                            c.getHomeDirectory()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
                store.setDirs(
                    id,
                    listOfNotNull(
                        home?.let { ServerCommonDir("~", it) },
                        ServerCommonDir("mnt", CommonDirsDefaults.MNT),
                        ServerCommonDir("Projects", CommonDirsDefaults.PROJECTS),
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best-effort convenience seed: a disk/IO failure must not crash
                // the process (the store write is the only unguarded suspend call
                // left, and DataStore edit can throw IOException).
            } finally {
                seeding -= id
            }
        }
    }
}

/** Standalone push destination (own top bar, Back returns to the caller). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommonDirsRoute(onBack: () -> Unit) {
    val colors = CodegTheme.colors
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_leaf_common_dirs)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = colors.textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = colors.textPrimary),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            CommonDirsScreen()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommonDirsScreen(viewModel: CommonDirsViewModel = hiltViewModel()) {
    val colors = CodegTheme.colors
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedId.collectAsStateWithLifecycle()
    val dirs by viewModel.dirs.collectAsStateWithLifecycle()

    var showDeviceSheet by rememberSaveable { mutableStateOf(false) }
    var showBrowser by rememberSaveable { mutableStateOf(false) }
    // Picked path waiting for its label; null = no dialog.
    var labelDraft by rememberSaveable { mutableStateOf<String?>(null) }
    var labelText by rememberSaveable { mutableStateOf("") }
    /** Path of the row being renamed (resolved back to a [ServerCommonDir] against
     *  [dirs] at commit time — a plain String is Bundle-saveable, the object isn't). */
    var renameTarget by rememberSaveable { mutableStateOf<String?>(null) }

    val selectedProfile = profiles.firstOrNull { it.id == selectedId }

    if (showDeviceSheet) {
        OptionListSheet(
            title = stringResource(R.string.common_dirs_pick_device),
            options = profiles.map { PickerOption(it.id, it.name) },
            selectedId = selectedId,
            onSelect = {
                viewModel.select(it.id)
                showDeviceSheet = false
            },
            onDismiss = { showDeviceSheet = false },
        )
    }

    // Directory picker for "add" — resolves the device client first (a device
    // without a token just doesn't open the browser).
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var browserClient by androidx.compose.runtime.remember { mutableStateOf<CodegClient?>(null) }
    fun openBrowser() {
        scope.launch {
            val c = viewModel.currentClient()
            if (c != null) {
                browserClient = c
                showBrowser = true
            }
        }
    }

    if (showBrowser && browserClient != null) {
        DirectoryBrowserSheet(
            title = stringResource(R.string.common_dirs_browse_title),
            confirmLabel = stringResource(R.string.common_dirs_browse_confirm),
            loadHome = { browserClient!!.getHomeDirectory() },
            loadDirs = { path -> browserClient!!.listDirectoryEntries(path) },
            onSelect = { path ->
                showBrowser = false
                labelText = path.substringAfterLast('/').ifEmpty { path }
                labelDraft = path
            },
            onDismiss = { showBrowser = false },
            showRoot = true,
        )
    }

    // Add / rename label dialog (one dialog at a time: labelDraft = add, renameTarget = rename).
    val dialogPath = labelDraft ?: renameTarget
    if (dialogPath != null) {
        val isRename = labelDraft == null
        AlertDialog(
            onDismissRequest = { labelDraft = null; renameTarget = null },
            title = { Text(stringResource(if (isRename) R.string.common_dirs_rename_title else R.string.common_dirs_add)) },
            text = {
                Column {
                    Text(dialogPath, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                    OutlinedTextField(
                        value = labelText,
                        onValueChange = { labelText = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.common_dirs_label_title)) },
                        placeholder = { Text(stringResource(R.string.common_dirs_label_hint)) },
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = labelText.isNotBlank(), onClick = {
                    if (isRename) {
                        renameTarget?.let { path -> dirs.firstOrNull { it.path == path } }
                            ?.let { viewModel.rename(it, labelText) }
                    } else {
                        labelDraft?.let { viewModel.add(labelText, it) }
                    }
                    labelDraft = null
                    renameTarget = null
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { labelDraft = null; renameTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            PickerField(
                icon = Icons.Rounded.PhoneAndroid,
                label = stringResource(R.string.common_dirs_pick_device),
                value = selectedProfile?.name,
                placeholder = stringResource(R.string.common_dirs_pick_device),
                onClick = { showDeviceSheet = true },
                modifier = Modifier.padding(vertical = 8.dp),
            )
            if (dirs.isEmpty()) {
                Text(
                    text = stringResource(R.string.common_dirs_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textTertiary,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(32.dp),
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(dirs, key = { it.path + it.label }) { dir ->
                    ListItem(
                        headlineContent = {
                            Text(dir.label, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(dir.path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        leadingContent = {
                            Icon(Icons.Rounded.Folder, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { labelText = dir.label; renameTarget = dir.path }) {
                                    Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.common_rename), tint = colors.textTertiary, modifier = Modifier.size(18.dp))
                                }
                                IconButton(onClick = { viewModel.remove(dir) }) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.common_delete), tint = colors.danger, modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = colors.textPrimary),
                    )
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { openBrowser() },
            icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.common_dirs_add)) },
            containerColor = colors.accent,
            contentColor = colors.onAccent,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}
