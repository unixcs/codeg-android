package app.codeg.android.feature.sessiondetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * The send-while-running queue, docked between the attachment strip and the composer
 * (the MindFS short-card layout). One dim row per queued draft with three actions at
 * the right edge: inject into the running turn, pull back into the composer, delete.
 * Capped at 96dp so a long queue scrolls instead of shoving the composer off-screen.
 */
@Composable
fun QueuedDraftsBar(
    queue: List<QueuedDraft>,
    onInsert: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (queue.isEmpty()) return
    val colors = CodegTheme.colors
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 96.dp)
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(queue, key = { it.id }) { item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.bgElevated)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = item.text,
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Rounded.Bolt,
                    contentDescription = stringResource(R.string.queue_insert_now),
                    tint = colors.accent,
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onInsert(item.id) }
                        .padding(2.dp),
                )
                Icon(
                    Icons.Rounded.Edit,
                    contentDescription = stringResource(R.string.queue_edit),
                    tint = colors.textSecondary,
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onEdit(item.id) }
                        .padding(2.dp),
                )
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = stringResource(R.string.queue_delete),
                    tint = colors.textTertiary,
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onDelete(item.id) }
                        .padding(2.dp),
                )
            }
        }
    }
}
