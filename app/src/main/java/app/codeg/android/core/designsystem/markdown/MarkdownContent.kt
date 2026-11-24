package app.codeg.android.core.designsystem.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalLayoutDirection
import android.content.pm.ActivityInfo
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import app.codeg.android.core.designsystem.component.CodeBlock
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.R

/**
 * Block-level Markdown for assistant text — the Compose port of iOS
 * `MarkdownContent`. Splits the source into blocks (paragraphs, headings, lists,
 * quotes, fenced code, rules, GFM tables) so an agent's replies render like a
 * chat client. Inline spans within each block go through [rememberInlineMarkdown].
 */
@Composable
fun MarkdownContent(raw: String, modifier: Modifier = Modifier) {
    // Slot-scoped parse only. The shared [MarkdownCache] is reserved for the persisted
    // node-build path; routing streaming callers (e.g. a collapsed ReasoningBlock that
    // the user expands mid-stream) through it would churn/evict persisted entries with
    // dead growing-tail prefixes.
    val blocks = remember(raw) { parseMarkdownBlocks(raw) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (block in blocks) SingleBlockView(block)
    }
}

/**
 * Renders one already-parsed Markdown block. Extracted from the old private
 * `BlockView` so the session timeline can emit one LazyColumn item per block (a long
 * message is no longer a single non-lazy [Column]); [MarkdownContent] loops over it.
 */
@Composable
internal fun SingleBlockView(block: MarkdownBlock, modifier: Modifier = Modifier) {
    val colors = CodegTheme.colors
    when (block) {
        is MarkdownBlock.Paragraph -> TappableMarkdownText(
            raw = block.text,
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 14.sp,
                lineHeight = 21.sp,
                color = colors.textPrimary,
            ),
            modifier = modifier.fillMaxWidth(),
        )

        is MarkdownBlock.Heading -> TappableMarkdownText(
            raw = block.text,
            style = androidx.compose.ui.text.TextStyle(
                fontSize = headingSize(block.level),
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            ),
            modifier = modifier.fillMaxWidth().padding(top = 2.dp),
        )

        is MarkdownBlock.BulletList -> Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            for (item in block.items) ListRow(marker = "•", content = item)
        }

        is MarkdownBlock.NumberedList -> Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            for ((marker, content) in block.items) ListRow(marker = marker, content = content)
        }

        is MarkdownBlock.Quote -> Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(colors.accent.copy(alpha = 0.5f)),
            )
            TappableMarkdownText(
                raw = block.text,
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    fontStyle = FontStyle.Italic,
                    color = colors.textSecondary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is MarkdownBlock.Code -> CodeBlock(code = block.code, modifier = modifier, language = block.language)

        MarkdownBlock.Rule -> Box(
            modifier.fillMaxWidth().height(0.5.dp).padding(vertical = 2.dp).background(colors.hairline),
        )

        is MarkdownBlock.Table -> MarkdownTable(block, modifier)
    }
}

@Composable
private fun ListRow(marker: String, content: String) {
    val colors = CodegTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = marker,
            fontSize = 14.sp,
            color = colors.textTertiary,
            modifier = Modifier.width(16.dp),
        )
        TappableMarkdownText(
            raw = content,
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 14.sp,
                lineHeight = 21.sp,
                color = colors.textPrimary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MarkdownTable(table: MarkdownBlock.Table, modifier: Modifier = Modifier) {
    var fullscreen by remember(table) { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // The parent's constraint is the usable width (LazyColumn content padding is
        // already subtracted). Fall back to a phone-sized width if a future caller
        // puts a table inside an unbounded horizontal scroller.
        val available = if (maxWidth.value.isFinite()) maxWidth.value else 360f
        val layout = remember(table, available) {
            tableColumnWidths(estimateNaturalWidths(table.header, table.rows), available)
        }
        val total = layout.widths.sum().dp
        if (layout.overflow) {
            val pinned = remember(table, available) {
                pinnedTableLayout(estimateNaturalWidths(table.header, table.rows), available)
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = CodegTheme.colors.codeSurface,
                border = androidx.compose.foundation.BorderStroke(0.5.dp, CodegTheme.colors.hairline),
            ) {
                Column {
                    TableToolbar(table.rawMarkdown(), fullscreen = true) { fullscreen = true }
                    PinnedTableGrid(table, pinned)
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = CodegTheme.colors.codeSurface,
                border = androidx.compose.foundation.BorderStroke(0.5.dp, CodegTheme.colors.hairline),
            ) {
                TableGrid(table, layout.widths, total)
            }
        }
    }
    if (fullscreen) TableFullscreen(table) { fullscreen = false }
}

@Composable
private fun TableToolbar(
    markdown: String,
    fullscreen: Boolean,
    onExpand: () -> Unit,
) {
    val colors = CodegTheme.colors
    val clipboard = LocalClipboardManager.current
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.markdown_table),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { clipboard.setText(AnnotatedString(markdown)) }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.code_copy), tint = colors.textTertiary, modifier = Modifier.size(16.dp))
        }
        if (fullscreen) {
            IconButton(onClick = onExpand, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.OpenInFull, contentDescription = stringResource(R.string.markdown_table_expand), tint = colors.textTertiary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

private fun MarkdownBlock.Table.rawMarkdown(): String {
    val separator = header.joinToString(" | ", prefix = "| ", postfix = " |")
    val aligns = header.joinToString(" | ", prefix = "| ", postfix = " |") { "---" }
    val body = rows.joinToString("\n") { row -> row.joinToString(" | ", prefix = "| ", postfix = " |") }
    return listOf(separator, aligns, body).filter { it.isNotBlank() }.joinToString("\n")
}

@Composable
private fun TableGrid(table: MarkdownBlock.Table, widths: List<Float>, total: Dp) {
    val colors = CodegTheme.colors
    Column(
        Modifier
            .width(total)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.codeSurface)
            .border(0.5.dp, colors.hairline, RoundedCornerShape(10.dp)),
    ) {
        TableRow(table.header, widths, isHeader = true)
        // An explicit width: `fillMaxWidth()` inside a horizontal scroll has an
        // unbounded constraint and collapses to zero.
        Box(Modifier.width(total).height(0.5.dp).background(colors.hairline))
        table.rows.forEachIndexed { idx, cells ->
            TableRow(cells, widths, isHeader = false)
            if (idx < table.rows.size - 1) {
                Box(Modifier.width(total).height(0.5.dp).background(colors.hairline.copy(alpha = 0.5f)))
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, widths: List<Float>, isHeader: Boolean) {
    val colors = CodegTheme.colors
    Row {
        cells.forEachIndexed { index, cell ->
            Text(
                text = rememberInlineMarkdown(cell),
                fontSize = 12.sp,
                fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isHeader) colors.textPrimary else colors.textSecondary,
                modifier = Modifier
                    .width((widths.getOrElse(index) { 64f }).dp)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun PinnedTableGrid(table: MarkdownBlock.Table, layout: PinnedTableLayout) {
    val colors = CodegTheme.colors
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(layout.pinnedWidth.dp)) {
            PinnedCell(table.header.firstOrNull().orEmpty(), isHeader = true)
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.hairline))
            table.rows.forEachIndexed { index, row ->
                PinnedCell(row.firstOrNull().orEmpty())
                if (index < table.rows.lastIndex) Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.hairline.copy(alpha = 0.5f)))
            }
        }
        VerticalDivider(thickness = 0.5.dp, color = colors.hairline)
        Box(Modifier.weight(1f)) {
            if (layout.scrollWidths.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Column(Modifier.width(layout.scrollTotalWidth.dp)) {
                        TableRow(table.header.drop(1), layout.scrollWidths, isHeader = true)
                        Box(Modifier.width(layout.scrollTotalWidth.dp).height(0.5.dp).background(colors.hairline))
                        table.rows.forEachIndexed { index, row ->
                            TableRow(row.drop(1), layout.scrollWidths, isHeader = false)
                            if (index < table.rows.lastIndex) Box(Modifier.width(layout.scrollTotalWidth.dp).height(0.5.dp).background(colors.hairline.copy(alpha = 0.5f)))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PinnedCell(text: String, isHeader: Boolean = false) {
    val colors = CodegTheme.colors
    Text(
        text = rememberInlineMarkdown(text),
        fontSize = 12.sp,
        fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
        color = if (isHeader) colors.textPrimary else colors.textSecondary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@Composable
private fun TableFullscreen(table: MarkdownBlock.Table, onDismiss: () -> Unit) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val activity = LocalContext.current.findActivity()
    var orientationLocked by remember { mutableStateOf(false) }
    DisposableEffect(activity) {
        val previous = activity?.requestedOrientation
        onDispose {
            if (previous != null) activity.requestedOrientation = previous
            else activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    val available = with(density) {
        if (configuration.screenWidthDp > configuration.screenHeightDp) {
            (configuration.screenWidthDp - 24).toFloat()
        } else {
            (configuration.screenWidthDp - 24).toFloat()
        }
    }
    val layout = remember(table, available) {
        tableColumnWidths(estimateNaturalWidths(table.header, table.rows), available, minColumn = 92f)
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(
            color = CodegTheme.colors.bg,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxSize().padding(vertical = 24.dp, horizontal = 8.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.markdown_table), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = CodegTheme.colors.textPrimary, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    IconButton(
                        onClick = {
                            if (orientationLocked) {
                                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                orientationLocked = false
                            } else {
                                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                orientationLocked = true
                            }
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Rounded.ScreenRotation,
                            contentDescription = stringResource(R.string.markdown_table_rotate),
                            tint = if (orientationLocked) CodegTheme.colors.accent else CodegTheme.colors.textTertiary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.CloseFullscreen, contentDescription = stringResource(R.string.common_close), tint = CodegTheme.colors.textTertiary, modifier = Modifier.size(18.dp))
                    }
                }
                Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                    TableGrid(table, layout.widths, layout.widths.sum().dp)
                }
            }
        }
    }
}

private fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun headingSize(level: Int) = when (level) {
    1 -> 20.sp
    2 -> 18.sp
    3 -> 16.sp
    else -> 14.5.sp
}
