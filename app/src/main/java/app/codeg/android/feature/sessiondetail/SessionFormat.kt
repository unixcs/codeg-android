package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.SessionConfigKind
import app.codeg.android.core.model.SessionConfigOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Hours/minutes/seconds breakdown of an elapsed span, for the run-status line. */
internal data class ElapsedParts(val hours: Long, val minutes: Long, val seconds: Long)

/** Split [elapsedMs] into display units, clamping negatives (clock skew) to zero. */
internal fun elapsedParts(elapsedMs: Long): ElapsedParts {
    val totalSeconds = elapsedMs.coerceAtLeast(0L) / 1000L
    return ElapsedParts(
        hours = totalSeconds / 3600,
        minutes = (totalSeconds % 3600) / 60,
        seconds = totalSeconds % 60,
    )
}

/** Compact token count, matching the web client: `670.6K`, `2.7M`, `1234`. */
internal fun formatTokens(tokens: Long): String = when {
    tokens >= 1_000_000L -> trimTrailingZero(String.format(Locale.US, "%.1fM", tokens / 1_000_000.0))
    tokens >= 1_000L -> trimTrailingZero(String.format(Locale.US, "%.1fK", tokens / 1_000.0))
    else -> tokens.toString()
}

/** `1.0M` → `1M`, `1.5M` stays `1.5M` (N13: drop a useless trailing `.0`). */
private fun trimTrailingZero(formatted: String): String =
    if (formatted.contains(".0")) formatted.replace(".0", "") else formatted

/** One-decimal percentage, e.g. `64.0%`. */
internal fun formatPercent(percent: Double): String =
    String.format(Locale.US, "%.1f%%", percent.coerceIn(0.0, 100.0))

/**
 * The display label of the session's currently selected model, resolved from the
 * session's config options — `null` when there is nothing honest to show. Mirrors
 * the web client's `isModelConfigOption` + label resolution:
 *
 * - the selector is the option with `id == "model"` or `category == "model"` (ACP
 *   reserves no id; every agent codeg drives uses the id, `category` is the spec signal);
 * - only a `select` kind carries a current value;
 * - the label is the matching option/group entry's display name, falling back to the
 *   raw value id — never fabricated (an agent with no selector shows no model).
 */
internal fun currentModelLabel(options: List<SessionConfigOption>): String? {
    val model = options.firstOrNull { it.id == "model" || it.category == "model" } ?: return null
    val kind: SessionConfigKind = model.kind
    if (kind.type != "select") return null
    val current = kind.currentValue?.takeIf { it.isNotBlank() } ?: return null
    val named = kind.options.firstOrNull { it.value == current }?.name?.takeIf { it.isNotBlank() }
        ?: kind.groups.asSequence()
            .flatMap { g -> g.options.asSequence() }
            .firstOrNull { it.value == current }?.name?.takeIf { it.isNotBlank() }
    return named ?: current
}

/** Absolute completion time `HH:mm` (24h, system timezone) for a reply's footer. */
internal fun completedAtLabel(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("HH:mm", Locale.US).withZone(zone).format(instant)

/**
 * Compact run duration for the footer's parenthesized span: `42s` under a minute,
 * `2m13s` under an hour, `1h04m` beyond (seconds dropped — hour-long replies are
 * navigated by minutes).
 */
internal fun turnDurationLabel(elapsedMs: Long): String {
    val parts = elapsedParts(elapsedMs)
    return when {
        parts.hours > 0 -> String.format(Locale.US, "%dh%02dm", parts.hours, parts.minutes)
        parts.minutes > 0 -> String.format(Locale.US, "%dm%02ds", parts.minutes, parts.seconds)
        else -> "${parts.seconds}s"
    }
}
