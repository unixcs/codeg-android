package app.codeg.android.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Light / Dark / System appearance, mirroring the iOS `AppearanceMode`. */
enum class ThemeMode(val id: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun from(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/** App-wide appearance preferences. [accentId] maps to a design-system palette. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentId: String = DEFAULT_ACCENT,
    /** Bottom-tab display order as route names ("chats", "folders", …). Missing
     *  entries fall back to their enum order; unknown names are dropped. */
    val homeTabOrder: List<String> = emptyList(),
    /** 批v152: bottom-tab routes hidden by the user (Settings shows all five, the
     *  bar/rail renders `all - hidden`). SETTINGS is never hideable — the settings
     *  page is the only way back, so it is filtered out of the stored list too. */
    val hiddenHomeTabs: List<String> = emptyList(),
    /** Route name of the tab the app opens on cold start (default: activity). */
    val startTab: String = DEFAULT_START_TAB,
    /** 批v152: workbench 总览区 (stats + quick row) folded by default on cold start.
     *  A per-session manual toggle still wins for the rest of the process. */
    val workbenchOverviewExpandedDefault: Boolean = true,
    /** 批v152: workbench 需要处理区 folded by default on cold start. */
    val workbenchAttentionExpandedDefault: Boolean = true,
    /** Horizontal swipe between devices while on the Activity tab (default: off).
     *  批v150: the Folders twin toggle retired with the Folders tab. */
    val activityDeviceSwipe: Boolean = false,
    /** Floating ball on/off (batch v126; default on — it's a primary nav surface). */
    val floatBallEnabled: Boolean = true,
    /** Floating ball size tier: "small" | "medium" | "large" (unknown → medium). */
    val floatBallSize: String = DEFAULT_FLOAT_BALL_SIZE,
    /** 批v160: how many recent sessions 「循环切最近会话」 walks (1..10, running
     *  sessions first per the list registration order). */
    val floatBallCycleDepth: Int = DEFAULT_FLOAT_BALL_CYCLE_DEPTH,
    /** Inbox "new idea" button placement (批v151): "corner" = classic M3 FAB
     *  bottom-right (default) | "centerWide" = bottom-center pill half the
     *  screen wide (either thumb reaches it). */
    val inboxNewIdeaStyle: String = DEFAULT_INBOX_IDEA_STYLE,
    /** 批v160: idea list columns — 1 = classic single column (default),
     *  2 = two-column grid for denser scanning. */
    val inboxColumns: Int = DEFAULT_INBOX_COLUMNS,
    /** 批v160 W8: system notification when an agent blocks waiting on the user
     *  (permission / question / plan approval). Default ON. */
    val notifyInteractiveEnabled: Boolean = true,
    /** 系统级通知体系: task-board outcomes (done / failed) + turn completions
     *  posted on the task_results channel. Default ON. */
    val notifyTaskResultsEnabled: Boolean = true,
    /** 系统级通知体系: device-offline alerts on the silent device_events channel.
     *  Default ON. */
    val notifyDeviceEventsEnabled: Boolean = true,
    /** 批v160 W8: keep the interaction listener alive in the background via a
     *  foreground service (dataSync). Default OFF — it costs battery; users on
     *  aggressive OEM ROMs opt in. */
    val keepAliveEnabled: Boolean = false,
    /** 批v160 W5: the Tasks tab's horizontal-swipe semantics — "segments" =
     *  step between the board's four kanban columns (default), "devices" =
     *  the cross-device sequence (the pre-v160 behavior). */
    val taskSwipeMode: String = DEFAULT_TASK_SWIPE_MODE,
    /** 批v161: the task board's shape — "kanban" (default, the web-parity
     *  board) or "list" (the pre-v160 four-status tabs + one list). */
    val taskBoardLayout: String = DEFAULT_TASK_BOARD_LAYOUT,
    /** 批v161: columns shown per kanban page — 1 or 2 (default 2 on phones so a
     *  narrow screen sees two complete columns; tablets keep all four). */
    val taskBoardKanbanColumns: Int = DEFAULT_TASK_BOARD_KANBAN_COLUMNS,
    /** 批v161: list mode's column count — 1 (default, the classic single list)
     *  or 2 (a denser two-column grid). */
    val taskBoardListColumns: Int = DEFAULT_TASK_BOARD_LIST_COLUMNS,
    /** 批v160 W9: the top chip row's dimension on 动态/会话 — "devices" (default,
     *  the pre-v160 row) or "projects" (全部 + project chips + 未添加). */
    val sessionChipMode: String = DEFAULT_SESSION_CHIP_MODE,
    /** 批v168: the session row's swipe bindings — action ids from the long-press
     *  menu catalog, or "none" to disable that direction (right swipe defaults
     *  to 加入任务板, left swipe to 复制会话引用). The feature layer resolves the
     *  id ([app.codeg.android.feature.sessions.SessionSwipeAction.from]). */
    val sessionSwipeLeftAction: String = DEFAULT_SESSION_SWIPE_LEFT,
    val sessionSwipeRightAction: String = DEFAULT_SESSION_SWIPE_RIGHT,
    /** 批v171: master switch for the session-row swipe gestures (default on). */
    val sessionSwipeEnabled: Boolean = true,
    /** 批v171: the inbox idea card's swipe bindings (默认左=复制引用 右=派发).
     *  Ids from INBOX_SWIPE_ACTIONS, or "none". */
    val inboxSwipeLeftAction: String = DEFAULT_INBOX_SWIPE_LEFT,
    val inboxSwipeRightAction: String = DEFAULT_INBOX_SWIPE_RIGHT,
    /** 批v171: master switch for the inbox card swipe gestures (default on). */
    val inboxSwipeEnabled: Boolean = true,
    /** 批v160 W9: the projects' manual left-to-right chip order (project ids).
     *  Missing ids append after the known ones; stale ids are ignored. */
    val projectChipOrder: List<String> = emptyList(),
    /** 批v160 W9: order projects by session count (desc) instead of the manual
     *  order. Default OFF — the manual order wins. */
    val projectChipSortByCount: Boolean = false,
    /** Multiplier ladder over the system font scale (0.85 / 1.0 / 1.15 / 1.3). */
    val fontScale: Float = 1f,
    /** 批v172 D3: per-interface font scale — ABSOLUTE multiplier over the SYSTEM
     *  scale, replacing the global one for that interface. 0 = follow global.
     *  Legal values are the FONT_STEPS ladder (or 0); anything else is cleaned
     *  on read back into the usable band. */
    val sessionFontScale: Float = 0f,
    val inboxFontScale: Float = 0f,
    val taskhubFontScale: Float = 0f,
    /** 批v171（经典外观）: false = restrained selected-state (v170 浅灰, default),
     *  true = the pre-v170 accent-fill skin — one toggle, four colour roles. */
    val classicLook: Boolean = false,
    /** Dispatch-sheet defaults (v1.0.1-fengx.34): preselected device id, folder
     *  id/name and agent wire name. Blank device / id <= 0 / blank agent = unset
     *  (the sheet opens with its previous no-default behavior). */
    val dispatchDefaultDevice: String = "",
    val dispatchDefaultFolderId: Int = -1,
    val dispatchDefaultFolderName: String = "",
    val dispatchDefaultAgent: String = "",
    /** P0: remember the board form's 「创建后立即开始」 choice (default off —
     *  a tap on 创建任务 must not silently start a run). */
    val taskAutoStartDefault: Boolean = false,
) {
    companion object {
        // 批v150 §2.1 克制的青蓝：持久化层默认值必须与 AccentPalette.DEFAULT 的
        // id 一致（那边只兜「未知 id」，新用户拿到的是这里的值）。显式选过
        // 配色的用户存的是自己的 id，不受影响。
        const val DEFAULT_ACCENT = "teal"
        // The app opens on the workbench unless the user picked another start tab.
        const val DEFAULT_START_TAB = "activity"
        const val DEFAULT_FLOAT_BALL_SIZE = "medium"
        const val DEFAULT_FLOAT_BALL_CYCLE_DEPTH = 5
        const val DEFAULT_INBOX_IDEA_STYLE = INBOX_IDEA_STYLE_CORNER
        const val DEFAULT_INBOX_COLUMNS = 1
        // 批v171: inbox card swipe defaults mirror the session row's spirit —
        // 左滑复制引用，右滑该域的高频主动作（收件箱=派发）。
        const val DEFAULT_INBOX_SWIPE_LEFT = "copy"
        const val DEFAULT_INBOX_SWIPE_RIGHT = "dispatch"
        const val DEFAULT_TASK_SWIPE_MODE = TASK_SWIPE_SEGMENTS
        const val DEFAULT_TASK_BOARD_LAYOUT = TASK_BOARD_KANBAN
        const val DEFAULT_TASK_BOARD_KANBAN_COLUMNS = 2
        const val DEFAULT_TASK_BOARD_LIST_COLUMNS = 1
        const val DEFAULT_SESSION_CHIP_MODE = SESSION_CHIP_DEVICES
        const val DEFAULT_SESSION_SWIPE_LEFT = "copy"
        const val DEFAULT_SESSION_SWIPE_RIGHT = "board"
        /** The initial value [AppSettingsStore.settings] exposes before DataStore's
         *  first emission; used to detect "settings not loaded yet". */
        val INITIAL = AppSettings()
    }
}

/** The five tab routes in the enum's default order. Kept as plain strings so the
 *  data layer stays free of UI types (feature/main's HomeTab mirrors this list). */
private val HOME_TAB_ROUTES = listOf("activity", "chats", "inbox", "tasks", "settings")

/**
 * 批v152 read-side migration: orders saved before 会话 returned as a tab never
 * mention its route, and the tab order resolver appends unknown tabs AFTER the
 * saved ones — which would park 会话 behind 设置. Insert it right after 动态 so an
 * upgraded bar matches the fresh-install default instead of a surprise tail slot.
 */
internal fun migrateHomeTabOrder(saved: List<String>): List<String> {
    // 批v153: dedupe like the other resolvers — a corrupted stored order must not
    // leak duplicate routes into the bar (the writer can't produce them; the
    // storage layer can). Same defense HomeTab.orderedBy got in v153 R1.
    val clean = saved.distinct()
    if (clean.isEmpty() || "chats" in clean) return clean
    val after = clean.indexOf("activity")
    return if (after < 0) listOf("chats") + clean
    else clean.toMutableList().apply { add(after + 1, "chats") }
}

/** The order a cold start/bar renders: saved known routes first, then every tab
 *  the order does not mention yet (same rule as HomeTab.orderedBy). */
private fun resolvedTabOrder(saved: List<String>): List<String> =
    (saved.filter { it in HOME_TAB_ROUTES } + HOME_TAB_ROUTES.filterNot { it in saved }).distinct()

/**
 * Persists appearance + shell settings (theme, accent, bottom-tab order, cold-start
 * tab). Applied app-wide instantly; never synced to the server (matches iOS).
 */
@Singleton
class AppSettingsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    /** Last settings emission (null until DataStore's first read). A ViewModel
     *  created after the app reports settings loaded can seed derived state from
     *  the real values instead of flashing the defaults for a frame. */
    @Volatile
    private var latest: AppSettings? = null
    val current: AppSettings? get() = latest

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            themeMode = ThemeMode.from(prefs[MODE]),
            accentId = prefs[ACCENT] ?: AppSettings.DEFAULT_ACCENT,
            // Comma-joined route names: DataStore's stringSet doesn't guarantee order,
            // and the whole point of this pref is the ordering. Route names contain
            // no commas.
            homeTabOrder = migrateHomeTabOrder(
                prefs[TAB_ORDER]?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
            ),
            hiddenHomeTabs = prefs[HIDDEN_TABS]?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
            startTab = prefs[START_TAB] ?: AppSettings.DEFAULT_START_TAB,
            workbenchOverviewExpandedDefault = prefs[WORKBENCH_OVERVIEW_DEFAULT] ?: true,
            workbenchAttentionExpandedDefault = prefs[WORKBENCH_ATTENTION_DEFAULT] ?: true,
            activityDeviceSwipe = prefs[ACTIVITY_DEVICE_SWIPE] ?: false,
            floatBallEnabled = prefs[FLOAT_BALL_ENABLED] ?: true,
            floatBallSize = prefs[FLOAT_BALL_SIZE]?.takeIf { it in FLOAT_BALL_SIZES } ?: AppSettings.DEFAULT_FLOAT_BALL_SIZE,
            floatBallCycleDepth = (prefs[FLOAT_BALL_CYCLE_DEPTH] ?: AppSettings.DEFAULT_FLOAT_BALL_CYCLE_DEPTH)
                .coerceIn(FLOAT_BALL_CYCLE_MIN, FLOAT_BALL_CYCLE_MAX),
            inboxNewIdeaStyle = prefs[INBOX_NEW_IDEA_STYLE]?.takeIf { it in INBOX_IDEA_STYLES }
                ?: AppSettings.DEFAULT_INBOX_IDEA_STYLE,
            inboxColumns = (prefs[INBOX_COLUMNS] ?: AppSettings.DEFAULT_INBOX_COLUMNS).coerceIn(1, 2),
            notifyInteractiveEnabled = prefs[NOTIFY_INTERACTIVE_ENABLED] ?: true,
            notifyTaskResultsEnabled = prefs[NOTIFY_TASK_RESULTS_ENABLED] ?: true,
            notifyDeviceEventsEnabled = prefs[NOTIFY_DEVICE_EVENTS_ENABLED] ?: true,
            keepAliveEnabled = prefs[KEEP_ALIVE_ENABLED] ?: false,
            taskSwipeMode = prefs[TASK_SWIPE_MODE]?.takeIf { it in TASK_SWIPE_MODES }
                ?: AppSettings.DEFAULT_TASK_SWIPE_MODE,
            taskBoardLayout = prefs[TASK_BOARD_LAYOUT]?.takeIf { it in TASK_BOARD_LAYOUTS }
                ?: AppSettings.DEFAULT_TASK_BOARD_LAYOUT,
            taskBoardKanbanColumns = (prefs[TASK_BOARD_KANBAN_COLUMNS]
                ?: AppSettings.DEFAULT_TASK_BOARD_KANBAN_COLUMNS).coerceIn(1, 2),
            taskBoardListColumns = (prefs[TASK_BOARD_LIST_COLUMNS]
                ?: AppSettings.DEFAULT_TASK_BOARD_LIST_COLUMNS).coerceIn(1, 2),
            sessionChipMode = prefs[SESSION_CHIP_MODE]?.takeIf { it in SESSION_CHIP_MODES }
                ?: AppSettings.DEFAULT_SESSION_CHIP_MODE,
            sessionSwipeLeftAction = sanitizeSessionSwipeAction(
                prefs[SESSION_SWIPE_LEFT_ACTION],
                AppSettings.DEFAULT_SESSION_SWIPE_LEFT,
            ),
            sessionSwipeRightAction = sanitizeSessionSwipeAction(
                prefs[SESSION_SWIPE_RIGHT_ACTION],
                AppSettings.DEFAULT_SESSION_SWIPE_RIGHT,
            ),
            sessionSwipeEnabled = prefs[SESSION_SWIPE_ENABLED] ?: true,
            inboxSwipeLeftAction = sanitizeInboxSwipeAction(
                prefs[INBOX_SWIPE_LEFT_ACTION],
                AppSettings.DEFAULT_INBOX_SWIPE_LEFT,
            ),
            inboxSwipeRightAction = sanitizeInboxSwipeAction(
                prefs[INBOX_SWIPE_RIGHT_ACTION],
                AppSettings.DEFAULT_INBOX_SWIPE_RIGHT,
            ),
            inboxSwipeEnabled = prefs[INBOX_SWIPE_ENABLED] ?: true,
            classicLook = prefs[CLASSIC_LOOK] ?: false,
            projectChipOrder = prefs[PROJECT_CHIP_ORDER]?.split(',')?.filter { it.isNotBlank() }
                ?: emptyList(),
            projectChipSortByCount = prefs[PROJECT_CHIP_SORT_BY_COUNT] ?: false,
            fontScale = (prefs[FONT_SCALE] ?: 1f).coerceIn(0.5f, 2f),
            sessionFontScale = cleanInterfaceScale(prefs[SESSION_FONT_SCALE]),
            inboxFontScale = cleanInterfaceScale(prefs[INBOX_FONT_SCALE]),
            taskhubFontScale = cleanInterfaceScale(prefs[TASKHUB_FONT_SCALE]),
            dispatchDefaultDevice = prefs[DISPATCH_DEFAULT_DEVICE] ?: "",
            dispatchDefaultFolderId = prefs[DISPATCH_DEFAULT_FOLDER_ID] ?: -1,
            dispatchDefaultFolderName = prefs[DISPATCH_DEFAULT_FOLDER_NAME] ?: "",
            dispatchDefaultAgent = prefs[DISPATCH_DEFAULT_AGENT] ?: "",
            taskAutoStartDefault = prefs[TASK_AUTOSTART_DEFAULT] ?: false,
        ).also { latest = it }
    }

    /** Persist the dispatch-sheet defaults in one write (they change together). */
    suspend fun setDispatchDefaults(
        device: String,
        folderId: Int,
        folderName: String,
        agentWire: String,
    ) {
        dataStore.edit {
            it[DISPATCH_DEFAULT_DEVICE] = device
            it[DISPATCH_DEFAULT_FOLDER_ID] = folderId
            it[DISPATCH_DEFAULT_FOLDER_NAME] = folderName
            it[DISPATCH_DEFAULT_AGENT] = agentWire
        }
    }

    /** P0: persist the board form's auto-start choice for the next open. */
    suspend fun setTaskAutoStartDefault(value: Boolean) {
        dataStore.edit { it[TASK_AUTOSTART_DEFAULT] = value }
    }

    /** Clear the dispatch defaults (sheet falls back to no preselection). */
    suspend fun clearDispatchDefaults() {
        dataStore.edit {
            it.remove(DISPATCH_DEFAULT_DEVICE)
            it.remove(DISPATCH_DEFAULT_FOLDER_ID)
            it.remove(DISPATCH_DEFAULT_FOLDER_NAME)
            it.remove(DISPATCH_DEFAULT_AGENT)
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[MODE] = mode.id }
    }

    suspend fun setAccent(accentId: String) {
        dataStore.edit { it[ACCENT] = accentId }
    }

    /** Persist the bottom-tab order as an ordered list of route names. */
    suspend fun setHomeTabOrder(routes: List<String>) {
        dataStore.edit { it[TAB_ORDER] = routes.joinToString(",") }
    }

    /** Persist the cold-start tab by route name. */
    suspend fun setStartTab(route: String) {
        dataStore.edit { it[START_TAB] = route }
    }

    /** 批v152: hide/show a bottom tab by route. SETTINGS is force-kept — without
     *  it the settings page itself would be unreachable (self-lock). */
    suspend fun setTabHidden(route: String, hidden: Boolean) {
        if (route == "settings") return
        dataStore.edit { prefs ->
            val current = prefs[HIDDEN_TABS]?.split(',')?.filter { it.isNotBlank() }.orEmpty()
            val next = if (hidden) (current + route).distinct() else current - route
            prefs[HIDDEN_TABS] = next.joinToString(",")
            // A hidden tab cannot be a cold-start destination: repoint the saved
            // start tab to the first still-visible one (SETTINGS can never be
            // hidden, so the fallback list is non-empty). The running shell keeps
            // its bound start destination — its bar renders regardless (MainShell).
            if (hidden && prefs[START_TAB] == route) {
                // 批v153: run the raw order through the read-side migration first —
                // the bar renders migrateHomeTabOrder → ordered order, so the repoint
                // must pick the first visible of the SAME sequence (a pre-v152 order
                // would otherwise append 会话 last and repoint straight past it).
                val visible = resolvedTabOrder(
                    migrateHomeTabOrder(
                        prefs[TAB_ORDER]?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
                    ),
                ).filterNot { it in next }
                prefs[START_TAB] = visible.firstOrNull() ?: "settings"
            }
        }
    }

    /** 批v152: cold-start default for the workbench 总览区. */
    suspend fun setWorkbenchOverviewExpandedDefault(expanded: Boolean) {
        dataStore.edit { it[WORKBENCH_OVERVIEW_DEFAULT] = expanded }
    }

    /** 批v152: cold-start default for the workbench 需要处理区. */
    suspend fun setWorkbenchAttentionExpandedDefault(expanded: Boolean) {
        dataStore.edit { it[WORKBENCH_ATTENTION_DEFAULT] = expanded }
    }

    /** Persist the Activity-tab device-swipe toggle. */
    suspend fun setActivityDeviceSwipe(enabled: Boolean) {
        dataStore.edit { it[ACTIVITY_DEVICE_SWIPE] = enabled }
    }

    /** Persist the floating-ball on/off toggle (batch v126). */
    suspend fun setFloatBallEnabled(enabled: Boolean) {
        dataStore.edit { it[FLOAT_BALL_ENABLED] = enabled }
    }

    /** Persist the floating-ball size tier; unknown tiers coerce to the default. */
    suspend fun setFloatBallSize(size: String) {
        dataStore.edit { it[FLOAT_BALL_SIZE] = size.takeIf { s -> s in FLOAT_BALL_SIZES } ?: AppSettings.DEFAULT_FLOAT_BALL_SIZE }
    }

    /** 批v160: persist the 「循环切最近会话」 depth (clamped to 1..10). */
    suspend fun setFloatBallCycleDepth(depth: Int) {
        dataStore.edit { it[FLOAT_BALL_CYCLE_DEPTH] = depth.coerceIn(FLOAT_BALL_CYCLE_MIN, FLOAT_BALL_CYCLE_MAX) }
    }

    /** Persist the inbox "new idea" button placement; unknown values coerce back. */
    suspend fun setInboxNewIdeaStyle(style: String) {
        dataStore.edit { it[INBOX_NEW_IDEA_STYLE] = style.takeIf { s -> s in INBOX_IDEA_STYLES } ?: AppSettings.DEFAULT_INBOX_IDEA_STYLE }
    }

    /** 批v160: persist the idea-list column count (1 or 2). */
    suspend fun setInboxColumns(columns: Int) {
        dataStore.edit { it[INBOX_COLUMNS] = columns.coerceIn(1, 2) }
    }

    /** 批v160 W9: persist the top chip row's dimension (devices|projects). */
    suspend fun setSessionChipMode(mode: String) {
        dataStore.edit {
            it[SESSION_CHIP_MODE] = mode.takeIf { m -> m in SESSION_CHIP_MODES }
                ?: AppSettings.DEFAULT_SESSION_CHIP_MODE
        }
    }

    /** 批v168: persist the session row's left/right swipe bindings; unknown ids
     *  coerce back to the defaults. */
    suspend fun setSessionSwipeLeftAction(action: String) {
        dataStore.edit {
            it[SESSION_SWIPE_LEFT_ACTION] = sanitizeSessionSwipeAction(
                action,
                AppSettings.DEFAULT_SESSION_SWIPE_LEFT,
            )
        }
    }

    suspend fun setSessionSwipeRightAction(action: String) {
        dataStore.edit {
            it[SESSION_SWIPE_RIGHT_ACTION] = sanitizeSessionSwipeAction(
                action,
                AppSettings.DEFAULT_SESSION_SWIPE_RIGHT,
            )
        }
    }

    /** 批v171: master switches for the two swipe surfaces. */
    suspend fun setSessionSwipeEnabled(enabled: Boolean) {
        dataStore.edit { it[SESSION_SWIPE_ENABLED] = enabled }
    }

    suspend fun setInboxSwipeEnabled(enabled: Boolean) {
        dataStore.edit { it[INBOX_SWIPE_ENABLED] = enabled }
    }

    /** 批v171: persist the inbox card's left/right swipe bindings. */
    suspend fun setInboxSwipeLeftAction(action: String) {
        dataStore.edit {
            it[INBOX_SWIPE_LEFT_ACTION] = sanitizeInboxSwipeAction(
                action,
                AppSettings.DEFAULT_INBOX_SWIPE_LEFT,
            )
        }
    }

    suspend fun setInboxSwipeRightAction(action: String) {
        dataStore.edit {
            it[INBOX_SWIPE_RIGHT_ACTION] = sanitizeInboxSwipeAction(
                action,
                AppSettings.DEFAULT_INBOX_SWIPE_RIGHT,
            )
        }
    }

    /** 批v171（经典外观）: persist the selected-state skin toggle. */
    suspend fun setClassicLook(enabled: Boolean) {
        dataStore.edit { it[CLASSIC_LOOK] = enabled }
    }

    /** 批v160 W9: persist the projects' manual chip order (ids, left→right). */
    suspend fun setProjectChipOrder(order: List<String>) {
        dataStore.edit { it[PROJECT_CHIP_ORDER] = order.filter { id -> id.isNotBlank() }.joinToString(",") }
    }

    /** 批v160 W9: persist the sort-by-session-count toggle. */
    suspend fun setProjectChipSortByCount(enabled: Boolean) {
        dataStore.edit { it[PROJECT_CHIP_SORT_BY_COUNT] = enabled }
    }

    /** 批v160 W8: persist the "agent needs you" system-notification toggle. */
    suspend fun setNotifyInteractiveEnabled(enabled: Boolean) {
        dataStore.edit { it[NOTIFY_INTERACTIVE_ENABLED] = enabled }
    }

    /** 系统级通知体系: persist the task-results notification toggle. */
    suspend fun setNotifyTaskResultsEnabled(enabled: Boolean) {
        dataStore.edit { it[NOTIFY_TASK_RESULTS_ENABLED] = enabled }
    }

    /** 系统级通知体系: persist the device-offline notification toggle. */
    suspend fun setNotifyDeviceEventsEnabled(enabled: Boolean) {
        dataStore.edit { it[NOTIFY_DEVICE_EVENTS_ENABLED] = enabled }
    }

    /** 批v160 W8: persist the keep-alive foreground-service toggle (the service
     *  itself is started/stopped by whoever observes this flow — Application). */
    suspend fun setKeepAliveEnabled(enabled: Boolean) {
        dataStore.edit { it[KEEP_ALIVE_ENABLED] = enabled }
    }

    /** 批v160 W5: persist the Tasks tab's swipe semantics; unknown values coerce back. */
    suspend fun setTaskSwipeMode(mode: String) {
        dataStore.edit { it[TASK_SWIPE_MODE] = mode.takeIf { m -> m in TASK_SWIPE_MODES } ?: AppSettings.DEFAULT_TASK_SWIPE_MODE }
    }

    /** 批v161: persist the task board's layout; unknown values coerce back. */
    suspend fun setTaskBoardLayout(layout: String) {
        dataStore.edit {
            it[TASK_BOARD_LAYOUT] = layout.takeIf { l -> l in TASK_BOARD_LAYOUTS }
                ?: AppSettings.DEFAULT_TASK_BOARD_LAYOUT
        }
    }

    /** 批v161: persist the kanban page's column count (1 or 2). */
    suspend fun setTaskBoardKanbanColumns(columns: Int) {
        dataStore.edit { it[TASK_BOARD_KANBAN_COLUMNS] = columns.coerceIn(1, 2) }
    }

    /** 批v161: persist the list mode's column count (1 or 2). */
    suspend fun setTaskBoardListColumns(columns: Int) {
        dataStore.edit { it[TASK_BOARD_LIST_COLUMNS] = columns.coerceIn(1, 2) }
    }

    /** Persist the font-scale multiplier (coerced to the usable band on read too). */
    suspend fun setFontScale(scale: Float) {
        dataStore.edit { it[FONT_SCALE] = scale.coerceIn(0.5f, 2f) }
    }

    /** 批v172 D3: per-interface scale; 0 = follow the global setting. */
    suspend fun setSessionFontScale(scale: Float) = setInterfaceScale(SESSION_FONT_SCALE, scale)

    suspend fun setInboxFontScale(scale: Float) = setInterfaceScale(INBOX_FONT_SCALE, scale)

    suspend fun setTaskhubFontScale(scale: Float) = setInterfaceScale(TASKHUB_FONT_SCALE, scale)

    private suspend fun setInterfaceScale(key: Preferences.Key<Float>, scale: Float) {
        // 0 is the "follow global" sentinel; anything else lands in the band.
        val v = if (scale <= 0f) 0f else scale.coerceIn(0.5f, 2f)
        dataStore.edit { it[key] = v }
    }

    private fun cleanInterfaceScale(raw: Float?): Float =
        raw?.takeIf { it > 0f }?.coerceIn(0.5f, 2f) ?: 0f

    /** Floating-ball position as screen-fraction (fx, fy) in 0..1, or null until the
     *  user first drags the ball. Same separate-flow pattern as [sessionReadMarks]:
     *  the JSON decode must not ride the app-wide settings flow. */
    val floatBallPos: Flow<Pair<Float, Float>?> = dataStore.data.map { prefs ->
        prefs[FLOAT_BALL_POS]?.let { raw ->
            runCatching { Json.decodeFromString(PosSerde.serializer(), raw) }
                .getOrNull()
                ?.takeIf { it.fx.isFinite() && it.fy.isFinite() }
                ?.let { it.fx.coerceIn(0f, 1f) to it.fy.coerceIn(0f, 1f) }
        }
    }

    suspend fun setFloatBallPos(fx: Float, fy: Float) {
        dataStore.edit {
            it[FLOAT_BALL_POS] = Json.encodeToString(
                PosSerde.serializer(),
                PosSerde(fx.coerceIn(0f, 1f), fy.coerceIn(0f, 1f)),
            )
        }
    }

    /** Gesture→action bindings for the floating ball. Values are action ids; an
     *  explicit JSON null means "deliberately unbound" (distinct from a missing
     *  key = "use the default"). Sanitizing to the known gesture/action catalogs
     *  is the feature layer's job ([app.codeg.android.feature.floatingball.
     *  FloatingBallCatalog.sanitize]) — the store stays a dumb map. */
    val floatBallBindings: Flow<Map<String, String?>> = dataStore.data.map { prefs ->
        prefs[FLOAT_BALL_BINDINGS]?.let { raw ->
            runCatching { Json.decodeFromString(BindingsSerde.serializer(), raw).bindings }
                .getOrDefault(emptyMap())
        } ?: emptyMap()
    }

    suspend fun setFloatBallBindings(bindings: Map<String, String?>) {
        dataStore.edit {
            it[FLOAT_BALL_BINDINGS] = Json.encodeToString(BindingsSerde.serializer(), BindingsSerde(bindings))
        }
    }

    /** Local read marks, keyed `"<serverId>:<conversationId>"` → opened-at epochMilli.
     *  Deliberately NOT part of [AppSettings]: the JSON map decode must not ride the
     *  app-wide settings flow. Server has no read tracking, so this is device-local. */
    val sessionReadMarks: Flow<Map<String, Long>> = dataStore.data.map { prefs ->
        prefs[SESSION_READ_MARKS]?.let { raw ->
            runCatching { Json.decodeFromString(ReadMarksSerde.serializer(), raw).marks }
                .getOrDefault(emptyMap())
        } ?: emptyMap()
    }

    suspend fun markSessionRead(key: String, at: Long = System.currentTimeMillis()) {
        dataStore.edit { prefs ->
            val current = prefs[SESSION_READ_MARKS]?.let { raw ->
                runCatching { Json.decodeFromString(ReadMarksSerde.serializer(), raw).marks }
                    .getOrDefault(emptyMap())
            } ?: emptyMap()
            val next = pruneReadMarks(current + (key to at))
            prefs[SESSION_READ_MARKS] = Json.encodeToString(ReadMarksSerde.serializer(), ReadMarksSerde(next))
        }
    }

    private companion object {
        val MODE = stringPreferencesKey("theme_mode")
        val ACCENT = stringPreferencesKey("accent_id")
        val TAB_ORDER = stringPreferencesKey("home_tab_order")
        val HIDDEN_TABS = stringPreferencesKey("hidden_home_tabs")
        val START_TAB = stringPreferencesKey("start_tab")
        val WORKBENCH_OVERVIEW_DEFAULT = booleanPreferencesKey("workbench_overview_expanded_default")
        val WORKBENCH_ATTENTION_DEFAULT = booleanPreferencesKey("workbench_attention_expanded_default")
        val ACTIVITY_DEVICE_SWIPE = booleanPreferencesKey("activity_device_swipe")
        val SESSION_READ_MARKS = stringPreferencesKey("session_read_marks")
        val FLOAT_BALL_ENABLED = booleanPreferencesKey("float_ball_enabled")
        val FLOAT_BALL_SIZE = stringPreferencesKey("float_ball_size")
        val FLOAT_BALL_CYCLE_DEPTH = intPreferencesKey("float_ball_cycle_depth")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val SESSION_FONT_SCALE = floatPreferencesKey("session_font_scale")
        val INBOX_FONT_SCALE = floatPreferencesKey("inbox_font_scale")
        val TASKHUB_FONT_SCALE = floatPreferencesKey("taskhub_font_scale")
        val FLOAT_BALL_POS = stringPreferencesKey("float_ball_pos")
        val FLOAT_BALL_BINDINGS = stringPreferencesKey("float_ball_bindings")
        val DISPATCH_DEFAULT_DEVICE = stringPreferencesKey("dispatch_default_device")
        val DISPATCH_DEFAULT_FOLDER_ID = intPreferencesKey("dispatch_default_folder_id")
        val DISPATCH_DEFAULT_FOLDER_NAME = stringPreferencesKey("dispatch_default_folder_name")
        val DISPATCH_DEFAULT_AGENT = stringPreferencesKey("dispatch_default_agent")
        val TASK_AUTOSTART_DEFAULT = booleanPreferencesKey("task_autostart_default")
        val INBOX_NEW_IDEA_STYLE = stringPreferencesKey("inbox_new_idea_style")
        val INBOX_COLUMNS = intPreferencesKey("inbox_columns")
        val NOTIFY_INTERACTIVE_ENABLED = booleanPreferencesKey("notify_interactive_enabled")
        val NOTIFY_TASK_RESULTS_ENABLED = booleanPreferencesKey("notify_task_results")
        val NOTIFY_DEVICE_EVENTS_ENABLED = booleanPreferencesKey("notify_device_events")
        val KEEP_ALIVE_ENABLED = booleanPreferencesKey("keep_alive_enabled")
        val TASK_SWIPE_MODE = stringPreferencesKey("task_swipe_mode")
        val TASK_BOARD_LAYOUT = stringPreferencesKey("task_board_layout")
        val TASK_BOARD_KANBAN_COLUMNS = intPreferencesKey("task_board_kanban_columns")
        val TASK_BOARD_LIST_COLUMNS = intPreferencesKey("task_board_list_columns")
        val SESSION_CHIP_MODE = stringPreferencesKey("session_chip_mode")
        val SESSION_SWIPE_LEFT_ACTION = stringPreferencesKey("session_swipe_left_action")
        val SESSION_SWIPE_RIGHT_ACTION = stringPreferencesKey("session_swipe_right_action")
        val SESSION_SWIPE_ENABLED = booleanPreferencesKey("session_swipe_enabled")
        val INBOX_SWIPE_LEFT_ACTION = stringPreferencesKey("inbox_swipe_left_action")
        val INBOX_SWIPE_RIGHT_ACTION = stringPreferencesKey("inbox_swipe_right_action")
        val INBOX_SWIPE_ENABLED = booleanPreferencesKey("inbox_swipe_enabled")
        val CLASSIC_LOOK = booleanPreferencesKey("classic_look")
        val PROJECT_CHIP_ORDER = stringPreferencesKey("project_chip_order")
        val PROJECT_CHIP_SORT_BY_COUNT = booleanPreferencesKey("project_chip_sort_by_count")
    }
}

/** Legal floating-ball size tiers (mirrors the feature layer's dp ladder). */
val FLOAT_BALL_SIZES = setOf("small", "medium", "large")

/** Legal 「循环切最近会话」 depths (批v160). */
const val FLOAT_BALL_CYCLE_MIN = 1
const val FLOAT_BALL_CYCLE_MAX = 10

/** Legal inbox "new idea" button placements (批v151) — screens branch on the
 *  constants, not raw literals, so a future style can't silently fall back. */
const val INBOX_IDEA_STYLE_CORNER = "corner"
const val INBOX_IDEA_STYLE_CENTER_WIDE = "centerWide"
val INBOX_IDEA_STYLES = setOf(INBOX_IDEA_STYLE_CORNER, INBOX_IDEA_STYLE_CENTER_WIDE)

/** Legal Tasks-tab swipe semantics (批v160 W5) — screens branch on the constants. */
const val TASK_SWIPE_SEGMENTS = "segments"
const val TASK_SWIPE_DEVICES = "devices"
val TASK_SWIPE_MODES = setOf(TASK_SWIPE_SEGMENTS, TASK_SWIPE_DEVICES)

/** Legal task-board layouts (批v161) — screens branch on the constants. */
const val TASK_BOARD_KANBAN = "kanban"
const val TASK_BOARD_LIST = "list"
val TASK_BOARD_LAYOUTS = setOf(TASK_BOARD_KANBAN, TASK_BOARD_LIST)

/** Legal top chip-row dimensions (批v160 W9) — screens branch on the constants. */
const val SESSION_CHIP_DEVICES = "devices"
const val SESSION_CHIP_PROJECTS = "projects"
val SESSION_CHIP_MODES = setOf(SESSION_CHIP_DEVICES, SESSION_CHIP_PROJECTS)

/** Legal session-row swipe bindings (批v168): the long-press menu catalog's raw
 *  ids plus [SESSION_SWIPE_NONE] ("direction off"). The feature layer owns the
 *  typed enum; this whitelist is the storage-side sanitize source of truth. */
const val SESSION_SWIPE_NONE = "none"
val SESSION_SWIPE_ACTIONS = setOf(
    SESSION_SWIPE_NONE,
    "board",
    "copy",
    "pin",
    "handoff",
    "rename",
    "add_project",
    "delete",
)

/** 批v171: the inbox card's bindable swipe actions（对齐长按菜单+派发）. */
val INBOX_SWIPE_ACTIONS = setOf(
    "none",
    "dispatch",
    "edit",
    "copy",
    "append",
    "delete",
)

/** 批v171: unknown ids coerce to the given default (same contract as
 *  [sanitizeSessionSwipeAction]). */
fun sanitizeInboxSwipeAction(raw: String?, default: String): String =
    raw?.takeIf { it in INBOX_SWIPE_ACTIONS } ?: default

/** Pure read/set-side sanitize: unknown or blank ids fall back to [default]. */
fun sanitizeSessionSwipeAction(raw: String?, default: String): String =
    raw?.takeIf { it in SESSION_SWIPE_ACTIONS } ?: default

/** JSON envelope for the read-marks map (DataStore carries one string). */
@Serializable
private data class ReadMarksSerde(val marks: Map<String, Long> = emptyMap())

/** JSON envelope for the ball position (screen fractions). */
@Serializable
private data class PosSerde(val fx: Float = 0.85f, val fy: Float = 0.55f)

/** JSON envelope for the gesture→action map (null value = deliberately unbound). */
@Serializable
private data class BindingsSerde(val bindings: Map<String, String?> = emptyMap())

/** Max stored read marks — beyond this the OLDEST marks (by opened-at) are dropped.
 *  A session that vanished from the lists years ago must not pin its entry forever. */
const val READ_MARKS_CAP = 500

/** Pure LRU-ish prune: keep the newest [READ_MARKS_CAP] entries by timestamp. */
fun pruneReadMarks(marks: Map<String, Long>): Map<String, Long> =
    if (marks.size <= READ_MARKS_CAP) marks
    else marks.entries.sortedByDescending { it.value }.take(READ_MARKS_CAP).associate { it.toPair() }

/** Pure unread predicate for the Activity feed's Last-24h rows: a row is unread when
 *  it changed after the last time the user opened it (or was never opened). Live rows
 *  pulse instead — never double-flagged. */
fun isSessionUnread(statusLive: Boolean, updatedAt: Long, readMark: Long?): Boolean =
    !statusLive && updatedAt > (readMark ?: Long.MIN_VALUE)
