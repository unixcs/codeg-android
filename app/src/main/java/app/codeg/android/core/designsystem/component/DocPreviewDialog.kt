package app.codeg.android.core.designsystem.component

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Toc
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
 * 批v197: the header share action opens a 文本/文件 format picker before the
 * system chooser; the last-used format gets a check mark and is persisted by
 * the caller. The body's selection menu remains our own toolbar with 分享所选
 * (reflection-extracted, auto-degrades to the whole doc); the outline moved to
 * the shared MindFS-dims [OutlineAwareMarkdownBody].
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
    val toolbarState = remember { DocSelectionToolbarState() }
    val toolbar = remember { DocTextToolbar(toolbarState) }
    val clipboard = LocalClipboardManager.current
    val scrollState = rememberScrollState()
    val shareFailedMsg = stringResource(R.string.doc_preview_share_failed)
    val shareTooLongMsg = stringResource(R.string.doc_preview_share_text_too_long)
    // A menu pinned to a stale rect is worse than no menu — hide on scroll.
    LaunchedEffect(scrollState.isScrollInProgress) {
        if (scrollState.isScrollInProgress) toolbarState.hide()
    }
    LaunchedEffect(outlineOpen) {
        if (outlineOpen) {
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
                            }) {
                                Icon(Icons.Rounded.Toc, contentDescription = stringResource(R.string.doc_preview_outline), tint = colors.textSecondary)
                            }
                        }
                        DocShareButton(
                            fileName = state.title,
                            content = state.content,
                            savedMode = shareMode,
                            onModePicked = onShareModePicked,
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
                            val outcome = shareDocument(context, state.title, picked ?: state.content, asFile = false)
                            showShareOutcome(context, outcome, shareFailedMsg, shareTooLongMsg)
                        }
                    },
                    onShareAll = {
                        scope.launch {
                            val outcome = shareDocument(context, state.title, state.content, asFile = false)
                            showShareOutcome(context, outcome, shareFailedMsg, shareTooLongMsg)
                        }
                    },
                )
            }
        }
    }
}

/**
 * Header share action shared by the session artifact preview and the file
 * browser preview. A tap first opens the format picker — "text" or "file" —
 * because the old direct-share path made the receiving app's behavior a
 * surprise. The last picked format gets a check mark; the actual system
 * chooser is launched only after the user chooses.
 */
@Composable
internal fun DocShareButton(
    fileName: String,
    content: String,
    savedMode: String?,
    onModePicked: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = CodegTheme.colors
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    val shareFailedMsg = stringResource(R.string.doc_preview_share_failed)
    val shareTooLongMsg = stringResource(R.string.doc_preview_share_text_too_long)
    val selectedMode = savedMode?.takeIf { it == DOC_SHARE_MODE_TEXT || it == DOC_SHARE_MODE_FILE }
    Box(modifier) {
        IconButton(onClick = { menuOpen = true }) {
            Icon(
                Icons.Rounded.Share,
                contentDescription = stringResource(R.string.doc_preview_share),
                tint = colors.textSecondary,
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            Text(
                stringResource(R.string.doc_preview_share_mode_title),
                fontSize = 11.sp,
                color = colors.textTertiary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
            )
            ShareModeMenuItem(
                label = stringResource(R.string.doc_preview_share_as_text),
                checked = selectedMode == DOC_SHARE_MODE_TEXT,
            ) {
                menuOpen = false
                onModePicked(DOC_SHARE_MODE_TEXT)
                scope.launch {
                    val outcome = shareDocument(context, fileName, content, asFile = false)
                    showShareOutcome(context, outcome, shareFailedMsg, shareTooLongMsg)
                }
            }
            ShareModeMenuItem(
                label = stringResource(R.string.doc_preview_share_as_file),
                checked = selectedMode == DOC_SHARE_MODE_FILE,
            ) {
                menuOpen = false
                onModePicked(DOC_SHARE_MODE_FILE)
                scope.launch {
                    val outcome = shareDocument(context, fileName, content, asFile = true)
                    showShareOutcome(context, outcome, shareFailedMsg, shareTooLongMsg)
                }
            }
        }
    }
}

@Composable
private fun ShareModeMenuItem(label: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = if (checked) {
            {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = CodegTheme.colors.accent,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            null
        },
        onClick = onClick,
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

internal enum class DocShareOutcome {
    TEXT,
    FILE,
    TEXT_TOO_LONG_AS_FILE,
    FAILED,
}

private data class SharePlan(
    val intent: Intent,
    val outcome: DocShareOutcome,
)

private fun showShareOutcome(
    context: Context,
    outcome: DocShareOutcome,
    failedMessage: String,
    tooLongMessage: String,
) {
    val message = when (outcome) {
        DocShareOutcome.FAILED -> failedMessage
        DocShareOutcome.TEXT_TOO_LONG_AS_FILE -> tooLongMessage
        else -> return
    }
    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
}

/**
 * Share in one of two shapes (批v197): text goes as EXTRA_TEXT (the receiving
 * chat app shows it inline — Deepseek/豆包 read it directly); file mode writes
 * the full content into the FileProvider cache and sends a read-granted URI.
 * The URI is mirrored into ClipData because several share targets (including
 * some chat apps) only read the stream from there. The authority MUST be
 * `${applicationId}.fileprovider` — the fork's per-variant applicationId
 * suffixes make a literal authority collide on dual installs.
 */
internal suspend fun shareDocument(
    context: Context,
    fileName: String,
    content: String,
    asFile: Boolean = false,
): DocShareOutcome {
    val plan = try {
        buildShareIntent(context, fileName, content, asFile)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        return DocShareOutcome.FAILED
    }
    val chooser = Intent.createChooser(plan.intent, null).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        plan.intent.clipData?.let { clipData = it }
    }
    return runCatching { context.startActivity(chooser) }
        .fold({ plan.outcome }, { DocShareOutcome.FAILED })
}

private suspend fun buildShareIntent(
    context: Context,
    fileName: String,
    content: String,
    asFile: Boolean,
): SharePlan =
    if (!asFile && content.length <= SHARE_TEXT_LIMIT) {
        SharePlan(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, fileName)
                .putExtra(Intent.EXTRA_TITLE, fileName)
                .putExtra(Intent.EXTRA_TEXT, content),
            DocShareOutcome.TEXT,
        )
    } else {
        val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
        // The share cache is best-effort and must not grow forever. Keep files
        // for 24h: a receiver may read the URI asynchronously after the chooser
        // closes, so immediate deletion would break the share.
        withContext(Dispatchers.IO) { pruneOldShareDirs(dir) }
        val safeName = safeShareFileName(fileName)
        // A unique subdirectory keeps two same-named docs from overwriting each
        // other while a receiver is still reading the first URI.
        val shareDir = java.io.File(dir, java.util.UUID.randomUUID().toString()).apply { mkdirs() }
        val file = java.io.File(shareDir, safeName)
        withContext(Dispatchers.IO) { file.writeText(content) }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file,
        )
        SharePlan(
            Intent(Intent.ACTION_SEND)
                .setType(resolveShareFileMime(fileName))
                .putExtra(Intent.EXTRA_SUBJECT, fileName)
                .putExtra(Intent.EXTRA_TITLE, fileName)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .apply { clipData = ClipData.newRawUri(safeName, uri) }
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            if (asFile) DocShareOutcome.FILE else DocShareOutcome.TEXT_TOO_LONG_AS_FILE,
        )
    }

private fun pruneOldShareDirs(sharedRoot: java.io.File) {
    val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
    sharedRoot.listFiles()
        ?.filter { it.isDirectory && it.lastModified() < cutoff }
        ?.forEach { it.deleteRecursively() }
}

/**
 * "Share as file" must be received as an attachment, not rendered inline.
 * Text-ish types therefore use the generic binary MIME; receivers that accept
 * documents show a file (with the original extension preserved) instead of a
 * plain-text bubble. Real media/PDF keep their specific MIME so the receiving
 * app can still recognize and preview them.
 */
internal fun resolveShareFileMime(name: String): String =
    resolveOpenWithMime(name).let { mime ->
        if (mime.startsWith("text/")) "application/octet-stream" else mime
    }

/** Keep the cache path inside `shared/`; never let a display name become `..`. */
internal fun safeShareFileName(fileName: String): String {
    val base = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
        .replace(Regex("[\\u0000-\\u001F/\\\\]"), "_")
        .trimEnd('.', ' ')
    if (base.isEmpty() || base == "." || base == "..") return "document.txt"

    val extension = base.substringAfterLast('.', "")
        .takeIf { it.isNotEmpty() && it.length <= 12 && it.all(Char::isLetterOrDigit) }
    val suffix = extension?.let { ".$it" }.orEmpty()
    if (base.codePointCount(0, base.length) <= MAX_SHARE_NAME_CODE_POINTS) return base

    val headLimit = (MAX_SHARE_NAME_CODE_POINTS - suffix.codePointCount(0, suffix.length)).coerceAtLeast(1)
    val head = truncateCodePoints(base, headLimit).trimEnd('.', ' ').ifEmpty { "document" }
    return head + suffix
}

private const val MAX_SHARE_NAME_CODE_POINTS = 60

private fun truncateCodePoints(value: String, maxCodePoints: Int): String {
    if (value.codePointCount(0, value.length) <= maxCodePoints) return value
    return value.substring(0, value.offsetByCodePoints(0, maxCodePoints))
}
