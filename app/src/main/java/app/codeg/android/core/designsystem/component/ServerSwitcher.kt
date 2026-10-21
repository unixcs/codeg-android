package app.codeg.android.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.codeg.android.R
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * The server switcher in a top bar: the selected server's name with a ▾ chevron,
 * opening a dropdown of every saved server + a "manage servers" row. Shared by the
 * Chats tab (title) and the Activity tab (same switch-elsewhere pattern) so both
 * screens switch the app-global selected server — one switch, everywhere consistent.
 */
@Composable
fun ServerSwitcher(
    name: String,
    servers: List<ServerProfile>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onManageServers: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val colors = CodegTheme.colors
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { open = true }
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.padding(0.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            servers.forEach { server ->
                DropdownMenuItem(
                    text = { Text(server.name) },
                    onClick = {
                        open = false
                        if (server.id != selectedId) onSelect(server.id)
                    },
                    trailingIcon = {
                        if (server.id == selectedId) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                tint = colors.accent,
                            )
                        }
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.home_manage_servers)) },
                leadingIcon = { Icon(Icons.Rounded.Dns, contentDescription = null) },
                onClick = {
                    open = false
                    onManageServers()
                },
            )
        }
    }
}
