package app.codeg.android.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.codeg.android.R

/**
 * Type scale. Body/UI text uses the platform default (Roboto); code uses
 * [FontFamily.Monospace]. Sizes are tuned to read like the iOS app (17pt body,
 * 13pt code). A bundled developer mono (e.g. JetBrains Mono) can be dropped in
 * later by swapping [CodegMono].
 */
val CodegMono: FontFamily = FontFamily.Monospace

/** Shared style for inline code / commands / monospaced values (iOS `Font.mono(13)`). */
val CodeTextStyle = TextStyle(
    fontFamily = CodegMono,
    fontSize = 13.sp,
    lineHeight = 18.sp,
)

/**
 * 批v172 D5: bundled 楷体正文字体 — 霞鹜文楷 Lite v1.530 Regular 子集
 * (GB2312 全表 + Latin/Greek/标点/全角，3.6MB / 8.3k glyphs)，OFL 授权。
 * APK 内置属 OFL 意义上的 Modified Version，按其保留字条款字体族改名为
 * "WenKai"；许可全文随包 assets/LICENSE-LXGW-WenKai-Lite-OFL.txt。
 */
val CodegWenKai = FontFamily(Font(R.font.lxgw_wenkai))

/**
 * 批v172 D5: app-wide body font choice. Numbers/code keep [CodegMono] — the
 * swap only touches the M3 type scale, mono call sites are unaffected.
 */
enum class AppFont(val id: String) {
    SYSTEM("system"),
    WENKAI("wenkai"),
    SERIF("serif");

    /** null = keep the platform default (the pre-D5 behaviour). */
    fun resolve(): FontFamily? = when (this) {
        SYSTEM -> null
        WENKAI -> CodegWenKai
        SERIF -> FontFamily.Serif
    }

    companion object {
        val all: List<AppFont> = entries
        fun from(id: String?): AppFont = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/**
 * Type scale aligned to the Material 3 reference scale (16sp body, 14sp label),
 * so text reads at native Android sizes rather than the iOS 17pt body. Titles
 * keep a SemiBold weight for brand emphasis; labels use Material line-heights.
 */
private val CodegTypeBase = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
)

/** 批v172 D5: body-font swap over the type scale; null keeps the stock look. */
fun codegTypography(appFont: FontFamily?): Typography {
    if (appFont == null) return CodegTypeBase
    return CodegTypeBase.copy(
        titleLarge = CodegTypeBase.titleLarge.copy(fontFamily = appFont),
        titleMedium = CodegTypeBase.titleMedium.copy(fontFamily = appFont),
        titleSmall = CodegTypeBase.titleSmall.copy(fontFamily = appFont),
        bodyLarge = CodegTypeBase.bodyLarge.copy(fontFamily = appFont),
        bodyMedium = CodegTypeBase.bodyMedium.copy(fontFamily = appFont),
        bodySmall = CodegTypeBase.bodySmall.copy(fontFamily = appFont),
        labelLarge = CodegTypeBase.labelLarge.copy(fontFamily = appFont),
        labelMedium = CodegTypeBase.labelMedium.copy(fontFamily = appFont),
        labelSmall = CodegTypeBase.labelSmall.copy(fontFamily = appFont),
    )
}
