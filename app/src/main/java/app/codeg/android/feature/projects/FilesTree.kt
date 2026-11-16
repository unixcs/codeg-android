package app.codeg.android.feature.projects

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
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    )

    data class TreeUi(
        val busy: Boolean = false,
        val rootError: String? = null,
        val nodes: List<TreeNode> = emptyList(),
        /** True when the current display root's listing came back empty. */
        val rootListingEmpty: Boolean = false,
    )

    private val _ui = MutableStateFlow(TreeUi())
    val ui: StateFlow<TreeUi> = _ui.asStateFlow()

    private var client: CodegClient? = null
    private var serverId = ""

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

    /** Idempotent bind: same (client, root, device) → keep state (recomposition). */
    fun bind(client: CodegClient, rootPath: String, serverId: String) {
        val normalizedRoot = rootPath.trimEnd('/').ifEmpty { "/" }
        if (bound && writeRoot == normalizedRoot && this.serverId == serverId) return
        this.client = client
        this.serverId = serverId
        writeRoot = normalizedRoot
        root = normalizedRoot
        bound = true
        children.clear()
        expanded.value = emptySet()
        loading.value = emptySet()
        errors.value = emptyMap()
        _ui.value = TreeUi(busy = true)
        viewModelScope.launch {
            try {
                children[root] = client.listDirectoryWithFiles(root)
                // Restore saved expansions for THIS (device, root); the root row
                // itself starts expanded. Saved descendants materialize through
                // [materializeExpanded] — a saved dir whose parent chain is gone
                // (moved/deleted) simply doesn't render, no error surface.
                val prefix = "$serverId|$writeRoot|"
                val saved = expandStore.expanded.first()
                    .filter { it.startsWith(prefix) }
                    .map { it.removePrefix(prefix) }
                    .filter { it != root }
                expanded.value = setOf(root) + saved
                materializeExpanded()
                _ui.update { it.copy(busy = false, rootError = null) }
                rebuild()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, rootError = e.displayMessage()) }
            }
        }
    }

    /** Root load failed (offline, gone…) — retry from scratch. */
    fun retryRoot() {
        val client = this.client ?: return
        bound = false
        bind(client, writeRoot, serverId)
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
        val client = this.client ?: return
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
            expanded.value = expanded.value + path
            if (path !in children) {
                try {
                    children[path] = client.listDirectoryWithFiles(path)
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
                cur = cur.substringBeforeLast('/')
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
                        children[dir] = client?.listDirectoryWithFiles(dir) ?: break
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
                children[path] = client.listDirectoryWithFiles(path)
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

    /** Flatten the visible tree: root row first, then expanded dirs depth-first. */
    private fun rebuild() {
        val nodes = mutableListOf<TreeNode>()
        val exp = expanded.value
        val load = loading.value
        val errs = errors.value

        fun push(path: String, name: String, isDir: Boolean, depth: Int) {
            nodes += TreeNode(
                path = path,
                name = name,
                isDir = isDir,
                depth = depth,
                expanded = path in exp,
                loading = path in load,
                error = errs[path],
            )
        }

        fun walk(dir: String, depth: Int) {
            val kids = children[dir] ?: return
            for (item in sortItems(kids)) {
                push(item.path, item.name, item.isDir, depth)
                if (item.isDir && item.path in exp) walk(item.path, depth + 1)
            }
        }

        push(root, root.substringAfterLast('/').ifEmpty { root }, isDir = true, depth = 0)
        if (root in exp) walk(root, 1)
        _ui.update { it.copy(nodes = nodes, rootListingEmpty = children[root]?.isEmpty() == true) }
    }
}

/**
 * 批v180-C: the expand/collapse file tree for ephemeral full-disk browsing.
 * Breadcrumb at the top (segments re-root the displayed tree — the ancestor
 * climb), flattened LazyColumn below with per-depth indentation, lazy child
 * loading, and the shared [FileSurfaceHost] for preview/edit. Registered
 * projects keep their drill-down Files tab — this surface is the overview's
 * "all files" entry.
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

    // Full-screen file surface (preview/edit) replaces the tree, like the old
    // drill-down browser did.
    var fileSurface by rememberSaveable { mutableStateOf<String?>(null) }
    val surface = fileSurface
    if (surface != null) {
        FileSurfaceHost(
            client = client,
            rootPath = rootPath,
            path = surface,
            onDismiss = { fileSurface = null },
        )
        return
    }

    val colors = CodegTheme.colors
    val state = ui

    Column(modifier.fillMaxSize()) {
        BreadcrumbBar(
            currentRoot = state.nodes.firstOrNull()?.path ?: rootPath,
            onSegment = viewModel::setRoot,
        )
        when {
            state.busy ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingView(message = stringResource(R.string.common_loading))
                }
            state.rootError != null ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    InlineError(
                        Icons.Rounded.FolderOpen,
                        stringResource(R.string.files_load_failed),
                        state.rootError,
                        onRetry = viewModel::retryRoot,
                    )
                }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    if (state.nodes.size == 1 && state.rootListingEmpty) {
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
                    items(state.nodes, key = { it.path }) { node ->
                        TreeRow(
                            node = node,
                            onOpen = {
                                if (node.isDir) viewModel.toggle(node.path) else fileSurface = node.path
                            },
                            onRetry = { viewModel.retry(node.path) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Horizontal breadcrumb of the current display root. Every segment re-roots the
 * tree on tap (the ancestor climb); the last segment is the root itself and
 * reads as the anchor.
 */
@Composable
private fun BreadcrumbBar(currentRoot: String, onSegment: (String) -> Unit) {
    val colors = CodegTheme.colors
    val segments = currentRoot.split('/').filter { it.isNotBlank() }
    if (segments.isEmpty()) return
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
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
 * while children fetch, and an inline retry on a failed listing.
 */
@Composable
private fun TreeRow(
    node: FilesTreeViewModel.TreeNode,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = CodegTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onOpen)
            .padding(start = 6.dp + node.depth * 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (node.isDir) {
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
        } else {
            Icon(
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
