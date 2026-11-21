package app.codeg.android.core.designsystem.component

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.codeg.android.R
import app.codeg.android.core.designsystem.markdown.MarkdownBlock
import app.codeg.android.core.designsystem.markdown.SingleBlockView
import app.codeg.android.core.designsystem.markdown.TappableMarkdownText
import app.codeg.android.core.designsystem.markdown.openUrlExternally
import app.codeg.android.core.designsystem.markdown.parseMarkdownBlocks
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.feature.sessiondetail.DocPreviewKind
import app.codeg.android.feature.sessiondetail.DocPreviewState
import kotlin.math.roundToInt

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
 * 批v194 M4: the MindFS 大纲 port — Markdown previews gain a TOC action that
 * pops a compact heading card; tapping a heading scrolls the (block-anchored)
 * body to it. One parse source ([parseMarkdownBlocks]) feeds BOTH the body and
 * the outline, so the outline is fence-safe by construction and can never list
 * a heading the body doesn't render.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocPreviewDialog(state: DocPreviewState, onDismiss: () -> Unit) {
    val colors = CodegTheme.colors
    // Parse only for Markdown previews — HTML/text bodies skip the wasted pass.
    val blocks = remember(state.content) {
        if (state.kind == DocPreviewKind.MARKDOWN) parseMarkdownBlocks(state.content) else emptyList()
    }
    val headings = remember(blocks) {
        blocks.mapIndexedNotNull { i, b -> (b as? MarkdownBlock.Heading)?.let { i to it } }
    }
    var outlineOpen by remember { mutableStateOf(false) }
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
                        if (state.kind == DocPreviewKind.MARKDOWN && headings.isNotEmpty()) {
                            IconButton(onClick = { outlineOpen = !outlineOpen }) {
                                Icon(Icons.Rounded.Toc, contentDescription = stringResource(R.string.doc_preview_outline), tint = colors.textSecondary)
                            }
                        }
                        ShareIconButton(fileName = state.title, content = state.content)
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.bg),
                )
            },
        ) { padding ->
            SelectionContainer(Modifier.padding(padding)) {
                when (state.kind) {
                    DocPreviewKind.MARKDOWN -> MarkdownPreviewBody(
                        blocks = blocks,
                        headings = headings,
                        outlineOpen = outlineOpen,
                        onDismissOutline = { outlineOpen = false },
                    )

                    DocPreviewKind.HTML -> DocHtmlPreview(state.content)

                    else -> Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
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
    }
}

/**
 * The Markdown body: one block per [SingleBlockView] (the exact render
 * [MarkdownContent] loops) with a position anchor on every heading, plus the
 * outline popup. Anchors are root-relative and converted with the scroll
 * container's own root position, so nested padding can't skew the jump.
 */
@Composable
private fun MarkdownPreviewBody(
    blocks: List<MarkdownBlock>,
    headings: List<Pair<Int, MarkdownBlock.Heading>>,
    outlineOpen: Boolean,
    onDismissOutline: () -> Unit,
) {
    val colors = CodegTheme.colors
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val containerY = remember { mutableStateOf<Float?>(null) }
    val viewportPx = remember { mutableStateOf(0) }
    val anchorY = remember { mutableStateMapOf<Int, Float>() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .onSizeChanged { viewportPx.value = it.height }
            .onGloballyPositioned { containerY.value = it.positionInRoot().y }
            .padding(horizontal = 14.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
        ) {
            for ((i, block) in blocks.withIndex()) {
                val anchor = if (block is MarkdownBlock.Heading) {
                    Modifier.onGloballyPositioned { anchorY[i] = it.positionInRoot().y }
                } else {
                    Modifier
                }
                SingleBlockView(block, Modifier.fillMaxWidth().then(anchor))
            }
        }
    }

    if (outlineOpen && headings.isNotEmpty()) {
        val headerOffsetPx = with(density) { 56.dp.toPx().roundToInt() }
        Popup(
            alignment = Alignment.TopEnd,
            offset = IntOffset(0, headerOffsetPx),
            onDismissRequest = onDismissOutline,
            properties = PopupProperties(focusable = true),
        ) {
            // Active heading: the last one whose anchor sits above the reading
            // line (scroll top + 40% viewport) — MindFS's highlight, cheap port.
            val activeIdx = headings.lastOrNull { (i, _) ->
                // y-cy is already viewport-relative; adding scroll here would let
                // every heading below the line qualify as the doc grows.
                val cy = containerY.value
                val y = anchorY[i]
                cy != null && y != null && (y - cy) <= viewportPx.value * 0.4f
            }?.first ?: -1

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.bgElevated,
                border = BorderStroke(1.dp, colors.surfaceStroke),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .width(280.dp)
                    .heightIn(max = 420.dp),
            ) {
                Column {
                    Text(
                        stringResource(R.string.doc_preview_outline),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    LazyColumn {
                        itemsIndexed(headings, key = { _, p -> p.first }) { _, pair ->
                            val (idx, heading) = pair
                            Text(
                                heading.text,
                                fontSize = when {
                                    heading.level <= 1 -> 13.sp
                                    heading.level == 2 -> 12.sp
                                    else -> 11.sp
                                },
                                fontWeight = if (heading.level <= 2) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (idx == activeIdx) colors.accent else colors.textPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onDismissOutline()
                                        val cy = containerY.value
                                        val y = anchorY[idx]
                                        if (cy != null && y != null) {
                                            // y-cy is viewport-relative at tap time; the
                                            // absolute content offset adds the scroll back.
                                            val target = ((y - cy) + scrollState.value - with(density) { 24.dp.toPx() })
                                                .roundToInt().coerceAtLeast(0)
                                            scope.launch { scrollState.animateScrollTo(target) }
                                        }
                                    }
                                    .padding(
                                        start = (12 + (heading.level - 1) * 10).dp,
                                        end = 12.dp,
                                        top = 6.dp,
                                        bottom = 6.dp,
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
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

/**
 * The header share action. Two shapes by content size: small text goes as
 * EXTRA_TEXT (the receiving chat app shows it inline — Deepseek/豆包 read it
 * directly); large or file-shaped content goes through FileProvider as a stream.
 * The authority MUST be `${applicationId}.fileprovider` — the fork's per-variant
 * applicationId suffixes make a literal authority collide on dual installs.
 */
@Composable
private fun ShareIconButton(fileName: String, content: String) {
    val context = LocalContext.current
    val tint = CodegTheme.colors.textSecondary
    // Share write off the main thread (batch v130): the >100KB path wrote a file
    // to flash inline in the click handler.
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    IconButton(onClick = { scope.launch { shareDocument(context, fileName, content) } }) {
        Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.doc_preview_share), tint = tint)
    }
}

private const val SHARE_TEXT_LIMIT = 100_000

internal suspend fun shareDocument(context: Context, fileName: String, content: String) {
    val send: Intent = if (content.length <= SHARE_TEXT_LIMIT) {
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, fileName)
            .putExtra(Intent.EXTRA_TEXT, content)
    } else {
        val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
        val file = java.io.File(dir, fileName.substringAfterLast('/').ifEmpty { "document.txt" })
        // Caller is already off Main (shareDocument runs in a scope on Default).
        withContext(Dispatchers.IO) { runCatching { file.writeText(content.take(SHARE_TEXT_LIMIT)) } }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file,
        )
        Intent(Intent.ACTION_SEND)
            .setType(mimeFor(fileName))
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
