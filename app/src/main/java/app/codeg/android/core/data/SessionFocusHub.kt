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

    private val _highlight = MutableStateFlow<FocusHighlight?>(null)
    val highlight: StateFlow<FocusHighlight?> = _highlight.asStateFlow()

    private val _preview = MutableStateFlow<FocusPreview?>(null)
    val preview: StateFlow<FocusPreview?> = _preview.asStateFlow()

    private var previewJob: Job? = null
    private var stamp = 0L

    fun register(owner: String, list: List<FocusItem>) {
        owners.value = owners.value + (owner to list)
        val hl = _highlight.value
        if (hl?.owner == owner && list.none { it.ref == hl.item.ref }) _highlight.value = null
    }

    fun unregister(owner: String) {
        owners.value = owners.value - owner
        if (_highlight.value?.owner == owner) _highlight.value = null
    }

    /** Advance to the next entry of [owner]'s list (wrapping). No-op when the
     *  owner has no registered list (other tab / empty). */
    fun cycle(owner: String): FocusHighlight? {
        val list = owners.value[owner].orEmpty()
        if (list.isEmpty()) return null
        val current = _highlight.value?.takeIf { it.owner == owner }?.item?.ref
        val next = advanceFocusItems(list, current) ?: return null
        stamp += 1
        _highlight.value = FocusHighlight(owner, next.first, next.second + 1, list.size, stamp)
        _preview.value = FocusPreview(next.first.title, next.first.serverName, next.second + 1, list.size, stamp)
        schedulePreviewDismiss()
        return _highlight.value
    }

    /** The highlighted ref for [owner], or null (used by openHighlighted to
     *  re-validate before navigating). */
    fun highlightedRef(owner: String): SessionRef? =
        _highlight.value
            ?.takeIf { it.owner == owner }
            ?.item
            ?.ref
            ?.takeIf { ref -> owners.value[owner].orEmpty().any { it.ref == ref } }

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
