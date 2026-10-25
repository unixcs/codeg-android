package app.codeg.android.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
    /** Route name of the tab the app opens on cold start (default: chats). */
    val startTab: String = DEFAULT_START_TAB,
    /** Horizontal swipe between devices while on the Activity tab (default: off). */
    val activityDeviceSwipe: Boolean = false,
    /** Same gesture on the Folders tab (default: off; separate from [activityDeviceSwipe]). */
    val foldersDeviceSwipe: Boolean = false,
) {
    companion object {
        const val DEFAULT_ACCENT = "orange"
        const val DEFAULT_START_TAB = "chats"
        /** The initial value [AppSettingsStore.settings] exposes before DataStore's
         *  first emission; used to detect "settings not loaded yet". */
        val INITIAL = AppSettings()
    }
}

/**
 * Persists appearance + shell settings (theme, accent, bottom-tab order, cold-start
 * tab). Applied app-wide instantly; never synced to the server (matches iOS).
 */
@Singleton
class AppSettingsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            themeMode = ThemeMode.from(prefs[MODE]),
            accentId = prefs[ACCENT] ?: AppSettings.DEFAULT_ACCENT,
            // Comma-joined route names: DataStore's stringSet doesn't guarantee order,
            // and the whole point of this pref is the ordering. Route names contain
            // no commas.
            homeTabOrder = prefs[TAB_ORDER]?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
            startTab = prefs[START_TAB] ?: AppSettings.DEFAULT_START_TAB,
            activityDeviceSwipe = prefs[ACTIVITY_DEVICE_SWIPE] ?: false,
            // Migration: the folders toggle was born from the activity one (v123), so
            // a user who already had the activity gesture on keeps it there AND gets
            // the folders gesture — matching what they'd have seen had the two been
            // one switch all along. Once set, each evolves independently.
            foldersDeviceSwipe = prefs[FOLDERS_DEVICE_SWIPE] ?: false,
        )
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

    /** Persist the Activity-tab device-swipe toggle. */
    suspend fun setActivityDeviceSwipe(enabled: Boolean) {
        dataStore.edit { it[ACTIVITY_DEVICE_SWIPE] = enabled }
    }

    /** Persist the Folders-tab device-swipe toggle (independent since v123). */
    suspend fun setFoldersDeviceSwipe(enabled: Boolean) {
        dataStore.edit { it[FOLDERS_DEVICE_SWIPE] = enabled }
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
        val START_TAB = stringPreferencesKey("start_tab")
        val ACTIVITY_DEVICE_SWIPE = booleanPreferencesKey("activity_device_swipe")
        val FOLDERS_DEVICE_SWIPE = booleanPreferencesKey("folders_device_swipe")
        val SESSION_READ_MARKS = stringPreferencesKey("session_read_marks")
    }
}

/** JSON envelope for the read-marks map (DataStore carries one string). */
@Serializable
private data class ReadMarksSerde(val marks: Map<String, Long> = emptyMap())

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
