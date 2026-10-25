package app.codeg.android.feature.main

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavGraph.Companion.findStartDestination
import kotlin.math.roundToInt
import app.codeg.android.R
import app.codeg.android.app.AppViewModel
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.designsystem.component.GlobalDeviceBar
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.feature.activity.ActivityScreen
import app.codeg.android.feature.projects.ProjectDetailScreen
import app.codeg.android.feature.search.SearchScreen
import app.codeg.android.feature.projects.ProjectListScreen
import app.codeg.android.feature.server.ServerEditorScreen
import app.codeg.android.feature.settings.SettingsScreen
import app.codeg.android.feature.server.ServerListScreen
import app.codeg.android.feature.sessiondetail.SessionDetailScreen
import app.codeg.android.feature.sessions.SessionListScreen

/**
 * Bottom-navigation destinations. Each carries an outlined icon (unselected) and
 * a filled icon (selected) — the Material 3 / GitHub / ChatGPT convention where
 * the active tab fills in.
 */
enum class HomeTab(
    val route: String,
    @StringRes val label: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    CHATS("chats", R.string.tab_chats, Icons.Outlined.Forum, Icons.Rounded.Forum),
    FOLDERS("folders", R.string.tab_folders, Icons.Outlined.FolderOpen, Icons.Rounded.FolderOpen),
    ACTIVITY("activity", R.string.tab_activity, Icons.Outlined.Bolt, Icons.Rounded.Bolt),
    SEARCH("search", R.string.tab_search, Icons.Outlined.Search, Icons.Rounded.Search),
    SETTINGS("settings", R.string.tab_settings, Icons.Outlined.Settings, Icons.Rounded.Settings);

    companion object {
        /** Tabs in the user's saved order; unsaved/unknown routes keep enum order. */
        fun orderedBy(savedOrder: List<String>): List<HomeTab> {
            val known = savedOrder.mapNotNull { route -> entries.firstOrNull { it.route == route } }
            val rest = entries.filterNot { known.contains(it) }
            return known + rest
        }

        /** The tab matching a saved start route, falling back to [CHATS]. */
        fun byRoute(route: String?): HomeTab = entries.firstOrNull { it.route == route } ?: CHATS
    }
}

private const val ROUTE_SERVERS = "servers"
private const val ROUTE_EDITOR = "editor"
private const val ROUTE_NEW_TASK = "conversation_new"

/** Shape of a tab's device sequence for the horizontal device swipe (batch v123). */
internal enum class DeviceSwipeScope {
    /** Activity: aggregate "All" is index 0, devices follow (GlobalDeviceBar order). */
    WITH_ALL,

    /** Folders: no aggregate row — only the devices, 0..n-1. */
    DEVICES_ONLY,
}

/** What a committed device-swipe step should do (pure verdict from [resolveDeviceSwipeStep]). */
internal sealed interface DeviceSwipeVerdict {
    /** Focus the device with this profile id (Folders; or Activity leaving "All"). */
    data class Device(val serverId: String) : DeviceSwipeVerdict

    /** Enter the aggregate "All" mode (Activity only). */
    data object All : DeviceSwipeVerdict

    /** Off the sequence's end — the outer layer (tab switch) takes the gesture. */
    data object Tab : DeviceSwipeVerdict
}

/**
 * Pure arithmetic behind the device swipe: given the tab's sequence [scope], the
 * current index, and the swipe [step] (+1 next / -1 previous), decide what the
 * gesture does. Falling off either end yields [DeviceSwipeVerdict.Tab] so the
 * outer tab-switch layer picks it up — mirroring the Activity tab's established
 * "inner first, outer at the edge" convention.
 */
internal fun resolveDeviceSwipeStep(
    scope: DeviceSwipeScope,
    currentIndex: Int,
    deviceCount: Int,
    step: Int,
): DeviceSwipeVerdict {
    // The sequence's logical bounds: WITH_ALL has deviceCount+1 entries
    // (All + devices); DEVICES_ONLY has deviceCount. A missing selection on
    // Folders (currentIndex clamped to 0 upstream) behaves like the first device.
    val last = when (scope) {
        DeviceSwipeScope.WITH_ALL -> deviceCount // devices occupy 1..deviceCount
        DeviceSwipeScope.DEVICES_ONLY -> deviceCount - 1
    }
    val next = currentIndex + step
    if (next < 0 || next > last) return DeviceSwipeVerdict.Tab
    return when {
        scope == DeviceSwipeScope.WITH_ALL && next == 0 -> DeviceSwipeVerdict.All
        // WITH_ALL: device i sits at sequence index i+1.
        scope == DeviceSwipeScope.WITH_ALL -> DeviceSwipeVerdict.Device(serverId = deviceAt(deviceCount, next - 1))
        else -> DeviceSwipeVerdict.Device(serverId = deviceAt(deviceCount, next))
    }
}

/** Stable stand-in for "the server at index i" — the verdict carries a positional
 *  id the caller resolves against the real list (`firstOrNull { it.id == ... }`);
 *  a stale gesture (list changed between index capture and commit) drops silently.
 *  The bounded index from [resolveDeviceSwipeStep] makes a miss unreachable in
 *  practice; the id shape is what the tests pin. */
private fun deviceAt(deviceCount: Int, index: Int): String = "idx:$index/$deviceCount"

/** Build the new-task route pre-seeded with a handoff reference (and optionally the
 *  source conversation's folder, so the new session starts in the same workspace —
 *  "接手" continues in-place). The reference (`[title](codeg://session/<id>)`) is
 *  URL-encoded — it contains brackets/parens that would otherwise be eaten as
 *  route path/arg syntax. */
internal fun handoffRoute(folderId: Int, reference: String): String {
    val q = buildString {
        append("handoff=")
        append(android.net.Uri.encode(reference))
        if (folderId > 0) {
            append("&folderId=")
            append(folderId)
        }
    }
    return "$ROUTE_NEW_TASK?$q"
}

/**
 * Whether the bottom bar — and, by extension, the New Task FAB — is currently
 * shown. Driven by scroll direction in [MainShell] and read by the Chats screen
 * so the FAB collapses to just "+" in lockstep with the bar sliding away.
 */
val LocalBarsVisible = compositionLocalOf { true }

/**
 * The main app shell once a server exists: a Material 3 bottom navigation bar
 * over a single [NavHost]. Tab destinations show the bar; full-screen
 * destinations (session detail, server management) hide it. Mirrors the iOS
 * compact-size `TabView`, with session detail / server management pushed on top.
 */
@Composable
fun MainShell(appViewModel: AppViewModel, servers: List<ServerProfile>) {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // User-customized bottom-tab order + cold-start tab (Settings → General). The
    // enum is only the fallback; every render here goes through `homeTabs`. The
    // shell holds until the settings flow has emitted once so the first frame is
    // already in the saved order / start tab (no default-order flash on cold start).
    val settingsLoaded by appViewModel.settingsLoaded.collectAsStateWithLifecycle()
    val appSettings by appViewModel.settings.collectAsStateWithLifecycle()
    val homeTabs = remember(appSettings.homeTabOrder) { HomeTab.orderedBy(appSettings.homeTabOrder) }
    // The start destination is bound once per shell lifetime: NavHost rebuilds (and
    // wipes) the whole graph when startDestination changes, so a runtime edit must
    // not feed through here — it takes effect on the next cold start.
    val startTab = remember { HomeTab.byRoute(appSettings.startTab) }
    val showBottomBar = homeTabs.any { it.route == currentRoute }
    val tabRoutes = remember(homeTabs) { homeTabs.map { it.route }.toSet() }
    val selectedProfile by appViewModel.selectedProfile.collectAsStateWithLifecycle()
    // Aggregate "All devices" mode (Activity tab), owned by ServerRepository so the
    // ActivityViewModel can react without ViewModel-to-ViewModel injection.
    val activityAll by appViewModel.activityAll.collectAsStateWithLifecycle()
    // Single-flight for the aggregate row tap (leave All → select device → push the
    // conversation). Double-taps would otherwise stack two detail pages resolved
    // against whichever selection landed last.
    var handoffInFlight by remember { mutableStateOf(false) }
    // Open a conversation AND stamp it read in one place — every entry point that
    // shows a conversation from a list (Chats / Activity single / Activity aggregate /
    // Search) routes through this so the Activity unread dot always clears on open.
    // serverId scopes the mark (conversation ids are server-local); the device-local
    // write is fire-and-forget and never gates the navigation.
    val openConversationAndMark: (serverId: String?, conversationId: Int) -> Unit =
        { serverId, conversationId ->
            if (serverId != null) appViewModel.markSessionRead("$serverId:$conversationId")
            nav.navigate("conversation/$conversationId")
        }
    // Wide screens (tablets / landscape) use a side NavigationRail instead of the bottom bar.
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 720

    // Scroll-driven bottom bar: scrolling up (reading further down the list) slides
    // the bar off the bottom edge and collapses the New Task FAB to just "+";
    // scrolling back down slides it in and restores the label. The bar lives in this
    // shared Scaffold while the scroll happens in each tab's LazyColumn, so a
    // NestedScrollConnection on the content picks up every tab's scroll for free.
    var barsVisible by remember { mutableStateOf(true) }
    val density = LocalDensity.current
    // 0 = fully shown, 1 = fully hidden. Collapsing the bar's *reported* height (not
    // just translating it) is what lets the Scaffold hand the freed strip back to the
    // content, so every tab's list reclaims the space instead of leaving it blank.
    val barProgress by animateFloatAsState(
        targetValue = if (barsVisible) 0f else 1f,
        animationSpec = tween(durationMillis = 280),
        label = "bottomBarCollapse",
    )
    // Accumulate the scroll the list *actually consumed* and flip fully open/closed
    // once it passes a small threshold, resetting the accumulator on each direction
    // change so a deliberate nudge toggles but jitter doesn't. Keying off consumed
    // (not the raw gesture) means a short, non-scrolling list — or over-dragging past
    // the top/bottom edge — never toggles the bar. Keyed to currentRoute so it resets
    // per tab.
    val barConnection = remember(currentRoute, density) {
        val threshold = with(density) { 6.dp.toPx() }
        object : NestedScrollConnection {
            var acc = 0f
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val dy = consumed.y
                if (dy == 0f) return Offset.Zero // list didn't move → leave the bar alone
                if ((dy < 0f && acc > 0f) || (dy > 0f && acc < 0f)) acc = 0f
                acc += dy
                if (acc < -threshold && barsVisible) {
                    barsVisible = false; acc = 0f
                } else if (acc > threshold && !barsVisible) {
                    barsVisible = true; acc = 0f
                }
                return Offset.Zero // observe only; never consume scroll
            }
        }
    }
    // Any route change — switching tabs or pushing a full-screen detail — reveals the bar.
    LaunchedEffect(currentRoute) { barsVisible = true }

    val onTab: (HomeTab) -> Unit = { tab ->
        nav.navigate(tab.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Horizontal swipe between the bottom tabs: drag left → next tab, right →
    // previous (the user's HomeTab order). Only active on tab routes on narrow
    // screens — full-screen pushes and the rail layout don't need it, and the
    // gesture modifier itself skips the system back-gesture edge band.
    //
    // With the device-swipe setting on, the swipe is claimed by the device
    // sequence first (the GlobalDeviceBar's own order): mid-sequence it switches
    // devices only, and falling off either END hands the gesture back to the tab
    // switch — the "inner first, outer at the edge" convention. Single-device
    // setups get no device layer and keep plain tab swipes. The per-tab sequence
    // shape ([DeviceSwipeScope]) and the step arithmetic live in
    // [resolveDeviceSwipeStep] (pure, unit-tested); here we only apply its verdict.
    val deviceSwipeOn = appSettings.activityDeviceSwipe &&
        (currentRoute == HomeTab.ACTIVITY.route || currentRoute == HomeTab.FOLDERS.route) &&
        servers.size > 1
    val deviceSwipeScope = when (currentRoute) {
        HomeTab.ACTIVITY.route -> DeviceSwipeScope.WITH_ALL
        HomeTab.FOLDERS.route -> DeviceSwipeScope.DEVICES_ONLY
        else -> null
    }
    val currentTabIndex = homeTabs.indexOfFirst { it.route == currentRoute }
    // Index inside the tab's device sequence: Activity counts the aggregate "All"
    // as 0; Folders has no All row, so devices are 0..n-1.
    val currentDeviceIndex = when (deviceSwipeScope) {
        DeviceSwipeScope.WITH_ALL ->
            if (activityAll) 0 else servers.indexOfFirst { it.id == selectedProfile?.id } + 1
        else -> servers.indexOfFirst { it.id == selectedProfile?.id }.coerceAtLeast(0)
    }
    val swipeModifier = if (showBottomBar && !wide && currentTabIndex >= 0) {
        Modifier.tabSwipeNavigation(enabled = true) { direction ->
            val step = if (direction == SwipeDirection.NEXT) 1 else -1
            if (deviceSwipeOn && deviceSwipeScope != null) {
                when (val verdict = resolveDeviceSwipeStep(
                    scope = deviceSwipeScope,
                    currentIndex = currentDeviceIndex,
                    deviceCount = servers.size,
                    step = step,
                )) {
                    is DeviceSwipeVerdict.Device -> {
                        val server = servers.firstOrNull { it.id == verdict.serverId }
                        if (server == null) return@tabSwipeNavigation
                        if (deviceSwipeScope == DeviceSwipeScope.WITH_ALL) {
                            appViewModel.setActivityAll(false)
                        }
                        appViewModel.selectServer(server.id)
                    }
                    is DeviceSwipeVerdict.All -> appViewModel.setActivityAll(true)
                    // Off the sequence's ends → the outer layer (tab switch) takes it.
                    DeviceSwipeVerdict.Tab -> {
                        val next = currentTabIndex + step
                        if (next in homeTabs.indices) onTab(homeTabs[next])
                    }
                }
            } else {
                val next = currentTabIndex + step
                if (next in homeTabs.indices) onTab(homeTabs[next])
            }
        }
    } else Modifier

    // Handle incoming codeg:// deep links.
    val pendingLink by app.codeg.android.core.common.DeepLinkBus.pending.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(pendingLink) {
        val route = pendingLink ?: return@LaunchedEffect
        fun toTab(it: HomeTab) = nav.navigate(it.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true; restoreState = true
        }
        when (route) {
            is app.codeg.android.core.common.DeepLinkRoute.OpenTab -> when (route.tab) {
                "chats" -> toTab(HomeTab.CHATS)
                "projects", "folders" -> toTab(HomeTab.FOLDERS)
                "activity" -> toTab(HomeTab.ACTIVITY)
                "search" -> toTab(HomeTab.SEARCH)
                "settings" -> toTab(HomeTab.SETTINGS)
                else -> {}
            }
            is app.codeg.android.core.common.DeepLinkRoute.OpenConversation -> nav.navigate("conversation/${route.id}")
            is app.codeg.android.core.common.DeepLinkRoute.OpenProject -> nav.navigate("project/${route.id}")
            is app.codeg.android.core.common.DeepLinkRoute.OpenSettings -> toTab(HomeTab.SETTINGS)
        }
        app.codeg.android.core.common.DeepLinkBus.consume(route)
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar && !wide) {
                CodegBottomBar(
                    currentRoute,
                    homeTabs,
                    onTab,
                    // Report a shrinking height as the bar hides so the Scaffold gives the
                    // freed strip back to the content (the whole point — reclaim the space);
                    // the bar is drawn at full height and slid down within, clipped away.
                    modifier = Modifier
                        .clipToBounds()
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            val full = placeable.height
                            val reported = (full * (1f - barProgress)).roundToInt().coerceIn(0, full)
                            layout(placeable.width, reported) {
                                placeable.placeRelative(0, (full * barProgress).roundToInt())
                            }
                        },
                )
            }
        },
    ) { padding ->
        // Only intercept scroll when the bottom bar is actually shown (a tab route on a
        // narrow screen); the side rail and full-screen routes don't hide-on-scroll.
        val contentModifier =
            if (showBottomBar && !wide) Modifier.nestedScroll(barConnection).then(swipeModifier) else Modifier
        // The app-global device bar rides above every tab's content (hidden on
        // full-screen routes and for single-server setups, where there is nothing
        // to switch). It owns the status-bar inset; each tab's TopAppBar is told
        // to stop applying it too (windowInsets zeroed) so the height isn't doubled.
        androidx.compose.foundation.layout.Column(
            Modifier.padding(padding).fillMaxSize().then(contentModifier),
        ) {
            if (showBottomBar && servers.size > 1) {
                GlobalDeviceBar(
                    servers = servers,
                    selectedId = selectedProfile?.id,
                    allSelected = activityAll && currentRoute == HomeTab.ACTIVITY.route,
                    showAll = currentRoute == HomeTab.ACTIVITY.route,
                    onSelectServer = { id ->
                        // Tapping the already-focused device (or "All" while a device
                        // is focused) must not restart the feed — the aggregate poll
                        // treats any profile/tick emission as a mode change.
                        if (activityAll || id != selectedProfile?.id) {
                            appViewModel.setActivityAll(false)
                            appViewModel.selectServer(id)
                        }
                    },
                    onSelectAll = {
                        if (activityAll) appViewModel.refreshActivityAll() else appViewModel.setActivityAll(true)
                    },
                    onManageServers = { nav.navigate(ROUTE_SERVERS) },
                )
            }
            androidx.compose.foundation.layout.Row {
                if (showBottomBar && wide) CodegNavRail(currentRoute, homeTabs, onTab)
            NavHost(
                navController = nav,
                startDestination = startTab.route,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                // Material motion: lateral tab switches fade through; pushing a
                // detail/editor slides in along the X axis (and back reverses it).
                enterTransition = {
                    val lateral = initialState.destination.route in tabRoutes &&
                        targetState.destination.route in tabRoutes
                    if (lateral) fadeIn(tween(190))
                    else slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300)) +
                        fadeIn(tween(300))
                },
                exitTransition = {
                    val lateral = initialState.destination.route in tabRoutes &&
                        targetState.destination.route in tabRoutes
                    if (lateral) fadeOut(tween(190))
                    else slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300)) +
                        fadeOut(tween(300))
                },
                popEnterTransition = {
                    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) +
                        fadeIn(tween(300))
                },
                popExitTransition = {
                    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) +
                        fadeOut(tween(300))
                },
            ) {
            composable(HomeTab.CHATS.route) {
                CompositionLocalProvider(LocalBarsVisible provides barsVisible) {
                    SessionListScreen(
                        servers = servers,
                        selectedId = selectedProfile?.id,
                        onManageServers = { nav.navigate(ROUTE_SERVERS) },
                        onOpenConversation = { id -> openConversationAndMark(selectedProfile?.id, id) },
                        onNewTask = { nav.navigate(ROUTE_NEW_TASK) },
                        onHandoff = { folderId, ref -> nav.navigate(handoffRoute(folderId, ref)) },
                    )
                }
            }
            composable(HomeTab.FOLDERS.route) {
                ProjectListScreen(onOpenFolder = { id -> nav.navigate("project/$id") })
            }
            composable(HomeTab.ACTIVITY.route) {
                ActivityScreen(
                    servers = servers,
                    selectedServerId = selectedProfile?.id,
                    allMode = activityAll,
                    onRefreshAll = appViewModel::refreshActivityAll,
                    // In aggregate mode a row tap focuses that device: leave "All",
                    // wait for the selection to land, then push the conversation
                    // (session detail reads selectedProfile exactly once on entry).
                    onOpenRow = { serverId, conversationId ->
                        if (!handoffInFlight) {
                            handoffInFlight = true
                            appViewModel.setActivityAll(false)
                            appViewModel.selectServerAndWait(serverId) {
                                handoffInFlight = false
                                openConversationAndMark(serverId, conversationId)
                            }
                        }
                    },
                    onSelectServer = appViewModel::selectServer,
                    onManageServers = { nav.navigate(ROUTE_SERVERS) },
                    // Long-press handoff on an aggregate row must land on the OWNING
                    // device first (folderId is server-local), same dance as onOpenRow.
                    onHandoff = { serverId, folderId, ref ->
                        if (!handoffInFlight) {
                            handoffInFlight = true
                            if (activityAll) {
                                appViewModel.setActivityAll(false)
                                appViewModel.selectServerAndWait(serverId) {
                                    handoffInFlight = false
                                    nav.navigate(handoffRoute(folderId, ref))
                                }
                            } else {
                                handoffInFlight = false
                                nav.navigate(handoffRoute(folderId, ref))
                            }
                        }
                    },
                    onOpenConversation = { id -> openConversationAndMark(selectedProfile?.id, id) },
                )
            }
            composable(HomeTab.SEARCH.route) {
                SearchScreen(onOpenConversation = { id -> openConversationAndMark(selectedProfile?.id, id) })
            }
            composable(HomeTab.SETTINGS.route) {
                SettingsScreen()
            }

            composable(
                route = "conversation/{id}",
                arguments = listOf(navArgument("id") { type = NavType.IntType }),
            ) {
                SessionDetailScreen(
                    onBack = { nav.popBackStack() },
                    onOpenSession = { folderId -> nav.navigate("$ROUTE_NEW_TASK?folderId=$folderId") },
                )
            }
            composable(
                route = "$ROUTE_NEW_TASK?folderId={folderId}",
                arguments = listOf(
                    navArgument("folderId") {
                        type = NavType.IntType
                        defaultValue = -1
                    },
                ),
            ) {
                SessionDetailScreen(
                    onBack = { nav.popBackStack() },
                    onOpenSession = { folderId -> nav.navigate("$ROUTE_NEW_TASK?folderId=$folderId") },
                )
            }
            composable(
                route = "$ROUTE_NEW_TASK?folderId={folderId}&handoff={handoff}",
                arguments = listOf(
                    navArgument("folderId") {
                        type = NavType.IntType
                        defaultValue = -1
                    },
                    navArgument("handoff") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                // The handoff entry sits directly above the list, so Back lands there
                // naturally; its arguments also survive process death and re-seed the
                // composer (draft is remembered-empty then, seed re-applies). No stack
                // surgery needed.
                SessionDetailScreen(
                    onBack = { nav.popBackStack() },
                    onOpenSession = { folderId -> nav.navigate("$ROUTE_NEW_TASK?folderId=$folderId") },
                    seedText = entry.arguments?.getString("handoff"),
                )
            }

            composable(
                route = "project/{folderId}",
                arguments = listOf(navArgument("folderId") { type = NavType.IntType }),
            ) {
                ProjectDetailScreen(onBack = { nav.popBackStack() })
            }

            composable(ROUTE_SERVERS) {
                ServerListScreen(
                    servers = servers,
                    selectedId = selectedProfile?.id,
                    onSelect = appViewModel::selectServer,
                    onDelete = appViewModel::deleteServer,
                    onAdd = { nav.navigate(ROUTE_EDITOR) },
                    onEdit = { id -> nav.navigate("$ROUTE_EDITOR?serverId=$id") },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                route = "$ROUTE_EDITOR?serverId={serverId}",
                arguments = listOf(
                    navArgument("serverId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                ServerEditorScreen(onDone = { nav.popBackStack() })
            }
            }
            }
        }
    }
}

@Composable
private fun CodegNavRail(
    currentRoute: String?,
    tabs: List<HomeTab>,
    onSelect: (HomeTab) -> Unit,
) {
    val colors = CodegTheme.colors
    // The wide-screen analogue of the bottom bar: the same frosted glass material,
    // delineated from the content by a trailing hairline rather than a top one.
    androidx.compose.foundation.layout.Row {
        androidx.compose.material3.NavigationRail(
            containerColor = Color.Transparent,
        ) {
            tabs.forEach { tab ->
                val selected = currentRoute == tab.route
                androidx.compose.material3.NavigationRailItem(
                    selected = selected,
                    onClick = { onSelect(tab) },
                    icon = {
                        Icon(
                            if (selected) tab.selectedIcon else tab.icon,
                            contentDescription = stringResource(tab.label),
                        )
                    },
                    label = { Text(stringResource(tab.label)) },
                    alwaysShowLabel = true,
                    colors = androidx.compose.material3.NavigationRailItemDefaults.colors(
                        selectedIconColor = colors.accent,
                        selectedTextColor = colors.accent,
                        unselectedIconColor = colors.textTertiary,
                        unselectedTextColor = colors.textTertiary,
                        indicatorColor = colors.accent.copy(alpha = 0.20f),
                    ),
                )
            }
        }
        androidx.compose.material3.VerticalDivider(
            thickness = androidx.compose.ui.unit.Dp.Hairline,
            color = colors.surfaceStroke,
        )
    }
}

@Composable
private fun CodegBottomBar(
    currentRoute: String?,
    tabs: List<HomeTab>,
    onSelect: (HomeTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    androidx.compose.foundation.layout.Column(modifier) {
        // A hairline that delineates the bar from the content above it, the way
        // GitHub / Telegram / ChatGPT separate their bottom navigation.
        HorizontalDivider(thickness = androidx.compose.ui.unit.Dp.Hairline, color = colors.surfaceStroke)
        NavigationBar(
            // Fully transparent so the CodegBackground (base + both glows) flows
            // uninterrupted to the bottom edge. A translucent fill is pointless in
            // light mode (elevated #FFFFFF and base #F2F4F6 are both near-white, so it
            // still reads as a white slab); letting the canvas itself show through is
            // what actually makes the tabs continuous with the content above them.
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
        ) {
            tabs.forEach { tab ->
                val selected = currentRoute == tab.route
                NavigationBarItem(
                    selected = selected,
                    onClick = { onSelect(tab) },
                    icon = {
                        Icon(
                            if (selected) tab.selectedIcon else tab.icon,
                            contentDescription = stringResource(tab.label),
                        )
                    },
                    label = { Text(stringResource(tab.label)) },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = colors.accent,
                        selectedTextColor = colors.accent,
                        unselectedIconColor = colors.textTertiary,
                        unselectedTextColor = colors.textTertiary,
                        indicatorColor = colors.accent.copy(alpha = 0.20f),
                    ),
                )
            }
        }
    }
}
