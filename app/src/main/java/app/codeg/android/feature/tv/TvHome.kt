package app.codeg.android.feature.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Settings
import app.codeg.android.R
import app.codeg.android.app.AppViewModel

/** Top-level TV destinations. tv-material 1.1.0 has no NavigationRail — the
 *  NavigationDrawer in its Closed state IS the TV left rail (icons when the
 *  content pane has focus, icon+label once the drawer gains focus). */
private enum class TvDestination(val labelRes: Int, val icon: ImageVector) {
    OVERVIEW(R.string.tv_nav_overview, Icons.Rounded.Home),
    SESSIONS(R.string.tv_nav_sessions, Icons.Rounded.Forum),
    INBOX(R.string.tv_nav_inbox, Icons.Rounded.Inbox),
    TASKS(R.string.tv_nav_tasks, Icons.Rounded.Checklist),
    SETTINGS(R.string.tv_nav_settings, Icons.Rounded.Settings),
}

/**
 * TV shell — M1 slice 2. Left navigation drawer + one content pane per
 * destination; BACK steps back to Overview first and only exits the app from
 * there. Panes are placeholders here; the read-only screens land in slices 3–4
 * and the D-pad focus pass closes M1 in slice 5.
 */
@Composable
fun TvHome(appViewModel: AppViewModel) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val destinations = TvDestination.entries
    BackHandler(enabled = selected != 0) { selected = 0 }

    NavigationDrawer(
        modifier = Modifier.padding(TvOverscan),
        drawerState = rememberDrawerState(DrawerValue.Closed),
        drawerContent = { drawerValue ->
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = 12.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(8.dp))
                destinations.forEachIndexed { index, destination ->
                    NavigationDrawerItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        onFocus = { selected = index },
                        leadingContent = { Icon(destination.icon, contentDescription = null) },
                    ) {
                        if (drawerValue == DrawerValue.Open) {
                            Text(stringResource(destination.labelRes))
                        }
                    }
                }
            }
        },
    ) {
        when (destinations[selected]) {
            TvDestination.OVERVIEW -> TvOverviewPane(appViewModel)
            else -> TvPlaceholderPane(destinations[selected])
        }
    }
}

@Composable
private fun TvOverviewPane(appViewModel: AppViewModel) {
    val servers by appViewModel.allServers.collectAsStateWithLifecycle()
    val saved = servers.orEmpty()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Codeg TV", style = MaterialTheme.typography.displaySmall)
            Text(
                stringResource(R.string.tv_shell_servers, saved.size),
                style = MaterialTheme.typography.titleMedium,
            )
            saved.forEach { profile ->
                Text("· " + profile.name, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun TvPlaceholderPane(destination: TvDestination) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(destination.labelRes), style = MaterialTheme.typography.displaySmall)
            Text(
                stringResource(R.string.tv_pane_placeholder),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
