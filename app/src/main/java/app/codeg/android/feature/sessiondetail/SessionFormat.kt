package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.SessionConfigKind
import app.codeg.android.core.model.SessionConfigOption
import java.time.Duration
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

/** Absolute completion time `MM-dd HH:mm` (24h, system timezone) for a reply's footer. */
internal fun completedAtLabel(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.US).withZone(zone).format(instant)

/**
 * Compact run duration for the footer's parenthesized span: `42s` under a minute,
 * `4m` under an hour, `1h04m` beyond. Past a minute the seconds are dropped — the
 * footer answers "how long did this reply run" at a glance, and `31m` reads better
 * than `31m04s` (owner-specified shape: `10-21 10:11 （31m）`).
 */
internal fun turnDurationLabel(elapsedMs: Long): String {
    val parts = elapsedParts(elapsedMs)
    return when {
        parts.hours > 0 -> String.format(Locale.US, "%dh%02dm", parts.hours, parts.minutes)
        parts.minutes > 0 -> String.format(Locale.US, "%dm", parts.minutes)
        else -> "${parts.seconds}s"
    }
}

/**
 * The reply footer's third line: `10-21 10:11 （31m）` — absolute finish time to
 * the minute plus how long this reply ran.
 *
 * - Finish time is the server's `completed_at`, else the turn's own [MessageTurn.timestamp]
 *   (always a clock, never the old bare `10-21` relative date). `completed_at` is shown
 *   as reported even when it predates the timestamp (clock skew): it is the server's
 *   own finish marker, and only the derived span treats that ordering as unusable.
 * - Run time is the server's `duration_ms` when present; otherwise a LOCAL ESTIMATE
 *   from the transcript span (first assistant event → finish). The two are not
 *   guaranteed to measure identical work — the fallback only has to be close enough
 *   to answer "how long did this take", and it is a lower bound when intermediate
 *   turns carried no timestamps. A non-positive span (missing data, clock skew)
 *   omits the parenthesized part rather than inventing a number.
 *
 * Folded into one function so the composable stays free of time arithmetic and this
 * shape is unit-tested directly.
 */
internal fun replyFinishedLabel(turn: MessageTurn, zone: ZoneId = ZoneId.systemDefault()): String {
    val finishedAt = turn.completedAt ?: turn.timestamp
    val runMs = turn.durationMs?.takeIf { it > 0 }?.toLong()
        ?: Duration.between(turn.timestamp, finishedAt).toMillis().takeIf { it > 0 }
    return completedAtLabel(finishedAt, zone) + (runMs?.let { " （${turnDurationLabel(it)}）" } ?: "")
}
