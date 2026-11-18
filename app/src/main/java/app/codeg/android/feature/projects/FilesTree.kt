package app.codeg.android.feature.projects

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
import app.codeg.android.core.datastore.AppSettings
import app.codeg.android.core.datastore.AppSettingsStore
import app.codeg.android.core.datastore.CommonDirsStore
import app.codeg.android.core.datastore.FILE_SORT_MODE_NAME
import app.codeg.android.core.datastore.FILE_SORT_MODE_SIZE
import app.codeg.android.core.datastore.LastBrowseStore
import app.codeg.android.core.datastore.ServerCommonDir
import app.codeg.android.core.datastore.TreeExpandStore
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.component.InlineError
import app.codeg.android.core.designsystem.component.LoadingView
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.DirectoryItem
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.displayMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 批v180-C: drives the persistent expand/collapse file tree of the ephemeral
 * browse. Children load LAZILY (a folder fetches its listing the first time it
 * expands — no full-disk walk), the expanded set persists per (device, root)
 * through [TreeExpandStore], and the flattened node list renders in a LazyColumn.
 *
 * The DISPLAY root can move (breadcrumb taps re-root upward, the tree twin of
 * the old browser's ".." climb); writes stay anchored to the ORIGINAL browse
 * root ([writeRoot], 批v176 semantics). Expansion memory keys off the bind root
 * — re-rooted sessions are ephemeral by design.
 */
@HiltViewModel
class FilesTreeViewModel @Inject constructor(
    private val expandStore: TreeExpandStore,
    private val commonDirsStore: CommonDirsStore,
    private val lastBrowseStore: LastBrowseStore,
    private val appSettingsStore: AppSettingsStore,
) : ViewModel() {

    /** One rendered row: a visible node of the flattened tree. */
    data class TreeNode(
        val path: String,
        val name: String,
        val isDir: Boolean,
        val depth: Int,
        val expanded: Boolean,
        val loading: Boolean,
        val error: String?,
        /** 批v184: file size in bytes — dirs/null stay unlabeled. */
        val size: Long? = null,
    )

    data class TreeUi(
        val busy: Boolean = false,
        val rootError: String? = null,
        val nodes: List<TreeNode> = emptyList(),
        /** True when the current display root's listing came back empty. */
        val rootListingEmpty: Boolean = false,
        /** 批v180-D: multi-select mode + the selected paths + the filter query. */
        val selecting: Boolean = false,
        val selected: Set<String> = emptySet(),
        val query: String = "",
        /** 审查F8: the VM's current display root — the view's breadcrumb and
         *  create-sheet anchor must not drift back to the bind root when a
         *  filtered tree renders zero rows. */
        val root: String = "",
        /** 批v181 审查: the VM's current write anchor — the view's per-item
         *  write actions must anchor to the re-rooted tree, not the stale
         *  navigation rootPath the screen was first bound with. */
        val writeRoot: String = "",
        /** 批v181: this device's 常用目录 chips (quick re-root). */
        val commonDirs: List<ServerCommonDir> = emptyList(),
        /** 批v183-C: the active sort mode (name/size) — drives the menu checkmarks. */
        val sortMode: String = FILE_SORT_MODE_NAME,
    )

    private val _ui = MutableStateFlow(TreeUi())
    val ui: StateFlow<TreeUi> = _ui.asStateFlow()

    private var client: CodegClient? = null
    private var serverId = ""
    private var commonDirsJob: Job? = null

    /** 批v181 审查: the root-load coroutine — a newer bind supersedes an
     *  in-flight one (chips are rapid-fire) so its recovery never touches
     *  the newer session's state. */
    private var bindJob: Job? = null

    /** Where the tree is rooted right now (breadcrumb moves this). */
    private var root = ""

    /** The ORIGINAL browse root from bind — the write anchor and the expansion
     *  memory's context; the display root resets here on rebind. */
    private var writeRoot = ""
    private var bound = false

    /** Dir path → fetched children. Session-local; the persistent part is [expandStore]. */
    private val children = HashMap<String, List<DirectoryItem>>()
    private val expanded = MutableStateFlow<Set<String>>(emptySet())
    private val loading = MutableStateFlow<Set<String>>(emptySet())
    private val errors = MutableStateFlow<Map<String, String>>(emptyMap())
    private val loadJobs = HashMap<String, Job>()

    private val selecting = MutableStateFlow(false)
    private val selected = MutableStateFlow<Set<String>>(emptySet())
    private val query = MutableStateFlow("")

    /** 批v183-C: the sort mode — persisted pref, applied to every directory's
     *  children on rebuild. */
    private val sortMode = MutableStateFlow(AppSettings.DEFAULT_FILE_SORT_MODE)

    init {
        viewModelScope.launch {
            sortMode.value = appSettingsStore.settings.first().fileSortMode
            rebuild()
        }
    }

    /** 批v183-C: switch the sort mode; persists and re-sorts what's loaded. */
    fun setSortMode(mode: String) {
        if (mode == sortMode.value) return
        sortMode.value = mode
        rebuild()
        viewModelScope.launch { appSettingsStore.setFileSortMode(mode) }
    }

    /** Idempotent bind: same (client, root, device) → keep state (recomposition). */
    fun bind(client: CodegClient, rootPath: String, serverId: String) {
        val normalizedRoot = rootPath.trimEnd('/').ifEmpty { "/" }
        if (bound && writeRoot == normalizedRoot && this.serverId == serverId) return
        bindJob?.cancel()
        this.client = client
        this.serverId = serverId
        writeRoot = normalizedRoot
        root = normalizedRoot
        bound = true
        loadJobs.values.forEach { it.cancel() }
        loadJobs.clear()
        children.clear()
        expanded.value = emptySet()
        loading.value = emptySet()
        errors.value = emptyMap()
        selecting.value = false
        selected.value = emptySet()
        query.value = ""
        rememberBrowse(normalizedRoot)
        commonDirsJob?.cancel()
        commonDirsJob = viewModelScope.launch {
            commonDirsStore.dirsFor(serverId.takeIf { it.isNotBlank() }).collect { dirs ->
                _ui.update { it.copy(commonDirs = dirs) }
            }
        }
        _ui.value = TreeUi(busy = true, writeRoot = normalizedRoot, sortMode = sortMode.value)
        bindJob = viewModelScope.launch {
            // 批v181 审查: capture the bind identity — a superseded bind must
            // not write state into the newer session (its root/worktree differ).
            val boundRoot = root
            val boundWriteRoot = writeRoot
            val boundServer = serverId
            // 批v183 审查 P2: re-sync the sort pref on each bind — the Files-tab
            // browser shares this DataStore and may have changed it since init
            // read it (a stale checkmark would linger otherwise).
            runCatching { appSettingsStore.settings.first().fileSortMode }
                .getOrNull()?.let { sortMode.value = it }
            try {
                fetchChildren(boundRoot)
                // Restore saved expansions for THIS (device, root); the root row
                // itself starts expanded. Saved descendants materialize through
                // [materializeExpanded] — a saved dir whose parent chain is gone
                // (moved/deleted) simply doesn't render, no error surface.
                val prefix = "$boundServer|$boundWriteRoot|"
                val saved = expandStore.expanded.first()
                    .filter { it.startsWith(prefix) }
                    .map { it.removePrefix(prefix) }
                    .filter { it != boundRoot }
                if (writeRoot != boundWriteRoot) return@launch
                expanded.value = setOf(boundRoot) + saved
                materializeExpanded()
                _ui.update { it.copy(busy = false, rootError = null) }
                rebuild()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (writeRoot == boundWriteRoot) {
                    _ui.update { it.copy(busy = false, rootError = e.displayMessage()) }
                }
            }
        }
    }

    /** Root load failed (offline, gone…) — retry from scratch. */
    fun retryRoot() {
        val client = this.client ?: return
        bound = false
        bind(client, writeRoot, serverId)
    }

    /**
     * 批v181: jump the whole tree to an arbitrary root — the "change root"
     * action and the common-dir chips. Rebind semantics: the new root becomes
     * the write anchor and the expansion-memory context (a new workspace) and
     * session state resets. Re-rooting to the CURRENT bind root keeps the
     * session and only moves the display root instead.
     */
    fun rebindTo(path: String) {
        val c = client ?: return
        val normalized = path.trimEnd('/').ifEmpty { "/" }
        if (!normalized.startsWith("/")) return
        if (bound && writeRoot == normalized) {
            setRoot(normalized)
        } else {
            bind(c, normalized, serverId)
        }
    }

    /** 批v181: remember where the user browsed (per device) — "全部文件" reopens
     *  there instead of always landing on $HOME. Tracks the display root. */
    private fun rememberBrowse(path: String) {
        val id = serverId
        if (id.isNotBlank()) viewModelScope.launch { lastBrowseStore.setLast(id, path) }
    }

    fun toggle(path: String) {
        val nowExpanded = path !in expanded.value
        expanded.value = if (nowExpanded) expanded.value + path else expanded.value - path
        viewModelScope.launch { expandStore.setExpanded("$serverId|$writeRoot|$path", nowExpanded) }
        if (nowExpanded && path !in children) {
            load(path)
        } else {
            rebuild()
        }
    }

    fun retry(path: String) {
        load(path)
    }

    /**
     * Breadcrumb tap: re-root the DISPLAY tree at [path] — an ancestor of the
     * current root (the ".." climb) or the root itself (expand in place). The
     * write anchor stays [writeRoot]; only what's shown moves. Cached listings
     * survive the move (paths are absolute), so descending back is instant.
     */
    fun setRoot(path: String) {
        if (this.client == null) return
        if (!path.startsWith("/")) return
        if (path == root) {
            if (path !in children) {
                load(path)
            } else {
                rebuild()
            }
            return
        }
        viewModelScope.launch {
            root = path
            rememberBrowse(path)
            expanded.value = expanded.value + path
            if (path !in children) {
                try {
                    fetchChildren(path)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The re-root itself failed — surface on the root row; retry
                    // refetches it.
                    errors.value = errors.value + (path to e.displayMessage())
                }
            }
            rebuild()
        }
    }

    /** Load children for saved-expanded dirs whose ancestors are visible, so a
     *  restored deep expansion actually shows content. */
    private suspend fun materializeExpanded() {
        val targets = expanded.value.filter { it != root }.sortedBy { it.count { c -> c == '/' } }
        for (target in targets) {
            // The row renders only when every dir between root and target is
            // expanded — skip targets whose chain isn't (don't auto-expand beyond
            // what the user saved).
            val chain = mutableListOf<String>()
            var ok = true
            var cur = target
            while (cur.length > root.length && cur != "/") {
                // 审查F7: "/a".substringBeforeLast('/') is "" — map it back to "/"
                // or a "/"-rooted tree can never materialize its saved expansions.
                cur = cur.substringBeforeLast('/').ifEmpty { "/" }
                if (cur.length < root.length) {
                    ok = false
                    break
                }
                if (cur != root && cur !in expanded.value) {
                    ok = false
                    break
                }
                chain.add(cur)
                if (cur == root) break
            }
            if (!ok) continue
            for (dir in chain.reversed()) {
                if (dir !in children) {
                    try {
                        fetchChildren(dir)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        break
                    }
                }
            }
        }
        rebuild()
    }

    private fun load(path: String) {
        val client = this.client ?: return
        loadJobs.remove(path)?.cancel()
        val job = viewModelScope.launch {
            loading.value = loading.value + path
            rebuild()
            try {
                fetchChildren(path)
                errors.value = errors.value - path
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors.value = errors.value + (path to e.displayMessage())
            }
            loading.value = loading.value - path
            rebuild()
        }
        loadJobs[path] = job
    }

    /** 批v180-D: the single fetch funnel — fills the session cache AND the
     *  path→isDir map the batch delete consults. */
    private suspend fun fetchChildren(dir: String): List<DirectoryItem> {
        val client = this.client ?: error("FilesTreeViewModel not bound")
        val items = client.listDirectoryWithFiles(dir)
        children[dir] = items
        return items
    }

    // ---- 批v180-D: multi-select + batch ops + name filter ----

    /** Enter selection mode with an empty set (toolbar entry, next to the
     *  breadcrumb). The depth-0 root row is never selectable — it IS the write
     *  anchor, and deleting it has no meaning. */
    fun enterSelection() {
        selecting.value = true
        selected.value = emptySet()
        rebuild()
    }

    fun exitSelection() {
        selecting.value = false
        selected.value = emptySet()
        rebuild()
    }

    fun toggleSelect(path: String) {
        if (path == root) return
        selected.value = if (path in selected.value) selected.value - path else selected.value + path
        rebuild()
    }

    /** Select every currently rendered row (the depth-0 root row stays out).
     *  审查F2: with a filter active, ancestor-chain dirs render only to give
     *  matches their folder context — selecting them would batch-delete whole
     *  subtrees the filter never matched (server deletes recurse), so they stay
     *  out of the sweep. */
    fun selectAllVisible() {
        val q = query.value.trim()
        selected.value = selected.value + _ui.value.nodes.filter { node ->
            node.depth > 0 && (q.isEmpty() || node.name.contains(q, ignoreCase = true))
        }.map { it.path }
        rebuild()
    }

    /** Live name filter over the flattened tree; blank resets. */
    fun setQuery(value: String) {
        query.value = value
        rebuild()
    }

    /** 批v180-D: refetch the listings of [paths] already in the session cache
     *  (post-action freshness); uncached dirs refetch lazily on expand. */
    fun refreshDirs(paths: Collection<String>) {
        for (path in paths) if (path in children) load(path)
    }

    /**
     * 批v180-D: delete the selected rows one server call at a time (the tree
     * API has no bulk endpoint), each anchored to [writeRoot] — selections
     * outside the anchor count as failures. Affected parent listings refetch
     * inline, selection exits, then [onResult] reports (deleted, failed).
     */
    suspend fun batchDelete(client: CodegClient, onResult: (deleted: Int, failed: Int) -> Unit) {
        var deleted = 0
        var failed = 0
        val parents = mutableSetOf<String>()
        val swept = mutableListOf<String>()
        for (path in selected.value) {
            // 审查F3: a dir delete sweeps its whole subtree server-side — a
            // selected descendant of an already-deleted ancestor is GONE, not
            // failed (counting it failed would report "1 deleted, 12 failed"
            // for a clean sweep).
            if (swept.any { prefix -> path.startsWith(if (prefix.endsWith("/")) prefix else "$prefix/") }) {
                deleted++
                continue
            }
            val rel = relPathOrNull(writeRoot, path)
            if (rel.isNullOrEmpty()) {
                failed++
                continue
            }
            try {
                client.deleteFileTreeEntry(writeRoot, rel)
                deleted++
                swept.add(path)
                parents.add(path.substringBeforeLast('/').ifEmpty { "/" })
                expanded.value = expanded.value - path
                // 审查F12: the deleted dir's own cached listing must not survive —
                // a same-named recreate would briefly show ghost content.
                children.remove(path)
                errors.value = errors.value - path
                val touched = children.keys.filter { dir -> children[dir]!!.any { it.path == path } }
                for (dir in touched) children[dir] = children[dir]!!.filterNot { it.path == path }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
            }
        }
        exitSelection()
        for (parent in parents) {
            if (parent in children) {
                try {
                    fetchChildren(parent)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The inline removal above already hides the deleted rows;
                    // the next expand refetches the listing.
                }
            }
        }
        rebuild()
        onResult(deleted, failed)
    }

    /** 批v180-D: keep rows whose NAME matches the query (case-insensitive) plus
     *  their ancestor chain, so matches still read inside their folder context.
     *  In DFS order the parent is the nearest earlier row one level shallower. */
    private fun filterByQuery(nodes: List<TreeNode>, raw: String): List<TreeNode> {
        val q = raw.trim()
        if (q.isEmpty()) return nodes
        val keep = BooleanArray(nodes.size)
        for (i in nodes.indices) {
            if (!nodes[i].name.contains(q, ignoreCase = true)) continue
            keep[i] = true
            var depth = nodes[i].depth
            var j = i - 1
            while (depth > 0 && j >= 0) {
                if (nodes[j].depth == depth - 1) {
                    keep[j] = true
                    depth--
                }
                j--
            }
        }
        return nodes.filterIndexed { index, _ -> keep[index] }
    }

    /** Flatten the visible tree (root row first, expanded dirs depth-first),
     *  then apply the name filter when set. */
    private fun rebuild() {
        val nodes = mutableListOf<TreeNode>()
        val exp = expanded.value
        val load = loading.value
        val errs = errors.value

        fun push(path: String, name: String, isDir: Boolean, depth: Int, size: Long? = null) {
            nodes += TreeNode(
                path = path,
                name = name,
                isDir = isDir,
                depth = depth,
                expanded = path in exp,
                loading = path in load,
                error = errs[path],
                size = size,
            )
        }

        fun walk(dir: String, depth: Int) {
            val kids = children[dir] ?: return
            for (item in sortItems(kids, sortMode.value)) {
                push(item.path, item.name, item.isDir, depth, item.size)
                if (item.isDir && item.path in exp) walk(item.path, depth + 1)
            }
        }

        push(root, root.substringAfterLast('/').ifEmpty { root }, isDir = true, depth = 0)
        if (root in exp) walk(root, 1)
        val visible = filterByQuery(nodes, query.value)
        _ui.update {
            it.copy(
                nodes = visible,
                root = root,
                rootListingEmpty = children[root]?.isEmpty() == true,
                selecting = selecting.value,
                selected = selected.value,
                query = query.value,
                sortMode = sortMode.value,
            )
        }
    }
}

/**
 * 批v180-C/D: the expand/collapse file tree for ephemeral full-disk browsing.
 * Breadcrumb at the top (segments re-root the displayed tree — the ancestor
 * climb), flattened LazyColumn below with per-depth indentation, lazy child
 * loading, and the shared [FileSurfaceHost] for preview/edit. 批v180-D adds the
 * full management surface: long-press row actions ([FileActionSheet]), a create
 * entry, a live name filter, and multi-select batch delete. Registered projects
 * keep their drill-down Files tab — this surface is the overview's "all files"
 * entry.
 */
@Composable
fun FilesTreeView(
    client: CodegClient,
    rootPath: String,
    serverId: String?,
    modifier: Modifier = Modifier,
    viewModel: FilesTreeViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(client, rootPath, serverId) {
        viewModel.bind(client, rootPath, serverId.orEmpty())
    }

    val colors = CodegTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val outsideWorkspaceMsg = stringResource(R.string.files_outside_workspace)
    val tooLargeMsg = stringResource(R.string.files_too_large_edit)
    val genericErrorMsg = stringResource(R.string.files_error)

    // Full-screen file surface (preview/edit) replaces the tree, like the old
    // drill-down browser did. Editor/seed flags turn the host straight into the
    // editor (edit action, create-recovery); cleared together on dismiss.
    var fileSurface by rememberSaveable { mutableStateOf<String?>(null) }
    var surfaceEditor by rememberSaveable { mutableStateOf(false) }
    var surfaceSeed by rememberSaveable { mutableStateOf<String?>(null) }
    val surface = fileSurface
    if (surface != null) {
        FileSurfaceHost(
            client = client,
            rootPath = rootPath,
            path = surface,
            startInEditor = surfaceEditor,
            initialText = surfaceSeed,
            onDismiss = {
                fileSurface = null
                surfaceEditor = false
                surfaceSeed = null
            },
        )
        // 审查F5: one-shot — consumed on the very first frame like the
        // drill-down side's pastedRecovery, so discarding an edit and re-entering
        // the editor reads the file fresh instead of resurrecting abandoned text.
        surfaceSeed = null
        return
    }

    val state = ui

    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<DirectoryItem?>(null) }
    var showNewSheet by remember { mutableStateOf(false) }
    var showBatchConfirm by remember { mutableStateOf(false) }
    // 批v181: the change-root browser — rebinds the whole tree to the pick.
    var showRootBrowser by rememberSaveable { mutableStateOf(false) }
    // Breadcrumb segments re-root the tree; the create sheet anchors on the
    // CURRENT display root (createEntryWithContent re-checks the write anchor).
    // 审查F8: read it from the VM state — nodes.first() vanishes when a filter
    // matches nothing, which would silently re-anchor to the bind root.
    val displayRoot = state.root.ifEmpty { rootPath }

    Column(modifier.fillMaxSize()) {
        if (state.selecting) {
            SelectionBar(
                count = state.selected.size,
                onExit = viewModel::exitSelection,
                onSelectAll = viewModel::selectAllVisible,
                onDelete = { showBatchConfirm = true },
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BreadcrumbBar(
                    currentRoot = displayRoot,
                    onSegment = viewModel::setRoot,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = stringResource(R.string.files_tree_new),
                            tint = colors.textSecondary,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.files_tree_new)) },
                            onClick = {
                                menuOpen = false
                                showNewSheet = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.files_tree_change_root)) },
                            leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) },
                            onClick = {
                                menuOpen = false
                                showRootBrowser = true
                            },
                        )
                        // 批v183-C: sort mode as a radio pair inside the overflow —
                        // the toolbar row already carries three icon buttons and the
                        // "界面乱" feedback says keep it that way.
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.files_sort_name)) },
                            trailingIcon = if (state.sortMode == FILE_SORT_MODE_NAME) {
                                { Icon(Icons.Rounded.Check, null, tint = colors.accent) }
                            } else null,
                            onClick = {
                                menuOpen = false
                                viewModel.setSortMode(FILE_SORT_MODE_NAME)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.files_sort_size)) },
                            trailingIcon = if (state.sortMode == FILE_SORT_MODE_SIZE) {
                                { Icon(Icons.Rounded.Check, null, tint = colors.accent) }
                            } else null,
                            onClick = {
                                menuOpen = false
                                viewModel.setSortMode(FILE_SORT_MODE_SIZE)
                            },
                        )
                    }
                }
                IconButton(onClick = { searchOpen = !searchOpen }) {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = stringResource(R.string.files_tree_search),
                        tint = if (state.query.isNotBlank()) colors.accent else colors.textSecondary,
                    )
                }
                IconButton(onClick = viewModel::enterSelection) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = stringResource(R.string.files_tree_select),
                        tint = colors.textSecondary,
                    )
                }
            }
            // 批v181: 常用目录 chips — one tap re-roots the whole tree (CommonDirsStore
            // per-device bindings; falls back to /mnt, /mnt/Projects via the store).
            if (!state.selecting && state.commonDirs.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.commonDirs.forEach { dir ->
                        // 批v181 审查: compare normalized (rebindTo normalizes too,
                        // so a trailing-slash stored path would never light up).
                        val active =
                            displayRoot.trimEnd('/').ifEmpty { "/" } == dir.path.trimEnd('/').ifEmpty { "/" }
                        Text(
                            dir.label,
                            fontSize = 11.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) colors.onAccent else colors.textSecondary,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) colors.accent else colors.textPrimary.copy(alpha = 0.06f))
                                .clickable { viewModel.rebindTo(dir.path) }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
            if (searchOpen) {
                TextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    singleLine = true,
                    placeholder = {
                        Text(stringResource(R.string.files_tree_search_hint), fontSize = 13.sp)
                    },
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
        }
        when {
            state.busy ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingView(message = stringResource(R.string.common_loading))
                }
            state.rootError != null ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    // 批v182: retry only repeats the same root — if it was deleted
                    // server-side the user needs the root browser as an escape
                    // hatch (its Home// rows climb out of any dead path).
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        InlineError(
                            Icons.Rounded.FolderOpen,
                            stringResource(R.string.files_load_failed),
                            state.rootError,
                            onRetry = viewModel::retryRoot,
                        )
                        TextButton(onClick = { showRootBrowser = true }) {
                            Text(stringResource(R.string.files_tree_change_root), fontSize = 13.sp)
                        }
                    }
                }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    if (state.query.isBlank() && state.nodes.size == 1 && state.rootListingEmpty) {
                        item("empty") {
                            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                                EmptyState(
                                    Icons.Rounded.Folder,
                                    stringResource(R.string.files_empty_folder),
                                    stringResource(R.string.files_empty_folder_message),
                                )
                            }
                        }
                    }
                    if (state.query.isNotBlank() && state.nodes.isEmpty()) {
                        item("no-results") {
                            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                                EmptyState(
                                    Icons.Rounded.Search,
                                    stringResource(R.string.files_tree_no_results),
                                    stringResource(R.string.files_tree_no_results_message),
                                )
                            }
                        }
                    }
                    items(state.nodes, key = { it.path }) { node ->
                        TreeRow(
                            node = node,
                            selecting = state.selecting,
                            checked = node.path in state.selected,
                            onOpen = {
                                when {
                                    state.selecting -> viewModel.toggleSelect(node.path)
                                    node.isDir -> viewModel.toggle(node.path)
                                    else -> {
                                        fileSurface = node.path
                                        surfaceEditor = false
                                    }
                                }
                            },
                            onLongPress = {
                                if (!state.selecting && node.depth > 0) {
                                    actionTarget = DirectoryItem(node.name, node.path, node.isDir)
                                }
                            },
                            onRetry = { viewModel.retry(node.path) },
                        )
                    }
                }
            }
        }
    }

    actionTarget?.let { target ->
        FileActionSheet(
            client = client,
            // 批v181 审查: anchor writes to the re-rooted tree — the navigation
            // rootPath is stale after 换根/chips (per-item ops would otherwise
            // all reject with "outside workspace").
            rootPath = state.writeRoot.ifEmpty { rootPath },
            target = target,
            outsideWorkspaceMsg = outsideWorkspaceMsg,
            onDismiss = { actionTarget = null },
            onOpen = {
                actionTarget = null
                if (target.isDir) {
                    viewModel.toggle(target.path)
                } else {
                    fileSurface = target.path
                    surfaceEditor = false
                }
            },
            onEdit = {
                actionTarget = null
                fileSurface = target.path
                surfaceEditor = true
            },
            onDone = {
                actionTarget = null
                viewModel.refreshDirs(listOf(target.path.substringBeforeLast('/').ifEmpty { "/" }))
            },
        )
    }
    if (showNewSheet) {
        NewEntrySheet(
            parentPath = displayRoot,
            onCreate = { name, isDir, content ->
                showNewSheet = false
                scope.launch {
                    try {
                        createEntryWithContent(
                            client, state.writeRoot.ifEmpty { rootPath }, displayRoot, name, isDir, content,
                            outsideWorkspaceMsg = outsideWorkspaceMsg,
                            tooLargeMsg = tooLargeMsg,
                            genericErrorMsg = genericErrorMsg,
                            onError = { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() },
                            onRecoverInEditor = { absPath, seed ->
                                fileSurface = absPath
                                surfaceEditor = true
                                surfaceSeed = seed
                            },
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 审查F1: createFileTreeEntry itself sits OUTSIDE the helper's
                        // try — offline/duplicate/permission errors must toast, not
                        // crash the process (the drill-down side is wrapped by
                        // runFileAction; this side needs its own net).
                        Toast.makeText(
                            context,
                            e.message ?: genericErrorMsg,
                            Toast.LENGTH_SHORT,
                        ).show()
                    } finally {
                        viewModel.refreshDirs(listOf(displayRoot))
                    }
                }
            },
            onDismiss = { showNewSheet = false },
        )
    }
    if (showBatchConfirm && state.selected.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showBatchConfirm = false },
            title = { Text(stringResource(R.string.files_tree_batch_delete_title, state.selected.size)) },
            text = {
                Column {
                    state.selected.map { it.substringAfterLast('/') }.sorted().take(3).forEach { name ->
                        Text("· " + name, fontSize = 13.sp)
                    }
                    if (state.selected.size > 3) {
                        Text(
                            stringResource(R.string.files_tree_batch_delete_more, state.selected.size - 3),
                            fontSize = 12.sp,
                            color = colors.textSecondary,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showBatchConfirm = false
                    scope.launch {
                        viewModel.batchDelete(client) { deleted, failed ->
                            Toast.makeText(
                                context,
                                context.getString(R.string.files_tree_batch_delete_result, deleted, failed),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }) {
                    Text(stringResource(R.string.files_action_delete), color = colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
    if (showRootBrowser) {
        // 批v181: pick any start directory (same browser the ⋯ menu uses) and
        // rebind — the pick becomes the new write anchor / expansion context.
        DirectoryBrowserSheet(
            title = stringResource(R.string.files_tree_change_root),
            confirmLabel = stringResource(R.string.files_tree_change_root_confirm),
            loadHome = { client.getHomeDirectory() },
            loadDirs = { path -> client.listDirectoryEntries(path) },
            onSelect = { path ->
                showRootBrowser = false
                viewModel.rebindTo(path)
            },
            onDismiss = { showRootBrowser = false },
            showRoot = true,
            initialPath = displayRoot,
            // 批v185: the tree's own 常用目录 ride the root browser — quick jump
            // without typing a path (the address bar stays for anything else).
            commonDirs = state.commonDirs,
        )
    }
}

/**
 * Horizontal breadcrumb of the current display root. Every segment re-roots the
 * tree on tap (the ancestor climb); the last segment is the root itself and
 * reads as the anchor.
 */
@Composable
private fun BreadcrumbBar(currentRoot: String, onSegment: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = CodegTheme.colors
    val segments = currentRoot.split('/').filter { it.isNotBlank() }
    Row(
        modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // 批v181: the filesystem root is always one tap away — previously a
        // /root-started tree had no segment above it and nowhere to climb.
        Text(
            "/",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (segments.isEmpty()) FontWeight.SemiBold else FontWeight.Normal,
            color = if (segments.isEmpty()) colors.accent else colors.textSecondary,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { onSegment("/") }
                .padding(horizontal = 2.dp, vertical = 2.dp),
        )
        var acc = ""
        segments.forEachIndexed { index, segment ->
            acc = "$acc/$segment"
            val isRoot = index == segments.lastIndex
            Text(
                "/$segment",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isRoot) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isRoot) colors.accent else colors.textSecondary,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onSegment(acc) }
                    .padding(horizontal = 2.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * One tree row: per-depth indentation, a chevron for folders, a loading spinner
 * while children fetch, and an inline retry on a failed listing. 批v180-D: in
 * selection mode the leading icon flips to the row's check state, and a
 * long-press anywhere opens the row actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeRow(
    node: FilesTreeViewModel.TreeNode,
    selecting: Boolean,
    checked: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = CodegTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(start = 6.dp + 16.dp * node.depth, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            selecting -> Icon(
                if (checked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (checked) colors.accent else colors.textTertiary,
                modifier = Modifier.size(18.dp),
            )
            node.isDir -> {
                Icon(
                    if (node.expanded) Icons.Rounded.ExpandMore else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(18.dp),
                )
                Icon(
                    if (node.expanded) Icons.Rounded.FolderOpen else Icons.Rounded.Folder,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(18.dp),
                )
            }
            else -> Icon(
                Icons.Rounded.InsertDriveFile,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            node.name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (node.isDir) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // 批v184: file size on the row's tail — pairs with the size sort so big
        // files are findable at a glance; dirs and unknown sizes stay unlabeled.
        node.size?.takeIf { !node.isDir }?.let { bytes ->
            Text(
                formatBytes(bytes),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.textTertiary,
                maxLines = 1,
            )
        }
        when {
            node.loading -> CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            node.error != null -> Text(
                stringResource(R.string.devices_retry),
                fontSize = 11.sp,
                color = colors.danger,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/** 批v180-D: the multi-select toolbar — exit, select-all, count, delete. */
@Composable
private fun SelectionBar(count: Int, onExit: () -> Unit, onSelectAll: () -> Unit, onDelete: () -> Unit) {
    val colors = CodegTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onExit) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = stringResource(R.string.common_cancel),
                tint = colors.textSecondary,
            )
        }
        Text(
            stringResource(R.string.files_tree_selected_count, count),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSelectAll) {
            Text(stringResource(R.string.files_tree_select_all), fontSize = 13.sp)
        }
        IconButton(onClick = onDelete, enabled = count > 0) {
            Icon(
                Icons.Rounded.Delete,
                contentDescription = stringResource(R.string.files_action_delete),
                tint = if (count > 0) colors.danger else colors.textTertiary,
            )
        }
    }
}
