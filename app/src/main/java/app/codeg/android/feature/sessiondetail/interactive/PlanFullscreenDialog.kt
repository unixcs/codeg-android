package app.codeg.android.feature.sessiondetail.interactive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.codeg.android.R
import app.codeg.android.core.designsystem.markdown.MarkdownContent
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
 */
@Composable
fun PlanFullscreenDialog(
    title: String,
    markdown: String,
    onDismiss: () -> Unit,
) {
    val colors = CodegTheme.colors
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
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                ) {
                    MarkdownContent(raw = markdown)
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}
