package app.codeg.android.core.designsystem.markdown

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Text

/**
 * Callback a host screen can install to open a document preview for an absolute
 * file path tapped inside rendered Markdown. Default null: every consumer that
 * doesn't provide one (experts, skills, tool output, plan cards…) renders the
 * path tinted but inert — zero behavioral change for them.
 */
val LocalPathOpener = compositionLocalOf<((path: String) -> Unit)?> { null }

/**
 * The Markdown tap layer (batch v123): a [Text] whose tap position is resolved
 * against the [AnnotatedString] annotations produced by [parseInline]:
 * - URL_TAG → browser (ActivityNotFound → no-op; the label still shows the accent color);
 * - PATH_TAG → [LocalPathOpener] when the host provides one.
 *
 * Implementation notes from the adversarial review:
 * - [TextLayoutResult] lives in a `mutableStateOf` written by `onTextLayout` — a plain
 *   local var would be captured stale by the pointerInput's first-composition lambda
 *   and every tap would silently no-op;
 * - `pointerInput(text)` restarts with the text so a new layout pairs with new strings
 *   (streaming rebuilds included); the tap reads the CURRENT layout, so there is no
 *   stale-TextLayoutResult problem by construction;
 * - detectTapGestures consumes only clean taps — vertical list scrolling is never hijacked.
 */
@Composable
fun TappableMarkdownText(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onOpenUrl: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val pathOpener = LocalPathOpener.current
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    Text(
        text = text,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = { layoutResult = it },
        modifier = modifier.pointerInput(text) {
            detectTapGestures { pos ->
                val layout = layoutResult ?: return@detectTapGestures
                val offset = layout.getOffsetForPosition(pos)
                if (offset > text.length) return@detectTapGestures
                val url = text.getStringAnnotations(URL_TAG, offset, offset).firstOrNull()
                if (url != null) {
                    if (onOpenUrl != null) {
                        onOpenUrl(url.item)
                    } else if (!openUrlExternally(context, url.item)) {
                        // Non-http(s) scheme or no browser — the label stays tinted but inert.
                    }
                    return@detectTapGestures
                }
                val path = text.getStringAnnotations(PATH_TAG, offset, offset).firstOrNull()
                if (path != null) pathOpener?.invoke(path.item)
            }
        },
    )
}

/** Convenience overload keeping the [rememberInlineMarkdown] call close to the tap layer. */
@Composable
fun TappableMarkdownText(
    raw: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onOpenUrl: ((String) -> Unit)? = null,
) = TappableMarkdownText(
    text = rememberInlineMarkdown(raw),
    style = style,
    modifier = modifier,
    maxLines = maxLines,
    overflow = overflow,
    onOpenUrl = onOpenUrl,
)

/** Swallow ActivityNotFoundException at one shared call site (browser open). */
fun openUrlExternally(context: android.content.Context, url: String): Boolean {
    // Scheme allowlist: URLs come from agent output (markdown [text](url)), i.e.
    // untrusted content — file://, content:// and custom schemes must never be
    // handed to another component (adversarial review round 4).
    if (Uri.parse(url).scheme?.lowercase() !in setOf("http", "https")) return false
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}