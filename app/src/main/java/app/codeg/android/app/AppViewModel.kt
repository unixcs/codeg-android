package app.codeg.android.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.QuickSwitchRing
import app.codeg.android.core.data.QuickSwitchStep
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.data.SessionFocusHub
import app.codeg.android.core.data.SessionProjectRepository
import app.codeg.android.core.data.nextQuickSwitchStep
import app.codeg.android.core.datastore.AppSettings
import app.codeg.android.core.datastore.AppSettingsStore
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.datastore.SessionProject
import app.codeg.android.core.datastore.SessionRef
import app.codeg.android.core.datastore.ThemeMode
import app.codeg.android.core.designsystem.theme.AccentPalette
import app.codeg.android.feature.floatingball.FloatingBallCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App-level state: appearance settings (drives the theme) and the saved servers
 * + selection. Owns server-selection / deletion actions.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val appSettingsStore: AppSettingsStore,
    private val sessionProjectRepo: SessionProjectRepository,
    /** Floating-ball session cycling — the shell dispatches the ball actions here. */
    val focusHub: SessionFocusHub,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = appSettingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings.INITIAL)

    /** False until DataStore's first emission lands — the shell holds on it so the
     *  start tab / tab order are applied from the real saved values, not defaults. */
    val settingsLoaded: StateFlow<Boolean> = appSettingsStore.settings
        .map { true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** null = not loaded yet (show a splash); non-null = loaded (possibly empty).
     *  Day-to-day subset only — hidden devices are excluded from the shell, device
     *  bar/swipe/switching and the aggregate feed. The Servers management screen
     *  reads [allServers] so hidden devices stay reachable there. */
    val servers: StateFlow<List<ServerProfile>?> = serverRepository.visibleProfiles
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Every saved device, including hidden ones (Servers screen + its toggle). */
    val allServers: StateFlow<List<ServerProfile>?> = serverRepository.profiles
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val selectedProfile: StateFlow<ServerProfile?> = serverRepository.selectedProfile
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Aggregate "All devices" mode for the Activity tab (see ServerRepository —
    // shared state lives there so feature ViewModels can observe it too).
    val activityAll: StateFlow<Boolean> = serverRepository.activityAll

    fun setActivityAll(all: Boolean) = serverRepository.setActivityAll(all)

    /** Re-poll every device's activity feed while already in "All" mode. */
    fun refreshActivityAll() = serverRepository.requestActivityRefresh()

    fun selectServer(id: String) {
        viewModelScope.launch { serverRepository.select(id) }
    }

    /**
     * Select a server and run [onDone] only after the selection has landed in the
     * store. The fire-and-forget [selectServer] races anyone who reads
     * `selectedProfile.first()` right after (session detail reads it exactly once on
     * entry and would resolve the previous server).
     */
    /** Select [id] and run [onDone] once the switch has LANDED. The callback is
     *  suspend so it can re-read the selection: the composed state updates a frame
     *  later, and a concurrent switch (floating ball / device bar) must be visible
     *  to a handoff that is about to resolve server-local ids. */
    fun selectServerAndWait(id: String, onDone: suspend () -> Unit) {
        viewModelScope.launch {
            // A failed write (disk full, corrupt prefs) must still run [onDone]: the
            // caller releases its single-flight flag there. The handoff's own
            // selectedIdNow() check then sees the old selection and drops the tap.
            runCatching { serverRepository.select(id) }
            // The landing step reads DataStore again (it can throw on I/O) and
            // navigates; a failure there must not surface as an uncaught coroutine
            // crash — the tap is simply lost and the user can repeat it.
            try {
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /** The selection as of right now — a fresh DataStore read, not the state the
     *  last recomposition saw. */
    suspend fun selectedIdNow(): String? = serverRepository.selectedId.first()

    fun deleteServer(id: String) {
        viewModelScope.launch {
            serverRepository.delete(id)
            onServerDeleted(id)
        }
    }

    /** Show/hide a device (Servers screen toggle). If the focused device was just
     *  hidden, fall back to the first remaining visible one so the shell never
     *  points at a device the day-to-day surfaces no longer list. */
    fun setServerVisible(id: String, visible: Boolean) {
        viewModelScope.launch {
            serverRepository.setVisible(id, visible)
            if (!visible && serverRepository.selectedId.first() == id) {
                val fallback = serverRepository.visibleProfiles.first()
                    .firstOrNull { it.id != id }
                // No visible device left: clear selection (select(null)) rather than keep
                // pointing the shell at a device no day-to-day surface lists.
                serverRepository.select(fallback?.id)
            }
        }
    }

    fun setHomeTabOrder(routes: List<String>) {
        viewModelScope.launch { appSettingsStore.setHomeTabOrder(routes) }
    }

    fun setStartTab(route: String) {
        viewModelScope.launch { appSettingsStore.setStartTab(route) }
    }

    fun setActivityDeviceSwipe(enabled: Boolean) {
        viewModelScope.launch { appSettingsStore.setActivityDeviceSwipe(enabled) }
    }

    // ---- Appearance + floating-ball passthroughs (batch v126) ----
    // The ball's theme/font actions fire from MainShell, which owns this VM; the
    // Settings screens have their own VMs but land on the same store.

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { appSettingsStore.setThemeMode(mode) }
    }

    fun toggleThemeMode() {
        val next = when (settings.value.themeMode) {
            ThemeMode.LIGHT -> ThemeMode.DARK
            else -> ThemeMode.LIGHT // SYSTEM resolves at render time; the ball's toggle just flips light/dark
        }
        setThemeMode(next)
    }

    fun setAccent(accentId: String) {
        viewModelScope.launch { appSettingsStore.setAccent(accentId) }
    }

    /** Step to the next accent in palette order (wraps). */
    fun cycleAccent() {
        val all = AccentPalette.all
        val index = all.indexOfFirst { it.id == settings.value.accentId }.let { i ->
            if (i < 0) 0 else (i + 1) % all.size
        }
        setAccent(all[index].id)
    }

    fun setFontScale(scale: Float) {
        viewModelScope.launch { appSettingsStore.setFontScale(scale) }
    }

    fun setFloatBallEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettingsStore.setFloatBallEnabled(enabled) }
    }

    fun setFloatBallSize(size: String) {
        viewModelScope.launch { appSettingsStore.setFloatBallSize(size) }
    }

    /** Ball position as (fx, fy) screen fractions; null until the first drag. */
    val floatBallPos: StateFlow<Pair<Float, Float>?> = appSettingsStore.floatBallPos
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setFloatBallPos(fx: Float, fy: Float) {
        viewModelScope.launch { appSettingsStore.setFloatBallPos(fx, fy) }
    }

    /** Gesture→action bindings, sanitized against the gesture/action catalogs on
     *  every emission so a corrupt or stale stored map can never crash or act wild.
     *  Missing/null entries resolve to the catalog defaults. */
    val floatBallBindings: StateFlow<Map<String, String?>> = appSettingsStore.floatBallBindings
        .map { FloatingBallCatalog.sanitize(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, FloatingBallCatalog.DEFAULT_BINDINGS)

    fun setFloatBallBindings(bindings: Map<String, String?>) {
        viewModelScope.launch { appSettingsStore.setFloatBallBindings(FloatingBallCatalog.sanitize(bindings)) }
    }

    /** Raw read marks ("<serverId>:<conversationId>" → openedAt), for the ball's
     *  recent-session shortcuts. */
    val sessionReadMarks: StateFlow<Map<String, Long>> = appSettingsStore.sessionReadMarks
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Stamp a session as read (device-local read marks — see AppSettingsStore).
     *  Fire-and-forget: a navigation always follows, the write must never gate it. */
    fun markSessionRead(key: String) {
        viewModelScope.launch { appSettingsStore.markSessionRead(key) }
    }

    /** Per-depth quick-switch rings (v136). Process-local walking cursors, not prefs —
     *  a ring's whole point is to survive while its members reorder. */
    private val quickSwitchRings = mutableMapOf<Int, QuickSwitchRing>()

    /** Next target for the "quick switch · depth N" ball action: walks the N most
     *  recently opened sessions as a stable ring (see [nextQuickSwitchStep]).
     *  [current] is the conversation currently on screen (null from a list), so a fire
     *  advances instead of re-opening it. Peek only — [commitQuickSwitchStep] advances
     *  the walking cursor once the switch really navigates, so a gesture dropped by the
     *  handoff single-flight can't burn a ring slot. */
    fun peekQuickSwitchTarget(depth: Int, validServers: Set<String>, current: SessionRef?): QuickSwitchStep? =
        nextQuickSwitchStep(
            marks = sessionReadMarks.value,
            validServers = validServers,
            depth = depth,
            current = current,
            previous = quickSwitchRings[depth],
        )

    /** Record a step returned by [peekQuickSwitchTarget] as the new walking cursor. */
    fun commitQuickSwitchStep(depth: Int, ring: QuickSwitchRing) {
        quickSwitchRings[depth] = ring
    }

    // ---- Session projects + aggregate Chats mode (batch v132) ----

    val chatsAll: StateFlow<Boolean> = serverRepository.chatsAll

    fun setChatsAll(all: Boolean) = serverRepository.setChatsAll(all)

    val sessionProjects: StateFlow<List<SessionProject>> = sessionProjectRepo.projects

    val sessionProjectMembers: StateFlow<Map<String, List<SessionRef>>> = sessionProjectRepo.members

    val activeProjectId: StateFlow<String?> = sessionProjectRepo.activeProjectId

    fun setActiveProject(id: String?) = sessionProjectRepo.setActive(id)

    /**
     * Leave every aggregate context at once. THE exit path for device selection,
     * swipes, row opens and the <2-visible-device reset, so a project filter can
     * never silently survive behind a device chip or another tab (review #2/#3).
     */
    fun exitAggregate() {
        serverRepository.setActivityAll(false)
        serverRepository.setChatsAll(false)
        sessionProjectRepo.setActive(null)
    }

    /**
     * Create-and-join / create-and-filter in one gesture (R1-1): the filter is set
     * straight from the created object — [SessionProjectRepository.projects] is at
     * least one dispatcher hop behind the DataStore write, so routing through
     * setActive()'s re-validation would silently drop the just-created id. Guarded
     * by the CURRENT aggregate flags (R2-5): if the user left aggregate mode while
     * the write was in flight, activating here would strand the filter behind a
     * single-device view. Runs on viewModelScope (Main), so [onCreated] may touch
     * Compose state.
     */
    fun createAndActivateProject(name: String, onCreated: (SessionProject) -> Unit = {}) {
        viewModelScope.launch {
            val project = sessionProjectRepo.create(name)
            if (chatsAll.value || activityAll.value) sessionProjectRepo.activateCreated(project.id)
            onCreated(project)
        }
    }

    /** Device removed: its sessions' project memberships die with it. */
    fun onServerDeleted(serverId: String) {
        viewModelScope.launch { sessionProjectRepo.removeRefsForServer(serverId) }
    }
}
