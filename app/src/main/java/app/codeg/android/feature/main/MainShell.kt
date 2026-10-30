package app.codeg.android.feature.main

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import app.codeg.android.core.data.FocusPreview
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.designsystem.component.GlobalDeviceBar
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.feature.activity.ActivityScreen
import app.codeg.android.feature.floatingball.FloatingBallHost
import app.codeg.android.feature.floatingball.FloatingBallSettingsBus
import app.codeg.android.feature.floatingball.floatBallSizeDp
import app.codeg.android.feature.floatingball.stepFontScale
import app.codeg.android.feature.projects.ProjectDetailScreen
import app.codeg.android.feature.search.SearchScreen
import app.codeg.android.feature.projects.ProjectListScreen
import app.codeg.android.feature.server.ServerEditorScreen
import app.codeg.android.feature.settings.SettingsScreen
import app.codeg.android.feature.server.ServerListScreen
import app.codeg.android.feature.sessiondetail.SessionDetailScreen
import app.codeg.android.feature.sessions.ProjectFilterSheet
import app.codeg.android.feature.sessions.ProjectSettingsBus
import app.codeg.android.feature.sessions.SessionListScreen
import app.codeg.android.feature.sessions.nextProjectFilter

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
    /** Focus the device at this index in the caller's server list (0-based). */
    data class Device(val deviceIndex: Int) : DeviceSwipeVerdict

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
 * "inner first, outer at the edge" convention. The verdict names the DEVICE INDEX,
 * never a profile id — the caller resolves it against the live server list.
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
        scope == DeviceSwipeScope.WITH_ALL -> DeviceSwipeVerdict.Device(deviceIndex = next - 1)
        else -> DeviceSwipeVerdict.Device(deviceIndex = next)
    }
}

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
    // Aggregate "全部" mode for the Chats tab + the project filter (batch v132).
    val chatsAll by appViewModel.chatsAll.collectAsStateWithLifecycle()
    val sessionProjects by appViewModel.sessionProjects.collectAsStateWithLifecycle()
    val sessionProjectMembers by appViewModel.sessionProjectMembers.collectAsStateWithLifecycle()
    val activeProjectId by appViewModel.activeProjectId.collectAsStateWithLifecycle()
    var showProjectSheet by remember { mutableStateOf(false) }
    // Single-flight for the aggregate row tap (leave All → select device → push the
    // conversation). Double-taps would otherwise stack two detail pages resolved
    // against whichever selection landed last.
    var handoffInFlight by remember { mutableStateOf(false) }

    // Single-flight "select the owning device, then open something on it" (v134).
    // The selection write suspends, so while it runs the user can switch devices or
    // navigate away; [onLanded] runs only when the switch really landed AND the stack
    // is still where the tap came from. The in-flight flag is released on every exit
    // path — including a failed DataStore write — or one bad tap would wedge every
    // cross-device open until the process dies.
    fun handoffTo(serverId: String, onLanded: suspend () -> Unit) {
        if (handoffInFlight) return
        handoffInFlight = true
        val origin = nav.currentBackStackEntry?.destination?.route
        appViewModel.selectServerAndWait(serverId) {
            try {
                if (nav.currentBackStackEntry?.destination?.route == origin &&
                    appViewModel.selectedIdNow() == serverId
                ) {
                    onLanded()
                }
            } finally {
                handoffInFlight = false
            }
        }
    }
    // Open a conversation AND stamp it read in one place — every entry point that
    // shows a conversation from a list (Chats / Activity single / Activity aggregate /
    // Search) routes through this so the Activity unread dot always clears on open.
    // serverId scopes the mark (conversation ids are server-local); the device-local
    // write is fire-and-forget and never gates the navigation.
    val openConversationAndMark: (serverId: String?, conversationId: Int) -> Unit =
        { serverId, conversationId ->
            // Deep links carry no device — resolve against the current selection so
            // the unread dot still clears (the mark is device-scoped).
            val ownerId = serverId ?: selectedProfile?.id
            // v134: repeated triggers of the SAME conversation (ball gestures re-fire
            // in the window before the NavHost recomposes; recent-session ranks fire
            // on the detail itself) must not stack duplicate detail entries — Back
            // then needs one press per duplicate. Read the back queue DIRECTLY (it is
            // in sync right after navigate(); the AsState flow is a frame late).
            // Cross-device ids collide (server-local), so compare against the device
            // the top detail was actually opened for (detailServerId), never the
            // live selection the ball may have changed underneath.
            val top = nav.currentBackStackEntry
            val topId = top?.arguments?.getInt("id")
            // The owning device rides the route, so it belongs to THIS entry: pushing
            // another detail over it and coming back restores the right stamp, which a
            // single shell-level field could not.
            val topDevice = top?.arguments?.getString("serverId")
            if (!isDuplicateDetailPush(top?.destination?.route, topId, conversationId, ownerId, topDevice)) {
                // Marked only for a REAL open: re-triggering the conversation already
                // on screen must not bump its "recent" timestamp again.
                if (ownerId != null) appViewModel.markSessionRead("$ownerId:$conversationId")
                nav.navigate(
                    if (ownerId != null) "conversation/$conversationId?serverId=$ownerId"
                    else "conversation/$conversationId",
                )
            }
        }
    // Open a conversation from an aggregate ("全部") row (v134 rework). The
    // owning-device selection still lands BEFORE the push (session detail reads
    // selectedProfile exactly once on entry), but the aggregate mode itself now
    // SURVIVES: the aggregate lists never consume selectedProfile, so the old
    // pre-push clear of chatsAll/activityAll only served to strand Back on the
    // single-device view instead of the 全部 surface the user came from. Each tab's
    // All flag is independent and untouched (the old per-tab exit note is moot).
    fun openAggregateRow(serverId: String, conversationId: Int) {
        handoffTo(serverId) { openConversationAndMark(serverId, conversationId) }
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

    // Project-filter invariant (batch v132 review #3, v134 rework): the filter only
    // lives while a tab surface is in an aggregate mode. Adjudicate ONLY on tab
    // landings — a full-screen detail (conversation/handoff/project) pushed from an
    // aggregate surface must not clear the filter, or Back couldn't return to the
    // exact 全部 view it came from; the effect re-runs and enforces the invariant the
    // moment the tab is revealed again.
    androidx.compose.runtime.LaunchedEffect(currentRoute, activityAll, chatsAll) {
        // currentBackStackEntryAsState starts at null for one frame after rotation /
        // process restore (the NavHost restores its graph during composition, after
        // the initial read). Adjudicating on that frame used to clear the project
        // filter while every aggregate flag survived the config change — wait for a
        // real route instead.
        if (currentRoute == null) return@LaunchedEffect
        if (currentRoute in HomeTab.entries.map { it.route }) {
            val aggregateOn = (currentRoute == HomeTab.ACTIVITY.route && activityAll) ||
                (currentRoute == HomeTab.CHATS.route && chatsAll)
            if (!aggregateOn) appViewModel.setActiveProject(null)
        }
    }

    // Fewer than two visible devices → the device bar (and every aggregate context)
    // disappears; drop the modes instead of stranding the UI with no exit (review #13).
    androidx.compose.runtime.LaunchedEffect(servers.size) {
        if (servers.size < 2) appViewModel.exitAggregate()
    }

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
    // With a device-swipe setting on, the swipe is claimed by the device sequence
    // first (the GlobalDeviceBar's own order): mid-sequence it switches devices
    // only, and falling off either END hands the gesture back to the tab switch —
    // the "inner first, outer at the edge" convention. Activity and Folders have
    // SEPARATE settings toggles (v123 regression fix); single-device setups get no
    // device layer and keep plain tab swipes. The per-tab sequence shape
    // ([DeviceSwipeScope]) and the step arithmetic live in [resolveDeviceSwipeStep]
    // (pure, unit-tested); here we only apply its verdict.
    val deviceSwipeScope = when (currentRoute) {
        HomeTab.ACTIVITY.route ->
            if (appSettings.activityDeviceSwipe) DeviceSwipeScope.WITH_ALL else null
        HomeTab.FOLDERS.route ->
            if (appSettings.foldersDeviceSwipe) DeviceSwipeScope.DEVICES_ONLY else null
        else -> null
    }
    val deviceSwipeOn = deviceSwipeScope != null && servers.size > 1
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
                        val server = servers.getOrNull(verdict.deviceIndex)
                        if (server == null) return@tabSwipeNavigation
                        // Leaving any aggregate context (All/Chats-All/project filter).
                        appViewModel.exitAggregate()
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
            is app.codeg.android.core.common.DeepLinkRoute.OpenConversation -> openConversationAndMark(null, route.id)
            is app.codeg.android.core.common.DeepLinkRoute.OpenProject -> nav.navigate("project/${route.id}")
            is app.codeg.android.core.common.DeepLinkRoute.OpenSettings -> toTab(HomeTab.SETTINGS)
        }
        app.codeg.android.core.common.DeepLinkBus.consume(route)
    }

    // ---- Floating ball (batch v126) ----
    // The ball rides ABOVE the whole shell (Scaffold + bottom bar) as its sibling
    // inside a Box, so ball touches never leak into the tab-swipe / scroll layers.
    // The registry below maps catalog action ids onto this shell's real handlers;
    // "panel" never arrives here (the host opens its own action panel).
    val ballBindings by appViewModel.floatBallBindings.collectAsStateWithLifecycle()
    val ballPos by appViewModel.floatBallPos.collectAsStateWithLifecycle()
    val ballContext = androidx.compose.ui.platform.LocalContext.current

    // Jump to the rank-th most-recent conversation across devices, resolved from
    // the device-local read marks ("<serverId>:<conversationId>" → openedAt).
    // Selection must land before the push (session detail reads it once on entry).
    fun openRecentConversation(rank: Int) {
        val validServers = servers.map { it.id }.toSet()
        val entry = appViewModel.sessionReadMarks.value.entries
            .filter { it.key.substringBeforeLast(':') in validServers }
            .sortedByDescending { it.value }
            .getOrNull(rank) ?: return
        val serverId = entry.key.substringBeforeLast(':')
        val conversationId = entry.key.substringAfterLast(':').toIntOrNull() ?: return
        // Same single-flight as the aggregate row taps: two selection dances racing
        // each other would push details resolved against whichever landed last.
        handoffTo(serverId) {
            // Same entry point as every other list open: the mark (same key) rides
            // along and the v134 same-conversation dedupe applies.
            openConversationAndMark(serverId, conversationId)
        }
    }

    // Ring-step the focused device (wraps); leaves every aggregate context first so
    // the step always lands on a concrete device with no hidden project filter.
    fun switchServerBy(step: Int) {
        if (servers.size < 2) return
        val idx = servers.indexOfFirst { it.id == selectedProfile?.id }.coerceAtLeast(0)
        val target = servers[(idx + step + servers.size) % servers.size]
        appViewModel.exitAggregate()
        appViewModel.selectServer(target.id)
    }

    // Open one cross-device session ref (floating-ball "openHighlighted"): same
    // owning-device dance as the aggregate row taps, guarded by the same single-flight.
    // v134: no exitAggregate — Back from the opened detail returns to the 全部 surface
    // the highlight came from (same rule as the aggregate row taps).
    fun openRef(ref: app.codeg.android.core.datastore.SessionRef) {
        openAggregateRow(ref.serverId, ref.conversationId)
    }

    // Open the bare new-task screen. Repeated floating-ball gestures (or FAB taps)
    // must NEVER stack a second one: popUpTo the new-task destination inclusive
    // replaces the existing entry in place, so Back from it always lands on the tab
    // the user came from — exactly one clean composer, no matter how many times the
    // gesture fires. (launchSingleTop alone can't dedupe here: the graph registers
    // three route-pattern variants, and navigate-to-constant vs the pattern with
    // default args are matched as separate entries.)
    val openNewTask: () -> Unit = {
        nav.navigate(ROUTE_NEW_TASK) {
            popUpTo(ROUTE_NEW_TASK) { inclusive = true }
            launchSingleTop = true
        }
    }

    val onBallAction: (String) -> Unit = { id ->
        when (id) {
            "newSession" -> openNewTask()
            "tabChats" -> onTab(HomeTab.CHATS)
            "tabFolders" -> onTab(HomeTab.FOLDERS)
            "tabActivity" -> onTab(HomeTab.ACTIVITY)
            "tabSearch" -> onTab(HomeTab.SEARCH)
            "tabSettings" -> onTab(HomeTab.SETTINGS)
            "toggleThemeMode" -> appViewModel.toggleThemeMode()
            "nextAccent" -> appViewModel.cycleAccent()
            "fontScaleUp", "fontScaleDown", "fontScaleReset" -> {
                val scale = when (id) {
                    "fontScaleUp" -> stepFontScale(appViewModel.settings.value.fontScale, +1)
                    "fontScaleDown" -> stepFontScale(appViewModel.settings.value.fontScale, -1)
                    else -> 1f
                }
                appViewModel.setFontScale(scale)
                android.widget.Toast.makeText(
                    ballContext,
                    ballContext.getString(R.string.ball_font_toast, (scale * 100).roundToInt()),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
            "recentSession1" -> openRecentConversation(0)
            "recentSession2" -> openRecentConversation(1)
            "recentSession3" -> openRecentConversation(2)
            "switchServerNext" -> switchServerBy(+1)
            "switchServerPrev" -> switchServerBy(-1)
            // Batch v132 two-segment gestures: cycle the project filter, then walk
            // the visible list. The project cycle only acts on the list tabs (no
            // invisible jumps from Settings/Folders); entering "All" is implicit on
            // the first fire so 【全部 → P1 → … → 全部】 works from anywhere on-tab.
            // Single/zero-device setups stay OUT: aggregate has no visible exit
            // there (the All chip needs >1 device) — R1-4.
            "cycleProject" -> if (servers.size >= 2) when (currentRoute) {
                HomeTab.ACTIVITY.route -> {
                    if (!activityAll) appViewModel.setActivityAll(true)
                    appViewModel.setActiveProject(
                        nextProjectFilter(appViewModel.activeProjectId.value, appViewModel.sessionProjects.value),
                    )
                }
                HomeTab.CHATS.route -> {
                    if (!chatsAll) appViewModel.setChatsAll(true)
                    appViewModel.setActiveProject(
                        nextProjectFilter(appViewModel.activeProjectId.value, appViewModel.sessionProjects.value),
                    )
                }
                else -> {}
            }
            "cycleSession" -> appViewModel.focusHub.cycle(currentRoute ?: "")
            "openHighlighted" -> appViewModel.focusHub.highlightedRef(currentRoute ?: "")?.let { openRef(it) }
            "ballSettings" -> {
                onTab(HomeTab.SETTINGS)
                FloatingBallSettingsBus.open()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
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
                    // Batch v132: the All chip now also lives on the Chats tab (where
                    // it toggles the aggregate list); the subtitle echoes the active
                    // project so a filter is never invisible.
                    allSelected = (activityAll && currentRoute == HomeTab.ACTIVITY.route) ||
                        (chatsAll && currentRoute == HomeTab.CHATS.route),
                    showAll = currentRoute == HomeTab.ACTIVITY.route || currentRoute == HomeTab.CHATS.route,
                    allSubtitle = activeProjectId?.let { pid ->
                        sessionProjects.firstOrNull { it.id == pid }?.name
                    },
                    onSelectServer = { id ->
                        // Tapping the already-focused device (or "All" while a device
                        // is focused) must not restart the feed — the aggregate poll
                        // treats any profile/tick emission as a mode change. chatsAll
                        // joins the guard so an aggregate Chats list is escapable too.
                        if (activityAll || chatsAll || id != selectedProfile?.id) {
                            appViewModel.exitAggregate()
                            appViewModel.selectServer(id)
                        }
                    },
                    onSelectAll = {
                        // Two-stage tap (v133 owner call): the FIRST tap on "All" just
                        // switches to the aggregate view; only a tap on an ALREADY-active
                        // "All" opens the project sheet (to change/clear the filter).
                        // Programmatic entries — swipes, gestures — never open it.
                        // Per-tab flags: CHATS reads chatsAll, every other route reads
                        // activityAll; refresh is pull-to-refresh on either tab.
                        when (currentRoute) {
                            HomeTab.CHATS.route -> {
                                if (!chatsAll) appViewModel.setChatsAll(true) else showProjectSheet = true
                            }
                            else -> {
                                if (!activityAll) appViewModel.setActivityAll(true) else showProjectSheet = true
                            }
                        }
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
                        onNewTask = openNewTask,
                        onHandoff = { folderId, ref -> nav.navigate(handoffRoute(folderId, ref)) },
                        // Aggregate rows carry their owning device — same single-flight
                        // dance as the Activity aggregate (review #14). Exit is scoped
                        // to THIS tab (chatsAll + filter) so the Activity tab's own
                        // All mode survives, mirroring the Activity path (R1-6).
                        onOpenAggregateRow = { serverId, conversationId ->
                            // v134: selection only — the aggregate mode survives so Back
                            // returns to the 全部 surface (see openAggregateRow).
                            openAggregateRow(serverId, conversationId)
                        },
                        onHandoffAggregate = { serverId, folderId, ref ->
                            // v134: selection only, exactly like a row tap — the
                            // composer is pushed over the tab, so Back must land
                            // back on the 全部 surface the user came from.
                            handoffTo(serverId) { nav.navigate(handoffRoute(folderId, ref)) }
                        },
                        onManageProjects = {
                            ProjectSettingsBus.open()
                            onTab(HomeTab.SETTINGS)
                        },
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
                        // v134: selection only — the aggregate mode survives so Back
                        // returns to the 全部 surface (see openAggregateRow).
                        openAggregateRow(serverId, conversationId)
                    },
                    onSelectServer = appViewModel::selectServer,
                    onManageServers = { nav.navigate(ROUTE_SERVERS) },
                    // Long-press handoff on an aggregate row must land on the OWNING
                    // device first (folderId is server-local), same dance as onOpenRow.
                    onHandoff = { serverId, folderId, ref ->
                        // v134: selection only — keep the aggregate mode so Back from
                        // the composer returns to the 全部 surface. Already on the
                        // owning device in single mode, so no selection dance needed.
                        if (activityAll) handoffTo(serverId) { nav.navigate(handoffRoute(folderId, ref)) }
                        else nav.navigate(handoffRoute(folderId, ref))
                    },
                    onOpenConversation = { id -> openConversationAndMark(selectedProfile?.id, id) },
                    onManageProjects = {
                        ProjectSettingsBus.open()
                        onTab(HomeTab.SETTINGS)
                    },
                )
            }
            composable(HomeTab.SEARCH.route) {
                SearchScreen(onOpenConversation = { id -> openConversationAndMark(selectedProfile?.id, id) })
            }
            composable(HomeTab.SETTINGS.route) {
                SettingsScreen()
            }

            composable(
                // `serverId` is the owning device, stamped at push time. Optional so
                // old pushes and any external route still resolve; the dedupe guard
                // treats a missing value as "unknown device".
                route = "conversation/{id}?serverId={serverId}",
                arguments = listOf(
                    navArgument("id") { type = NavType.IntType },
                    navArgument("serverId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                SessionDetailScreen(
                    onBack = { nav.popBackStack() },
                    onOpenSession = { folderId -> nav.navigate("$ROUTE_NEW_TASK?folderId=$folderId") },
                    onOpenFolder = { folderId -> nav.navigate("project/$folderId") },
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
                    onOpenFolder = { folderId -> nav.navigate("project/$folderId") },
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
                    onOpenFolder = { folderId -> nav.navigate("project/$folderId") },
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
                // The management screen lists EVERY saved device (hidden ones too)
                // plus the visibility toggle — the only place a hidden device stays
                // reachable, since the shell's day-to-day surfaces filter them out.
                val allServers by appViewModel.allServers.collectAsStateWithLifecycle()
                ServerListScreen(
                    servers = allServers.orEmpty(),
                    selectedId = selectedProfile?.id,
                    onSelect = appViewModel::selectServer,
                    onDelete = appViewModel::deleteServer,
                    onToggleVisible = appViewModel::setServerVisible,
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

        // Tapping the 全部 chip (either list tab) opens the project filter sheet
        // (batch v132): 全部会话 / a project / create-and-activate. Rendered here —
        // the sheet must float above whatever tab is showing, and both list tabs'
        // chip taps route through onSelectAll into this one instance.
        if (showProjectSheet) {
            ProjectFilterSheet(
                projects = sessionProjects,
                counts = sessionProjectMembers.mapValues { it.value.size },
                activeId = activeProjectId,
                onSelect = { pid ->
                    appViewModel.setActiveProject(pid)
                    showProjectSheet = false
                },
                onCreateActivate = { name ->
                    // Create-and-filter in one step (R1-1); close the sheet so the
                    // freshly filtered list is immediately visible (R2-9).
                    appViewModel.createAndActivateProject(name)
                    showProjectSheet = false
                },
                onManage = {
                    showProjectSheet = false
                    ProjectSettingsBus.open()
                    onTab(HomeTab.SETTINGS)
                },
                onDismiss = { showProjectSheet = false },
            )
        }

        FloatingBallHost(
            enabled = appSettings.floatBallEnabled,
            sizeDp = floatBallSizeDp(appSettings.floatBallSize),
            bindings = ballBindings,
            posRatio = ballPos,
            onMoved = { fx, fy -> appViewModel.setFloatBallPos(fx, fy) },
            onAction = onBallAction,
        )

        // Focus-cycle preview card (batch v132): after the second ball gesture
        // highlights a row, this transient card says WHAT was highlighted.
        // Self-collecting (R2-8): a preview emit/clear recomposes only this card,
        // not the whole shell scope.
        FloatingBallPreviewCard(focusHub = appViewModel.focusHub)
    }
}

/**
 * Transient toast-like card naming the row the ball's "cycle session" gesture
 * just highlighted — readable without hunting the glow down a long list.
 * `lastPreview` holds the payload through the fade-out (null would blank the
 * card mid-exit).
 */
@Composable
private fun FloatingBallPreviewCard(focusHub: app.codeg.android.core.data.SessionFocusHub) {
    val preview by focusHub.preview.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    var lastPreview by remember { mutableStateOf<FocusPreview?>(null) }
    // Capture on the AFTER-composition pass (R3-5): a state write during
    // composition is the discouraged pattern and costs an extra recomposition.
    val current = preview
    androidx.compose.runtime.SideEffect { if (current != null) lastPreview = current }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = current != null,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(240)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val shown = lastPreview
            if (shown != null) {
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = colors.textPrimary.copy(alpha = 0.92f),
                    shadowElevation = 6.dp,
                    modifier = Modifier.padding(bottom = 96.dp),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            text = shown.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.bg,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            text = shown.serverName + "  ·  " + shown.position + "/" + shown.total,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.bg.copy(alpha = 0.7f),
                            modifier = Modifier.padding(top = 2.dp),
                        )
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
