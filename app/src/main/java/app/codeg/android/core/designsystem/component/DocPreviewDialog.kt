package app.codeg.android.core.designsystem.component

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Toc
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.codeg.android.R
import app.codeg.android.core.designsystem.markdown.OutlineAwareMarkdownBody
import app.codeg.android.core.designsystem.markdown.TappableMarkdownText
import app.codeg.android.core.designsystem.markdown.openUrlExternally
import app.codeg.android.core.designsystem.markdown.outlineEntries
import app.codeg.android.core.designsystem.markdown.parseMarkdownBlocks
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.feature.sessiondetail.DocPreviewKind
import app.codeg.android.feature.sessiondetail.DocPreviewState

/** Persisted share formats (AppSettingsStore.docShareMode). */
internal const val DOC_SHARE_MODE_TEXT = "text"
internal const val DOC_SHARE_MODE_FILE = "file"

/**
 * Full-screen document preview (batch v123): renders a Markdown/HTML/text file
 * opened from a tapped path in the transcript. Header: close · title · share.
 * Body text sits in ONE [SelectionContainer] (no nesting — the review's Telegram
 * model): every character is selectable and copyable here, while the compact
 * timeline keeps its gesture set.
 *
 * The HTML body reuses FolderFilesView's hardened WebView defaults (JS off, no
 * file/content access, network loads blocked) — copied here rather than shared,
 * because that composable is private to the folder screen and promoting it would
 * widen this batch's blast radius. With `loadDataWithBaseURL(null)`, relative
 * resources cannot resolve (nothing is served) — acceptable: the preview targets
 * self-contained AI-written HTML, and deployed pages open in the browser via URL
 * taps instead.
 *
 * 批v195 拍板 1A/2B/3A: the share action is dual-mode — short press shares with
 * the last-used format (settings-persisted; first use falls back to a smart
 * default), long press picks 文本/文件; the body's selection menu is our own
 * toolbar with 分享所选 (reflection-extracted, auto-degrades to the whole doc);
 * the outline moved to the shared MindFS-dims [OutlineAwareMarkdownBody].
 *
 * 批v194 M4: the MindFS 大纲 port — Markdown previews gain a TOC action that
 * pops a compact heading card; tapping a heading scrolls the (block-anchored)
 * body to it. One parse source ([parseMarkdownBlocks]) feeds BOTH the body and
 * the outline, so the outline is fence-safe by construction and can never list
 * a heading the body doesn't render.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocPreviewDialog(
    state: DocPreviewState,
    onDismiss: () -> Unit,
    shareMode: String? = null,
    onShareModePicked: (String) -> Unit = {},
) {
    val colors = CodegTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Parse only for Markdown previews — HTML/text bodies skip the wasted pass.
    val blocks = remember(state.content) {
        if (state.kind == DocPreviewKind.MARKDOWN) parseMarkdownBlocks(state.content) else emptyList()
    }
    val hasOutline = remember(blocks) { outlineEntries(blocks).isNotEmpty() }
    var outlineOpen by remember { mutableStateOf(false) }
    var shareMenuOpen by remember { mutableStateOf(false) }
    val toolbarState = remember { DocSelectionToolbarState() }
    val toolbar = remember { DocTextToolbar(toolbarState) }
    val clipboard = LocalClipboardManager.current
    val scrollState = rememberScrollState()
    // A menu pinned to a stale rect is worse than no menu — hide on scroll.
    LaunchedEffect(scrollState.isScrollInProgress) {
        if (scrollState.isScrollInProgress) toolbarState.hide()
    }
    // Only one of the two top-end popups open at a time.
    LaunchedEffect(outlineOpen) {
        if (outlineOpen) {
            shareMenuOpen = false
            toolbarState.hide()
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Scaffold(
            containerColor = colors.bg,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            state.title,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = colors.textPrimary,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_close), tint = colors.textSecondary)
                        }
                    },
                    actions = {
                        if (state.kind == DocPreviewKind.MARKDOWN && hasOutline) {
                            IconButton(onClick = {
                                outlineOpen = !outlineOpen
                                if (outlineOpen) shareMenuOpen = false
                            }) {
                                Icon(Icons.Rounded.Toc, contentDescription = stringResource(R.string.doc_preview_outline), tint = colors.textSecondary)
                            }
                        }
                        ShareIconButton(
                            fileName = state.title,
                            content = state.content,
                            savedMode = shareMode,
                            onModePicked = onShareModePicked,
                            menuOpen = shareMenuOpen,
                            onMenuToggle = {
                                shareMenuOpen = !shareMenuOpen
                                if (shareMenuOpen) {
                                    outlineOpen = false
                                    toolbarState.hide()
                                }
                            },
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.bg),
                )
            },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                    SelectionContainer(Modifier.fillMaxSize()) {
                        when (state.kind) {
                            DocPreviewKind.MARKDOWN -> OutlineAwareMarkdownBody(
                                content = state.content,
                                outlineOpen = outlineOpen,
                                onDismissOutline = { outlineOpen = false },
                                scrollState = scrollState,
                            )

                            DocPreviewKind.HTML -> DocHtmlPreview(state.content)

                            else -> Column(
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(scrollState)
                                    .padding(14.dp),
                            ) {
                                TappableMarkdownText(
                                    raw = state.content,
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        lineHeight = 18.sp,
                                        color = colors.textPrimary,
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
                DocSelectionToolbarHost(
                    state = toolbarState,
                    onShareSelection = {
                        // 拍板 2B: the selection types are Kotlin-internal in
                        // foundation 1.7, so the public door is the toolbar's
                        // copy callback — invoke it, read the clipboard back,
                        // and degrade to the whole doc when it didn't change.
                        val copy = toolbarState.menu?.onCopy
                        scope.launch {
                            val before = clipboard.getText()?.text
                            copy?.invoke()
                            val after = clipboard.getText()?.text
                            val picked = after?.takeIf { it.isNotBlank() && after != before }
                            shareDocument(context, state.title, picked ?: state.content, asFile = false)
                        }
                    },
                    onShareAll = {
                        scope.launch { shareDocument(context, state.title, state.content, asFile = false) }
                    },
                )
                if (shareMenuOpen) {
                    val density = LocalDensity.current
                    Popup(
                        alignment = Alignment.TopEnd,
                        offset = IntOffset(0, with(density) { 56.dp.roundToPx() }),
                        onDismissRequest = { shareMenuOpen = false },
                        properties = PopupProperties(focusable = true),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = colors.bgElevated,
                            border = BorderStroke(1.dp, colors.surfaceStroke),
                            shadowElevation = 8.dp,
                        ) {
                            Column(Modifier.widthIn(min = 160.dp)) {
                                Text(
                                    stringResource(R.string.doc_preview_share_mode_title),
                                    fontSize = 11.sp,
                                    color = colors.textTertiary,
                                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 2.dp),
                                )
                                ShareModeRow(stringResource(R.string.doc_preview_share_as_text)) {
                                    shareMenuOpen = false
                                    onShareModePicked(DOC_SHARE_MODE_TEXT)
                                    scope.launch { shareDocument(context, state.title, state.content, asFile = false) }
                                }
                                ShareModeRow(stringResource(R.string.doc_preview_share_as_file)) {
                                    shareMenuOpen = false
                                    onShareModePicked(DOC_SHARE_MODE_FILE)
                                    scope.launch { shareDocument(context, state.title, state.content, asFile = true) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The header share action (批v195 拍板 1A). Short press shares with the last
 * used format; long press opens the 文本/文件 picker which also records the
 * choice via [onModePicked]. First use falls back to a smart default: a long
 * doc reads as a document → file, a short note → inline text.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ShareIconButton(
    fileName: String,
    content: String,
    savedMode: String?,
    onModePicked: (String) -> Unit,
    menuOpen: Boolean,
    onMenuToggle: () -> Unit,
) {
    val context = LocalContext.current
    val colors = CodegTheme.colors
    val scope = rememberCoroutineScope()
    val effectiveMode = when (savedMode) {
        DOC_SHARE_MODE_TEXT, DOC_SHARE_MODE_FILE -> savedMode
        else -> if (content.length > SHARE_TEXT_LIMIT / 3) DOC_SHARE_MODE_FILE else DOC_SHARE_MODE_TEXT
    }
    // IconButton can't long-press — a plain Box with the same 48dp touch target.
    Box(
        Modifier
            .size(48.dp)
            .combinedClickable(
                onClick = {
                    scope.launch {
                        shareDocument(context, fileName, content, asFile = effectiveMode == DOC_SHARE_MODE_FILE)
                    }
                },
                onLongClick = onMenuToggle,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Share,
            contentDescription = stringResource(R.string.doc_preview_share),
            tint = if (menuOpen) colors.accent else colors.textSecondary,
        )
    }
}

@Composable
private fun ShareModeRow(label: String, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    Text(
        label,
        fontSize = 13.sp,
        color = colors.textPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/**
 * HTML in the same locked-down WebView FolderFilesView uses (defaults copied, see
 * class kdoc), with one deliberate addition: http(s) navigations open in the
 * browser instead of being swallowed — a report that links out still works.
 */
@Composable
private fun DocHtmlPreview(html: String) {
    val context = LocalContext.current
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            android.webkit.WebView(ctx).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                settings.mediaPlaybackRequiresUserGesture = true
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: android.webkit.WebView,
                        request: android.webkit.WebResourceRequest,
                    ): Boolean {
                        val url = request.url.toString()
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            runCatching { openUrlExternally(context, url) }
                        }
                        return true
                    }
                }
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

private const val SHARE_TEXT_LIMIT = 100_000

/**
 * Share in one of two shapes (批v195 拍板 1A): text goes as EXTRA_TEXT (the
 * receiving chat app shows it inline — Deepseek/豆包 read it directly);
 * everything else goes through FileProvider as a FULL-content stream — the
 * old `take(SHARE_TEXT_LIMIT)` silently truncated every file share past
 * 100KB. Text mode still falls back to a file past the binder-safe limit (a
 * huge EXTRA_TEXT fails inside the picker, not gracefully). The authority MUST
 * be `${applicationId}.fileprovider` — the fork's per-variant applicationId
 * suffixes make a literal authority collide on dual installs.
 */
internal suspend fun shareDocument(context: Context, fileName: String, content: String, asFile: Boolean = false) {
    val send: Intent = if (!asFile && content.length <= SHARE_TEXT_LIMIT) {
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, fileName)
            .putExtra(Intent.EXTRA_TITLE, fileName)
            .putExtra(Intent.EXTRA_TEXT, content)
    } else {
        val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
        val file = java.io.File(dir, fileName.substringAfterLast('/').ifEmpty { "document.txt" })
        // Caller is already off Main (shareDocument runs in a scope on Default).
        withContext(Dispatchers.IO) { runCatching { file.writeText(content) } }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file,
        )
        Intent(Intent.ACTION_SEND)
            .setType(mimeFor(fileName))
            .putExtra(Intent.EXTRA_TITLE, fileName)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, null).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }
}

private fun mimeFor(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "md", "markdown" -> "text/markdown"
    "html", "htm" -> "text/html"
    "json" -> "application/json"
    else -> "text/plain"
}
