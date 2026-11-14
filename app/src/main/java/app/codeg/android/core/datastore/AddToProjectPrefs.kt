package app.codeg.android.core.datastore

/**
 * 批v179: the 添加到项目 sheet's two persisted dimensions, narrowed from
 * [AppSettings] by each screen's ViewModel (the sheet stays stateless — single
 * instance hoisted by the calling screen, same constraint as the row menus).
 */
data class AddToProjectPrefs(
    /** 1 = classic single list, 2/3 = card grid columns. */
    val columns: Int,
    /** One of the ADD_TO_PROJECT_SORT_* constants. */
    val sort: String,
)

/** What [AppSettingsStore.settings] exposes before DataStore's first emission. */
val ADD_TO_PROJECT_PREFS_DEFAULT = AddToProjectPrefs(
    columns = AppSettings.DEFAULT_ADD_TO_PROJECT_COLUMNS,
    sort = AppSettings.DEFAULT_ADD_TO_PROJECT_SORT,
)
