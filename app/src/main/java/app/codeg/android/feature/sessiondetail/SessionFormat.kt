package app.codeg.android.feature.sessiondetail

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
    tokens >= 1_000_000L -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
    tokens >= 1_000L -> String.format(Locale.US, "%.1fK", tokens / 1_000.0)
    else -> tokens.toString()
}

/** One-decimal percentage, e.g. `64.0%`. */
internal fun formatPercent(percent: Double): String =
    String.format(Locale.US, "%.1f%%", percent.coerceIn(0.0, 100.0))
