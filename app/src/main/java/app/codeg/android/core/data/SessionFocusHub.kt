package app.codeg.android.core.data

import app.codeg.android.core.datastore.SessionRef
import app.codeg.android.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** One cycle-able entry of the currently visible list: identity + render info. */
data class FocusItem(
    val ref: SessionRef,
    val title: String,
    val serverName: String,
)

/** The session the floating-ball "cycle session" gesture currently points at.
 *  [stamp] is monotonic per fire — identity for consumers that must react to a
 *  NEW highlight but ignore rebuild-rereads of the same one (scroll dedupe). */
data class FocusHighlight(
    val owner: String,
    val item: FocusItem,
    val position: Int,
    val total: Int,
    val stamp: Long,
)

/** Transient overlay payload for the ball-driven cycling preview card. */
data class FocusPreview(
    val title: String,
    val serverName: String,
    val position: Int,
    val total: Int,
    val stamp: Long,
)

/** A cycle step computed by [SessionFocusHub.peekCycle] but not yet published. The
 *  floating ball peeks, navigates, and only [SessionFocusHub.commitCycle]s once the
 *  selection handoff has landed — a gesture dropped by the single-flight must not
 *  move the cursor and skip a session (v136). */
data class FocusStep(
    val owner: String,
    val item: FocusItem,
    val position: Int,
    val total: Int,
)

/**
 * Lets the floating ball walk the list that is currently on screen (batch v132).
 * Visible list screens register their flattened items under an owner id (their
 * tab/route id) and unregister on dispose; because NavHost disposes non-current
 * tab compositions, at most one owner is "live" per moment — but during the
 * ~190ms lateral fade two tabs are composed at once, so EVERY lookup is
 * owner-scoped (review #7): cycling never merges lists, and a highlight whose
 * ref vanished from its owner's set is dropped instead of re-scrolling.
 */
@Singleton
class SessionFocusHub @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val owners = MutableStateFlow<Map<String, List<FocusItem>>>(emptyMap())

    /** Last non-empty list seen per owner. The list screen is disposed the moment the
     *  ball opens a conversation, but cycling must keep working from the detail page —
     *  so the ring outlives the registration (v136). Kept out of [owners] so every
     *  in-screen lookup stays scoped to what is actually visible. */
    private val lastLists = mutableMapOf<String, List<FocusItem>>()

    /** Owner of the most recently registered non-empty list — the fallback target for
     *  a fire from a screen that owns no list itself (detail / composer / servers). */
    var lastOwner: String? = null
        private set

    private val _highlight = MutableStateFlow<FocusHighlight?>(null)
    val highlight: StateFlow<FocusHighlight?> = _highlight.asStateFlow()

    private val _preview = MutableStateFlow<FocusPreview?>(null)
    val preview: StateFlow<FocusPreview?> = _preview.asStateFlow()

    private var previewJob: Job? = null
    private var stamp = 0L

    fun register(owner: String, list: List<FocusItem>) {
        owners.value = owners.value + (owner to list)
        // An empty registration (list still loading) must not erase the last-known ring.
        if (list.isNotEmpty()) {
            lastLists[owner] = list
            lastOwner = owner
        }
        val hl = _highlight.value
        // A momentary empty emission is "still loading", not "the row vanished": the
        // highlight doubles as the ring cursor, so only a NON-empty list may drop it.
        if (list.isNotEmpty() && hl?.owner == owner && list.none { it.ref == hl.item.ref }) {
            _highlight.value = null
        }
    }

    fun unregister(owner: String) {
        owners.value = owners.value - owner
        // v136: the highlight SURVIVES disposal — it doubles as the ring cursor, and
        // the ball now opens the highlighted conversation straight away, so the list
        // that owned it is gone by the time the next fire lands. Every read is
        // owner-scoped, so a dormant highlight can't leak into another tab; on return
        // the list re-registers and keeps its scroll position (the surviving stamp is
        // seeded as the dedupe baseline), and register() drops the highlight if the
        // row vanished from the visible set.
    }

    /** Live list when one is registered, else the owner's last non-empty one when
     *  [stale] is allowed (firing from a detail page). [validServers], when given,
     *  filters out refs of devices that no longer exist — a last-known list outlives
     *  device deletion, and opening one would select a device with no profile. */
    private fun listFor(
        owner: String,
        stale: Boolean,
        validServers: Set<String>? = null,
    ): List<FocusItem> {
        val list = owners.value[owner]?.takeIf { it.isNotEmpty() }
            ?: if (stale) lastLists[owner].orEmpty() else emptyList()
        return if (validServers == null) list else list.filter { it.ref.serverId in validServers }
    }

    /** Does [owner] currently have a NON-empty registration? A tab route alone is not
     *  proof: Folders/Search/Settings never register, and a list that is still loading
     *  registers empty. The ball's cycle gesture uses this to decide whether the
     *  visible tab can drive the ring or the last-known list has to (v136). */
    fun hasLiveList(owner: String): Boolean = !owners.value[owner].isNullOrEmpty()

    /** Compute (without publishing) the next entry of [owner]'s list (wrapping), where
     *  [current] is the conversation actually on screen — the owner's list is usually
     *  disposed by then (the highlight alone can't say what the user opened by hand).
     *  Falls back to the highlighted ref when the caller has no current conversation.
     *  [stale] lets a fire from a detail page walk the last-known list of that owner;
     *  on a list tab the visible list always wins. */
    fun peekCycle(
        owner: String,
        current: SessionRef? = null,
        stale: Boolean = false,
        validServers: Set<String>? = null,
    ): FocusStep? {
        val list = listFor(owner, stale, validServers)
        if (list.isEmpty()) return null
        val anchor = current ?: _highlight.value?.takeIf { it.owner == owner }?.item?.ref
        val next = advanceFocusItems(list, anchor) ?: return null
        return FocusStep(owner, next.first, next.second + 1, list.size)
    }

    /** Publish a step from [peekCycle]: advance the highlight/cursor and show the
     *  preview card. Call only once the navigation the step belongs to has landed. */
    fun commitCycle(step: FocusStep): FocusHighlight {
        stamp += 1
        val hl = FocusHighlight(step.owner, step.item, step.position, step.total, stamp)
        _highlight.value = hl
        _preview.value = FocusPreview(step.item.title, step.item.serverName, step.position, step.total, stamp)
        schedulePreviewDismiss()
        return hl
    }

    /** Peek + commit in one call (pre-v136 API; kept for callers that don't route
     *  through a navigation handoff). */
    fun cycle(owner: String, current: SessionRef? = null, stale: Boolean = false): FocusHighlight? =
        peekCycle(owner, current, stale)?.let { commitCycle(it) }

    /** The highlighted ref for [owner], or null (used by openHighlighted to
     *  re-validate before navigating). Validates against the live list when the owner
     *  is registered, else against its last-known one — a highlight whose owner was
     *  disposed must still open if the row still exists. */
    fun highlightedRef(owner: String, validServers: Set<String>? = null): SessionRef? {
        val hl = _highlight.value?.takeIf { it.owner == owner } ?: return null
        return hl.item.ref.takeIf { ref ->
            listFor(owner, stale = true, validServers = validServers).any { it.ref == ref }
        }
    }

    /** [highlightedRef] for [owner] when given, else for whichever owner currently
     *  holds the highlight — the ball fires from screens that own no list (detail,
     *  composer), and the most recently registered list isn't necessarily the
     *  highlight's owner. Always validated against the owning list. */
    fun resolveHighlightedRef(owner: String?, validServers: Set<String>? = null): SessionRef? {
        val hl = _highlight.value ?: return null
        if (owner != null && hl.owner != owner) return null
        return highlightedRef(hl.owner, validServers)
    }

    fun clearPreview() {
        _preview.value = null
    }

    private fun schedulePreviewDismiss() {
        val at = stamp
        previewJob?.cancel()
        previewJob = scope.launch {
            delay(PREVIEW_MS)
            if (_preview.value?.stamp == at) _preview.value = null
        }
    }

    private companion object {
        const val PREVIEW_MS = 2_000L
    }
}

/** Which owner a "cycle session" fire walks, and whether it must fall back to that
 *  owner's last-known list (the visible tab has no live registration, or the fire comes
 *  from a non-list screen). Null when no list has ever registered. */
data class CycleTarget(val owner: String, val stale: Boolean)

/**
 * Owner/stale policy for a ball "cycle session" fire (v136). A tab route only owns the
 * ring while it has a live, non-empty registration — Folders/Search/Settings never
 * register, and Chats/Activity used to be silent no-ops there. Anything without a live
 * list falls back to the last list that registered, which is also what makes the
 * gesture land from a detail/composer screen.
 */
fun resolveCycleTarget(
    currentRoute: String?,
    isTabRoute: Boolean,
    hasLiveList: (String) -> Boolean,
    lastOwner: String?,
): CycleTarget? {
    val visible = currentRoute?.takeIf { isTabRoute && hasLiveList(it) }
    val owner = visible ?: lastOwner ?: return null
    return CycleTarget(owner = owner, stale = owner != visible)
}

/** Pure wrap-around step: index after [current] (0 when absent/stale), -1 when empty. */
fun advanceFocusItems(
    list: List<FocusItem>,
    current: SessionRef?,
): Pair<FocusItem, Int>? {
    if (list.isEmpty()) return null
    val idx = current?.let { c -> list.indexOfFirst { it.ref == c } } ?: -1
    val next = if (idx < 0) 0 else (idx + 1) % list.size
    return list[next] to next
}
