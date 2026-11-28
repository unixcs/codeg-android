package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.SessionConfigOption
import app.codeg.android.core.model.SessionConfigSelectOption
import kotlin.math.ceil

/** One visually grouped model option. `name == null` renders without a header. */
internal data class AgentModelGroup(
    val key: String,
    val name: String?,
    val options: List<SessionConfigSelectOption>,
)

/** The useful right-side metadata for a compact model row. */
internal data class AgentModelPresentation(
    val shortLabel: String,
    val rate: String?,
)

/**
 * Extract a model multiplier from the human name first, then the description.
 * Covers both `Foo · 0.29x` and CodeBuddy's `x0.21` / `x0.21 credits`; leaves
 * normal provider labels intact.
 */
internal fun modelRate(name: String?, description: String?): String? {
    val nameRate = name?.let(::endingModelRate)
    if (nameRate != null) return nameRate
    return description?.let(::endingModelRate)
}

private val MODEL_RATE_END = Regex(
    """\s*(?:[·•|]\s*)?(?:[x×]\s*(\d+(?:\.\d+)?)(?:\s*credits)?|(\d+(?:\.\d+)?)\s*(?:[x×](?:\s*credits)?|credits))\s*$""",
    RegexOption.IGNORE_CASE,
)

private fun endingModelRate(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val match = MODEL_RATE_END.find(trimmed) ?: return null
    val value = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return null
    return "${value}x"
}

/** Remove a trailing multiplier so the row never repeats it. */
private fun stripEndingModelRate(text: String): String {
    val trimmed = text.trim()
    val match = MODEL_RATE_END.find(trimmed) ?: return trimmed
    return trimmed.substring(0, match.range.first)
        .trimEnd(' ', '·', '•', '|', '-', '–', '—')
        .trim()
}

/** Split a name into its first `/` segments, or null for unusable prefixes. */
private fun splitNamePrefix(name: String): Pair<String, String>? {
    val index = name.indexOf('/')
    if (index <= 0 || index >= name.length - 1) return null
    val head = name.take(index).trim()
    val tail = name.drop(index + 1).trim()
    if (head.isEmpty() || tail.isEmpty()) return null
    return head to tail
}

/** The leading name segment repeated by every item, or null. */
private fun sharedNamePrefix(items: List<SessionConfigSelectOption>): String? {
    var shared: String? = null
    for (item in items) {
        val prefix = splitNamePrefix(item.name)?.first ?: return null
        if (shared == null) shared = prefix
        else if (shared != prefix) return null
    }
    return shared
}

private fun strippablePrefix(valuePrefix: String, items: List<SessionConfigSelectOption>): String? {
    val shared = sharedNamePrefix(items) ?: return null
    if (items.size < 2 && !shared.equals(valuePrefix, ignoreCase = true)) return null
    return shared
}

private fun valuePrefix(value: String): String? {
    val index = value.indexOf('/')
    if (index <= 0 || index >= value.length - 1) return null
    return value.take(index)
}

private fun buildGroup(
    valuePrefix: String,
    items: List<SessionConfigSelectOption>,
): AgentModelGroup {
    val shared = strippablePrefix(valuePrefix, items)
    if (shared == null) return AgentModelGroup(valuePrefix, valuePrefix, items)
    return AgentModelGroup(
        key = valuePrefix,
        name = shared,
        options = items.map { option ->
            option.copy(name = splitNamePrefix(option.name)?.second ?: option.name)
        },
    )
}

/**
 * Port of the web composer's provider-prefix grouping. Flat model options are
 * grouped by the stable value prefix; a repeated display prefix becomes the
 * header and is stripped from rows. Server groups are preserved verbatim, and
 * non-model options stay untouched.
 */
internal fun deriveModelGroups(option: SessionConfigOption): List<AgentModelGroup>? {
    if (option.id != "model" && option.category != "model") return null
    if (option.kind.type != "select") return null
    if (option.kind.groups.isNotEmpty()) return null
    val prefixes = option.kind.options.map { valuePrefix(it.value) }
    if (prefixes.none { it != null }) return null

    val floating = mutableListOf<SessionConfigSelectOption>()
    val order = mutableListOf<String>()
    val byPrefix = linkedMapOf<String, MutableList<SessionConfigSelectOption>>()
    option.kind.options.forEachIndexed { index, option ->
        val prefix = prefixes[index]
        if (prefix == null) {
            floating += option
            return@forEachIndexed
        }
        byPrefix.getOrPut(prefix) {
            order += prefix
            mutableListOf()
        }.add(option)
    }

    if (order.size == 1 && floating.isEmpty()) {
        val prefix = order.single()
        val items = byPrefix.getValue(prefix)
        if (strippablePrefix(prefix, items) == null) return null
        return listOf(buildGroup(prefix, items))
    }

    val groups = mutableListOf<AgentModelGroup>()
    if (floating.isNotEmpty()) groups += AgentModelGroup("__ungrouped__", null, floating)
    order.forEach { prefix -> groups += buildGroup(prefix, byPrefix.getValue(prefix)) }
    return groups
}

/** Server groups verbatim; derived groups if useful; otherwise one flat group. */
internal fun modelGroups(option: SessionConfigOption): List<AgentModelGroup> {
    if (option.kind.type != "select") return emptyList()
    deriveModelGroups(option)?.let { return it }
    if (option.kind.groups.isNotEmpty()) {
        return option.kind.groups.map { group ->
            AgentModelGroup(group.group, group.name, group.options)
        }
    }
    return listOf(AgentModelGroup("__all__", null, option.kind.options))
}

internal fun filterModelGroups(groups: List<AgentModelGroup>, query: String): List<AgentModelGroup> {
    val normalized = query.trim()
    if (normalized.isEmpty()) return groups
    return groups.mapNotNull { group ->
        val options = group.options.filter { option ->
            option.name.contains(normalized, ignoreCase = true) ||
                option.value.contains(normalized, ignoreCase = true) ||
                option.description.orEmpty().contains(normalized, ignoreCase = true)
        }
        if (options.isEmpty()) null else group.copy(options = options)
    }
}

/** Flatten groups to selectable options while preserving server/derived order. */
internal fun flattenModelGroups(groups: List<AgentModelGroup>): List<SessionConfigSelectOption> =
    groups.flatMap { it.options }

/**
 * A model row's display metadata: the provider prefix is stripped only when a
 * group already shows it; the rate moves out of the long label. Falls back to
 * the provider description when there is no rate.
 */
internal fun modelPresentation(
    option: SessionConfigSelectOption,
    group: AgentModelGroup?,
): AgentModelPresentation {
    val rawName = option.name.ifBlank { option.value }
    val prefix = group?.name?.takeIf { rawName.startsWith("$it/", ignoreCase = true) }
        ?: valuePrefix(option.value)?.takeIf { rawName.startsWith("$it/", ignoreCase = true) }
    val labelWithoutPrefix = if (prefix == null) rawName else rawName.drop(prefix.length + 1).trim()
    // Magpie plumbing reaches the app as `magpie › group/vendor/model · 0.08x`.
    // Strip the rate first, then reuse the transcript's provider-prefix
    // compactor so the row keeps the model name (and its suffix), not the
    // plumbing. Plain provider labels without `›` or `/` pass through untouched.
    val shortLabel = compactModelName(stripEndingModelRate(labelWithoutPrefix))
        .ifBlank { labelWithoutPrefix }
    return AgentModelPresentation(
        shortLabel = shortLabel.ifBlank { rawName },
        rate = modelRate(option.name, option.description),
    )
}

/**
 * The header's "current" label uses the same compact presentation as the row,
 * including the rate, so the selected value is recognizable even when the full
 * server name is a long `magpie › ...` plumbing string.
 */
internal fun activeModelLabel(option: SessionConfigOption, currentValue: String?): String? {
    val raw = flattenChoiceValues(option).firstOrNull { it.value == currentValue } ?: return null
    val group = modelGroups(option).firstOrNull { candidate ->
        candidate.options.any { it.value == raw.value }
    }
    val presentation = modelPresentation(raw, group)
    return presentation.rate?.let { "${presentation.shortLabel} · $it" } ?: presentation.shortLabel
}

/**
 * The model picker exposes only single or two-column layouts. An explicit
 * two-column choice must win even when Magpie model ids are long: silently
 * collapsing back to one column makes the setting look broken, while the row's
 * ellipsis + trailing rate/check keeps a narrow grid readable.
 */
internal fun requestedModelColumns(requested: Int): Int = requested.coerceIn(1, 2)

/**
 * Config chips show only their labels; `requested == 0` picks density from
 * label length alone, capped by the number of choices.
 */
internal fun requestedConfigColumns(
    choices: List<ConfigOptionChoice>,
    requested: Int,
): Int {
    if (choices.isEmpty()) return 1
    if (requested == 0) {
        val longest = choices.maxOf { it.label.length }
        return when {
            longest <= 6 -> choices.size.coerceIn(1, 3)
            longest <= 11 -> choices.size.coerceIn(1, 2)
            else -> 1
        }
    }
    if (requested == 1) return 1
    // An explicit 2/3-column choice is a user preference, not a hint: honor it
    // and let the existing single-line ellipsis handle a long label. Only shrink
    // when there are fewer choices than requested columns, so a one-option
    // selector never leaves two empty cells.
    return requested.coerceIn(2, 3).coerceAtMost(choices.size)
}

/** Balanced grid rows; the last row may be shorter. */
internal fun <T> gridRows(values: List<T>, columns: Int): List<List<T>> {
    if (columns <= 1) return values.map { listOf(it) }
    val safeColumns = columns.coerceAtLeast(1)
    val rowCount = ceil(values.size / safeColumns.toDouble()).toInt().coerceAtLeast(1)
    return List(rowCount) { row ->
        val start = row * safeColumns
        values.subList(start, (start + safeColumns).coerceAtMost(values.size))
    }
}

