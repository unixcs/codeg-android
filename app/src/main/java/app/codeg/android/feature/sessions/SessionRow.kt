package app.codeg.android.feature.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.codeg.android.R
import app.codeg.android.core.common.RelativeTime
import app.codeg.android.core.designsystem.component.AgentAvatar
import app.codeg.android.core.designsystem.component.LivePulse
import app.codeg.android.core.designsystem.component.statusColor
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.ConversationSummary
import androidx.compose.ui.res.stringResource

/**
 * One conversation row in the Chats / Activity / Search lists: a single
 * borderless line. The agent's brand avatar (with a status-tinted dot) anchors
 * the left, the title fills the middle, and the trailing edge shows a live pulse
 * while running or a compact relative time otherwise. Long-press opens a Pin/
 * Unpin menu plus — when [onHandoff] is wired — a handoff item that opens a new
 * task seeded with a `[title](codeg://session/<id>)` reference, and a copy-
 * reference fallback. The Compose analogue of the iOS row context menu.
 *
 * Faithful port of the iOS `SessionRow`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionRow(
    conversation: ConversationSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    folderName: String? = null,
    selected: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    onHandoff: ((reference: String) -> Unit)? = null,
    serverName: String? = null,
) {
    val colors = CodegTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    val hasMenu = onTogglePin != null || onHandoff != null
    val clipboard = LocalClipboardManager.current
    // The serialized session reference — the web composer's badge syntax
    // (`[title](codeg://session/<numeric id>)`; same `#id` fallback for a blank
    // title). The agent resolves the id via `get_session_info`. The label is
    // escaped exactly like the web's `escapeMarkdownText` (backslash before every
    // inline-significant ASCII punctuation, newline runs folded to spaces) so a
    // crafted title can't inject Markdown structure or break the link token.
    fun referenceBadge(): String {
        val label = conversation.trimmedTitle?.takeIf { it.isNotBlank() } ?: "#${conversation.id}"
        val safe = label.replace(Regex("\\s*[\\r\\n]+\\s*"), " ").replace(Regex("[\\\\`*_~\\[\\]()<>]")) { "\\$it" }
        return "[$safe](codeg://session/${conversation.id})"
    }

    Box {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .then(
                    if (selected) Modifier.background(colors.accent.copy(alpha = 0.18f)) else Modifier,
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (hasMenu) {
                        { menuOpen = true }
                    } else {
                        null
                    },
                )
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box {
                AgentAvatar(conversation.agentType, size = 26.dp)
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 2.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor(conversation.status))
                        .border(1.5.dp, colors.bg, CircleShape),
                )
            }

            Text(
                text = conversation.trimmedTitle ?: stringResource(R.string.session_untitled),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            if (serverName != null) {
                Text(
                    text = serverName,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 72.dp),
                )
            }

            if (folderName != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.widthIn(max = 108.dp),
                ) {
                    Icon(
                        Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(11.dp),
                    )
                    Text(
                        text = folderName,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (conversation.status.isLive) {
                LivePulse()
            } else {
                Text(
                    text = RelativeTime.compact(conversation.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                )
            }
        }

        if (hasMenu) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (onTogglePin != null) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (conversation.isPinned) {
                                    stringResource(R.string.session_unpin)
                                } else {
                                    stringResource(R.string.session_pin)
                                },
                            )
                        },
                        leadingIcon = { Icon(Icons.Rounded.PushPin, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onTogglePin()
                        },
                    )
                }
                if (onHandoff != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.session_handoff)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.CallMade, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onHandoff(referenceBadge())
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.session_copy_reference)) },
                        leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            clipboard.setText(AnnotatedString(referenceBadge()))
                        },
                    )
                }
            }
        }
    }
}
