package app.codeg.android.feature.sessiondetail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.AvailableCommandInfo

/**
 * The prompt composer pinned to the bottom of the session detail screen. Two-row
 * ChatGPT-style layout: the text field owns the FULL width in the top row (no
 * side buttons stealing horizontal space), and every action sits in one bottom
 * row — insert, attach on the left; expand (when the draft is long), send / stop
 * on the right. The send button morphs between Send and Stop with a Material
 * scale-and-fade transition and an accent↔danger colour tween.
 *
 * [contextBar] renders above the field (the session's context chips — device /
 * folder / agent — for a new-task draft); it shares the composer's background so
 * the whole bottom block reads as one surface. [statusBar] sits above it (the
 * "generating + elapsed" run line). Typing a slash prefix opens the agent's
 * [commandSuggestions] list above the field; picking one inserts it.
 */
@Composable
fun ComposeBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isInFlight: Boolean,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    onPlus: (() -> Unit)? = null,
    onAttach: (() -> Unit)? = null,
    onTocClick: (() -> Unit)? = null,
    onExpandClick: (() -> Unit)? = null,
    canSendOverride: Boolean = false,
    contextBar: (@Composable () -> Unit)? = null,
    statusBar: (@Composable () -> Unit)? = null,
    commandSuggestions: List<AvailableCommandInfo> = emptyList(),
    onPickCommand: ((AvailableCommandInfo) -> Unit)? = null,
) {
    val colors = CodegTheme.colors
    val canSend = (text.isNotBlank() || canSendOverride) && !isInFlight
    val sendContainer by animateColorAsState(
        targetValue = if (isInFlight) colors.danger else colors.accent,
        animationSpec = tween(220),
        label = "send-container",
    )
    val showExpand = text.length > 120 || text.count { it == '\n' } >= 2
    // Only a bare slash prefix is a command query: "/compact" matches, "/compact now"
    // does not (the command was already picked and the user is typing arguments).
    // Leading whitespace doesn't disqualify — a draft that picked up an indent (or an
    // autocomplete space) still opens the palette; picking a command replaces the
    // whole text anyway.
    val slashQuery = text.trimStart().takeIf {
        it.startsWith("/") && it.none { c -> c == ' ' || c == '\n' }
    }
    val matches = if (slashQuery == null || onPickCommand == null) {
        emptyList()
    } else {
        val query = slashQuery.removePrefix("/")
        commandSuggestions
            .filter { it.name.removePrefix("/").startsWith(query, ignoreCase = true) }
            .take(6)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // A hairline setting the composer off from the timeline above it — the same
        // separator the bottom navigation bar uses, so every bottom-chrome surface is
        // delineated from its content the same way.
        HorizontalDivider(thickness = Dp.Hairline, color = colors.surfaceStroke)
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.bgElevated.copy(alpha = 0.92f))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Run status (generating + elapsed) — the web client's above-composer line.
            if (statusBar != null) statusBar()

            // Context chips (device / folder / agent) — visible without tapping anything.
            if (contextBar != null) contextBar()

            // Slash-command palette, opened by typing "/" (filtered as the user types).
            if (matches.isNotEmpty()) {
                CommandPalette(matches, onPick = onPickCommand!!)
            }

            // The field: full width, nothing flanking it.
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .background(colors.codeSurface, RoundedCornerShape(20.dp))
                    .border(0.5.dp, colors.surfaceStroke, RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (text.isEmpty()) {
                    Text(
                        stringResource(R.string.compose_message_placeholder),
                        color = colors.textTertiary,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accent),
                    maxLines = 6,
                    // Reserve a right gutter so text never flows under the overlaid TOC
                    // button — no dimmed line tails, no blocked cursor taps at full width.
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = if (onTocClick != null) 30.dp else 0.dp),
                )
                if (onTocClick != null) {
                    FilledIconButton(
                        onClick = onTocClick,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 1.dp, end = 1.dp)
                            .size(26.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = colors.textPrimary.copy(alpha = 0.08f),
                            contentColor = colors.textSecondary,
                        ),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.FormatListBulleted,
                            contentDescription = stringResource(R.string.session_toc_open),
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }

            // One action row under the field: + / 🖼 left, expand + send right.
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (onPlus != null) {
                    ComposerAction(Icons.Rounded.Add, stringResource(R.string.compose_insert), onPlus)
                }
                if (onAttach != null) {
                    ComposerAction(Icons.Rounded.Image, stringResource(R.string.compose_attach_image), onAttach)
                }
                Box(Modifier.weight(1f))
                // The expand affordance moved INTO the action row (Telegram's compact
                // pill was a separate band below the field; here it costs no extra
                // vertical space and only appears once the draft is long).
                if (showExpand && onExpandClick != null) {
                    Surface(
                        onClick = { onExpandClick?.invoke() },
                        shape = RoundedCornerShape(14.dp),
                        color = colors.textPrimary.copy(alpha = 0.08f),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Rounded.OpenInFull,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = colors.textSecondary,
                            )
                            Text(
                                stringResource(R.string.compose_expand),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary,
                            )
                        }
                    }
                }
                FilledIconButton(
                    onClick = { if (isInFlight) onStop() else onSend() },
                    enabled = canSend || isInFlight,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = sendContainer,
                        contentColor = colors.onAccent,
                        disabledContainerColor = colors.accent.copy(alpha = 0.4f),
                        disabledContentColor = colors.onAccent.copy(alpha = 0.7f),
                    ),
                ) {
                    AnimatedContent(
                        targetState = isInFlight,
                        transitionSpec = {
                            (fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.6f)) togetherWith
                                (fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.6f))
                        },
                        label = "send-stop-icon",
                    ) { inFlight ->
                        Icon(
                            imageVector = if (inFlight) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                            contentDescription = if (inFlight) stringResource(R.string.compose_stop) else stringResource(R.string.compose_send),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The slash-command palette: the agent's advertised commands, filtered by the typed prefix. */
@Composable
private fun CommandPalette(
    commands: List<AvailableCommandInfo>,
    onPick: (AvailableCommandInfo) -> Unit,
) {
    val colors = CodegTheme.colors
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.codeSurface,
        border = BorderStroke(0.5.dp, colors.surfaceStroke),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
            commands.forEach { command ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(command) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "/" + command.name.removePrefix("/"),
                            fontSize = 13.sp,
                            color = colors.textPrimary,
                        )
                        command.description?.takeIf { it.isNotBlank() }?.let { description ->
                            Text(
                                description,
                                fontSize = 11.sp,
                                color = colors.textSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    command.inputHint?.takeIf { it.isNotBlank() }?.let { hint ->
                        Text(
                            hint,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.textTertiary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** A small round action button in the composer's bottom row (insert / attach). */
@Composable
private fun ComposerAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    FilledIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = colors.textPrimary.copy(alpha = 0.06f),
            contentColor = colors.textSecondary,
        ),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
    }
}
