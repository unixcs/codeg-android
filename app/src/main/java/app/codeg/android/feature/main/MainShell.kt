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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Assignment
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
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
import app.codeg.android.core.data.UNASSIGNED_PROJECT_ID
import app.codeg.android.core.data.orderedProjectChips
import app.codeg.android.core.data.resolveCycleTarget
import app.codeg.android.core.datastore.DispatchState
import app.codeg.android.core.datastore.SESSION_CHIP_PROJECTS
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.designsystem.component.GlobalDeviceBar
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.designsystem.theme.InterfaceFontScale
import app.codeg.android.feature.activity.ActivityScreen
import app.codeg.android.feature.floatingball.FloatingBallHost
import app.codeg.android.feature.floatingball.FloatingBallSettingsBus
import app.codeg.android.feature.floatingball.floatBallSizeDp
import app.codeg.android.feature.floatingball.stepFontScale
import app.codeg.android.feature.inbox.InboxScreen
import app.codeg.android.feature.projects.CommonDirsRoute
import app.codeg.android.feature.projects.ProjectDetailScreen
import app.codeg.android.feature.quick.QuickActionViewModel
import app.codeg.android.feature.quick.QuickIdeaSheet
import app.codeg.android.feature.quick.QuickNewSessionSheet
import app.codeg.android.feature.quick.QuickSaveSheet
import app.codeg.android.feature.quick.QuickTaskSheet
import app.codeg.android.feature.quick.RecentSessionPickerSheet
import app.codeg.android.feature.search.SearchScreen
import app.codeg.android.feature.projects.ProjectListScreen
import app.codeg.android.feature.server.ServerEditorScreen
import app.codeg.android.feature.settings.SessionProjectsRoute
import app.codeg.android.feature.settings.SettingsScreen
import app.codeg.android.feature.server.ServerListScreen
import app.codeg.android.feature.sessiondetail.SessionDetailScreen
import app.codeg.android.feature.sessions.ProjectFilterSheet
import app.codeg.android.feature.sessions.SessionListScreen
import app.codeg.android.feature.sessions.SessionSwipeAction
import app.codeg.android.feature.sessions.swipeActionLabelRes
import app.codeg.android.feature.sessions.nextProjectFilter
import app.codeg.android.feature.taskhub.TaskHubScreen

/**
 * Bottom-navigation destinations (批v152 five-tab bar: 动态 | 会话 | 收件箱 |
 * 任务板 | 设置 — the Chats surface is back as a first-class tab). Each carries
 * an outlined icon (unselected) and a filled icon (selected) — the Material 3 /
 * GitHub / ChatGPT convention where the active tab fills in.
 *
 * Folders/Search are still demoted to full-screen push surfaces
 * ([ROUTE_FOLDERS]/[ROUTE_SEARCH], route strings unchanged) so persisted tab
 * orders, deep links and floating-ball action ids survive. Their entry points
 * live on the workbench and the ball's quick panel.
 */
enum class HomeTab(
    val route: String,
    @StringRes val label: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    ACTIVITY("activity", R.string.tab_activity, Icons.Outlined.Bolt, Icons.Rounded.Bolt),
    CHATS("chats", R.string.tab_chats, Icons.Outlined.Forum, Icons.Rounded.Forum),
    INBOX("inbox", R.string.tab_inbox, Icons.Outlined.Inbox, Icons.Rounded.Inbox),
    TASKS("tasks", R.string.tab_tasks, Icons.Outlined.Assignment, Icons.Rounded.Assignment),
    SETTINGS("settings", R.string.tab_settings, Icons.Outlined.Settings, Icons.Rounded.Settings);

    companion object {
        /** Tabs in the user's saved order; unsaved/unknown routes keep enum order. */
        fun orderedBy(savedOrder: List<String>): List<HomeTab> {
            // 批v153: dedupe — a corrupted persisted order must not render duplicate
            // bar entries (the writer can't produce them; the storage layer can).
            val known = savedOrder.distinct().mapNotNull { route -> entries.firstOrNull { it.route == route } }
            val rest = entries.filterNot { known.contains(it) }
            return known + rest
        }

        /** The tab matching a saved start route, falling back to the workbench. */
        fun byRoute(route: String?): HomeTab = entries.firstOrNull { it.route == route } ?: ACTIVITY
    }
}

/**
 * 批v152: the tabs the bar/rail actually renders — the saved order minus the
 * user's hidden routes. SETTINGS is never hidden (the store refuses to persist
 * it too): it is the only route back to the visibility switches, so dropping it
 * would let the user lock himself out of every other tab. Because SETTINGS can
 * never be filtered out, the result is guaranteed non-empty.
 */
internal fun visibleTabs(savedOrder: List<String>, hidden: List<String>): List<HomeTab> {
    val hiddenSet = hidden.toSet() - HomeTab.SETTINGS.route
    return HomeTab.orderedBy(savedOrder).filterNot { it.route in hiddenSet }
}

/** 批v152: the cold-start tab, constrained to the visible set — a hidden start
 *  tab falls back to the first visible tab instead of opening an unreachable
 *  route as the NavHost start destination. */
internal fun resolveStartTab(savedStart: String?, tabs: List<HomeTab>): HomeTab =
    tabs.firstOrNull { it == HomeTab.byRoute(savedStart) } ?: tabs.firstOrNull() ?: HomeTab.ACTIVITY

private const val ROUTE_SERVERS = "servers"
private const val ROUTE_EDITOR = "editor"
private const val ROUTE_NEW_TASK = "conversation_new"
// 批v160: 会话项目管理 was a Settings leaf reached via a global bus + tab jump —
// Back landed on Settings instead of where the 管理 link lived. It is a real
// destination now, entered by push from the chats/activity bookmark and the
// project filter sheet.
private const val ROUTE_SESSION_PROJECTS = "session_projects"
// 批v176: 常用目录 per-device bindings, pushed from the file screen ⋯ menu and Settings.
private const val ROUTE_COMMON_DIRS = "common_dirs"

// Demoted former tabs, now full-screen pushes. The strings MUST stay stable:
// persisted homeTabOrder/startTab entries, codeg://tab/ deep links and floating
// ball action ids all reference them.
internal const val ROUTE_CHATS = "chats"
internal const val ROUTE_FOLDERS = "folders"
internal const val ROUTE_SEARCH = "search"

/**
 * Shape of a surface's device sequence for the horizontal device swipe (batch
 * v123; 批v151 revives the second shape for the demoted push surfaces).
 */
internal enum class DeviceSwipeScope {
    /** Aggregate "All" is index 0, devices follow (GlobalDeviceBar order). Used
     *  by Activity and — 批v151 — the Chats/Search pushes, whose aggregates the
     *  All chip now enters. */
    WITH_ALL,

    /** 批v151: Folders push — no aggregate exists there, devices are 0..n-1 and
     *  both ends fall through to [DeviceSwipeVerdict.Tab] (a no-op on pushes). */
    DEVICE_ONLY,
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
 * Pure arithmetic behind the device swipe: given the current index in the tab's
 * device sequence and the swipe [step] (+1 next / -1 previous), decide what the
 * gesture does. Falling off either end yields [DeviceSwipeVerdict.Tab] so the
 * outer tab-switch layer picks it up — mirroring the Activity tab's established
 * "inner first, outer at the edge" convention. The verdict names the DEVICE INDEX,
 * never a profile id — the caller resolves it against the live server list.
 * (批v151: [scope] selects the sequence shape — WITH_ALL counts the aggregate
 * "All" as index 0; DEVICE_ONLY has no All row and both ends are Tab.)
 */
internal fun resolveDeviceSwipeStep(
    currentIndex: Int,
    deviceCount: Int,
    step: Int,
    scope: DeviceSwipeScope = DeviceSwipeScope.WITH_ALL,
): DeviceSwipeVerdict {
    if (scope == DeviceSwipeScope.DEVICE_ONLY) {
        val next = currentIndex + step
        if (next < 0 || next >= deviceCount) return DeviceSwipeVerdict.Tab
        return DeviceSwipeVerdict.Device(deviceIndex = next)
    }
    // The sequence's logical bounds: All + devices → deviceCount+1 entries.
    val last = deviceCount // devices occupy 1..deviceCount
    val next = currentIndex + step
    if (next < 0 || next > last) return DeviceSwipeVerdict.Tab
    return when {
        next == 0 -> DeviceSwipeVerdict.All
        // WITH_ALL: device i sits at sequence index i+1.
        else -> DeviceSwipeVerdict.Device(deviceIndex = next - 1)
    }
}

/** 批v160 W9: the session-chip row's PROJECT dimension sequence — 全部项目 at 0,
 *  visible projects 1..n, 未添加 at n+1 (the row the bar renders in project mode).
 *  Entering from a focused device, the caller reads index -1 (before 全部项目):
 *  NEXT steps onto 全部项目, PREV falls through to the tab layer — the same
 *  "inner first, outer at the edge" convention as the device sequence. */
internal sealed interface ProjectSwipeVerdict {
    /** 0 = 全部项目, 1..n = the visible projects, n+1 = 未添加. */
    data class Chip(val index: Int) : ProjectSwipeVerdict

    /** Off the sequence's end — the outer tab layer takes the gesture. */
    data object Tab : ProjectSwipeVerdict
}

/** Pure arithmetic for the project swipe: [currentIndex] -1 = device focus,
 *  0 = 全部项目, 1..n projects, n+1 = 未添加 ([chipCount] = n+2). */
internal fun resolveProjectSwipeStep(currentIndex: Int, chipCount: Int, step: Int): ProjectSwipeVerdict {
    val next = currentIndex + step
    if (next < 0 || next >= chipCount) return ProjectSwipeVerdict.Tab
    return ProjectSwipeVerdict.Chip(next)
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
    // 批v172 D3: per-interface font scales (0 = follow global). "session" covers
    // the dynamic tab family (activity / chats / search / session detail).
    val sessionFontScale = appSettings.sessionFontScale
    val inboxFontScale = appSettings.inboxFontScale
    val taskhubFontScale = appSettings.taskhubFontScale
    val homeTabs = remember(appSettings.homeTabOrder, appSettings.hiddenHomeTabs) {
        visibleTabs(appSettings.homeTabOrder, appSettings.hiddenHomeTabs)
    }
    // The start destination is bound once per shell lifetime: NavHost rebuilds (and
    // wipes) the whole graph when startDestination changes, so a runtime edit must
    // not feed through here — it takes effect on the next cold start.
    // 批v153: rememberSaveable, not remember — the nav stack IS restored across
    // activity recreation/process death (its bottom entry was resolved under the
    // OLD start tab), so the anchor popUpTo keys on must restore with it; a plain
    // remember re-resolves under current settings and, after a start-tab hide, no
    // longer matches the restored stack bottom (bar taps then stack screens
    // instead of switching until the next cold start).
    val startTab = rememberSaveable { resolveStartTab(appSettings.startTab, homeTabs) }
    // 批v152 审查修正 + 批v153: the bar renders on EVERY tab route, visible or
    // not — a hidden tab pushed full-screen (ball action / workbench stat card)
    // has no in-screen back header (board/inbox), so dropping its bar left only
    // the system gesture as an exit. Hidden tabs are just not listed on the bar.
    val showBottomBar = HomeTab.entries.any { it.route == currentRoute }
    val tabRoutes = remember(homeTabs) { homeTabs.map { it.route }.toSet() }
    // 批v150: the demoted list surfaces keep the app-global device bar. They have
    // no in-screen device selector, so without the bar here an aggregate chats
    // list (chatsAll) would have no way to be entered or left (review BLOCKER).
    // 批v152: keyed off ALL tab routes, not just the visible ones — a tab hidden
    // from the bar can still be pushed (workbench quick entry / ball action) and
    // that screen must keep its device bar + status-bar inset.
    val barBearingRoutes = remember {
        HomeTab.entries.map { it.route }.toSet() + setOf(ROUTE_FOLDERS, ROUTE_SEARCH)
    }
    // Routes whose list registers a focus ring for the ball cycle/open gestures —
    // every tab surface (Activity and Chats are the only registering lists;
    // Folders/Search/Settings never did).
    // 批v153: keyed off ALL tab routes like barBearingRoutes above — the visible-only
    // set silently degraded the ball's cycle/open ring on hidden-tab screens (they
    // fell back to the last-owner path instead of the list in front of the user).
    // CHATS needed the explicit + back when v150 demoted it; it's a real tab again.
    val listRoutes = remember { HomeTab.entries.map { it.route }.toSet() }
    val selectedProfile by appViewModel.selectedProfile.collectAsStateWithLifecycle()
    // Aggregate "All devices" mode (Activity tab), owned by ServerRepository so the
    // ActivityViewModel can react without ViewModel-to-ViewModel injection.
    val activityAll by appViewModel.activityAll.collectAsStateWithLifecycle()
    // 批v153（UX 审查）：底栏收件箱徽标计数（草稿数，派发成功即删）。
    val inboxBadge by appViewModel.inboxBadge.collectAsStateWithLifecycle()
    // 批v160 W7: 动态 tab 徽标——智能体卡在等用户确认的会话数（watcher 全量，
    // 环境感知与收件箱徽标同款）。
    val activityBadge by appViewModel.activityBadge.collectAsStateWithLifecycle()
    // 系统级通知体系: the foreground start/stop DisposableEffect is GONE — the
    // watchers are process-lifetime now (CodegApplication.onCreate holds the
    // reference), otherwise the global start followed by this shell's stop on
    // dispose would drive the refcount to zero and kill polling in background.
    // Aggregate "全部" mode for the Chats tab + the project filter (batch v132).
    val chatsAll by appViewModel.chatsAll.collectAsStateWithLifecycle()
    // 批v151: the Search push joins the aggregate family (device bar All chip).
    val searchAll by appViewModel.searchAll.collectAsStateWithLifecycle()
    // 批v152: the task board joins too — 全部 aggregating every device's board.
    val tasksAll by appViewModel.tasksAll.collectAsStateWithLifecycle()
    val sessionProjects by appViewModel.sessionProjects.collectAsStateWithLifecycle()
    val sessionProjectMembers by appViewModel.sessionProjectMembers.collectAsStateWithLifecycle()
    val activeProjectId by appViewModel.activeProjectId.collectAsStateWithLifecycle()
    // 批v160 W9: hidden projects in the 全部 aggregate (bar long-press hide ⇄
    // filter sheet project column — one set, two surfaces).
    val hiddenProjectIds by appViewModel.hiddenProjectIds.collectAsStateWithLifecycle()
    // 旋转存活（第2轮审查修正）：旗标用 Saveable——manifest 无 configChanges，
    // 旋转重建 Activity 时普通 remember 归 false 会连人带输入一起拆掉；本库
    // 自身约定（InboxScreen 编辑态）就是"旋转不得关 sheet、丢输入即丢工作"。
    var showProjectSheet by rememberSaveable { mutableStateOf(false) }
    // 批v160 W9: the mode card anchored under the 全部 chip (点击) — device ⇄
    // project dimension switch. The project sheet moved to long-press.
    // R2-A P3: plain remember — a mode card resurrecting from process-death
    // restore (saveable true with no scope behind it) is worse than a menu that
    // closes on rotation; transient popups don't cross recreations by design.
    var showModeCard by remember { mutableStateOf(false) }
    // 批v150 quick-center sheets: the ball panel's 记录想法/创建任务/会话交接
    // cards land here — the shell owns them because the ball's actions do.
    var showQuickIdea by rememberSaveable { mutableStateOf(false) }
    var showQuickTask by rememberSaveable { mutableStateOf(false) }
    var showQuickHandoff by rememberSaveable { mutableStateOf(false) }
    // 批v151: ball "quickNewSession" — the global new-session sheet (P6).
    var showQuickNewSession by rememberSaveable { mutableStateOf(false) }
    // 批v172 D7: the quick-save-to-markdown sheet (ball + workbench card +
    // inbox long-press hosts it separately for prefilled ideas).
    var showQuickSave by rememberSaveable { mutableStateOf(false) }
    // 每次打开快照一个 token：派发/保存的完成回调可能在 sheet 被关掉、又被重新
    // 打开之后才迟到（VM 协程持有旧闭包）。旧 token 与当前不一致时只弹回执，
    // 不关掉用户刚打开的新 sheet。两个 sheet 分开计数——共用计数器时后开的会
    // 顶掉前一个的 token，理论上能让先开的 sheet 永远关不掉。
    val quickIdeaSeq = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    val quickTaskSeq = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    val quickNewSessionSeq = remember { java.util.concurrent.atomic.AtomicInteger(0) }
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
    // 返回是否接单（第1轮审查修正）：在途被丢时调用方要走回执兜底，不能
    // "会话创建了却无声消失"。落地守卫的异步丢弃不在此列（用户已导航走）。
    fun handoffTo(serverId: String, onLanded: suspend () -> Unit): Boolean {
        if (handoffInFlight) return false
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
        return true
    }
    // Open a conversation AND stamp it read in one place — every entry point that
    // shows a conversation from a list (Chats / Activity single / Activity aggregate /
    // Search) routes through this so the Activity unread dot always clears on open.
    // serverId scopes the mark (conversation ids are server-local); the device-local
    // write is fire-and-forget and never gates the navigation.
    //
    // [replaceTop] is for ball-driven SWITCHING (quick switch / cycle / openHighlighted):
    // when a conversation detail is already on top, the new one replaces it instead of
    // stacking, so Back lands on whatever screen the switching started from rather than
    // walking back through every session the ring visited (v136).
    // Returns true when the detail was actually pushed (false for the v134 dedupe), so
    // ball-driven callers advance their ring/cursor only when something was opened.
    fun openConversationAndMark(serverId: String?, conversationId: Int, replaceTop: Boolean = false): Boolean {
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
        if (isDuplicateDetailPush(top?.destination?.route, topId, conversationId, ownerId, topDevice)) return false
        // Marked only for a REAL open: re-triggering the conversation already
        // on screen must not bump its "recent" timestamp again.
        if (ownerId != null) appViewModel.markSessionRead("$ownerId:$conversationId")
        val route = if (ownerId != null) "conversation/$conversationId?serverId=$ownerId"
        else "conversation/$conversationId"
        if (replaceTop && isConversationDetailRoute(top?.destination?.route) && top != null) {
            // popUpTo by destination id: every detail entry shares the destination, so
            // the first match from the top is exactly the entry being replaced.
            nav.navigate(route) { popUpTo(top.destination.id) { inclusive = true } }
        } else {
            nav.navigate(route)
        }
        return true
    }
    // Open a conversation from an aggregate ("全部") row (v134 rework). The
    // owning-device selection still lands BEFORE the push (session detail reads
    // selectedProfile exactly once on entry), but the aggregate mode itself now
    // SURVIVES: the aggregate lists never consume selectedProfile, so the old
    // pre-push clear of chatsAll/activityAll only served to strand Back on the
    // single-device view instead of the 全部 surface the user came from. Each tab's
    // All flag is independent and untouched (the old per-tab exit note is moot).
    // v136: a ball-driven switch REPLACES a detail already on top (see above).
    fun openAggregateRow(serverId: String, conversationId: Int) {
        // 幽灵设备守卫（第2轮审查修正）：聚合行可能在设备被删/隐藏后仍带旧
        // serverId——不校验会把全局选中持久化到一个不存在的设备上。
        if (servers.none { it.id == serverId }) return
        handoffTo(serverId) { openConversationAndMark(serverId, conversationId, replaceTop = true) }
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
    LaunchedEffect(currentRoute) {
        barsVisible = true
        // R1-B P3-4: the mode card is rememberSaveable — without this, a route
        // change while it's open (deep link, notification tap) leaves it stale
        // true and it resurrects on the next bar-bearing surface.
        showModeCard = false
    }

    // Project-filter invariant (batch v132 review #3, v134 rework): the filter only
    // lives while a tab surface is in an aggregate mode. Adjudicate ONLY on tab
    // landings — a full-screen detail (conversation/handoff/project) pushed from an
    // aggregate surface must not clear the filter, or Back couldn't return to the
    // exact 全部 view it came from; the effect re-runs and enforces the invariant the
    // moment the tab is revealed again.
    androidx.compose.runtime.LaunchedEffect(currentRoute, activityAll, chatsAll, searchAll) {
        // currentBackStackEntryAsState starts at null for one frame after rotation /
        // process restore (the NavHost restores its graph during composition, after
        // the initial read). Adjudicating on that frame used to clear the project
        // filter while every aggregate flag survived the config change — wait for a
        // real route instead.
        if (currentRoute == null) return@LaunchedEffect
        // 批v150: the invariant surface is the four tabs PLUS the demoted list
        // pushes — a Chats/Folders/Search screen is still a list surface where a
        // stale filter would silently narrow the rows.
        if (currentRoute in barBearingRoutes) {
            val aggregateOn = (currentRoute == HomeTab.ACTIVITY.route && activityAll) ||
                (currentRoute == ROUTE_CHATS && chatsAll) ||
                (currentRoute == ROUTE_SEARCH && searchAll)
            // The project filter only narrows the Activity/Chats aggregates (Search
            // and Folders never read it) — clearing it on THEIR landings would strip
            // a live filter from an aggregate still parked in the back stack (审查
            // 修正：v134 的"Back 回到来时视图"承诺同样约束这个不变量).
            val filterNarrowsHere = currentRoute == HomeTab.ACTIVITY.route || currentRoute == ROUTE_CHATS
            if (filterNarrowsHere && !aggregateOn) appViewModel.setActiveProject(null)
        }
    }

    // Fewer than two visible devices → the device bar (and every aggregate context)
    // disappears; drop the modes instead of stranding the UI with no exit (review #13).
    androidx.compose.runtime.LaunchedEffect(servers.size) {
        if (servers.size < 2) appViewModel.exitAggregate()
    }

    // 批v152/批v153: a cold start on 动态 / 会话 / 任务板 should land on the ALL-devices view —
    // the same promise as entering those tabs from the bar/ball (mandate B2). This
    // is an ENTRY rule, not a route-keyed effect: a route-triggered "default to All"
    // would yank the user back to 全部 every time he returns from a conversation, a
    // rotation or a device add. 批v153: the one-shot lives on the AppViewModel — a
    // rememberSaveable flag also survives process death, which silently skipped the
    // rule on the most common "reopen the app" path; a VM field burns once per
    // process, re-applies after a restore, still stands down across rotation.
    androidx.compose.runtime.LaunchedEffect(servers.size) {
        if (appViewModel.launchDeviceDefaultApplied || servers.isEmpty()) return@LaunchedEffect
        if (servers.size >= 2) {
            when (startTab) {
                HomeTab.ACTIVITY -> appViewModel.setActivityAll(true)
                HomeTab.CHATS -> appViewModel.setChatsAll(true)
                HomeTab.TASKS -> appViewModel.setTasksAll(true)
                else -> {}
            }
        }
        appViewModel.launchDeviceDefaultApplied = true
    }

    // 批v160: "从哪里跳转过去的，还跳转回哪里" — programmatic cross-jumps (回执 →
    // 任务板, workbench quick entries, deep links, ball settings) remember the tab
    // they left in [pendingReturnTab]; the shell-level BackHandler under the
    // NavHost spends it once to send Back there. Free navigation (bar/rail/ball
    // tab actions/tab swipe) clears it instead — the user has moved on
    // deliberately, and Back from the new tab must behave as before. Route
    // string, not enum: survives process death alongside the restored nav stack.
    var pendingReturnTab by rememberSaveable { mutableStateOf<String?>(null) }

    val onTab: (HomeTab) -> Unit = { tab ->
        nav.navigate(tab.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    // 批v150: the demoted list surfaces (chats/folders/search) are full-screen
    // pushes — Back returns to wherever the user came from. launchSingleTop only
    // dedupes a re-tap of the same surface; stacking from different origins is
    // the push semantics working as intended (review 修订⑤).
    val pushSurface: (String) -> Unit = { route ->
        nav.navigate(route) { launchSingleTop = true }
    }
    // 批v152: every "user enters a tab" gesture (bottom bar / rail / ball action /
    // deep link / workbench quick entry / cold-start) goes through here. Two jobs:
    //
    // 1. 动态/会话/任务板 enter on the ALL-devices view — the mandate (批v153
    //    extends B2 to the workbench: the "what are all my devices doing" question
    //    must be answered the moment the app opens, not after a manual 全部 tap).
    //    Only on an actual entry (route differs): re-tapping the current tab, the
    //    device swipe and coming BACK from a detail must keep the user's device.
    // 2. A tab hidden via Settings is not in [homeTabs]; navigating to its route as
    //    a tab destination would still work, but the back stack would not treat it
    //    as a tab. Push it instead (it stays reachable from the ball/workbench).
    val enterTab: (HomeTab) -> Unit = { tab ->
        if (tab.route != currentRoute) {
            if (servers.size >= 2) when (tab) {
                HomeTab.ACTIVITY -> appViewModel.setActivityAll(true)
                HomeTab.CHATS -> appViewModel.setChatsAll(true)
                HomeTab.TASKS -> appViewModel.setTasksAll(true)
                else -> {}
            }
        }
        // 批v153: a hidden tab's route may already sit on the stack — the hidden
        // start tab the user is on right now, or one pushed earlier. Popping to it
        // keeps re-entry from stacking a second identical screen. R5 (反编译
        // navigation 2.8.5 核实)：false 同时覆盖两种情形——route 不在栈里（push
        // 兜底进面）与 route 就是栈顶（pushSurface 的 launchSingleTop 把它抵消
        // 成 no-op），两分支都不会叠出第二张同名屏。
        if (tab in homeTabs) onTab(tab)
        else if (!nav.popBackStack(tab.route, inclusive = false)) pushSurface(tab.route)
    }
    // 批v160: a programmatic cross-jump — records where the user is leaving so
    // Back can bring them back. No-ops the record when the jump starts off a
    // tab root (nothing to return to that a plain pop wouldn't already do).
    fun jumpToTab(tab: HomeTab) {
        if (currentRoute != null && currentRoute in listRoutes && currentRoute != tab.route) {
            pendingReturnTab = currentRoute
        }
        enterTab(tab)
    }
    // 批v160: free navigation — bar taps, the rail, the ball's tab actions, the
    // tab swipe. Never records; always clears a pending return so a stale one
    // can't hijack a later Back.
    fun freeTab(tab: HomeTab) {
        pendingReturnTab = null
        enterTab(tab)
    }

    // Horizontal swipe between the bottom tabs: drag left → next tab, right →
    // previous (the user's HomeTab order). Only active on tab routes on narrow
    // screens — full-screen pushes and the rail layout don't need it, and the
    // gesture modifier itself skips the system back-gesture edge band.
    //
    // With the device-swipe layer on, the swipe is claimed by the device sequence
    // first (the GlobalDeviceBar's own order): mid-sequence it switches devices
    // only, and falling off either END hands the gesture back to the tab switch —
    // the "inner first, outer at the edge" convention. 批v151: the three demoted
    // list pushes carry the device layer too (mandate §四: 手势快速切换设备) —
    // Chats/Search use WITH_ALL (their All chip enters the aggregate), Folders is
    // DEVICE_ONLY, and on pushes the Tab verdict is a NO-OP (there is no tab
    // sequence to fall through to from a pushed screen; currentTabIndex is -1 and
    // the old index arithmetic would land on homeTabs[-1+step]).
    val deviceSwipeScope = when (currentRoute) {
        HomeTab.ACTIVITY.route ->
            if (appSettings.activityDeviceSwipe) DeviceSwipeScope.WITH_ALL else null
        ROUTE_CHATS, ROUTE_SEARCH -> DeviceSwipeScope.WITH_ALL
        // 批v152: the task board has an aggregate now, so it joins the WITH_ALL
        // sequence (swipe left from a device lands on 全部 before leaving the tab).
        // 批v160 W5: unless the user picked 「切任务状态」 — then the swipe belongs
        // to the kanban's four columns (routed through [TaskSegmentBus] below)
        // and the device layer stands down entirely.
        HomeTab.TASKS.route ->
            if (appSettings.taskSwipeMode == app.codeg.android.core.datastore.TASK_SWIPE_DEVICES) {
                DeviceSwipeScope.WITH_ALL
            } else null
        ROUTE_FOLDERS -> DeviceSwipeScope.DEVICE_ONLY
        else -> null
    }
    val deviceSwipeOn = deviceSwipeScope != null && servers.size > 1
    val currentTabIndex = homeTabs.indexOfFirst { it.route == currentRoute }
    // Index inside the surface's device sequence: WITH_ALL counts the active
    // aggregate "All" as 0 (each surface reads its own flag); DEVICE_ONLY is
    // 0..n-1; null (no device layer) never reads it.
    val routeAllActive = (currentRoute == HomeTab.ACTIVITY.route && activityAll) ||
        (currentRoute == ROUTE_CHATS && chatsAll) ||
        (currentRoute == ROUTE_SEARCH && searchAll) ||
        (currentRoute == HomeTab.TASKS.route && tasksAll)
    val currentDeviceIndex = when (deviceSwipeScope) {
        DeviceSwipeScope.WITH_ALL ->
            if (routeAllActive) 0 else servers.indexOfFirst { it.id == selectedProfile?.id } + 1
        DeviceSwipeScope.DEVICE_ONLY ->
            servers.indexOfFirst { it.id == selectedProfile?.id }.coerceAtLeast(0)
        null -> 0
    }
    // 批v160 W9: the session chip row's project dimension — on the two session
    // surfaces once the mode card flipped them to 项目. Chips honor the aggregate
    // hidden set (long-press hide) + the settings order / count sort.
    val projectMode = appSettings.sessionChipMode == SESSION_CHIP_PROJECTS &&
        (currentRoute == HomeTab.ACTIVITY.route || currentRoute == ROUTE_CHATS)
    val projectChips = if (projectMode) {
        orderedProjectChips(
            projects = sessionProjects,
            savedOrder = appSettings.projectChipOrder,
            sortByCount = appSettings.projectChipSortByCount,
            counts = sessionProjectMembers.mapValues { (_, refs) -> refs.size },
        ).filter { it.id !in hiddenProjectIds }
    } else emptyList()
    // Row shape: 全部项目 + projects + 未添加.
    val projectChipCount = projectChips.size + 2
    // -1 = still device-focused (the row not entered yet); 0 = 全部项目;
    // 1..n = projects; n+1 = 未添加. A stale active id (project deleted/hidden
    // mid-flight) degrades to 0 = 全部项目 rather than a dead index.
    val currentProjectIndex = when {
        !projectMode -> 0
        activeProjectId == null -> if (routeAllActive) 0 else -1
        activeProjectId == UNASSIGNED_PROJECT_ID -> projectChipCount - 1
        else -> projectChips.indexOfFirst { it.id == activeProjectId } + 1
    }
    val swipeModifier = if (!wide && ((showBottomBar && currentTabIndex >= 0) ||
            (deviceSwipeOn && currentRoute in barBearingRoutes))
    ) {
        Modifier.tabSwipeNavigation(enabled = true) { direction ->
            val step = if (direction == SwipeDirection.NEXT) 1 else -1
            // 批v160 W5: on the Tasks tab with 「切任务状态」 semantics the swipe
            // steps the kanban's four columns — never the device sequence, never
            // the tab row (the board clamps at its ends, no wraparound).
            if (currentRoute == HomeTab.TASKS.route &&
                appSettings.taskSwipeMode != app.codeg.android.core.datastore.TASK_SWIPE_DEVICES
            ) {
                appViewModel.taskSegmentBus.step(step)
            } else if (projectMode && servers.size > 1 &&
                (currentRoute == HomeTab.ACTIVITY.route || currentRoute == ROUTE_CHATS)
            ) {
                // 批v160 W9: project mode owns the swipe on the two session surfaces
                // (the device layer stands down — the row shows projects now).
                // R2-A P2: the same servers.size gate the device layer carries — on a
                // single device the bar doesn't render, so an aggregate entered here
                // would have no visible exit (the R1-4 cycleProject lesson, swipe cut).
                when (val verdict = resolveProjectSwipeStep(
                    currentIndex = currentProjectIndex,
                    chipCount = projectChipCount,
                    step = step,
                )) {
                    is ProjectSwipeVerdict.Chip -> {
                        // Entering a project scope IS entering the aggregate: the
                        // flag must be on or the scope renders as a single device.
                        if (currentRoute == ROUTE_CHATS) appViewModel.setChatsAll(true)
                        else appViewModel.setActivityAll(true)
                        when (verdict.index) {
                            0 -> appViewModel.setActiveProject(null)
                            projectChipCount - 1 -> appViewModel.setActiveProject(UNASSIGNED_PROJECT_ID)
                            else -> projectChips.getOrNull(verdict.index - 1)
                                ?.let { appViewModel.setActiveProject(it.id) }
                        }
                    }
                    ProjectSwipeVerdict.Tab -> {
                        if (currentTabIndex >= 0) {
                            val next = currentTabIndex + step
                            if (next in homeTabs.indices) freeTab(homeTabs[next])
                        }
                    }
                }
            } else if (deviceSwipeOn && deviceSwipeScope != null) {
                when (val verdict = resolveDeviceSwipeStep(
                    currentIndex = currentDeviceIndex,
                    deviceCount = servers.size,
                    step = step,
                    scope = deviceSwipeScope,
                )) {
                    is DeviceSwipeVerdict.Device -> {
                        val server = servers.getOrNull(verdict.deviceIndex)
                        if (server == null) return@tabSwipeNavigation
                        // Leaving any aggregate context (All/Chats-All/Search-All/project filter).
                        appViewModel.exitAggregate()
                        appViewModel.selectServer(server.id)
                    }
                    is DeviceSwipeVerdict.All -> when (currentRoute) {
                        HomeTab.ACTIVITY.route -> appViewModel.setActivityAll(true)
                        ROUTE_CHATS -> appViewModel.setChatsAll(true)
                        ROUTE_SEARCH -> appViewModel.setSearchAll(true)
                        HomeTab.TASKS.route -> appViewModel.setTasksAll(true)
                        // DEVICE_ONLY surfaces have no aggregate to enter.
                        else -> {}
                    }
                    // Off the sequence's ends → the outer layer (tab switch) takes it —
                    // but a PUSH has no tab sequence: the verdict stays a no-op there.
                    DeviceSwipeVerdict.Tab -> {
                        if (currentTabIndex >= 0) {
                            val next = currentTabIndex + step
                            // 批v152 审查修正: a swipe IS a tab entry — route it
                            // through enterTab so 会话/任务板 land on 全部 like a
                            // bar tap does (bare onTab skipped the aggregate flag).
                            if (next in homeTabs.indices) freeTab(homeTabs[next])
                        }
                    }
                }
            } else if (currentTabIndex >= 0) {
                val next = currentTabIndex + step
                if (next in homeTabs.indices) freeTab(homeTabs[next])
            }
        }
    } else Modifier

    // Handle incoming codeg:// deep links.
    val pendingLink by app.codeg.android.core.common.DeepLinkBus.pending.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(pendingLink) {
        val route = pendingLink ?: return@LaunchedEffect
        fun toTab(it: HomeTab) = jumpToTab(it)
        when (route) {
            is app.codeg.android.core.common.DeepLinkRoute.OpenTab -> when (route.tab) {
                // 批v152: Chats is a first-class tab again — the deep link lands on
                // the tab (All-devices per enterTab), not a pushed copy on top of it.
                // 批v160: notification-style links record the origin tab so Back
                // returns there (jumpToTab).
                "chats" -> jumpToTab(HomeTab.CHATS)
                "projects", "folders" -> pushSurface(ROUTE_FOLDERS)
                "activity" -> toTab(HomeTab.ACTIVITY)
                "inbox" -> toTab(HomeTab.INBOX)
                "tasks" -> toTab(HomeTab.TASKS)
                "search" -> pushSurface(ROUTE_SEARCH)
                "settings" -> toTab(HomeTab.SETTINGS)
                else -> {}
            }
            is app.codeg.android.core.common.DeepLinkRoute.OpenConversation -> {
                // 批v160 W8: notification links carry the OWNING device — switch to
                // it first (same single-flight dance as every cross-device open),
                // otherwise server-local ids resolve against the wrong device.
                // Unknown / stale device ids degrade to the legacy selected-device
                // open (target = null).
                val target = route.serverId?.takeIf { s -> servers.any { it.id == s } }
                if (target != null && target != selectedProfile?.id) {
                    handoffTo(target) { openConversationAndMark(target, route.id) }
                } else {
                    openConversationAndMark(target, route.id)
                }
            }
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

    // The conversation the DETAIL on top is showing (null on tabs / composer / elsewhere).
    // The quick-switch rings and the list cycler need "where am I now" — the list
    // registrations can't answer it once the detail replaced the list (v136).
    fun currentConversationRef(): app.codeg.android.core.datastore.SessionRef? {
        val top = nav.currentBackStackEntry ?: return null
        if (!isConversationDetailRoute(top.destination.route)) return null
        val id = top.arguments?.getInt("id") ?: return null
        // A detail restored from a deep link / process death may have no serverId arg;
        // fall back to the live selection the same way openConversationAndMark does, or
        // a quick switch could hand out the conversation already on screen.
        val device = top.arguments?.getString("serverId") ?: selectedProfile?.id ?: return null
        return app.codeg.android.core.datastore.SessionRef(device, id)
    }

    // 批v160 W8: mirror "which conversation is on screen" to the notifier so the
    // system alert for the session the user is ALREADY looking at is suppressed
    // (and comes back the moment they navigate away while it still waits).
    // 系统级通知体系: the same mirror also reports whether the TASK BOARD is the
    // top route, so finished-task alerts the user is already looking at are
    // suppressed the same way.
    LaunchedEffect(nav) {
        nav.currentBackStackEntryFlow.collect {
            app.codeg.android.core.notify.ForegroundConversation.set(currentConversationRef())
            app.codeg.android.core.notify.ForegroundBoard.set(
                it.destination.route == HomeTab.TASKS.route,
            )
        }
    }

    // Quick switch · depth N: walk the N most recently opened conversations as a stable
    // ring (v136 — the old fixed ranks always jumped to the same session). The ring is
    // snapshotted so opening a member (which bumps its read mark) can't make the next
    // fire bounce between two sessions; see nextQuickSwitchStep. The ring only advances
    // once the handoff really lands, so a gesture dropped by the single-flight can't
    // skip a member.
    fun quickSwitchTo(depth: Int) {
        val step = appViewModel.peekQuickSwitchTarget(
            depth = depth,
            validServers = servers.map { it.id }.toSet(),
            current = currentConversationRef(),
        ) ?: return
        val target = step.ref
        // Same single-flight as the aggregate row taps: two selection dances racing
        // each other would push details resolved against whichever landed last.
        handoffTo(target.serverId) {
            // Same entry point as every other list open: the mark rides along, the v134
            // same-conversation dedupe applies, and a detail on top is replaced (v136).
            // Commit the cursor only when something was really opened — the dedupe must
            // not burn a ring slot.
            if (openConversationAndMark(target.serverId, target.conversationId, replaceTop = true)) {
                appViewModel.commitQuickSwitchStep(depth, step.ring)
            }
        }
    }

    // Cycle the visible session list (v132) and ENTER the highlighted session (v136).
    // v132 only highlighted it — the reported "selects but never opens" bug. The owner
    // is the tab whose list is on screen; from a detail/composer (list disposed), or a
    // tab whose list hasn't registered yet (still loading), it is the last list that
    // did — a bound gesture always lands on a conversation.
    fun cycleFocusAndOpen() {
        val hub = appViewModel.focusHub
        val current = currentConversationRef()
        // 批v150: the Chats push carries a live ring too, so it counts as a list
        // surface for the cycle gesture (same as when it was a tab).
        val onListTab = currentRoute != null && currentRoute in listRoutes
        // The owner is the list actually on screen (its registration is what the user
        // sees); otherwise the last list that registered, even if it has since been
        // disposed (the detail replaced it). A list that hasn't registered yet (still
        // loading) must NOT win over lastOwner, or the gesture would resolve an empty
        // ring and do nothing. Single-device Chats/Activity DO register (v136), so
        // "shows a list" and "owns a ring" now coincide once loaded.
        val target = resolveCycleTarget(
            currentRoute = currentRoute,
            isTabRoute = onListTab,
            hasLiveList = hub::hasLiveList,
            lastOwner = hub.lastOwner,
        )
        if (target == null) {
            // No list has ever registered (cold start): the bound gesture still has to
            // land on a conversation, so fall back to the most recent one.
            quickSwitchTo(1)
            return
        }
        // Peek only; commit after the handoff lands. A fire dropped by the selection
        // single-flight must not move the highlight and skip a session.
        val step = hub.peekCycle(
            owner = target.owner,
            current = current,
            stale = target.stale,
            validServers = servers.map { it.id }.toSet(),
        ) ?: run {
            // Owner's ring fell empty (list left / filtered to nothing): still land on
            // a conversation rather than swallowing the gesture.
            quickSwitchTo(1)
            return
        }
        handoffTo(step.item.ref.serverId) {
            if (openConversationAndMark(step.item.ref.serverId, step.item.ref.conversationId, replaceTop = true)) {
                hub.commitCycle(step)
            }
        }
    }

    // 批v160: 「循环切最近会话」 — same ring as cycleSession (running sessions
    // first, then most recent) but truncated to the owner-set depth N (1..10,
    // AppSettings.floatBallCycleDepth): N fires walk exactly those N sessions.
    // The highlight/preview card reads position N of N, and the cursor wraps
    // inside the truncated ring. Cold start (no list ever registered) and an
    // empty ring degrade to the most recent session, like cycleSession.
    fun cycleRecentCustom() {
        val hub = appViewModel.focusHub
        val current = currentConversationRef()
        val onListTab = currentRoute != null && currentRoute in listRoutes
        val target = resolveCycleTarget(
            currentRoute = currentRoute,
            isTabRoute = onListTab,
            hasLiveList = hub::hasLiveList,
            lastOwner = hub.lastOwner,
        )
        val depth = appViewModel.settings.value.floatBallCycleDepth
        if (target == null) {
            quickSwitchTo(1)
            return
        }
        val step = hub.peekCycle(
            owner = target.owner,
            current = current,
            stale = target.stale,
            validServers = servers.map { it.id }.toSet(),
            limit = depth,
        ) ?: run {
            quickSwitchTo(1)
            return
        }
        handoffTo(step.item.ref.serverId) {
            if (openConversationAndMark(step.item.ref.serverId, step.item.ref.conversationId, replaceTop = true)) {
                hub.commitCycle(step)
            }
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

    // 批v172c 审查R2-3: the one common closer for all five shell-level sheets.
    val closeQuickSheets: () -> Unit = {
        showQuickIdea = false
        showQuickTask = false
        showQuickHandoff = false
        showQuickNewSession = false
        showQuickSave = false
    }

    val onBallAction: (String) -> Unit = { id ->
        when (id) {
            "newSession" -> openNewTask()
            // 批v150 quick-center actions (panel 2×2 cards): idea/task/handoff
            // open shell-level sheets; search is the demoted push surface.
            // 批v172c 审查R2-3: 五张 shell 级 sheet 统一「开一张、关其余四张」
            // 的互斥闭包——运行期 sheet scrim 本就挡住了 activity 窗口（叠开
            // 不可达），但零散的关集是埋雷：新入口一加就会两开。
            "quickIdea" -> { closeQuickSheets(); showQuickIdea = true }
            // quickTask/quickNewSession 共用宿主 B（QuickActionViewModel）——
            // 一张 sheet 的 loadTarget 会清掉另一张正在填的目录/Agent 列表。
            "quickTask" -> { closeQuickSheets(); showQuickTask = true }
            "quickHandoff" -> { closeQuickSheets(); showQuickHandoff = true }
            "quickSearch" -> pushSurface(ROUTE_SEARCH)
            // 批v151: global new-session sheet — pick device, then folder, then jump.
            "quickNewSession" -> { closeQuickSheets(); showQuickNewSession = true }
            // 批v172 D7: quick-save is sheet-hosted like the other quick-center
            // actions; own VM (no host-B contention), still one sheet at a time.
            "quickSave" -> { closeQuickSheets(); showQuickSave = true }
            // 批v152: Chats is a tab again — the ball entry goes through enterTab
            // (All-devices on entry); folders/search stay pushes. 批v160: the ball
            // is free navigation — never records, clears any pending return.
            "tabChats" -> freeTab(HomeTab.CHATS)
            "tabFolders" -> pushSurface(ROUTE_FOLDERS)
            "tabActivity" -> freeTab(HomeTab.ACTIVITY)
            "tabSearch" -> pushSurface(ROUTE_SEARCH)
            "tabSettings" -> freeTab(HomeTab.SETTINGS)
            // 批v151: the two remaining quick-entry tabs join the ball catalog.
            "tabInbox" -> freeTab(HomeTab.INBOX)
            "tabTasks" -> freeTab(HomeTab.TASKS)
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
            "recentSession1" -> quickSwitchTo(1)
            "recentSession2" -> quickSwitchTo(2)
            "recentSession3" -> quickSwitchTo(3)
            "switchServerNext" -> switchServerBy(+1)
            "switchServerPrev" -> switchServerBy(-1)
            // Batch v132 two-segment gestures: cycle the project filter, then walk
            // the visible list. The project cycle only acts on the list tabs (no
            // invisible jumps from Settings/Folders); entering "All" is implicit on
            // the first fire so 【全部 → P1 → … → 全部】 works from anywhere on-tab.
            // Single/zero-device setups stay OUT: aggregate has no visible exit
            // there (the All chip needs >1 device) — R1-4.
            "cycleProject" -> if (servers.size >= 2) {
                // R2-A P3: cycle the VISIBLE chip list (settings order + long-press
                // hidden excluded) — a hidden project must never become the active
                // scope its chip no longer renders for (mirror of projectChips,
                // computed chip-mode-independently so the ball keeps working when
                // the row shows devices).
                val visibleProjects = orderedProjectChips(
                    projects = appViewModel.sessionProjects.value,
                    savedOrder = appSettings.projectChipOrder,
                    sortByCount = appSettings.projectChipSortByCount,
                    counts = sessionProjectMembers.mapValues { (_, refs) -> refs.size },
                ).filter { it.id !in hiddenProjectIds }
                when (currentRoute) {
                    HomeTab.ACTIVITY.route -> {
                        if (!activityAll) appViewModel.setActivityAll(true)
                        appViewModel.setActiveProject(
                            nextProjectFilter(appViewModel.activeProjectId.value, visibleProjects),
                        )
                    }
                    ROUTE_CHATS -> {
                        if (!chatsAll) appViewModel.setChatsAll(true)
                        appViewModel.setActiveProject(
                            nextProjectFilter(appViewModel.activeProjectId.value, visibleProjects),
                        )
                    }
                    else -> {}
                }
            }
            "cycleSession" -> cycleFocusAndOpen()
            // 批v160: cycle only the most recent N sessions (owner-set depth).
            "cycleSessionCustom" -> cycleRecentCustom()
            "openHighlighted" -> {
                // The visible tab's own highlight wins; otherwise open whatever owner
                // currently holds it. Passing the tab route as a FILTER (old behaviour)
                // returned null whenever the ring belonged to another list — e.g. a
                // cycle fired from a detail page — so the gesture silently did nothing.
                // Invalid devices are filtered out so a stale last-known list can't
                // select a deleted profile.
                val hub = appViewModel.focusHub
                val valid = servers.map { it.id }.toSet()
                val ref = currentRoute?.takeIf { it in listRoutes }?.let { hub.highlightedRef(it, valid) }
                    ?: hub.resolveHighlightedRef(owner = null, validServers = valid)
                ref?.let { openRef(it) }
            }
            // 批v151: flip the CURRENT list surface's aggregate mode (the "全部"
            // chip equivalent, bindable to a gesture). Folders has no aggregate
            // (DEVICE_ONLY swipe scope); >1 device only — a single-device
            // aggregate has no visible exit (same restraint as cycleProject, R1-4).
            "toggleAllDevices" -> if (servers.size >= 2) when (currentRoute) {
                HomeTab.ACTIVITY.route -> appViewModel.setActivityAll(!activityAll)
                ROUTE_CHATS -> appViewModel.setChatsAll(!chatsAll)
                ROUTE_SEARCH -> appViewModel.setSearchAll(!searchAll)
                HomeTab.TASKS.route -> appViewModel.setTasksAll(!tasksAll)
                else -> {}
            }
            // Bumps the repository tick the Activity tab's combine already rides —
            // same channel the workbench's manual refresh uses, no new bus.
            "refreshActivity" -> appViewModel.refreshActivityAll()
            "ballSettings" -> {
                // 批v160: a cross-jump like the others — Back returns to the tab
                // the ball was floating over (the FLOATING_BALL leaf still opens).
                jumpToTab(HomeTab.SETTINGS)
                FloatingBallSettingsBus.open()
            }
            // 批v171: cycle the session row's swipe binding without a settings trip —
            // walks SessionSwipeAction.RING (none → board → … → delete → none) one
            // step per fire, then reads back the persisted name for the toast.
            "cycleSwipeLeft", "cycleSwipeRight" -> {
                val left = id == "cycleSwipeLeft"
                val ring = SessionSwipeAction.RING
                val current = if (left) {
                    appViewModel.settings.value.sessionSwipeLeftAction
                } else {
                    appViewModel.settings.value.sessionSwipeRightAction
                }
                val next = ring[(ring.indexOf(current).coerceAtLeast(0) + 1) % ring.size]
                val labelRes = swipeActionLabelRes(next) ?: R.string.swipe_action_none
                if (left) appViewModel.setSessionSwipeLeftAction(next)
                else appViewModel.setSessionSwipeRightAction(next)
                android.widget.Toast.makeText(
                    ballContext,
                    ballContext.getString(
                        if (left) R.string.ball_swipe_left_toast else R.string.ball_swipe_right_toast,
                        ballContext.getString(labelRes),
                    ),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
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
                    // 批v160: bar taps are free navigation — clear any pending return.
                    { tab -> freeTab(tab) },
                    inboxBadge = inboxBadge,
                    // 批v160 W7: 动态 tab 的「等确认」徽标。
                    activityBadge = activityBadge,
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
        // 批v151: the device-swipe gesture ALSO rides the three demoted list pushes
        // (barBearingRoutes) — they have no bottom bar, but swiping devices there is
        // the whole point (mandate §四); they just skip the bar-hide nested scroll.
        val contentModifier =
            if (showBottomBar && !wide) Modifier.nestedScroll(barConnection).then(swipeModifier)
            else if (!wide && currentRoute in barBearingRoutes) swipeModifier
            else Modifier
        // The app-global device bar rides above every tab's content (hidden on
        // full-screen routes and for single-server setups, where there is nothing
        // to switch). It owns the status-bar inset; each tab's TopAppBar is told
        // to stop applying it too (windowInsets zeroed) so the height isn't doubled.
        androidx.compose.foundation.layout.Column(
            // consumeWindowInsets (批v151 审查修正)：shell 底栏已吃掉底部系统
            // inset——不消费的话，内容里的 navigationBarsPadding（如收件箱底部
            // 胶囊按钮）会在底栏之上再垫一层死区；底栏滚动收起时 padding 归零，
            // inset 自然归还。全屏推入路由 shell 底 padding 为 0，不受影响。
            Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().then(contentModifier),
        ) {
            // 批v150: bar-bearing surfaces are the four tabs PLUS the demoted list
            // pushes (review 修订①) — those three screens have no in-screen device
            // selector, and the aggregate chats list lives behind the All chip.
            if (currentRoute in barBearingRoutes && servers.size > 1) {
                GlobalDeviceBar(
                    servers = servers,
                    selectedId = selectedProfile?.id,
                    // Batch v132: the All chip now also lives on the Chats surface (where
                    // it toggles the aggregate list); the subtitle echoes the active
                    // project so a filter is never invisible. 批v151: Search's aggregate
                    // joins the chip (fan-out results), no project filter there.
                    // 批v152: the task board is the fourth aggregate surface — the All
                    // chip fans out every device's board.
                    // 批v160 W9: in project mode the All chip means 全部项目 — active
                    // when the surface's aggregate flag is on with no project focused.
                    allSelected = if (projectMode) {
                        ((activityAll && currentRoute == HomeTab.ACTIVITY.route) ||
                            (chatsAll && currentRoute == ROUTE_CHATS)) && activeProjectId == null
                    } else {
                        (activityAll && currentRoute == HomeTab.ACTIVITY.route) ||
                            (chatsAll && currentRoute == ROUTE_CHATS) ||
                            (searchAll && currentRoute == ROUTE_SEARCH) ||
                            (tasksAll && currentRoute == HomeTab.TASKS.route)
                    },
                    showAll = currentRoute == HomeTab.ACTIVITY.route || currentRoute == ROUTE_CHATS ||
                        currentRoute == ROUTE_SEARCH || currentRoute == HomeTab.TASKS.route,
                    allSubtitle = when {
                        // R1-B P3-5: a project scope only narrows the two session
                        // surfaces — echo its name ONLY there. On tasks/search the
                        // subtitle would advertise a filter that doesn't apply.
                        currentRoute == HomeTab.ACTIVITY.route || currentRoute == ROUTE_CHATS ->
                            when {
                                projectMode && activeProjectId == UNASSIGNED_PROJECT_ID ->
                                    stringResource(R.string.sessions_unassigned)
                                activeProjectId != null ->
                                    sessionProjects.firstOrNull { it.id == activeProjectId }?.name
                                else -> null
                            }
                        else -> null
                    },
                    onSelectServer = { id ->
                        // Tapping the already-focused device (or "All" while a device
                        // is focused) must not restart the feed — the aggregate poll
                        // treats any profile/tick emission as a mode change. chatsAll
                        // joins the guard so an aggregate Chats list is escapable too;
                        // 批v151: searchAll likewise.
                        if (activityAll || chatsAll || searchAll || tasksAll || id != selectedProfile?.id) {
                            appViewModel.exitAggregate()
                            appViewModel.selectServer(id)
                        }
                    },
                    onSelectAll = {
                        // Two-stage tap (v133 owner call): the FIRST tap on "All" just
                        // switches to the aggregate view; only a tap on an ALREADY-active
                        // "All" opens the mode card (批v160 W9: device ⇄ project switch —
                        // the project sheet moved to the chip's LONG-press). Programmatic
                        // entries — swipes, gestures — never open it. In project mode a
                        // tap on a focused project first re-widens to 全部项目, matching
                        // the chip row's own click semantics.
                        // Per-tab flags: CHATS reads chatsAll, every other route reads
                        // activityAll; refresh is pull-to-refresh on either tab.
                        // 批v151: SEARCH reads searchAll — the second stage stays inert
                        // there (no project scope exists on search).
                        when (currentRoute) {
                            ROUTE_CHATS -> {
                                if (!chatsAll || (projectMode && activeProjectId != null)) {
                                    appViewModel.setChatsAll(true)
                                    if (projectMode) appViewModel.setActiveProject(null)
                                } else showModeCard = true
                            }
                            ROUTE_SEARCH -> {
                                if (!searchAll) appViewModel.setSearchAll(true)
                            }
                            HomeTab.TASKS.route -> {
                                // No project scope on the board — second stage inert.
                                if (!tasksAll) appViewModel.setTasksAll(true)
                            }
                            else -> {
                                if (!activityAll || (projectMode && activeProjectId != null)) {
                                    appViewModel.setActivityAll(true)
                                    if (projectMode) appViewModel.setActiveProject(null)
                                } else showModeCard = true
                            }
                        }
                    },
                    onManageServers = { nav.navigate(ROUTE_SERVERS) },
                    onSetIncludeInAll = appViewModel::setServerIncludeInAll,
                    // ---- 批v160 W9: the project dimension ----
                    projectMode = projectMode,
                    projects = projectChips,
                    selectedProjectId = if (projectMode && activeProjectId != null &&
                        activeProjectId != UNASSIGNED_PROJECT_ID
                    ) activeProjectId else null,
                    unassignedSelected = projectMode && activeProjectId == UNASSIGNED_PROJECT_ID,
                    onSelectProject = { pid ->
                        // Entering a project scope IS entering the aggregate: the
                        // surface's flag must be on or the scope renders empty.
                        if (currentRoute == ROUTE_CHATS) appViewModel.setChatsAll(true)
                        else appViewModel.setActivityAll(true)
                        appViewModel.setActiveProject(pid)
                    },
                    onSelectUnassigned = {
                        if (currentRoute == ROUTE_CHATS) appViewModel.setChatsAll(true)
                        else appViewModel.setActivityAll(true)
                        appViewModel.setActiveProject(UNASSIGNED_PROJECT_ID)
                    },
                    // The project sheet rides the chip's long-press on the two session
                    // surfaces only (search/tasks have no project scope).
                    onLongPressAll = if (currentRoute == HomeTab.ACTIVITY.route || currentRoute == ROUTE_CHATS) {
                        { showProjectSheet = true }
                    } else null,
                    onHideProject = { pid ->
                        // Long-press hide = the aggregate's hidden-projects set (the
                        // filter sheet's project column shares it). Hiding the focused
                        // project falls back to 全部项目.
                        appViewModel.setProjectVisible(pid, false)
                        if (activeProjectId == pid) appViewModel.setActiveProject(null)
                    },
                    showModeCard = showModeCard,
                    onDismissModeCard = { showModeCard = false },
                    onChipModeChange = { mode ->
                        appViewModel.setSessionChipMode(mode)
                        // A mode switch lands on 全部 (全部设备 / 全部项目): clear any
                        // project scope, and keep the current surface's aggregate flag
                        // on so the landing scope actually applies.
                        appViewModel.setActiveProject(null)
                        when (currentRoute) {
                            ROUTE_CHATS -> appViewModel.setChatsAll(true)
                            HomeTab.ACTIVITY.route -> appViewModel.setActivityAll(true)
                            else -> {}
                        }
                    },
                )
            }
            androidx.compose.foundation.layout.Row {
                if (showBottomBar && wide) CodegNavRail(currentRoute, homeTabs, { tab -> freeTab(tab) })
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
            composable(ROUTE_CHATS) {
                InterfaceFontScale(sessionFontScale) {
                    CompositionLocalProvider(LocalBarsVisible provides barsVisible) {
                        SessionListScreen(
                            servers = servers,
                            selectedId = selectedProfile?.id,
                            onManageServers = { nav.navigate(ROUTE_SERVERS) },
                            // 批v153（UX 审查）：会话页自带的搜索入口，不再绕工作台。
                            onOpenSearch = { pushSurface(ROUTE_SEARCH) },
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
                            // 批v160: push the real destination — Back returns to this tab
                            // (was: bus + Settings tab jump, which stranded the user there).
                            onManageProjects = { nav.navigate(ROUTE_SESSION_PROJECTS) },
                            swipeLeft = if (appSettings.sessionSwipeEnabled) SessionSwipeAction.from(appSettings.sessionSwipeLeftAction) else null,
                            swipeRight = if (appSettings.sessionSwipeEnabled) SessionSwipeAction.from(appSettings.sessionSwipeRightAction) else null,
                        )
                    }
                }
            }
            composable(ROUTE_FOLDERS) {
                ProjectListScreen(onOpenFolder = { id -> nav.navigate("project/$id") })
            }
            composable(HomeTab.ACTIVITY.route) {
                InterfaceFontScale(sessionFontScale) {
                    ActivityScreen(
                        servers = servers,
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
                        // 批v160: push, not the Settings tab jump — Back returns to the
                        // activity tab (从哪里跳转过去的，还跳转回哪里).
                        onManageProjects = { nav.navigate(ROUTE_SESSION_PROJECTS) },
                        // 批v150 工作台接线（批v152：会话已回到五栏；批v160：直达改
                        // jumpToTab，Back 回工作台；任务板段位直达用一次性请求）。
                        // 批v153（UX 审查）：快捷行会话卡与底栏重复，换成「创建任务」
                        // 直达球的快速任务 sheet。
                        quickCards = appSettings.workbenchQuickCards,
                        onQuickAction = { id ->
                            // 终审: sheet-opening cards close the other sheets
                            // first, same discipline as onBallAction (unreachable
                            // today behind the scrim — defense in depth).
                            closeQuickSheets()
                            when (id) {
                                "quickTask" -> showQuickTask = true
                                "folders" -> pushSurface(ROUTE_FOLDERS)
                                "search" -> pushSurface(ROUTE_SEARCH)
                                "newSession" -> openNewTask()
                                "quickSave" -> showQuickSave = true
                            }
                        },
                        // 批v160: workbench quick entries are cross-jumps — Back returns
                        // to the workbench tab (jumpToTab).
                        onOpenAllChats = { jumpToTab(HomeTab.CHATS) },
                        onOpenTasks = { segment ->
                            appViewModel.requestTaskSegment(segment)
                            jumpToTab(HomeTab.TASKS)
                            // 批v152 审查修正: the stat cards count the workbench's
                            // scope (single device unless the Activity tab is in 全部).
                            // enterTab defaults the board to 全部, which would show a
                            // DIFFERENT, larger set than the number just tapped —
                            // mirror the workbench scope instead.
                            if (servers.size >= 2 && !activityAll) appViewModel.setTasksAll(false)
                        },
                        // 批v160 W7: 「等待你确认」行直达会话——先切到持有设备（与
                        // 聚合行同款 single-flight 舞步），落地后打开并清未读。
                        onOpenInteraction = { row ->
                            handoffTo(row.serverId) { openConversationAndMark(row.serverId, row.conversationId) }
                        },
                        // 批v163（舰队 M2，Q3）：自动化失败行 → 任务板自动化段（one-shot
                        // request，与任务段位直达同款纪律）。
                        onOpenAutomations = {
                            appViewModel.requestAutomationTab()
                            jumpToTab(HomeTab.TASKS)
                            // M6 审查（与 M3 审查 H1 同款）：the automation pane reads
                            // the task board's scope flow (tasksAll) while the failure
                            // row counts the workbench's (activityAll) — mirror both
                            // directions unconditionally or the landing pane can show
                            // a different device set than the row just tapped.
                            if (servers.size >= 2) appViewModel.setTasksAll(activityAll)
                        },
                        // 批v163（舰队 M3）：今日用量卡 → 设置页用量 leaf（一次性总线）。
                        onOpenUsage = {
                            app.codeg.android.feature.usage.UsageOpenBus.request()
                            jumpToTab(HomeTab.SETTINGS)
                            // M3 审查 H1: the usage page reads the task board's scope
                            // flow (tasksAll) while this card counts the workbench's
                            // (activityAll) — mirror the tapped scope or the landing
                            // page can show a different device set than the number
                            // just tapped. SETTINGS has no enterTab default to lean
                            // on, so mirror both directions unconditionally.
                            if (servers.size >= 2) appViewModel.setTasksAll(activityAll)
                        },
                        swipeLeft = if (appSettings.sessionSwipeEnabled) SessionSwipeAction.from(appSettings.sessionSwipeLeftAction) else null,
                        swipeRight = if (appSettings.sessionSwipeEnabled) SessionSwipeAction.from(appSettings.sessionSwipeRightAction) else null,
                    )
                }
            }
            composable(ROUTE_SEARCH) {
                InterfaceFontScale(sessionFontScale) {
                    // 批v151: All mode fans out across the aggregate scope; row opens ride
                    // the owning-device handoff dance (same single-flight as the Activity
                    // aggregate) so Back returns to the 全部 surface. Single-mode rows keep
                    // the plain selected-device open.
                    SearchScreen(
                        allMode = searchAll,
                        onOpenConversation = { id -> openConversationAndMark(selectedProfile?.id, id) },
                        onOpenAggregateRow = { serverId, conversationId ->
                            openAggregateRow(serverId, conversationId)
                        },
                        // 批v174 P3: 「添加到会话」 rides the owning-device single-flight
                        // dance (same as the Chats aggregate), 管理 routes to the real
                        // projects destination (v160: push, Back returns here).
                        onHandoff = { serverId, folderId, ref ->
                            handoffTo(serverId) { nav.navigate(handoffRoute(folderId, ref)) }
                        },
                        onManageProjects = { nav.navigate(ROUTE_SESSION_PROJECTS) },
                        swipeLeft = if (appSettings.sessionSwipeEnabled) SessionSwipeAction.from(appSettings.sessionSwipeLeftAction) else null,
                        swipeRight = if (appSettings.sessionSwipeEnabled) SessionSwipeAction.from(appSettings.sessionSwipeRightAction) else null,
                    )
                }
            }
            composable(HomeTab.SETTINGS.route) {
                SettingsScreen()
            }
            // 批v150: the inbox and the task board are promoted from the Activity
            // tab's sub-pages to first-class tabs (new mandate §一.2). Same hosts,
            // same ViewModels (per-destination owners replace the Activity entry) —
            // capability unchanged.
            composable(HomeTab.INBOX.route) {
                InterfaceFontScale(inboxFontScale) {
                    InboxScreen(
                        onOpenSession = { serverId, conversationId ->
                            // Same dance as 批v149 wired on the old host: the detail reads
                            // selectedProfile exactly once on entry, so the owning-device
                            // selection lands before the push (Back returns to the inbox).
                            handoffTo(serverId) { openConversationAndMark(serverId, conversationId) }
                        },
                        // 批v153（UX 审查）：任务模式派发回执直达任务板。批v160: cross-jump.
                        onOpenTasks = { jumpToTab(HomeTab.TASKS) },
                    )
                }
            }
            composable(HomeTab.TASKS.route) {
                InterfaceFontScale(taskhubFontScale) {
                    TaskHubScreen(
                        // 批v155: 查看会话 — same owning-device handoff dance as the
                        // inbox tab (the session screen reads selectedProfile once).
                        onOpenSession = { serverId, conversationId ->
                            handoffTo(serverId) { openConversationAndMark(serverId, conversationId) }
                        },
                    )
                }
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
                InterfaceFontScale(sessionFontScale) {
                    SessionDetailScreen(
                        onBack = { nav.popBackStack() },
                        onOpenSession = { folderId -> nav.navigate("$ROUTE_NEW_TASK?folderId=$folderId") },
                        onOpenFolder = { folderId -> nav.navigate("project/$folderId") },
                        onOpenManageProjects = { nav.navigate(ROUTE_FOLDERS) },
                        servers = servers,
                    )
                }
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
                InterfaceFontScale(sessionFontScale) {
                    SessionDetailScreen(
                        onBack = { nav.popBackStack() },
                        onOpenSession = { folderId -> nav.navigate("$ROUTE_NEW_TASK?folderId=$folderId") },
                        onOpenFolder = { folderId -> nav.navigate("project/$folderId") },
                        onOpenManageProjects = { nav.navigate(ROUTE_FOLDERS) },
                        servers = servers,
                    )
                }
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
                InterfaceFontScale(sessionFontScale) {
                    // The handoff entry sits directly above the list, so Back lands there
                    // naturally; its arguments also survive process death and re-seed the
                    // composer (draft is remembered-empty then, seed re-applies). No stack
                    // surgery needed.
                    SessionDetailScreen(
                        onBack = { nav.popBackStack() },
                        onOpenSession = { folderId -> nav.navigate("$ROUTE_NEW_TASK?folderId=$folderId") },
                        onOpenFolder = { folderId -> nav.navigate("project/$folderId") },
                        onOpenManageProjects = { nav.navigate(ROUTE_FOLDERS) },
                        servers = servers,
                        seedText = entry.arguments?.getString("handoff"),
                    )
                }
            }

            // 批v176: folderId = -1 + path → ephemeral browse of an arbitrary
            // absolute path (jump menu / cross-device), serverId pins the device
            // (page-local; the global selection is untouched). Plain "project/{id}"
            // navigations keep their exact old behavior — query params default null.
            composable(
                route = "project/{folderId}?path={path}&serverId={serverId}",
                arguments = listOf(
                    navArgument("folderId") { type = NavType.IntType },
                    navArgument("path") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("serverId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                ProjectDetailScreen(
                    onBack = { nav.popBackStack() },
                    onJump = { folderId, path, serverId ->
                        val params = mutableListOf<String>()
                        path?.let { params.add("path=${android.net.Uri.encode(it)}") }
                        serverId?.let { params.add("serverId=${android.net.Uri.encode(it)}") }
                        nav.navigate("project/$folderId" + if (params.isEmpty()) "" else "?" + params.joinToString("&"))
                    },
                    onManageDirs = { nav.navigate(ROUTE_COMMON_DIRS) },
                )
            }

            composable(ROUTE_COMMON_DIRS) {
                CommonDirsRoute(onBack = { nav.popBackStack() })
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
                    onToggleIncludeInAll = appViewModel::setServerIncludeInAll,
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

            // 批v160: standalone 会话项目管理 (SessionProjectsRoute owns its top bar).
            // Pushed from the chats/activity bookmark and the project filter sheet —
            // Back pops to that origin. The Settings tab keeps its in-place leaf.
            composable(ROUTE_SESSION_PROJECTS) {
                SessionProjectsRoute(onBack = { nav.popBackStack() })
            }
            }
            }
        }
    }

        // 批v160: Back from a jumped-to tab returns to the tab the jump came from
        // ("从哪里跳转过去的，还跳转回哪里"). One-shot — the record is consumed here
        // and cleared by free navigation (bar/rail/ball/swipe). Composed AFTER the
        // NavHost so it outranks NavHost's own back callback; gated to tab roots so
        // a pushed detail (conversation/editor/…) still pops first, and NavHost's
        // pop handles it. If the recorded tab is not a visible tab, enterTab's
        // hidden-tab branch (pop/push) still lands there.
        androidx.activity.compose.BackHandler(
            enabled = pendingReturnTab != null &&
                currentRoute != null && currentRoute in listRoutes &&
                currentRoute != pendingReturnTab,
        ) {
            val target = pendingReturnTab ?: return@BackHandler
            pendingReturnTab = null
            enterTab(HomeTab.byRoute(target))
        }

        // Tapping the 全部 chip (either list tab) opens the project filter sheet
        // (batch v132): 全部会话 / a project / create-and-activate. Rendered here —
        // the sheet must float above whatever tab is showing, and both list tabs'
        // chip taps route through onSelectAll into this one instance.
        if (showProjectSheet) {
            ProjectFilterSheet(
                // R4-B A2: hidden projects must not be activatable from the
                // sheet — same invariant as cycleProject (a scope whose chip no
                // longer renders). Unhide lives in the W2 filter sheet.
                projects = sessionProjects.filter { it.id !in hiddenProjectIds },
                counts = sessionProjectMembers.mapValues { it.value.size },
                activeId = activeProjectId,
                onSelect = { pid ->
                    // R1-B P2-1: same guard as the chip row — entering a project
                    // scope IS entering the aggregate. Without it the scope would
                    // hang off a single-device view and render as an empty list.
                    if (currentRoute == ROUTE_CHATS) appViewModel.setChatsAll(true)
                    else appViewModel.setActivityAll(true)
                    appViewModel.setActiveProject(pid)
                    showProjectSheet = false
                },
                onCreateActivate = { name ->
                    // Create-and-filter in one step (R1-1); close the sheet so the
                    // freshly filtered list is immediately visible (R2-9). Same
                    // aggregate guard as onSelect.
                    if (currentRoute == ROUTE_CHATS) appViewModel.setChatsAll(true)
                    else appViewModel.setActivityAll(true)
                    appViewModel.createAndActivateProject(name)
                    showProjectSheet = false
                },
                onManage = {
                    showProjectSheet = false
                    // 批v160: push — Back returns to the tab the 全部 chip lives on.
                    nav.navigate(ROUTE_SESSION_PROJECTS)
                },
                onDismiss = { showProjectSheet = false },
            )
        }

        // 批v150 quick-center sheets — shell-level like the project filter sheet,
        // so the ball's cards work from ANY surface underneath. The VM below is
        // the SAME activity-scoped instance the sheets resolve (host B).
        val quickActionViewModel: QuickActionViewModel = hiltViewModel()
        val quickConfirmed by quickActionViewModel.confirmedRef.collectAsStateWithLifecycle()
        if (showQuickIdea) {
            val openToken = remember { quickIdeaSeq.incrementAndGet() }
            QuickIdeaSheet(
                onDismiss = { if (quickIdeaSeq.get() == openToken) showQuickIdea = false },
            )
        }
        if (showQuickTask) {
            val openToken = remember { quickTaskSeq.incrementAndGet() }
            QuickTaskSheet(
                onDismiss = { if (quickTaskSeq.get() == openToken) showQuickTask = false },
            )
        }
        if (showQuickHandoff) {
            RecentSessionPickerSheet(
                onPick = { serverId, conversationId ->
                    showQuickHandoff = false
                    // Same owning-device dance as every aggregate open (single-
                    // flight); when the detail lands, the bus auto-expands its
                    // HandoffSheet — the handoff composer with real data (v141).
                    handoffTo(serverId) {
                        openConversationAndMark(serverId, conversationId, replaceTop = true)
                        app.codeg.android.feature.handoff.HandoffAutoOpenBus.fire(serverId, conversationId)
                    }
                },
                onDismiss = { showQuickHandoff = false },
            )
        }
        // 批v151: the ball's 全局新建会话 sheet — session CONFIRMED closes the
        // sheet and jumps to the created conversation (no receipt dialog), via
        // the same owning-device dance as the inbox's v149 session dispatch.
        // 第1轮审查修正：跳转没走成时（单飞被占 / 迟到回调）回执兜底——
        // "创建了但没下文"至少留下一条全局可见记录。
        // 批v172 D7: the ball's quick-save sheet — title + body + device + folder
        // → <标题>.md on the device. The VM here resolves to the activity scope
        // (InboxScreen's host gets its own back-entry-scoped instance); the
        // closeQuickSheets() closures keep it exclusive with the other sheets.
        if (showQuickSave) {
            QuickSaveSheet(onDismiss = { showQuickSave = false })
        }
        if (showQuickNewSession) {
            val openToken = remember { quickNewSessionSeq.incrementAndGet() }
            QuickNewSessionSheet(
                onConfirmedSession = { ref ->
                    val convId = ref.remoteTodoId
                    // 批v153: the token alone misses one path — the sheet closed (and not
                    // reopened) while the dispatch was in flight leaves the sequence
                    // untouched, so a late CONFIRMED would yank the user (possibly
                    // mid-quickTask) to the created conversation. Demand the live flag.
                    if (showQuickNewSession && quickNewSessionSeq.get() == openToken && convId != null) {
                        showQuickNewSession = false
                        val accepted = handoffTo(ref.serverId) { openConversationAndMark(ref.serverId, convId) }
                        if (!accepted) quickActionViewModel.confirmCreated(ref)
                    } else {
                        quickActionViewModel.confirmCreated(ref)
                    }
                },
                onDismiss = { if (quickNewSessionSeq.get() == openToken) showQuickNewSession = false },
            )
        }

        // B-7 屏级回执（审查轮1 W2 上移）：快捷创建的 CONFIRMED 结果在这里渲染，
        // sheet 中途被关也不吞回执。
        quickConfirmed?.let { ref ->
            val noun = stringResource(
                if (ref.sessionMode) R.string.dispatch_mode_session else R.string.dispatch_mode_task,
            )
            AlertDialog(
                onDismissRequest = { quickActionViewModel.clearConfirmed() },
                title = { Text(stringResource(R.string.dispatch_created_title, noun)) },
                text = {
                    Text(
                        stringResource(
                            R.string.dispatch_created_body,
                            ref.serverName.ifBlank { ref.serverId },
                            ref.title,
                        ),
                    )
                },
                confirmButton = {
                    // 批v153（UX 审查）：回执闭合「派出去之后呢」——能自动跳会话的
                    // CONFIRMED（sessionMode + convId 非空）根本不会渲染本对话框，
                    // 所以 gate 只看 state：会话模式跳转被拒/迟到的兜底回执同样
                    // 给「去任务板」直达（R4 墨菲审查：原 !sessionMode 漏了这条死路）。
                    Row {
                        if (ref.state == DispatchState.CONFIRMED) {
                            TextButton(onClick = {
                                quickActionViewModel.clearConfirmed()
                                // 批v160: cross-jump — Back from the board returns here.
                                jumpToTab(HomeTab.TASKS)
                            }) {
                                Text(stringResource(R.string.receipt_open_task_board))
                            }
                        }
                        TextButton(onClick = { quickActionViewModel.clearConfirmed() }) {
                            Text(stringResource(R.string.common_ok))
                        }
                    }
                },
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
                    label = { Text(stringResource(tab.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    alwaysShowLabel = true,
                    colors = androidx.compose.material3.NavigationRailItemDefaults.colors(
                        // 批v170 克制化：全局 chrome（底栏/侧栏）选中态去
                        // accent——浅灰指示胶囊+浅色文字，accent 额度让给屏内 CTA。
                        selectedIconColor = colors.textPrimary,
                        selectedTextColor = colors.textPrimary,
                        unselectedIconColor = colors.textTertiary,
                        unselectedTextColor = colors.textTertiary,
                        indicatorColor = colors.textPrimary.copy(alpha = 0.08f),
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
    inboxBadge: Int = 0,
    // 批v160 W7: 动态 tab 的「智能体等你确认」徽标。
    activityBadge: Int = 0,
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
                        // 批v153（UX 审查）：收件箱有草稿时给环境感知徽标——不用切
                        // 过去才知道有想法等处理。横屏 NavRail 不挂（那台设备上
                        // 收件箱一般不是主要入口）。
                        BadgedBox(badge = {
                            if (tab == HomeTab.INBOX && inboxBadge > 0) {
                                Badge { Text(inboxBadge.toString()) }
                            }
                            // 批v160 W7: 动态 tab 徽标——智能体卡在等用户确认
                            // （权限/提问/计划）的会话数。横屏 NavRail 同样不挂。
                            if (tab == HomeTab.ACTIVITY && activityBadge > 0) {
                                Badge { Text(activityBadge.toString()) }
                            }
                        }) {
                            Icon(
                                if (selected) tab.selectedIcon else tab.icon,
                                contentDescription = stringResource(tab.label),
                            )
                        }
                    },
                    label = { Text(stringResource(tab.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        // 批v170 克制化：全局 chrome（底栏/侧栏）选中态去
                        // accent——浅灰指示胶囊+浅色文字，accent 额度让给屏内 CTA。
                        selectedIconColor = colors.textPrimary,
                        selectedTextColor = colors.textPrimary,
                        unselectedIconColor = colors.textTertiary,
                        unselectedTextColor = colors.textTertiary,
                        indicatorColor = colors.textPrimary.copy(alpha = 0.08f),
                    ),
                )
            }
        }
    }
}
