package app.codeg.android.core.designsystem.component

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.codeg.android.R
import app.codeg.android.core.designsystem.markdown.MarkdownContent
import app.codeg.android.core.designsystem.markdown.TappableMarkdownText
import app.codeg.android.core.designsystem.markdown.openUrlExternally
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.feature.sessiondetail.DocPreviewKind
import app.codeg.android.feature.sessiondetail.DocPreviewState

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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocPreviewDialog(state: DocPreviewState, onDismiss: () -> Unit) {
    val colors = CodegTheme.colors
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
                        ShareIconButton(fileName = state.title, content = state.content)
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.bg),
                )
            },
        ) { padding ->
            SelectionContainer(Modifier.padding(padding)) {
                when (state.kind) {
                    DocPreviewKind.MARKDOWN -> Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp),
                    ) {
                        MarkdownContent(state.content, modifier = Modifier.padding(vertical = 10.dp))
                    }

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
    IconButton(onClick = { shareDocument(context, fileName, content) }) {
        Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.doc_preview_share), tint = tint)
    }
}

private const val SHARE_TEXT_LIMIT = 100_000

internal fun shareDocument(context: Context, fileName: String, content: String) {
    val send: Intent = if (content.length <= SHARE_TEXT_LIMIT) {
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, fileName)
            .putExtra(Intent.EXTRA_TEXT, content)
    } else {
        val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
        val file = java.io.File(dir, fileName.substringAfterLast('/').ifEmpty { "document.txt" })
        runCatching { file.writeText(content.take(SHARE_TEXT_LIMIT)) }
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
