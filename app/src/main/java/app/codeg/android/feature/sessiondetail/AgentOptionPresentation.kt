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
 * Extract a trailing model multiplier from the human name first, then the
 * description. Covers `Foo · 0.29x` and provider entries whose rate lives in
 * the description; leaves normal provider labels intact.
 */
internal fun modelRate(name: String?, description: String?): String? {
    val nameRate = name?.let(::trailingModelRate)
    if (nameRate != null) return nameRate
    return description?.let(::trailingModelRate)
}

private fun trailingModelRate(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val match = Regex("""(\d+(?:\.\d+)?)\s*[x×]\s*$""", RegexOption.IGNORE_CASE).find(trimmed) ?: return null
    return "${match.groupValues[1]}x"
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
    if (items.size < 2 && shared != valuePrefix) return null
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
    val prefix = group?.name?.takeIf { rawName.startsWith("$it/") }
        ?: valuePrefix(option.value)?.takeIf { rawName.startsWith("$it/") }
    val shortLabel = if (prefix == null) rawName else rawName.drop(prefix.length + 1).trim()
    return AgentModelPresentation(
        shortLabel = shortLabel.ifBlank { rawName },
        rate = modelRate(option.name, option.description),
    )
}

/** Cap grid density only for genuinely long labels. */
internal fun requestedModelColumns(
    choices: List<SessionConfigSelectOption>,
    requested: Int,
): Int = requested.takeIf { requested == 1 || choices.none { it.name.length > 18 } } ?: 1

/** Config grids need a title and optional one-line description to stay readable. */
internal fun requestedConfigColumns(
    choices: List<ConfigOptionChoice>,
    requested: Int,
): Int = requested.takeIf {
    it == 1 || choices.none { choice -> choice.label.length > 18 || choice.description.orEmpty().length > 24 }
} ?: 1

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

