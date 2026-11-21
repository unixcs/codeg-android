package app.codeg.android.core.designsystem.component

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow

/**
 * 批v194 M2: single-line filename text that elides the MIDDLE instead of the
 * tail, keeping the extension visible — a file-manager staple (compose-ui-text
 * 1.7.x has no middle TextOverflow, so this measures and binary-searches the
 * cut). Long names read as "very-long-name…批v194.md" instead of losing the
 * extension, which is the piece that identifies the document.
 *
 * The pinned suffix is the file's extension (with the dot); extensionless names
 * and directories have no suffix to pin, and any measure failure degrades to
 * plain end-ellipsis rather than guessing.
 */
@Composable
fun MiddleEllipsisText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
) {
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val maxWidthPx = with(LocalDensity.current) { maxWidth.toPx() }.toInt()
        val fitted = remember(text, maxWidthPx, style) {
            fittedMiddleText(text, measurer, style, maxWidthPx)
        }
        if (fitted != null) {
            Text(fitted, color = color, style = style, maxLines = 1, softWrap = false)
        } else {
            Text(text, color = color, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "prefix…suffix" that fits [maxWidthPx]; null when the whole text fits or nothing does. */
private fun fittedMiddleText(
    text: String,
    measurer: TextMeasurer,
    style: TextStyle,
    maxWidthPx: Int,
): String? {
    if (maxWidthPx <= 0) return null
    val full = measurer.measure(AnnotatedString(text), style, maxLines = 1, softWrap = false)
    if (full.size.width <= maxWidthPx) return null

    val suffix = middleSuffix(text)
    var lo = 1
    var hi = text.length
    var best: String? = null
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        val candidate = text.take(mid) + "…" + suffix
        val w = measurer.measure(AnnotatedString(candidate), style, maxLines = 1, softWrap = false).size.width
        if (w <= maxWidthPx) {
            best = candidate
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return best
}

/** The pinned tail: the extension (with the dot); extensionless names pin nothing. */
private fun middleSuffix(text: String): String {
    val lastSegment = text.substringAfterLast('/')
    val dot = lastSegment.lastIndexOf('.')
    return if (dot > 0) lastSegment.substring(dot) else ""
}
