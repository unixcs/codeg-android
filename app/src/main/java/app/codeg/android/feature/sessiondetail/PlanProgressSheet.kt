package app.codeg.android.feature.sessiondetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import app.codeg.android.core.model.PlanEntry
import app.codeg.android.feature.sessiondetail.rendering.LivePlanView

/**
 * The running turn's plan / stage card (the web client's AgentPlanOverlay, as a
 * bottom sheet): title + done/total, a progress bar, then the plan checklist
 * itself. Purely [SessionDetailUiState.live]-driven — every `plan_update` event
 * re-renders it, so stages tick over in real time while the turn runs. The entry
 * point (the "计划 x/y" chip in the context row) only exists while a plan is live,
 * so the sheet never opens on a finished turn; historical plans stay in the
 * timeline as inline/tool cards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanProgressSheet(live: LiveTurnState, onDismiss: () -> Unit) {
    val colors = CodegTheme.colors
    val done = live.livePlan.count { it.normalizedStatus == PlanEntry.Status.COMPLETED }
    val total = live.livePlan.size
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.bgElevated,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.Checklist, null, tint = colors.accent, modifier = Modifier.padding(0.dp))
                Text(
                    stringResource(R.string.plan_progress_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "$done/$total",
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                )
            }
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { done.toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accent,
                    trackColor = colors.surfaceStroke,
                )
            }
            // The same checklist the timeline renders inline — status spinners/checks,
            // strikethrough on completed rows, high-priority badges.
            LivePlanView(entries = live.livePlan)
        }
    }
}
