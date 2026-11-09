package app.codeg.android.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.codeg.android.R
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * Near-fullscreen text editor shared by every long-form input (批v154: extracted
 * from the composer's prompt editor so the inbox idea editor and the dispatch
 * form's description box get the same treatment).
 *
 * Edits apply live to the caller's state — closing (back / close / send) lands
 * exactly where the compact field would have. With [onSend] null the editor is a
 * pure editor: only the close affordance shows.
 *
 * IME behaviour (batch v121): the dialog window must NOT react to the keyboard —
 * `ADJUST_NOTHING` stops the window-level pan that used to fight `imePadding()`
 * and make the editor bounce twice as the IME appeared. The single animation
 * source is the `imePadding()` on the Surface. Focus is requested on the field so
 * the keyboard opens onto it (never STATE_VISIBLE without focus — first keystrokes
 * would drop). androidx only re-stamps the dialog's soft-input mode on API < 31,
 * so one set is stable on the main flavor (minSdk 31); the legacy flavor (minSdk
 * 28) keeps the stock resize behaviour. Scrolling lives in an outer
 * `verticalScroll` Box rather than the field's internal scroll: the cursor's
 * bring-into-view requests bubble to that container, so tapping near the bottom
 * keeps the viewport there instead of snapping back to the first line.
 *
 * 批v172 D1: the editor has TWO shapes — fullscreen (default, every open) and a
 * ~72%-height floating card for glancing at the conversation behind it. The
 * header gains a toggle button; the shape is NOT remembered across opens (the
 * user's spec: "默认展开是进入全屏界面") and the IME never drives the shape
 * — collapsing/expanding is manual only, so typing flow is never interrupted.
 */
@Composable
fun FullscreenTextEditorDialog(
    text: String,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Header label, e.g. "编辑描述"; the live char count is appended. */
    label: String,
    /** When non-null a send button is shown in the header. */
    onSend: (() -> Unit)? = null,
    sendEnabled: Boolean = true,
) {
    val colors = CodegTheme.colors
    Dialog(
        onDismissRequest = onDismiss,
        // decorFitsSystemWindows=false lets the content window see the IME insets, so
        // imePadding below actually lifts the field above the keyboard (with the default
        // `true` the dialog pans instead and the prompt tail hides under it).
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // The dialog window must hold still while the IME animates — pan/resize
        // here would double the motion against imePadding(). See the helper.
        holdDialogWindowStill()
        // 批v172 D1: manual fullscreen ⇄ floating-card toggle. Saveable so a
        // rotation doesn't jump shapes mid-edit; opens fullscreen every time.
        var expanded by rememberSaveable { mutableStateOf(true) }
        val scroll = rememberScrollState()
        val focusRequester = remember { FocusRequester() }
        Box(
            Modifier
                .fillMaxSize()
                // IME padding lives on the Box so BOTH shapes lift above the
                // keyboard (the floating card must clear the IME too).
                .imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                color = colors.bg,
                shape = if (expanded) RectangleShape else RoundedCornerShape(24.dp),
                modifier = if (expanded) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .fillMaxHeight(0.72f)
                },
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledTonalIconButton(
                            onClick = onDismiss,
                            // R3-A: 44dp visual on our touch-target line (M3 default is 40).
                            modifier = Modifier.size(44.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = colors.textPrimary.copy(alpha = 0.06f),
                                contentColor = colors.textSecondary,
                            ),
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_close))
                        }
                        Text(
                            text = label + " · " + text.length,
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.textSecondary,
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                        // 批v172 D1: shape toggle — the icon shows what tapping DOES
                        // (fullscreen now → exit; card now → enter).
                        FilledTonalIconButton(
                            onClick = { expanded = !expanded },
                            modifier = Modifier.size(44.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = colors.textPrimary.copy(alpha = 0.06f),
                                contentColor = colors.textSecondary,
                            ),
                        ) {
                            Icon(
                                if (expanded) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                                contentDescription = stringResource(
                                    if (expanded) R.string.compose_edit_collapse else R.string.compose_edit_expand,
                                ),
                            )
                        }
                        if (onSend != null) {
                            FilledIconButton(
                                onClick = onSend,
                                enabled = sendEnabled,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = colors.accent,
                                    contentColor = colors.onAccent,
                                ),
                            ) {
                                Icon(Icons.Rounded.ArrowUpward, contentDescription = stringResource(R.string.compose_send))
                            }
                        }
                    }
                    HorizontalDivider(thickness = Dp.Hairline, color = colors.surfaceStroke)
                    Box(
                        Modifier
                            .weight(1f)
                            .verticalScroll(scroll),
                    ) {
                        BasicTextField(
                            value = text,
                            onValueChange = onTextChange,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = colors.textPrimary,
                                textDirection = TextDirection.Content,
                            ),
                            cursorBrush = SolidColor(colors.accent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                                .focusRequester(focusRequester),
                        )
                    }
                }
            }
        }
        // Focus first, then bring the text tail into view (batch v123): opening on a
        // long draft used to land the viewport at the top while the cursor sat at the
        // end, so the first IME frame scrolled the tail in — the "jerk and bounce" on
        // open. Animate after the first layout pass with the field focused, when the
        // scroll container's height is real.
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            withFrameNanos { }
            scroll.animateScrollTo(scroll.maxValue)
        }
    }
}