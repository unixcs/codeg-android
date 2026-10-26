package app.codeg.android.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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
    /** Floating ball on/off (batch v126; default on — it's a primary nav surface). */
    val floatBallEnabled: Boolean = true,
    /** Floating ball size tier: "small" | "medium" | "large" (unknown → medium). */
    val floatBallSize: String = DEFAULT_FLOAT_BALL_SIZE,
    /** Multiplier ladder over the system font scale (0.85 / 1.0 / 1.15 / 1.3). */
    val fontScale: Float = 1f,
) {
    companion object {
        const val DEFAULT_ACCENT = "orange"
        const val DEFAULT_START_TAB = "chats"
        const val DEFAULT_FLOAT_BALL_SIZE = "medium"
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
            floatBallEnabled = prefs[FLOAT_BALL_ENABLED] ?: true,
            floatBallSize = prefs[FLOAT_BALL_SIZE]?.takeIf { it in FLOAT_BALL_SIZES } ?: AppSettings.DEFAULT_FLOAT_BALL_SIZE,
            fontScale = (prefs[FONT_SCALE] ?: 1f).coerceIn(0.5f, 2f),
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

    /** Persist the floating-ball on/off toggle (batch v126). */
    suspend fun setFloatBallEnabled(enabled: Boolean) {
        dataStore.edit { it[FLOAT_BALL_ENABLED] = enabled }
    }

    /** Persist the floating-ball size tier; unknown tiers coerce to the default. */
    suspend fun setFloatBallSize(size: String) {
        dataStore.edit { it[FLOAT_BALL_SIZE] = size.takeIf { s -> s in FLOAT_BALL_SIZES } ?: AppSettings.DEFAULT_FLOAT_BALL_SIZE }
    }

    /** Persist the font-scale multiplier (coerced to the usable band on read too). */
    suspend fun setFontScale(scale: Float) {
        dataStore.edit { it[FONT_SCALE] = scale.coerceIn(0.5f, 2f) }
    }

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
        val START_TAB = stringPreferencesKey("start_tab")
        val ACTIVITY_DEVICE_SWIPE = booleanPreferencesKey("activity_device_swipe")
        val FOLDERS_DEVICE_SWIPE = booleanPreferencesKey("folders_device_swipe")
        val SESSION_READ_MARKS = stringPreferencesKey("session_read_marks")
        val FLOAT_BALL_ENABLED = booleanPreferencesKey("float_ball_enabled")
        val FLOAT_BALL_SIZE = stringPreferencesKey("float_ball_size")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val FLOAT_BALL_POS = stringPreferencesKey("float_ball_pos")
        val FLOAT_BALL_BINDINGS = stringPreferencesKey("float_ball_bindings")
    }
}

/** Legal floating-ball size tiers (mirrors the feature layer's dp ladder). */
val FLOAT_BALL_SIZES = setOf("small", "medium", "large")

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
