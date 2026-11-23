package app.codeg.android.core.designsystem.markdown

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.codeg.android.R
import app.codeg.android.core.designsystem.theme.CodegTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One outline row: [index] points into the parsed block list — the jump target. */
data class OutlineEntry(val index: Int, val text: String, val level: Int)

/**
 * Outline rows for a parsed document. Same [parseMarkdownBlocks] source the
 * body renders, so the card can never list a heading the body doesn't show
 * (fence-safe by construction — the v194 contract).
 */
fun outlineEntries(blocks: List<MarkdownBlock>): List<OutlineEntry> =
    blocks.mapIndexedNotNull { i, b -> (b as? MarkdownBlock.Heading)?.let { OutlineEntry(i, it.text, it.level) } }

/**
 * 批v195 拍板 3A: the shared TOC card, slimmed to MindFS mobile dims — width
 * min(240dp, 60% of screen), single-line 12sp rows with per-level indent,
 * active row tinted; a tap reports the entry (caller closes + jumps). Used by
 * the doc preview, the project file browser and the fullscreen plan view.
 */
@Composable
fun OutlineCard(
    entries: List<OutlineEntry>,
    activeIdx: Int?,
    onJump: (OutlineEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    val width = minOf(240.dp, LocalConfiguration.current.screenWidthDp.dp * 0.6f)
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = colors.bgElevated,
        border = BorderStroke(1.dp, colors.surfaceStroke),
        shadowElevation = 8.dp,
        modifier = modifier.width(width),
    ) {
        Column(Modifier.padding(8.dp)) {
            Text(
                stringResource(R.string.doc_preview_outline),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textTertiary,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
            )
            LazyColumn(Modifier.heightIn(max = 340.dp)) {
                itemsIndexed(entries, key = { _, e -> e.index }) { _, e ->
                    val active = e.index == activeIdx
                    Text(
                        e.text,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) colors.accent else colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (active) colors.accent.copy(alpha = 0.11f) else Color.Transparent)
                            .clickable { onJump(e) }
                            .padding(
                                start = (8 + (e.level - 1).coerceAtMost(3) * 10).dp,
                                end = 8.dp,
                                top = 5.dp,
                                bottom = 5.dp,
                            ),
                    )
                }
            }
        }
    }
}

/**
 * 批v195 拍板 3A: the one-scroll-container markdown body with heading anchors
 * and the outline popup, shared by DocPreviewDialog / FolderFilesView /
 * PlanFullscreenDialog. One parse source feeds body and outline (fence-safe).
 * [headerOffset] pushes the popup below the surface's own top bar; pass the
 * same [scrollState] at two call sites to observe scrolling from outside.
 */
@Composable
fun OutlineAwareMarkdownBody(
    content: String,
    outlineOpen: Boolean,
    onDismissOutline: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 14.dp,
    headerOffset: Dp = 56.dp,
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val containerY = remember { mutableStateOf<Float?>(null) }
    val viewportPx = remember { mutableStateOf(0) }
    val anchorY = remember { mutableStateMapOf<Int, Float>() }
    val blocks = remember(content) { parseMarkdownBlocks(content) }
    val entries = remember(blocks) { outlineEntries(blocks) }
    // v194 R1-B: a focusable Popup does not consume the system back key — back
    // would fall through to the dialog's onDismissRequest and kill the whole
    // preview (and the reading position) just for closing the TOC. Intercept it.
    BackHandler(enabled = outlineOpen) { onDismissOutline() }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .onSizeChanged { viewportPx.value = it.height }
            .onGloballyPositioned { containerY.value = it.positionInRoot().y }
            .padding(horizontal = horizontalPadding),
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

    if (outlineOpen && entries.isNotEmpty()) {
        Popup(
            alignment = Alignment.TopEnd,
            offset = IntOffset(0, with(density) { headerOffset.roundToPx().roundToInt() }),
            onDismissRequest = onDismissOutline,
            properties = PopupProperties(focusable = true),
        ) {
            // Active heading: the last one whose anchor sits above the reading
            // line (scroll top + 40% viewport) — MindFS's highlight, cheap port.
            val activeIdx = entries.lastOrNull { e ->
                val cy = containerY.value
                val y = anchorY[e.index]
                cy != null && y != null && (y - cy) <= viewportPx.value * 0.4f
            }?.index

            OutlineCard(
                entries = entries,
                activeIdx = activeIdx,
                onJump = { e ->
                    onDismissOutline()
                    val cy = containerY.value
                    val y = anchorY[e.index]
                    if (cy != null && y != null) {
                        // y-cy is viewport-relative at tap time; the absolute
                        // content offset adds the scroll back.
                        val target = ((y - cy) + scrollState.value - with(density) { 24.dp.toPx() })
                            .roundToInt().coerceAtLeast(0)
                        scope.launch { scrollState.animateScrollTo(target) }
                    }
                },
            )
        }
    }
}
