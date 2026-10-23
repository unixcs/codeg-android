package app.codeg.android.feature.sessiondetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * The context-window popup: usage gauge, cache hit rate, and the session's cumulative
 * token counters. Mirrors the web client's context card. Every value renders `—` when the
 * agent hasn't reported it yet, so the card is never misleadingly empty or zeroed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextUsageSheet(ui: SessionDetailUiState, onDismiss: () -> Unit) {
    val colors = CodegTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.bgElevated,
    ) {
        val stats = ui.sessionStats
        val usage = stats?.totalUsage
        val used = stats?.contextWindowUsedTokens
        val max = stats?.contextWindowMaxTokens
        val percent = stats?.contextWindowUsagePercent
        val cacheHit = usage?.let {
            val totalInput = it.inputTokens.toLong() + it.cacheReadInputTokens
            if (totalInput > 0) it.cacheReadInputTokens.toDouble() / totalInput * 100.0 else null
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.context_usage_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    percent?.let(::formatPercent) ?: "—",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (used != null && max != null) {
                    Text(
                        stringResource(
                            R.string.context_usage_used_max,
                            formatTokens(used.toLong()),
                            formatTokens(max.toLong()),
                        ),
                        fontSize = 13.sp,
                        color = colors.textSecondary,
                    )
                }
            }
            LinearProgressIndicator(
                progress = { ((percent ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = colors.accent,
                trackColor = colors.surfaceStroke,
            )
            StatLine(stringResource(R.string.context_usage_cache_hit), cacheHit?.let(::formatPercent) ?: "—")
            HorizontalDivider(thickness = 0.5.dp, color = colors.hairline)
            Text(
                stringResource(R.string.context_usage_tokens),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textSecondary,
            )
            StatLine(stringResource(R.string.context_usage_input), usage?.inputTokens?.let { formatTokens(it.toLong()) } ?: "—")
            StatLine(stringResource(R.string.context_usage_output), usage?.outputTokens?.let { formatTokens(it.toLong()) } ?: "—")
            StatLine(stringResource(R.string.context_usage_cache_read), usage?.cacheReadInputTokens?.let { formatTokens(it.toLong()) } ?: "—")
            StatLine(stringResource(R.string.context_usage_cache_write), usage?.cacheCreationInputTokens?.let { formatTokens(it.toLong()) } ?: "—")
            // `total_tokens` is the server's own cumulative figure; fall back to the
            // per-turn usage sum when an older server doesn't report it.
            val totalTokens = stats?.totalTokens?.toLong() ?: usage?.total?.toLong()
            StatLine(stringResource(R.string.context_usage_total), totalTokens?.let(::formatTokens) ?: "—")
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    val colors = CodegTheme.colors
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp, color = colors.textSecondary)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary)
    }
}
