package app.codeg.android.feature.sessiondetail.interactive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.Toc
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.codeg.android.R
import app.codeg.android.core.designsystem.markdown.OutlineAwareMarkdownBody
import app.codeg.android.core.designsystem.markdown.outlineEntries
import app.codeg.android.core.designsystem.markdown.parseMarkdownBlocks
import app.codeg.android.core.designsystem.theme.CodegTheme

/** What the fullscreen plan view shows — captured from the pending card when
 *  the owner taps the expand affordance, cleared when they collapse back. */
data class FullscreenPlanUi(
    val title: String,
    val markdown: String,
)

/**
 * 全屏查看审批卡正文（M7）：bottomBar 条带里 300/320dp 的滚动帽对一年的
 * 计划文本太局促——卡片右上角的展开按钮把 markdown 搬进全屏 Dialog
 * （DocPreviewDialog 范式），顶部一个按钮缩回默认卡片。只读查看：审批
 * 操作仍在原卡片上做，避免把 submitting/notes 状态整套搬进 Dialog。
 *
 * 批v195 拍板 3A: the body moves to the shared outline-aware renderer —
 * the header gains a TOC action (gated on actual headings) popping the same
 * MindFS-dims card as the doc preview / file browser.
 */
@Composable
fun PlanFullscreenDialog(
    title: String,
    markdown: String,
    onDismiss: () -> Unit,
) {
    val colors = CodegTheme.colors
    var outlineOpen by remember { mutableStateOf(false) }
    val hasOutline = remember(markdown) { outlineEntries(parseMarkdownBlocks(markdown)).isNotEmpty() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = colors.bg) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Rounded.CloseFullscreen,
                            contentDescription = stringResource(R.string.interactive_collapse_fullscreen),
                            tint = colors.textSecondary,
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (hasOutline) {
                        IconButton(onClick = { outlineOpen = !outlineOpen }) {
                            Icon(
                                Icons.Rounded.Toc,
                                contentDescription = stringResource(R.string.doc_preview_outline),
                                tint = colors.textSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                OutlineAwareMarkdownBody(
                    content = markdown,
                    outlineOpen = outlineOpen,
                    onDismissOutline = { outlineOpen = false },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalPadding = 20.dp,
                    headerOffset = 8.dp,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
