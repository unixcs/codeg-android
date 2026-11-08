package app.codeg.android.core.designsystem.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * Full-width prominent button in the accent colour (iOS `PrimaryGlassButton`).
 * Shows a spinner while [loading] and disables itself.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    val colors = CodegTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CodegTheme.dimens.radiusMd),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.accent,
            contentColor = colors.onAccent,
            disabledContainerColor = colors.accent.copy(alpha = 0.40f),
            disabledContentColor = colors.onAccent.copy(alpha = 0.70f),
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = colors.onAccent,
                strokeWidth = 2.dp,
            )
        } else {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Text(
                text = text,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = if (icon != null) 8.dp else 0.dp),
            )
        }
    }
}

/**
 * A Material 3 single-choice segmented control. 批v170 起选中态走中性
 * （浅灰低透明底+细边框），与 InsightKit.CapsuleChipRow 同一口径。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodegSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, label ->
            SegmentedButton(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                // 批v170 克制化：选中态=浅灰低透明底+细边框（shadcn 口径），
                // 蓝色选中块退役——10% accent 额度让给每屏唯一主 CTA。
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = colors.selectedBg,
                    activeContentColor = colors.selectedText,
                    activeBorderColor = colors.selectedBorder,
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = colors.textSecondary,
                    inactiveBorderColor = colors.surfaceStroke,
                ),
                icon = {},
            ) {
                Text(label, maxLines = 1)
            }
        }
    }
}

/**
 * Material 3 [FilterChip]: selected = 浅灰低透明底 + textPrimary + 细边框，
 * unselected hairline-outlined. [icon] renders as the leading
 * icon (pass `Icons.Rounded.Check` when selected for the canonical multi-select look).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodegFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = CodegTheme.colors
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        label = { Text(label) },
        leadingIcon = icon?.let {
            { Icon(it, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        },
        // 批v170 克制化：选中=浅灰低透明底+textPrimary+细边框（去蓝）。
        colors = FilterChipDefaults.filterChipColors(
            labelColor = colors.textSecondary,
            iconColor = colors.textSecondary,
            selectedContainerColor = colors.selectedBg,
            selectedLabelColor = colors.selectedText,
            selectedLeadingIconColor = colors.selectedText,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = colors.surfaceStroke,
            selectedBorderColor = colors.selectedBorder,
        ),
    )
}
