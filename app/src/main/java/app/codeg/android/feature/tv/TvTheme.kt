package app.codeg.android.feature.tv

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** Overscan margin for the target box (M0 profile: 1080p @ 320dpi) — every TV
 *  screen edge must keep content inside this so no UI dies behind the bezel. */
val TvOverscan: Dp = 48.dp

/** codeg brand teal (matches the launcher banner placeholder). */
private val TvPrimary = Color(0xFF2DD3C0)
private val TvSurface = Color(0xFF122E33)

/**
 * TV-only dark theme. Deliberately isolated from the phone CodegTheme — the
 * phone appearance system (accent cycling, font scaling) must never leak into
 * the 10-foot shell, and vice versa.
 */
@Composable
fun TvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = TvPrimary,
            background = TvSurface,
            surface = TvSurface,
        ),
        content = content,
    )
}
