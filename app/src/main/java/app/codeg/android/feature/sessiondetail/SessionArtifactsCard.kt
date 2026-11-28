package app.codeg.android.feature.sessiondetail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.core.designsystem.component.MiddleEllipsisText
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.R

/** 批v196: collapse / compact expand / full expand with separate full-expand control. */
internal enum class SessionArtifactsTier {
    COLLAPSED, COMPACT, FULL;

    fun toggleExpanded(): SessionArtifactsTier = if (this == COLLAPSED) COMPACT else COLLAPSED

    fun toggleFullExpand(): SessionArtifactsTier = if (this == FULL) COMPACT else FULL
}

/**
 * 批v194 M3: the MindFS "related files" card, ported to the transcript's data
 * path. A collapsible strip above the composer listing every file the session
 * wrote ([SessionArtifacts.derive]) — tapping a row opens the in-app preview,
 * so a doc the agent just produced is one tap away instead of a folder climb.
 *
 * D2 拍板: every file type is collected (code included), the card defaults to
 * compact expand, and the 「仅文档」 chip filters the
 * rows down to documents (md/txt/html) when a long session buries the report.
 * A manual toggle is remembered while the screen lives; per session key so a
 * switched session re-applies the auto default.
 */
@Composable
fun SessionArtifactsCard(
    artifacts: List<SessionArtifact>,
    sessionKey: String?,
    availableBodyHeight: Dp,
    onOpen: (SessionArtifact) -> Unit,
    onCopyPath: (SessionArtifact) -> Unit,
) {
    val colors = CodegTheme.colors
    var docsOnly by rememberSaveable(sessionKey) { mutableStateOf(false) }
    var displayTier by rememberSaveable(sessionKey) { mutableStateOf(SessionArtifactsTier.COMPACT) }
    val shown = if (docsOnly) artifacts.filter { it.isDocLike } else artifacts
    // R2-A: no early return — with docsOnly on and zero docs, vanishing the
    // header would strand the filter (unreachable until the session changes).
    // The screen only composes this card when artifacts itself is non-empty.
    val expanded = displayTier != SessionArtifactsTier.COLLAPSED
    val bodyMaxHeight = if (displayTier == SessionArtifactsTier.COMPACT) {
        110.dp
    } else {
        minOf(240.dp, availableBodyHeight)
    }
    val listState = key(sessionKey) { rememberLazyListState() }

    Surface(
        color = colors.bgElevated,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(
                            if (expanded) R.string.session_artifacts_collapse else R.string.session_artifacts_expand
                        ),
                    ) { displayTier = displayTier.toggleExpanded() }
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            ) {
                Icon(
                    Icons.Rounded.Description,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    stringResource(R.string.session_artifacts_title, shown.size),
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(start = 6.dp),
                )
                Text(
                    stringResource(R.string.session_artifacts_docs_only),
                    fontSize = 11.sp,
                    color = if (docsOnly) colors.accent else colors.textTertiary,
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { docsOnly = !docsOnly }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(
                            role = Role.Button,
                            onClickLabel = stringResource(
                                if (displayTier == SessionArtifactsTier.FULL) {
                                    R.string.session_artifacts_restore
                                } else {
                                    R.string.session_artifacts_full_expand
                                }
                            ),
                        ) { displayTier = displayTier.toggleFullExpand() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (displayTier == SessionArtifactsTier.FULL) {
                            Icons.Rounded.CloseFullscreen
                        } else {
                            Icons.Rounded.OpenInFull
                        },
                        contentDescription = stringResource(
                            if (displayTier == SessionArtifactsTier.FULL) {
                                R.string.session_artifacts_restore
                            } else {
                                R.string.session_artifacts_full_expand
                            }
                        ),
                        tint = colors.textTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(if (expanded) 180f else 0f),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    if (shown.isEmpty()) {
                        // docsOnly filtered everything out — keep the card (and the
                        // chip) alive with a one-line note instead of vanishing.
                        Text(
                            stringResource(R.string.session_artifacts_no_docs),
                            fontSize = 11.sp,
                            color = colors.textTertiary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    } else {
                        // Keep every row available while preventing a large session
                        // from pushing the composer off-screen under the IME.
                        LazyColumn(
                            state = listState,
                            userScrollEnabled = true,
                            modifier = Modifier.heightIn(max = bodyMaxHeight),
                        ) {
                            itemsIndexed(shown, key = { _, artifact -> artifact.path to artifact.name }) { idx, artifact ->
                                if (idx > 0) HorizontalDivider(color = colors.hairline)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpen(artifact) }
                                        .padding(horizontal = 10.dp, vertical = 3.dp),
                                ) {
                                    Icon(
                                        if (artifact.isDocLike) Icons.Rounded.Description else Icons.AutoMirrored.Rounded.InsertDriveFile,
                                        contentDescription = null,
                                        tint = when (artifact.op) {
                                            ArtifactOp.ADDED -> colors.success
                                            ArtifactOp.DELETED -> colors.danger
                                            ArtifactOp.MODIFIED -> colors.warning
                                        },
                                        modifier = Modifier.size(13.dp),
                                    )
                                    Column(Modifier.weight(1f)) {
                                        MiddleEllipsisText(
                                            artifact.name,
                                            color = colors.textPrimary,
                                            style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                                        )
                                        Text(
                                            // R2-A: a root-level file has no dirTail —
                                            // drop the "…/" prefix instead of a cryptic "?"
                                            buildString {
                                                if (artifact.dirTail.isNotEmpty()) {
                                                    append("…/")
                                                    append(artifact.dirTail)
                                                    append(" · ")
                                                }
                                                append(
                                                    stringResource(
                                                        when (artifact.op) {
                                                            ArtifactOp.ADDED -> R.string.session_artifacts_added
                                                            ArtifactOp.MODIFIED -> R.string.session_artifacts_modified
                                                            ArtifactOp.DELETED -> R.string.session_artifacts_deleted
                                                        },
                                                    ),
                                                )
                                            },
                                            fontSize = 10.sp,
                                            color = colors.textTertiary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    // R2-A: 26dp was well under the touch-target
                                    // guideline next to the row's own open-on-tap.
                                    IconButton(onClick = { onCopyPath(artifact) }, modifier = Modifier.size(48.dp)) {
                                        Icon(
                                            Icons.Rounded.ContentCopy,
                                            contentDescription = stringResource(R.string.session_artifacts_copy_path),
                                            tint = colors.textTertiary,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
