package app.codeg.android.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.Selectable
import androidx.compose.foundation.text.selection.Selection
import androidx.compose.foundation.text.selection.SelectionRegistrar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.codeg.android.R
import app.codeg.android.core.designsystem.theme.CodegTheme
import kotlin.math.roundToInt

/**
 * 批v195 拍板 2B: Compose's platform text toolbar has no share slot (the
 * built-in ActionMode never queries ACTION_PROCESS_TEXT), so the doc preview
 * supplies its own [TextToolbar] that mirrors showMenu/hide into a state
 * object, and a host composable renders the real menu. 复制/全选 stay
 * platform-delegated (the captured callbacks still do the work); 分享所选 /
 * 分享全文 are ours. Menu rect arrives in WINDOW coordinates (boundsInWindow).
 */
class DocSelectionToolbarState {
    internal var menu by mutableStateOf<DocMenu?>(null)

    /** Close without touching the selection (e.g. while the body scrolls). */
    fun hide() {
        menu = null
    }
}

internal data class DocMenu(
    val rect: Rect,
    val onCopy: (() -> Unit)?,
    val onSelectAll: (() -> Unit)?,
)

internal class DocTextToolbar(private val state: DocSelectionToolbarState) : TextToolbar {
    override fun getStatus(): TextToolbarStatus =
        if (state.menu != null) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        state.menu = DocMenu(rect, onCopyRequested, onSelectAllRequested)
    }

    override fun hide() {
        state.menu = null
    }
}

/** The floating menu host — render inside the same Box as the [androidx.compose.foundation.text.selection.SelectionContainer]. */
@Composable
fun DocSelectionToolbarHost(
    state: DocSelectionToolbarState,
    onShareSelection: () -> Unit,
    onShareAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    // The menu rect is window-based while a Popup offsets from THIS box, so
    // capture the box's own window bounds and subtract — correct regardless of
    // status-bar/window insets.
    val boxBounds = remember { mutableStateOf(Rect.Zero) }
    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { boxBounds.value = it.boundsInWindow() },
    ) {
        val menu = state.menu
        if (menu != null) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(
                    (menu.rect.left - boxBounds.value.left).roundToInt(),
                    (menu.rect.top - boxBounds.value.top).roundToInt(),
                ),
                onDismissRequest = { state.menu = null },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = colors.bgElevated,
                    border = BorderStroke(1.dp, colors.surfaceStroke),
                    shadowElevation = 8.dp,
                ) {
                    Column(Modifier.width(150.dp)) {
                        if (menu.onCopy != null) {
                            SelectionToolbarRow(stringResource(R.string.selection_menu_copy)) {
                                state.menu = null
                                menu.onCopy?.invoke()
                            }
                        }
                        if (menu.onSelectAll != null) {
                            SelectionToolbarRow(stringResource(R.string.selection_menu_select_all)) {
                                state.menu = null
                                menu.onSelectAll?.invoke()
                            }
                        }
                        SelectionToolbarRow(stringResource(R.string.selection_menu_share_selection)) {
                            state.menu = null
                            onShareSelection()
                        }
                        SelectionToolbarRow(stringResource(R.string.selection_menu_share_all)) {
                            state.menu = null
                            onShareAll()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionToolbarRow(label: String, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    Text(
        label,
        fontSize = 13.sp,
        color = colors.textPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/**
 * The selected text of a [Selection], reading the id→selectable registry
 * inside SelectionRegistrarImpl (internal — fetched reflectively, degraded to
 * null on any mismatch; the field is a LongObjectMap, not a java.util.Map, so
 * the list accessor is the only viable door). Everything after the fetch is
 * PUBLIC API: block order comes from each selectable's layout coordinates, so
 * cross-block selections concatenate in reading order. null = "unreadable" —
 * the caller shares the whole document instead (the 拍板 2B auto-degrade).
 */
fun extractSelectionText(registrar: SelectionRegistrar?, selection: Selection?): String? {
    if (registrar == null || selection == null) return null
    return runCatching {
        val raw: List<*> = registrar.javaClass.methods
            .firstOrNull { it.name == "getSelectables\$foundation_release" && it.parameterTypes.isEmpty() }
            ?.invoke(registrar) as? List<*>
            ?: return@runCatching null
        val entries = raw.filterIsInstance<Selectable>()
            .map { s ->
                Triple(
                    s.selectableId,
                    s.getText().text,
                    s.layoutCoordinates?.positionInRoot()?.y ?: Float.MAX_VALUE,
                )
            }
        if (entries.isEmpty()) return@runCatching null
        val start = selection.start
        val end = selection.end
        val posA = entries.indexOfFirst { it.first == start.selectableId }
        val posB = entries.indexOfFirst { it.first == end.selectableId }
        if (posA < 0 || posB < 0) return@runCatching null
        // Reading order: the anchor in the higher-on-screen block leads.
        val (leadPos, lead) = if (posA <= posB) posA to start else posB to end
        val (tailPos, tail) = if (posA <= posB) posB to end else posA to start
        val leadText = entries[leadPos].second
        val tailText = entries[tailPos].second
        val joined = if (leadPos == tailPos) {
            val o1 = minOf(lead.offset, tail.offset)
            val o2 = maxOf(lead.offset, tail.offset)
            if (o1 > leadText.length || o2 > leadText.length) return@runCatching null
            leadText.substring(o1, o2)
        } else {
            buildList {
                add(leadText.substring(minOf(lead.offset, leadText.length)))
                for (i in leadPos + 1 until tailPos) add(entries[i].second)
                add(tailText.substring(0, minOf(tail.offset, tailText.length)))
            }.joinToString("\n")
        }
        joined.trim().takeIf { it.isNotEmpty() }
    }.getOrNull()
}
