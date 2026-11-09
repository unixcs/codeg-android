package app.codeg.android.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * User-selectable accent palettes, ported from the iOS `AccentPalette`. Each has
 * a dark-mode and light-mode fill. The default is [TEAL] (批v150: 任务书 §2.1
 * 「克制的青蓝色强调」— the rebuild's mandated visual direction; users who
 * already picked a palette keep theirs).
 */
enum class AccentPalette(val id: String, val dark: Color, val light: Color) {
    MINT("mint", Color(0xFF66E0B3), Color(0xFF0F946B)),
    BLUE("blue", Color(0xFF63A8FF), Color(0xFF0073EB)),
    INDIGO("indigo", Color(0xFF8F99FC), Color(0xFF4A4FDB)),
    PURPLE("purple", Color(0xFFC28CFF), Color(0xFF8542D4)),
    PINK("pink", Color(0xFFFF73B5), Color(0xFFDB297D)),
    ORANGE("orange", Color(0xFFFF9E4D), Color(0xFFD96B0D)),
    TEAL("teal", Color(0xFF4DD1DB), Color(0xFF008594)),
    RED("red", Color(0xFFFF7373), Color(0xFFD13033));

    fun resolve(isDark: Boolean): Color = if (isDark) dark else light

    companion object {
        val DEFAULT = TEAL
        val all: List<AccentPalette> = entries
        fun from(id: String?): AccentPalette = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * 批v172 D4 护眼全家桶: light-mode paper variants. Dark mode ignores the
 * setting — this is the "paper" the app reads on, not a second dark theme.
 * Alpha-derived strokes/hairlines adapt to any of these on their own; the
 * tinted variants only need two ink colours darkened to hold WCAG AA at 12sp
 * (see [codegColors]).
 */
enum class LightSurface(val id: String, val bg: Color, val bgElevated: Color, val tinted: Boolean) {
    DEFAULT("default", Color(0xFFF2F4F6), Color(0xFFFFFFFF), tinted = false),
    PARCHMENT("parchment", Color(0xFFF2EAD8), Color(0xFFFAF5E9), tinted = true),
    SAGE("sage", Color(0xFFCCE8CF), Color(0xFFEAF4EC), tinted = true),
    PAPER_GRAY("gray", Color(0xFFE6E5E1), Color(0xFFF4F3F0), tinted = true);

    companion object {
        val all: List<LightSurface> = entries
        fun from(id: String?): LightSurface = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * The full set of semantic colours used across the app, ported from the iOS
 * `Theme`. Provided via `LocalCodegColors`; resolved for the active mode +
 * accent by [codegColors].
 */
@Immutable
data class CodegColors(
    val isDark: Boolean,
    val bg: Color,
    val bgElevated: Color,
    val surfaceStroke: Color,
    val hairline: Color,
    val codeSurface: Color,
    val rail: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val danger: Color,
    val accent: Color,
    val onAccent: Color,
    val accentDim: Color,
    /** Status-tag tones (task board): running/done green, waiting amber. */
    val success: Color,
    val warning: Color,
    // 批v171（经典外观开关）：选中态四角色——全仓胶囊/芯片/药丸的唯一取色口径。
    // restrained（默认，批v170 口径）= 浅灰低透明底+细边框+文字色差兜底；
    // classic（主人点名可一键切回的 v169 前观感）= accent 实心底+onAccent 文字。
    // 组件层禁止再硬编码 textPrimary@0.10 / surfaceStroke / onAccent 三元。
    val selectedBg: Color,
    val selectedBorder: Color,
    val selectedText: Color,
    val selectedSubText: Color,
)

/** Build the colour set for the given mode + accent (iOS `Theme.swift` values).
 *  [classicLook] flips the selected-state roles to the pre-v170 accent-fill skin;
 *  [lightSurface] swaps the LIGHT branch's paper tones only (批v172 D4). */
fun codegColors(
    isDark: Boolean,
    accent: AccentPalette,
    classicLook: Boolean = false,
    lightSurface: LightSurface = LightSurface.DEFAULT,
): CodegColors {
    val accentColor = accent.resolve(isDark)
    val onAccent = onAccentColor(accentColor)
    return if (isDark) {
        CodegColors(
            isDark = true,
            bg = Color(0xFF0A0B0D),
            bgElevated = Color(0xFF141619),
            surfaceStroke = Color.White.copy(alpha = 0.09f),
            hairline = Color.White.copy(alpha = 0.055f),
            codeSurface = Color.Black.copy(alpha = 0.30f),
            rail = Color.White.copy(alpha = 0.16f),
            textPrimary = Color(0xFFF7F7F7),
            textSecondary = Color(0xFFA3A3A3),
            // 批v153（第3轮可及性审查修正）：#707070 on bgElevated ≈3.7:1，12sp
            // 说明字不达 AA；#8A8A8A ≈4.5:1+。
            textTertiary = Color(0xFF8A8A8A),
            danger = Color(0xFFF57575),
            accent = accentColor,
            onAccent = onAccent,
            accentDim = accentColor.copy(alpha = 0.16f),
            success = Color(0xFF57C79A),
            warning = Color(0xFFE0B15C),
            selectedBg = if (classicLook) accentColor else Color(0xFFF7F7F7).copy(alpha = 0.10f),
            selectedBorder = if (classicLook) accentColor else Color.White.copy(alpha = 0.09f),
            selectedText = if (classicLook) onAccent else Color(0xFFF7F7F7),
            selectedSubText = if (classicLook) onAccent.copy(alpha = 0.85f) else Color(0xFFA3A3A3),
        )
    } else {
        CodegColors(
            isDark = false,
            // 批v172 D4: paper variant — DEFAULT reproduces the v170 light theme
            // exactly; tinted variants swap both paper tones (cards read as the
            // same paper, one step lighter than the page).
            bg = lightSurface.bg,
            bgElevated = lightSurface.bgElevated,
            surfaceStroke = Color.Black.copy(alpha = 0.10f),
            // 批v170 R4（浅色专项）：7% 叠白对白卡仅 1.17:1，卡片轮廓近乎隐形；
            // 9% ≈ Tremor gray-200 档，深色侧（白 5.5%）不动。
            hairline = Color.Black.copy(alpha = 0.09f),
            codeSurface = Color.Black.copy(alpha = 0.045f),
            rail = Color.Black.copy(alpha = 0.20f),
            textPrimary = Color(0xFF1C1C1C),
            textSecondary = Color(0xFF616161),
            // 批v153（第3轮可及性审查修正）：#8C8C8C ≈3.1-3.4:1（12sp 不达 AA，
            // 工作台新面大量使用）；#6E6E6E ≈4.6-5.1:1。
            // 批v172 D4: tinted paper drops that to ≈4.27:1 (parchment) — the
            // tinted variants darken one step (#5E5E5E ≈5.3:1 on every paper).
            textTertiary = if (lightSurface.tinted) Color(0xFF5E5E5E) else Color(0xFF6E6E6E),
            // 批v172 D4: same tinted-paper logic — #CC2E2E is ≈4.4:1 on parchment,
            // #B32E2E holds ≈5.2:1 on every tinted paper (default unchanged).
            danger = if (lightSurface.tinted) Color(0xFFB32E2E) else Color(0xFFCC2E2E),
            accent = accentColor,
            onAccent = onAccent,
            accentDim = accentColor.copy(alpha = 0.16f),
            success = Color(0xFF0F946B),
            // 浅色警告色加深（第1轮可及性审查修正）：#B07A10 在浅底上 3.4:1，
            // 12sp 小字不达 AA；#8A650B ≈5:1。暗色侧不变。
            warning = Color(0xFF8A650B),
            selectedBg = if (classicLook) accentColor else Color(0xFF1C1C1C).copy(alpha = 0.10f),
            selectedBorder = if (classicLook) accentColor else Color.Black.copy(alpha = 0.10f),
            selectedText = if (classicLook) onAccent else Color(0xFF1C1C1C),
            selectedSubText = if (classicLook) onAccent.copy(alpha = 0.85f) else Color(0xFF616161),
        )
    }
}

/** Black or white text/icon on a filled accent (iOS `onColor`).
 *  批v153（第3轮可及性审查修正）：亮度阈值是"悬崖"——0.4 之下还压着 5 块暗色板
 *  （BLUE/PURPLE/PINK/INDIGO/RED，白字只有 2.45-2.64:1，连大字 3:1 都不达），且
 *  旧口径声称的 PURPLE 翻黑实际未发生。改为两边比高：分别算对黑与对白的对比度、
 *  取高者——PURPLE/RED 暗色翻黑 ≈7-7.7:1，MINT 浅色升到 ≈4.9:1，其余色板不比
 *  旧逻辑差。 */
private fun onAccentColor(accent: Color): Color {
    val onDark = contrastRatio(accent, Color(0xFF0F0F0F))
    val onLight = contrastRatio(accent, Color.White)
    return if (onDark >= onLight) Color(0xFF0F0F0F) else Color.White
}

/** WCAG contrast ratio (relative luminance based). */
private fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return if (la > lb) la / lb else lb / la
}

/** The cool indigo glow used in the app background (iOS `CodegBackground`). */
val CodegGlowCool = Color(0xFF4D6BF2)
