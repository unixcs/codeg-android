package app.codeg.android.feature.sessiondetail.timeline

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.common.RelativeTime
import app.codeg.android.core.designsystem.markdown.MarkdownContent
import app.codeg.android.core.designsystem.markdown.SingleBlockView
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.feature.sessiondetail.completedAtLabel
import app.codeg.android.feature.sessiondetail.formatTokens
import app.codeg.android.feature.sessiondetail.rendering.InlineImage
import app.codeg.android.feature.sessiondetail.rendering.LivePlanView
import app.codeg.android.feature.sessiondetail.rendering.ReasoningBlock
import app.codeg.android.feature.sessiondetail.rendering.ToolCallCard
import app.codeg.android.feature.sessiondetail.rendering.ToolGroupCard
import app.codeg.android.feature.sessiondetail.turnDurationLabel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val SuccessGreen = Color(0xFF85D18F)

/** Scrolls the timeline to a node id — used by the footer's jump-to-question button. */
val LocalTimelineScroll = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * The session-level context-window occupancy the reply footers show (the MindFS
 * `28.7K(55%)` line). `null` fields mean the agent never reported that figure —
 * footers omit rather than fabricate. Provided by SessionDetailScreen from the
 * live `sessionStats`.
 */
data class SessionContextUsage(
    val usedTokens: Long? = null,
    val maxTokens: Long? = null,
    val percent: Double? = null,
)

val LocalSessionContext = staticCompositionLocalOf { SessionContextUsage() }

/**
 * Renders a node's content to the right of the rail. A pure dispatcher that reuses
 * the existing render leaves verbatim — the timeline is only a new shell. Port of
 * the iOS `NodeBody`.
 */
@Composable
fun NodeBody(
    node: TimelineNode,
    modifier: Modifier = Modifier,
    onForkFromTurn: ((String) -> Unit)? = null,
) {
    when (val c = node.content) {
        is NodeContent.User -> UserNodeBody(c.turn, modifier)
        is NodeContent.System -> SystemNodeBody(c.turn, modifier)
        is NodeContent.AssistantBlock ->
            if (c.streaming) {
                Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SingleBlockView(c.block)
                    BlinkingCaret()
                }
            } else {
                SingleBlockView(c.block, modifier)
            }
        is NodeContent.Reasoning -> ReasoningBlock(c.text, modifier, streaming = c.streaming)
        is NodeContent.Tool -> ToolCallCard(c.vm, modifier)
        is NodeContent.ToolGroup -> ToolGroupCard(c.items, modifier, streaming = c.streaming)
        is NodeContent.Image -> InlineImage(c.image, c.caption, modifier)
        is NodeContent.Compaction -> ContextCompactionDivider(c.before, c.after, c.running, modifier)
        is NodeContent.Footer -> TurnFooter(
            c.turn,
            c.questionId,
            onFork = onForkFromTurn?.let { cb -> c.forkTurnId?.let { id -> { cb(id) } } },
            modifier = modifier,
        )
        is NodeContent.Plan -> LivePlanView(c.entries, modifier)
        is NodeContent.Thinking -> ThinkingShimmer(modifier)
        is NodeContent.Error -> InlineTurnError(c.message, modifier)
        is NodeContent.Unsupported -> Text(
            stringResource(R.string.timeline_unsupported_block, c.type),
            fontSize = 11.sp,
            color = CodegTheme.colors.textTertiary,
            modifier = modifier,
        )
    }
}

/**
 * The user's prompt — a left-aligned faint-accent card filling the content column,
 * with a copy affordance beneath it (② in batch-v106: sent prompts were previously
 * uncopyable — the card is not text-selectable and had no button). Same CopyButton
 * the reply footers use; joins every text block like TurnFooter does.
 */
@Composable
private fun UserNodeBody(turn: MessageTurn, modifier: Modifier) {
    val colors = CodegTheme.colors
    val copyText = remember(turn) {
        turn.blocks
            .mapNotNull { (it as? ContentBlock.Text)?.text?.takeIf { t -> t.isNotBlank() } }
            .joinToString("\n\n")
    }
    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.accent.copy(alpha = 0.12f))
                .border(0.5.dp, colors.accent.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (block in turn.blocks) {
                when (block) {
                    is ContentBlock.Text -> if (block.text.isNotBlank()) MarkdownContent(block.text)
                    is ContentBlock.Image -> InlineImage(block.image, null)
                    is ContentBlock.ImageGeneration ->
                        if (block.image != null) InlineImage(block.image, block.revisedPrompt)
                        else if (!block.revisedPrompt.isNullOrBlank()) MarkdownContent(block.revisedPrompt)
                    else -> {}
                }
            }
        }
        if (copyText.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                CopyButton(copyText)
            }
        }
    }
}

/** A system note — a dim, card-less log line. */
@Composable
private fun SystemNodeBody(turn: MessageTurn, modifier: Modifier) {
    val text = remember(turn) { systemText(turn) }
    Text(
        text,
        fontSize = 12.sp,
        color = CodegTheme.colors.textTertiary,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Per-reply action buttons + metadata block, the MindFS footer shape: actions on
 * the left, a three-line meta column on the right (model / context occupancy /
 * `MM-dd HH:mm(duration)`). Long model names wrap instead of ellipsizing the
 * timestamp away — the whole reason this is a column, not one cramped row.
 */
@Composable
private fun TurnFooter(
    turn: MessageTurn,
    questionId: String?,
    modifier: Modifier,
    onFork: (() -> Unit)? = null,
) {
    val colors = CodegTheme.colors
    // Copy text joins every block → memoize it. Meta stays live so the clock
    // ("02:45") keeps honest rather than freezing at first composition.
    val copyText = remember(turn) {
        turn.blocks
            .mapNotNull { (it as? ContentBlock.Text)?.text?.takeIf { t -> t.isNotBlank() } }
            .joinToString("\n\n")
    }
    // Line 3: `10-21 02:45(4m)` — absolute finish time with the run duration in
    // parens. No completedAt → fall back to the relative compact time rather than
    // inventing a wall-clock value; duration only when the server sent one.
    val time = buildList {
        val at = turn.completedAt
        if (at != null) {
            val clock = completedAtLabel(at)
            val duration = turn.durationMs?.takeIf { it > 0 }?.let { "(${turnDurationLabel(it.toLong())})" } ?: ""
            add(clock + duration)
        } else {
            add(RelativeTime.compact(turn.timestamp))
        }
    }.joinToString(" · ")

    // Line 2: the session's live context-window occupancy (`28.7K(55%)`), from the
    // server's `usage_update` reporting. Without a window reading, fall back to
    // this reply's own token total — still honest, just a narrower fact. With
    // neither, the line vanishes rather than showing a fabricated figure.
    val context: String = run {
        val session = LocalSessionContext.current
        when {
            session.percent != null || (session.usedTokens != null && session.maxTokens != null) -> {
                val used = session.usedTokens?.let { formatTokens(it) }.orEmpty()
                val pct = session.percent?.let { "${it.roundToInt()}%" }.orEmpty()
                (used + pct).trim()
            }
            else -> turn.usage?.takeIf { it.total > 0 }?.let { formatTokens(it.total.toLong()) }.orEmpty()
        }
    }

    Row(
        modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (copyText.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CopyButton(copyText)
                if (onFork != null) ForkButton(onFork)
                if (questionId != null) JumpToQuestionButton(questionId)
            }
        } else if (questionId != null) {
            JumpToQuestionButton(questionId)
        }
        Spacer(Modifier.weight(1f))
        Column(Modifier.weight(2.2f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            turn.model?.takeIf { it.isNotBlank() }?.let {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = colors.textTertiary, maxLines = 2)
            }
            if (context.isNotEmpty()) {
                Text(context, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = colors.textTertiary, maxLines = 1)
            }
            Text(time, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = colors.textTertiary, maxLines = 1)
        }
    }
}

@Composable
private fun CopyButton(text: String) {
    val colors = CodegTheme.colors
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { clipboard.setText(AnnotatedString(text)); copied = true }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Crossfade(copied, label = "copy-icon") { c ->
            Icon(
                if (c) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                contentDescription = null,
                tint = if (c) SuccessGreen else colors.textTertiary,
                modifier = Modifier.size(13.dp),
            )
        }
        Text(if (copied) stringResource(R.string.timeline_copied) else stringResource(R.string.timeline_copy), fontSize = 11.sp, color = colors.textTertiary)
    }
}

@Composable
private fun ForkButton(onFork: () -> Unit) {
    val colors = CodegTheme.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onFork)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Rounded.CallSplit,
            // The label below already reads "Fork from here"; a description here
            // would make TalkBack announce it twice.
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(13.dp),
        )
        Text(stringResource(R.string.fork_from_here), fontSize = 11.sp, color = colors.textTertiary)
    }
}

@Composable
private fun JumpToQuestionButton(questionId: String) {
    val scroll = LocalTimelineScroll.current
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { scroll(questionId) }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            Icons.Rounded.ArrowUpward,
            contentDescription = stringResource(R.string.timeline_scroll_to_question),
            tint = CodegTheme.colors.textTertiary,
            modifier = Modifier.size(13.dp),
        )
        Text(stringResource(R.string.timeline_question), fontSize = 11.sp, color = CodegTheme.colors.textTertiary)
    }
}

/** Three pulsing dots shown before the agent's first token. Port of iOS `ThinkingShimmer`. */
@Composable
fun ThinkingShimmer(modifier: Modifier = Modifier) {
    val colors = CodegTheme.colors
    val transition = rememberInfiniteTransition(label = "thinking")
    Row(
        modifier.padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0..2) {
            val a by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse, StartOffset(i * 180)),
                label = "dot-$i",
            )
            Spacer(
                Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colors.textTertiary.copy(alpha = a)),
            )
        }
    }
}

/** A blinking text cursor trailing streamed prose. */
@Composable
private fun BlinkingCaret() {
    val transition = rememberInfiniteTransition(label = "caret")
    val a by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(530), RepeatMode.Reverse),
        label = "caret-alpha",
    )
    Text("▌", fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = CodegTheme.colors.accent, modifier = Modifier.alpha(a))
}

/**
 * The context-compaction boundary: a hairline running the content width with a small
 * archive glyph + label centred on it. Deliberately chrome-less (no card, no border) —
 * it marks "the conversation's context was compacted here", it is not something the
 * agent *did*.
 *
 * Grok stamps the before/after token counts on its compaction; codex sends none, and a
 * no-op delta (before == after) would read as a bug, so both fall back to the plain
 * label.
 */
@Composable
fun ContextCompactionDivider(before: Int?, after: Int?, running: Boolean, modifier: Modifier = Modifier) {
    val colors = CodegTheme.colors
    val label = when {
        running -> stringResource(R.string.compaction_running)
        before != null && after != null && before != after ->
            stringResource(R.string.compaction_done_tokens, groupDigits(before), groupDigits(after))
        else -> stringResource(R.string.compaction_done)
    }
    Row(
        modifier.fillMaxWidth().padding(vertical = 2.dp).alpha(if (running) 0.65f else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HorizontalDivider(Modifier.weight(1f), color = colors.hairline)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(Icons.Rounded.Archive, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(13.dp))
            Text(label, fontSize = 11.sp, color = colors.textTertiary)
        }
        HorizontalDivider(Modifier.weight(1f), color = colors.hairline)
    }
}

/** Thousands separators for the token counts, in the user's locale. */
private fun groupDigits(value: Int): String = String.format(java.util.Locale.getDefault(), "%,d", value)

/** A danger banner for a turn-tail error / cancellation. Port of iOS `InlineTurnError`. */
@Composable
fun InlineTurnError(message: String, modifier: Modifier = Modifier) {
    val colors = CodegTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.danger.copy(alpha = 0.10f))
            .border(0.5.dp, colors.danger.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.WarningAmber, null, tint = colors.danger, modifier = Modifier.size(14.dp))
        Text(message, fontSize = 12.sp, color = colors.danger, modifier = Modifier.weight(1f))
    }
}
