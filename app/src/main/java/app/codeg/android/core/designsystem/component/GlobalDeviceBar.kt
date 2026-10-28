package app.codeg.android.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.codeg.android.R
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * The app-global device bar, pinned above every tab's content: one chip per saved
 * server plus an aggregate "All" chip, and a trailing manage-servers shortcut.
 * Selecting a device re-points the whole app (all tabs refetch for that server);
 * the aggregate chip is hidden outright on tabs that don't support it (MainShell
 * passes [showAll] — batch v132: Activity AND Chats, where it opens the project
 * filter sheet), so it can never promise a view the current tab can't show.
 *
 * Rendered by [MainShell] only when the bottom bar is shown (tab routes) and more
 * than one server exists — single-device setups get nothing to switch.
 */
data class DeviceChipColor(val label: String, val color: Color)

/** One stable accent per server name — the same device keeps the same dot colour. */
@Composable
fun deviceBadgeColor(name: String): Color {
    val colors = CodegTheme.colors
    val palette = remember(colors.isDark) {
        listOf(
            Color(0xFF4C8DFF), Color(0xFF34B27B), Color(0xFFE0A63A),
            Color(0xFFE0666B), Color(0xFF9B7BE8), Color(0xFF2FB5C4),
        )
    }
    return palette[kotlin.math.abs(name.hashCode()) % palette.size]
}

@Composable
fun GlobalDeviceBar(
    servers: List<ServerProfile>,
    selectedId: String?,
    allSelected: Boolean,
    showAll: Boolean,
    /** Active project name shown inside the All chip; null collapses the slot
     *  so the chip's rhythm is unchanged when no filter is active (review #18). */
    allSubtitle: String? = null,
    onSelectServer: (String) -> Unit,
    onSelectAll: () -> Unit,
    onManageServers: () -> Unit,
) {
    val colors = CodegTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.bg.copy(alpha = 0.96f))
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (showAll) {
                item(key = "chip-all") {
                    DeviceChip(
                        selected = allSelected,
                        leading = { iconTint -> Icon(Icons.Rounded.Dns, null, tint = iconTint, modifier = Modifier.size(14.dp)) },
                        onClick = onSelectAll,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.activity_all))
                            // Collapsed slot when no project filter is active.
                            if (allSubtitle != null) {
                                Text(
                                    text = "· $allSubtitle",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (allSelected) colors.onAccent.copy(alpha = 0.85f) else colors.textTertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 88.dp),
                                )
                            }
                        }
                    }
                }
            }
            items(servers.size, key = { servers[it].id }) { i ->
                val server = servers[i]
                val selected = !allSelected && server.id == selectedId
                DeviceChip(
                    selected = selected,
                    leading = { iconTint ->
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(deviceBadgeColor(server.name)),
                        )
                    },
                    onClick = { onSelectServer(server.id) },
                ) {
                    Text(
                        text = server.name,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) colors.accent else colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item(key = "chip-manage") {
                IconButton(onClick = onManageServers, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Rounded.Settings,
                        contentDescription = stringResource(R.string.devicebar_manage),
                        tint = colors.textTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        HorizontalHairline()
    }
}

@Composable
private fun DeviceChip(
    selected: Boolean,
    leading: @Composable (Color) -> Unit,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = CodegTheme.colors
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) colors.accentDim else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) colors.accent.copy(alpha = 0.5f) else colors.surfaceStroke,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leading(if (selected) colors.accent else colors.textTertiary)
        content()
    }
}

@Composable
private fun HorizontalHairline() {
    androidx.compose.material3.HorizontalDivider(
        thickness = androidx.compose.ui.unit.Dp.Hairline,
        color = CodegTheme.colors.surfaceStroke,
    )
}
