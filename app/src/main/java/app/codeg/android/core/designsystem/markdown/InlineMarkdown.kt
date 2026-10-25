package app.codeg.android.core.designsystem.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * Inline-Markdown → [AnnotatedString] (the Compose analogue of iOS
 * `MarkdownText.attributed(from:)`, which used Apple's inline parser). Supports
 * `**bold**` / `__bold__`, `*italic*` / `_italic_`, `~~strike~~`, `` `code` ``,
 * and `[text](url)` links. Nesting is handled by recursion; code spans are
 * literal. Link URLs are attached as a string annotation under tag `URL`.
 *
 * Batch v123 adds two tap targets on top of the syntax spans:
 * - [URL_TAG] also covers bare http(s):// URLs in plain text;
 * - [PATH_TAG] covers absolute file paths with a previewable extension (bare, or
 *   inside a code span) so the session timeline can open the in-app document preview.
 * Both are plain string annotations — this file stays free of Android UI types and
 * pure-JVM testable; the click layer lives in [MarkdownContent].
 */
const val URL_TAG = "URL"

/** Annotation tag for tappable absolute file paths (value = the path text). */
const val PATH_TAG = "PATH"

/** Extensions eligible for in-app preview (see FolderFilesView's PreviewKind). */
internal val PREVIEWABLE_EXTENSIONS =
    listOf("md", "markdown", "html", "htm", "txt", "json", "log", "csv", "xml", "yml", "yaml", "toml")

/** Regex for a bare URL in plain text. Requires a scheme so "/etc/hosts" or
 *  "path/to" never match; trailing `.`/`,`/`)`/`]` are trimmed so a sentence
 *  period doesn't become part of the link. */
internal val BARE_URL_REGEX = Regex("""https?://[^\s<>"]+""")

/** Regex for an absolute path whose extension is previewable. Anchored on a
 *  boundary (whitespace, CJK/ASCII opening brackets, or start) so `foo/a.md`
 *  matches the path tail; segment characters include CJK because every real
 *  workspace here has 目录名 like `60-内容` (no Windows drive support — every
 *  codeg server is Linux). */
internal val BARE_PATH_REGEX = Regex("""(?:^|[\s(（「【>])(/[^\s()（）「」【】<>]+)""")

/**
 * Inline ranges (start/end in [raw]) where a tap-target may apply. A candidate is
 * accepted only if it overlaps NO consumed syntax region — `[text](/a/b.md)` already
 * carries a URL annotation for the whole span, and a code span owns its content.
 */
internal data class InlineRange(val start: Int, val end: Int)

/**
 * The tap-target matches in [raw] with their ranges: bare URLs, bare previewable
 * paths, and previewable paths inside code spans. Pure — the parser and the tests
 * share it, so annotations and spans can never drift apart.
 */
internal fun findTapTargets(raw: String): List<Pair<InlineRange, String>> {
    data class Region(val start: Int, val end: Int, val tag: String?, val value: String?)

    val regions = mutableListOf<Region>()

    // 1. Link syntax [text](url): the URL covers the whole `text` too (tapping the
    //    label must open the link, not a path inside it).
    var i = 0
    while (i < raw.length) {
        if (raw[i] == '[') {
            val close = raw.indexOf(']', i + 1)
            if (close > i && close + 1 < raw.length && raw[close + 1] == '(') {
                val urlEnd = raw.indexOf(')', close + 2)
                if (urlEnd > close) {
                    regions += Region(i, urlEnd + 1, URL_TAG, raw.substring(close + 2, urlEnd))
                    i = urlEnd + 1
                    continue
                }
            }
        }
        i++
    }

    fun overlapsConsumed(start: Int, end: Int): Boolean =
        regions.any { it.tag != null && start < it.end && end > it.start }

    // 2. Bare URLs in the remaining text. A trailing run of `.,;:)`」】` is sentence
    //    punctuation, not part of the URL — trim it off the match.
    for (m in BARE_URL_REGEX.findAll(raw)) {
        var end = m.range.last + 1
        var value = m.value
        while (value.isNotEmpty() && value.last() in ".,;:)」】") {
            value = value.dropLast(1)
            end--
        }
        if (value.isEmpty() || overlapsConsumed(m.range.first, end)) continue
        regions += Region(m.range.first, end, URL_TAG, value)
    }

    // 3. Code spans: literal content, but a previewable path inside one is still
    //    tappable (AI reports write paths as `/mnt/.../x.md` in backticks most of
    //    the time). The path must occupy the whole span content — a path embedded
    //    in prose inside a code span stays plain.
    var j = 0
    while (j < raw.length) {
        if (raw[j] == '`') {
            val end = raw.indexOf('`', j + 1)
            if (end > j) {
                val content = raw.substring(j + 1, end)
                val trimmed = content.trim()
                val path = BARE_PATH_REGEX.matchEntire(" $trimmed")?.groupValues?.get(1)
                if (path != null && path == trimmed && hasPreviewableExtension(path)) {
                    val contentStart = j + 1 + content.indexOf(trimmed)
                    regions += Region(contentStart, contentStart + trimmed.length, PATH_TAG, path)
                }
                j = end + 1
                continue
            }
        }
        j++
    }

    // 4. Bare previewable paths in the remaining text.
    for (m in BARE_PATH_REGEX.findAll(raw)) {
        val path = m.groupValues[1]
        if (!hasPreviewableExtension(path)) continue
        val start = m.range.first + (m.value.indexOf(path).takeIf { it >= 0 } ?: 0)
        val end = start + path.length
        if (!overlapsConsumed(start, end)) {
            regions += Region(start, end, PATH_TAG, path)
        }
    }

    return regions
        .filter { it.tag != null }
        .map { InlineRange(it.start, it.end) to it.value!! }
        .sortedBy { it.first.start }
}

/** Whether [path]'s extension is previewable in-app. Pure; shared with tests. */
internal fun hasPreviewableExtension(path: String): Boolean {
    val ext = path.substringAfterLast('.', "").lowercase()
    return ext.isNotEmpty() && ext in PREVIEWABLE_EXTENSIONS && path.contains('/')
}

/** Cached inline-markdown for the current theme colors (recomputed if colors change).
 *  Slot-scoped `remember` only — deliberately NOT a shared cache, so the growing tail of
 *  a streaming block can't churn/evict persisted spans. */
@Composable
fun rememberInlineMarkdown(raw: String): AnnotatedString {
    val colors = CodegTheme.colors
    return remember(raw, colors.accent, colors.codeSurface, colors.textPrimary) {
        inlineMarkdown(
            raw = raw,
            linkColor = colors.accent,
            codeBg = colors.codeSurface,
            codeColor = colors.textPrimary,
        )
    }
}

fun inlineMarkdown(
    raw: String,
    linkColor: Color,
    codeBg: Color,
    codeColor: Color,
): AnnotatedString = buildAnnotatedString {
    parseInline(this, raw, linkColor, codeBg, codeColor)
}

private fun parseInline(
    builder: AnnotatedString.Builder,
    raw: String,
    linkColor: Color,
    codeBg: Color,
    codeColor: Color,
) {
    val tapTargets = findTapTargets(raw)
    var i = 0
    val n = raw.length
    val plain = StringBuilder()

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            val start = i - plain.length
            builder.append(plain.toString())
            // Attach tap-target annotations to the plain-text run just flushed.
            for ((range, value) in tapTargets) {
                if (range.start in start until i) {
                    val tag = if (value.startsWith("http://") || value.startsWith("https://")) URL_TAG else PATH_TAG
                    val offsetStart = range.start - start
                    val offsetEnd = (range.end - start).coerceAtMost(i - start)
                    if (offsetEnd > offsetStart) {
                        builder.addStringAnnotation(tag, value, offsetStart, offsetEnd)
                    }
                }
            }
            plain.clear()
        }
    }

    while (i < n) {
        val c = raw[i]
        when {
            // Inline code — literal content, no recursion.
            c == '`' -> {
                val end = raw.indexOf('`', i + 1)
                if (end > i) {
                    flushPlain()
                    builder.withStyle(
                        SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, color = codeColor),
                    ) { append(raw.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    plain.append(c); i++
                }
            }

            // Bold (** or __).
            (c == '*' || c == '_') && i + 1 < n && raw[i + 1] == c -> {
                val marker = "$c$c"
                val end = raw.indexOf(marker, i + 2)
                if (end > i + 1) {
                    flushPlain()
                    builder.withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        parseInline(builder, raw.substring(i + 2, end), linkColor, codeBg, codeColor)
                    }
                    i = end + 2
                } else {
                    plain.append(c); i++
                }
            }

            // Strikethrough (~~).
            c == '~' && i + 1 < n && raw[i + 1] == '~' -> {
                val end = raw.indexOf("~~", i + 2)
                if (end > i + 1) {
                    flushPlain()
                    builder.withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        parseInline(builder, raw.substring(i + 2, end), linkColor, codeBg, codeColor)
                    }
                    i = end + 2
                } else {
                    plain.append(c); i++
                }
            }

            // Italic (single * or _).
            (c == '*' || c == '_') -> {
                val end = raw.indexOf(c, i + 1)
                if (end > i) {
                    flushPlain()
                    builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        parseInline(builder, raw.substring(i + 1, end), linkColor, codeBg, codeColor)
                    }
                    i = end + 1
                } else {
                    plain.append(c); i++
                }
            }

            // Link [text](url).
            c == '[' -> {
                val close = raw.indexOf(']', i + 1)
                if (close > i && close + 1 < n && raw[close + 1] == '(') {
                    val urlEnd = raw.indexOf(')', close + 2)
                    if (urlEnd > close) {
                        val text = raw.substring(i + 1, close)
                        val url = raw.substring(close + 2, urlEnd)
                        flushPlain()
                        builder.pushStringAnnotation(URL_TAG, url)
                        builder.withStyle(SpanStyle(color = linkColor)) {
                            parseInline(builder, text, linkColor, codeBg, codeColor)
                        }
                        builder.pop()
                        i = urlEnd + 1
                    } else {
                        plain.append(c); i++
                    }
                } else {
                    plain.append(c); i++
                }
            }

            else -> {
                plain.append(c); i++
            }
        }
    }
    flushPlain()
}
