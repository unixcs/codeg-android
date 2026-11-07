package app.codeg.android.feature.sessiondetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.designsystem.component.sheetContentScrollLock
import app.codeg.android.core.model.AgentMention

/**
 * The @-mention label escape — the web composer's `escapeMarkdownText`
 * convention, the same rule the session reference badge applies
 * (`SessionRow.referenceBadge`): fold newline runs to spaces, backslash every
 * inline-significant ASCII punctuation character. A crafted agent name can then
 * never inject Markdown structure or break out of the link token.
 */
private fun escapeMentionLabel(label: String): String =
    label.replace(Regex("\\s*[\\r\\n]+\\s*"), " ").replace(Regex("[\\\\`*_~\\[\\]()<>]")) { "\\$it" }

/**
 * The @ contract (批v165 M-A): a native Markdown link
 * `[@名](codeg://agent/<slug>)`, shape-identical to what the desktop composer
 * emits. The server's `prepare_agent_bound_prompt` parses this token out of the
 * prompt text and builds the versioned routing frame itself — the app never
 * sees, parses, or generates the frame (调研规划 §3.1, the single coupling face).
 */
internal fun agentMentionMarkdown(agent: AgentMention): String {
    val label = escapeMentionLabel(agent.name.ifBlank { agent.slug })
    return "[$label](codeg://agent/${agent.slug})"
}

/**
 * 批v165 @智能体 M-A: the composer's agent picker. One trigger (the composer's @
 * button) opens this sheet; the list is the SERVER's live agent table
 * (`acp_list_agents` via [SessionDetailViewModel.loadMentionAgents], enabled
 * rows only), so a server that adds an agent needs zero app changes. Search
 * filters locally — the table is tens of rows, not thousands.
 *
 * Picking inserts [agentMentionMarkdown] at the END of the draft (the composer
 * state is a plain String until M-B's TextFieldValue chip work — there is no
 * cursor position to honor yet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentMentionSheet(
    viewModel: SessionDetailViewModel,
    onPick: (AgentMention) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CodegTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    // Every open is a fresh probe: the agent table is the server's config, not
    // app state — a stale cache would offer agents the server no longer has.
    LaunchedEffect(Unit) { viewModel.loadMentionAgents() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = colors.bgElevated) {
        Column(
            Modifier.fillMaxWidth().sheetContentScrollLock().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.mention_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
            )
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.mention_sheet_search), color = colors.textTertiary) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.textSecondary) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.codeSurface,
                    unfocusedContainerColor = colors.codeSurface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = colors.accent,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            when {
                ui.mentionLoadFailed -> Column(
                    Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.mention_sheet_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                    )
                    TextButton(onClick = { viewModel.loadMentionAgents() }) {
                        Text(stringResource(R.string.mention_sheet_retry), color = colors.accent)
                    }
                }
                ui.mentionAgents.isEmpty() -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.mention_sheet_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                    )
                }
                else -> {
                    val q = query.trim()
                    val visible = ui.mentionAgents.filter {
                        q.isEmpty() || it.name.contains(q, ignoreCase = true) ||
                            it.slug.contains(q, ignoreCase = true) ||
                            it.description.contains(q, ignoreCase = true)
                    }
                    if (visible.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.mention_sheet_no_match, q),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary,
                            )
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).sheetContentScrollLock(),
                        contentPadding = PaddingValues(bottom = 8.dp),
                    ) {
                        items(visible, key = { it.slug }) { agent ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(agent) }
                                    .padding(horizontal = 4.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        agent.name.ifBlank { agent.slug },
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.textPrimary,
                                    )
                                    if (agent.description.isNotBlank()) {
                                        Text(
                                            agent.description,
                                            fontSize = 12.sp,
                                            color = colors.textSecondary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                if (!agent.available) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = colors.textPrimary.copy(alpha = 0.06f),
                                    ) {
                                        Text(
                                            stringResource(R.string.mention_sheet_unavailable),
                                            fontSize = 10.sp,
                                            color = colors.textTertiary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(thickness = androidx.compose.ui.unit.Dp.Hairline, color = colors.surfaceStroke)
                        }
                    }
                }
            }
        }
    }
}
