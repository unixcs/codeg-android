package app.codeg.android.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.crash.CrashRecord
import app.codeg.android.core.crash.CrashStore
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.AppSettings
import app.codeg.android.core.datastore.AppSettingsStore
import app.codeg.android.core.datastore.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import app.codeg.android.feature.floatingball.FloatingBallCatalog

/** Backs the Settings tab: live appearance prefs + the About server/version info. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appSettingsStore: AppSettingsStore,
    private val repository: ServerRepository,
    private val crashStore: CrashStore,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        appSettingsStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _about = MutableStateFlow(AboutInfo())
    val about: StateFlow<AboutInfo> = _about.asStateFlow()

    init {
        viewModelScope.launch {
            val profile = repository.selectedProfile.first()
            _about.update { it.copy(serverName = profile?.name) }
            if (profile != null) {
                val client = repository.client(profile)
                val version = client?.let { runCatching { it.health().version }.getOrNull() }
                _about.update { it.copy(serverVersion = version) }
            }
        }
        // Device-local last-crash report (batch v123), shown on the About page.
        viewModelScope.launch {
            crashStore.lastCrash.collect { crash ->
                _about.update { it.copy(lastCrash = crash) }
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { appSettingsStore.setThemeMode(mode) }
    fun setAccent(accentId: String) = viewModelScope.launch { appSettingsStore.setAccent(accentId) }

    /** Ball gesture→action bindings for the binding editor (sanitized on read). */
    val floatBallBindings: StateFlow<Map<String, String?>> = appSettingsStore.floatBallBindings
        .map { FloatingBallCatalog.sanitize(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, FloatingBallCatalog.DEFAULT_BINDINGS)

    /** Rebind one gesture. Unknown gesture ids are rejected (a sanitize no-op).
     *  The v129 longPress pin is gone with the gesture itself — the 1s hold is
     *  the fixed drag-to-move entry and fires no action. */
    fun setBallBinding(gesture: String, actionId: String?) {
        viewModelScope.launch {
            val current = FloatingBallCatalog.sanitize(appSettingsStore.floatBallBindings.first())
            if (gesture !in FloatingBallCatalog.GESTURES) return@launch
            val next = current.toMutableMap().apply {
                this[gesture] = if (actionId == null || actionId in FloatingBallCatalog.ACTION_IDS) actionId else null
            }
            appSettingsStore.setFloatBallBindings(FloatingBallCatalog.sanitize(next))
        }
    }

    /** Copy the full crash report text (for pasting into a bug report). */
    fun copyCrashReport(crash: CrashRecord) {
        val manager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        manager.setPrimaryClip(ClipData.newPlainText("codeg-crash", crash.toReportText()))
    }

    /** Dismiss the About-page crash card. */
    fun dismissCrash() = viewModelScope.launch { crashStore.clear() }
}

/** Build the copyable crash text. Pure so the About card and tests share it. */
fun CrashRecord.toReportText(): String = buildString {
    append("codeg crash report\n")
    append("time: ").append(time).append('\n')
    append("version: ").append(version).append('\n')
    append("thread: ").append(thread).append('\n')
    append(stack)
}

data class AboutInfo(
    val serverName: String? = null,
    val serverVersion: String? = null,
    val lastCrash: CrashRecord? = null,
)
