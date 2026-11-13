package app.codeg.android.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.codeg.android.R
import app.codeg.android.core.data.LocalAttachment
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * 批v160 W10: the attachment row + picker actions shared by the dispatch form
 * and the idea editor (they were two near-identical private copies). The card
 * language matches the W5 task cards: radiusSm corners, elevated fill, hairline
 * stroke, and a 44dp row the thumb can actually hit.
 */

/** One attachment card: thumbnail (images, opens the caller's viewer) or file
 *  glyph, the name, and a remove button. [onClick] wires the image viewer. */
@Composable
fun CodegAttachmentRow(
    attachment: LocalAttachment,
    onRemove: (LocalAttachment) -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = CodegTheme.colors
    val shape = RoundedCornerShape(CodegTheme.dimens.radiusSm)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(colors.bgElevated.copy(alpha = 0.55f))
            .border(1.dp, colors.surfaceStroke, shape)
            .clickable(enabled = onClick != null, onClick = { onClick?.invoke() })
            .padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    ) {
        if (attachment.isImage) {
            LocalImageThumbnail(
                path = attachment.localPath,
                modifier = Modifier
                    .size(width = 56.dp, height = 36.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Icon(
                Icons.Rounded.AttachFile,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier
                    .size(width = 56.dp, height = 36.dp)
                    .background(colors.bg.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(10.dp),
            )
        }
        Text(
            attachment.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
        )
        Icon(
            Icons.Rounded.Close,
            // R3-A P1: this is the ONLY removal path — a destructive action with
            // no name is un-usable (and dangerous) under TalkBack.
            contentDescription = stringResource(R.string.attach_remove),
            tint = colors.textTertiary,
            modifier = Modifier
                // 44dp hit circle (R2-B: 32dp was under thumb target) — the glyph
                // stays 16dp via padding.
                .size(44.dp)
                .clip(RoundedCornerShape(50))
                .clickable { onRemove(attachment) }
                .padding(14.dp),
        )
    }
}

/** One pill picker button (图片 / 文件): hairline outline, icon + label. */
@Composable
private fun AttachPillButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = CodegTheme.colors
    val shape = RoundedCornerShape(CodegTheme.dimens.radiusSm)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            // R2-B: 34dp of pill was under the 44dp thumb target.
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(if (enabled) colors.bgElevated.copy(alpha = 0.55f) else Color.Transparent)
            .border(1.dp, colors.surfaceStroke, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            // R3-A P3: a disabled pill that keeps the accent icon reads as
            // "still tappable" — dim the icon with the label.
            tint = if (enabled) colors.accent else colors.textTertiary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = if (enabled) colors.textPrimary else colors.textTertiary,
        )
    }
}

/** The three pickers (图片 / 拍照 / 任意文件) as a pill row — the cap mirrors the
 *  callers' [app.codeg.android.core.data.AttachmentLimits.MAX_IMAGES] gating via
 *  [enabled]. 批v178: the camera pill fires the shared [rememberCameraCapture]. */
@Composable
fun CodegAttachActions(
    pickImagesLabel: String,
    pickCameraLabel: String,
    pickFilesLabel: String,
    enabled: Boolean,
    onPickImages: () -> Unit,
    onCamera: () -> Unit,
    onPickFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AttachPillButton(Icons.Rounded.Image, pickImagesLabel, enabled, onPickImages)
        AttachPillButton(Icons.Rounded.PhotoCamera, pickCameraLabel, enabled, onCamera)
        AttachPillButton(Icons.Rounded.AttachFile, pickFilesLabel, enabled, onPickFiles)
    }
}
