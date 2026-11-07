package app.codeg.android.feature.sessiondetail.interactive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Assignment
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.CodeBlock
import app.codeg.android.core.designsystem.diff.DiffView
import app.codeg.android.core.designsystem.markdown.MarkdownContent
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.PermissionOption

/** A tool-permission approval card shown above the composer. Port of iOS `PermissionRequestCard`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PermissionRequestCard(
    parsed: ParsedPermission,
    options: List<PermissionOption>,
    onRespond: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Non-null renders the header expand button — only the plan branch passes
     *  it (a long plan is unreadable under the 300dp strip cap). */
    onExpandFullscreen: (() -> Unit)? = null,
) {
    val colors = CodegTheme.colors
    var submitting by remember(parsed.jsonPreview) { mutableStateOf<String?>(null) }
    // 批v160: the raw tool_call JSON starts as a 2-line summary — the owner reads
    // the question and the options first; the wire payload is noise until asked
    // for (web permission-dialog keeps its raw view behind the same principle).
    var rawExpanded by remember(parsed.jsonPreview) { mutableStateOf(false) }
    val accent = colors.accent

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.bgElevated.copy(alpha = 0.96f))
            .border(0.5.dp, colors.surfaceStroke, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Header.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                if (parsed.isPlan) Icons.AutoMirrored.Rounded.Assignment else Icons.Rounded.GppMaybe,
                contentDescription = null,
                tint = if (parsed.isPlan) accent else colors.danger,
                modifier = Modifier.size(20.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(parsed.title.ifBlank { stringResource(R.string.perm_request_title) }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                Text(
                    if (parsed.isPlan) stringResource(R.string.perm_plan_subtitle) else stringResource(R.string.perm_permission_subtitle),
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                )
            }
            if (!parsed.isPlan) {
                Text(
                    parsed.kind,
                    fontSize = 10.sp,
                    color = colors.textTertiary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.textPrimary.copy(alpha = 0.06f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            onExpandFullscreen?.let { onExpand ->
                IconButton(onClick = onExpand, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Rounded.OpenInFull,
                        contentDescription = stringResource(R.string.interactive_expand_fullscreen),
                        tint = colors.textTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // Body. The structured shapes (command / diff / plan) render directly; the
        // raw-JSON fallback collapses to two lines until expanded. Whatever shows
        // here scrolls under a fixed cap so the options below can never be pushed
        // off-screen.
        val scrolls = rawExpanded || parsed.diffFiles.isNotEmpty() || parsed.planMarkdown != null ||
            parsed.planEntries.size > 4 || parsed.allowedPrompts.size > 4
        Column(
            modifier = if (scrolls) Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()) else Modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!parsed.hasStructuredBody) {
                if (rawExpanded) {
                    CodeBlock(code = parsed.jsonPreview, language = "json")
                    Text(
                        stringResource(R.string.perm_details_collapse),
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        color = accent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { rawExpanded = false }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                } else {
                    RawJsonSummary(parsed.jsonPreview, onExpand = { rawExpanded = true })
                }
            } else {
                PermissionBody(parsed)
            }
        }

        // Option buttons — compact pills in a wrapping row (批v160): the old
        // full-width stack could overflow the bottomBar under the IME and leave
        // exactly one option visible.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (option in options) {
                OptionPill(
                    option = option,
                    submitting = submitting == option.optionId,
                    enabled = submitting == null,
                    onClick = {
                        submitting = option.optionId
                        onRespond(option.optionId)
                    },
                )
            }
        }
    }
}

/** Two-line monospace peek at the raw tool_call JSON, with an expand affordance. */
@Composable
private fun RawJsonSummary(jsonPreview: String, onExpand: () -> Unit) {
    val colors = CodegTheme.colors
    val peek = remember(jsonPreview) {
        val lines = jsonPreview.lines().filter { it.isNotBlank() }
        val joined = lines.take(2).joinToString("\n")
        when {
            joined.isEmpty() -> jsonPreview.take(120)
            lines.size > 2 || jsonPreview.length > 160 -> joined + " …"
            else -> joined
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.codeSurface)
            .border(0.5.dp, colors.hairline, RoundedCornerShape(8.dp))
            .clickable(onClick = onExpand)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            peek,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.5.sp,
            lineHeight = 15.sp,
            color = colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Rounded.ExpandMore,
            contentDescription = stringResource(R.string.perm_details_expand),
            tint = colors.textTertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun PermissionBody(parsed: ParsedPermission) {
    val colors = CodegTheme.colors
    parsed.command?.let { cmd ->
        CodeBlock(code = cmd, language = "bash")
        parsed.cwd?.let { Text("cwd: $it", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = colors.textTertiary) }
    }
    if (parsed.diffFiles.isNotEmpty()) DiffView(parsed.diffFiles)
    parsed.planMarkdown?.let { MarkdownContent(it) }
    if (parsed.planEntries.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            for (entry in parsed.planEntries) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("•", color = colors.textTertiary, fontSize = 13.sp)
                    Text(entry.text, fontSize = 13.sp, color = colors.textPrimary, modifier = Modifier.weight(1f))
                }
            }
        }
    }
    if (parsed.allowedPrompts.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (ap in parsed.allowedPrompts) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                    if (ap.tool.isNotEmpty()) {
                        Text(
                            ap.tool,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(colors.accent.copy(alpha = 0.12f))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                    Text(ap.prompt, fontSize = 12.sp, color = colors.textSecondary, modifier = Modifier.weight(1f))
                }
            }
        }
    }
    parsed.modeTarget?.let { Text("→ $it", fontSize = 12.sp, color = colors.textSecondary) }
    if (parsed.url != null || parsed.query != null || (parsed.prompt != null && parsed.command == null)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            parsed.url?.let { Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.textSecondary) }
            parsed.query?.let { Text(it, fontSize = 12.sp, color = colors.textSecondary) }
            if (parsed.command == null) parsed.prompt?.let { Text(it, fontSize = 12.sp, color = colors.textSecondary) }
        }
    }
}

/** Compact pill option (批v160) — reject keeps the outlined treatment. */
@Composable
private fun OptionPill(option: PermissionOption, submitting: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    val shape = RoundedCornerShape(20.dp)
    if (option.isReject) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            border = BorderStroke(0.5.dp, colors.surfaceStroke),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textPrimary),
        ) {
            if (submitting) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.textPrimary)
            } else {
                Text(option.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    } else {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = colors.onAccent,
                disabledContainerColor = colors.accent.copy(alpha = 0.4f),
                disabledContentColor = colors.onAccent.copy(alpha = 0.8f),
            ),
        ) {
            if (submitting) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.onAccent)
            } else {
                Text(option.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
