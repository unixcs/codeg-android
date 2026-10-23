package app.codeg.android.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

    private companion object {
        val MODE = stringPreferencesKey("theme_mode")
        val ACCENT = stringPreferencesKey("accent_id")
        val TAB_ORDER = stringPreferencesKey("home_tab_order")
        val START_TAB = stringPreferencesKey("start_tab")
    }
}
