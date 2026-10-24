package app.codeg.android.core.designsystem.markdown

/**
 * The resolved column widths (dp) for one Markdown table, plus whether the table
 * is wider than the space it was given (and therefore needs horizontal scrolling).
 */
internal data class TableLayout(val widths: List<Float>, val overflow: Boolean)

/**
 * Distribute [availableWidth] across columns so a table always uses the width it is
 * given, instead of falling back to a fixed per-cell width (the old 130dp columns
 * left a two-column table occupying only two thirds of the screen).
 *
 * - Content fits: columns grow proportionally to their natural width and exactly
 *   fill [availableWidth].
 * - Content does not fit: water-fill — columns shrink proportionally, but any
 *   column that would fall below [minColumn] is pinned there and the width it
 *   frees up is redistributed over the remaining columns (which may then exceed
 *   their natural width — a table always fills the row). Only when even the pinned
 *   minimums can't fit (`minColumn * n > available`) does the table overflow and
 *   scroll horizontally, at the minimum widths rather than the natural ones.
 */
internal fun tableColumnWidths(
    naturalWidths: List<Float>,
    availableWidth: Float,
    minColumn: Float = 64f,
): TableLayout {
    if (naturalWidths.isEmpty()) return TableLayout(emptyList(), false)
    val available = if (availableWidth.isFinite() && availableWidth > 0f) availableWidth else 0f
    val sum = naturalWidths.sum()
    if (sum <= 0f) return TableLayout(naturalWidths.map { minColumn }, true)

    if (available >= sum) {
        return TableLayout(naturalWidths.map { it * available / sum }, false)
    }

    // The pinned minimums are the floor of what can fit at all; check that BEFORE
    // iterating so the overflow contract stays a pure pre-check, not a side effect
    // of the redistribution loop.
    if (minColumn * naturalWidths.size > available + 0.5f) {
        return TableLayout(naturalWidths.map { minColumn }, true)
    }

    // Water-fill: repeatedly scale the unpinned columns to the width left over
    // after the pinned ones take their minimum. A column the scaled share would
    // squeeze below [minColumn] pins there (guaranteed ≥ its scaled share), which
    // frees/absorbs width for the rest — at most n iterations, each pinning one
    // column for good.
    val widths = FloatArray(naturalWidths.size)
    val pinned = BooleanArray(naturalWidths.size)
    while (true) {
        var pinnedSum = 0f
        var freeCount = 0
        for (i in naturalWidths.indices) {
            if (pinned[i]) pinnedSum += widths[i] else freeCount++
        }
        if (freeCount == 0) break
        val freeSpace = available - pinnedSum
        val freeNatural = naturalWidths.withIndex().sumOf { (i, w) -> if (pinned[i]) 0.0 else w.toDouble() }.toFloat()
        // freeSpace ≥ 0 is guaranteed by the pre-check + pinning monotonically
        // downward; freeNatural > 0 because sum > available ≥ 0 leaves unpinned mass.
        val scale = freeSpace / freeNatural
        var newlyPinned = false
        for (i in naturalWidths.indices) {
            if (pinned[i]) continue
            val share = naturalWidths[i] * scale
            if (share < minColumn) {
                widths[i] = minColumn
                pinned[i] = true
                newlyPinned = true
            } else {
                widths[i] = share
            }
        }
        if (!newlyPinned) break
    }
    return TableLayout(widths.toList(), false)
}

/**
 * Estimate each column's natural width (dp) from its longest cell. Only used to
 * choose a distribution ratio — never to clip content, so an approximation is fine.
 * Values are clamped to [64, 300] dp: narrow enough to keep short columns from
 * hogging the row, wide enough that long prose wraps instead of scrolling.
 */
internal fun estimateNaturalWidths(header: List<String>, rows: List<List<String>>): List<Float> {
    val columns = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0)
    return List(columns) { column ->
        val longest = (sequenceOf(header.getOrElse(column) { "" }) +
            rows.asSequence().map { it.getOrElse(column) { "" } })
            .maxOfOrNull { displayUnits(it) }
            ?: 0
        // 12sp text: ~6.6dp per ASCII unit; CJK counts as two units.
        (longest * 6.6f + 16f).coerceIn(64f, 300f)
    }
}

/** Approximate terminal width in ASCII units (CJK / full-width glyphs count twice). */
private fun displayUnits(text: String): Int = text.fold(0) { acc, ch ->
    acc + if (ch.isWideGlyph()) 2 else 1
}

private fun Char.isWideGlyph(): Boolean {
    val code = code
    return code in 0x1100..0x115F ||
        code in 0x2E80..0xA4CF ||
        code in 0xAC00..0xD7A3 ||
        code in 0xF900..0xFAFF ||
        code in 0xFE30..0xFE4F ||
        code in 0xFF00..0xFF60 ||
        code in 0xFFE0..0xFFE6
}
