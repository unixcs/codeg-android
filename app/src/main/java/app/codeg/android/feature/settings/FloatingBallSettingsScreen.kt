package app.codeg.android.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.feature.floatingball.FloatingBallCatalog

/**
 * Floating-ball gesture binding editor (batch v126; v129 removed the longPress
 * gesture — the 1s hold now moves the ball): all 16 gestures, one row each, tap
 * a row to pick its action (or unbind) from a radio dialog.
 */
@Composable
fun FloatingBallContent(viewModel: SettingsViewModel = hiltViewModel()) {
    val colors = CodegTheme.colors
    val bindings by viewModel.floatBallBindings.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<String?>(null) }

    @Composable
    fun actionLabel(actionId: String?): String = when (actionId) {
        null, "" -> stringResource(R.string.ball_action_none)
        else -> FloatingBallCatalog.ACTIONS.firstOrNull { it.first == actionId }
            ?.let { stringResource(it.second) } ?: stringResource(R.string.ball_action_none)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.ball_bindings_hint),
            fontSize = 12.sp,
            color = colors.textTertiary,
            modifier = Modifier.padding(start = 12.dp),
        )
        listOf(
            stringResource(R.string.ball_bindings_group_taps) to listOf("tap", "doubleTap"),
            stringResource(R.string.ball_bindings_group_swipes) to FloatingBallCatalog.GESTURES.drop(2),
        ).forEach { (groupTitle, gestures) ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(groupTitle, style = MaterialTheme.typography.labelLarge, color = colors.textTertiary, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.bgElevated.copy(alpha = 0.5f)),
                ) {
                    gestures.forEachIndexed { index, gesture ->
                        ListItem(
                            headlineContent = { Text(stringResource(FloatingBallCatalog.gestureLabel(gesture))) },
                            supportingContent = { Text(actionLabel(bindings[gesture])) },
                            trailingContent = {
                                Text(
                                    stringResource(R.string.ball_binding_tap_to_pick),
                                    fontSize = 12.sp,
                                    color = colors.textTertiary,
                                )
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent,
                                headlineColor = colors.textPrimary,
                                supportingColor = colors.accent,
                            ),
                            modifier = Modifier.clickable { picking = gesture },
                        )
                        if (index < gestures.lastIndex) {
                            HorizontalDivider(color = colors.hairline, modifier = Modifier.padding(start = 60.dp))
                        }
                    }
                }
            }
        }
    }

    picking?.let { gesture ->
        val current = bindings[gesture]
        AlertDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = { picking = null }) { Text(stringResource(R.string.common_cancel)) }
            },
            title = { Text(stringResource(FloatingBallCatalog.gestureLabel(gesture))) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    pickRow(null, stringResource(R.string.ball_action_none), current) {
                        viewModel.setBallBinding(gesture, null); picking = null
                    }
                    FloatingBallCatalog.ACTIONS.forEach { (id, labelRes) ->
                        pickRow(id, stringResource(labelRes), current) {
                            viewModel.setBallBinding(gesture, id); picking = null
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun pickRow(id: String?, label: String, current: String?, onPick: () -> Unit) {
    val colors = CodegTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = current == id, onClick = onPick)
        Text(label, color = colors.textPrimary, modifier = Modifier.padding(top = 12.dp))
    }
}
