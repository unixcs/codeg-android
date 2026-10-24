package app.codeg.android.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.codeg.android.core.designsystem.theme.CodegTheme

/** One action in a [CenteredActionMenu]. */
data class CenteredAction(
    val icon: ImageVector,
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * A long-press action menu rendered as a centred card over a dimming scrim — the
 * thumb-reachable replacement for the corner-anchored [androidx.compose.material3.DropdownMenu]
 * in list rows (batch v121: the row's bottom-left popup forced right-handed users into
 * an awkward reach; centred serves either hand the same way).
 *
 * MUST be composed conditionally by the caller (`if (open) CenteredActionMenu(...)`):
 * unlike DropdownMenu's Popup — which composes nothing while closed — a Dialog always
 * creates a window, and one per visible LazyColumn row would exhaust the window limit.
 * Dismiss works via the native outside-click (`dismissOnClickOutside`) and the scrim's
 * own clickable, whichever lands first.
 */
@Composable
fun CenteredActionMenu(
    actions: List<CenteredAction>,
    onDismiss: () -> Unit,
) {
    val colors = CodegTheme.colors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(onClick = onDismiss, indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = colors.bgElevated,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
                modifier = Modifier.widthIn(min = 200.dp, max = 320.dp),
            ) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    actions.forEach { action ->
                        val tint = if (action.destructive) colors.danger else colors.textPrimary
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onDismiss()
                                    action.onClick()
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                action.icon,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                action.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = tint,
                            )
                        }
                    }
                }
            }
        }
    }
}
