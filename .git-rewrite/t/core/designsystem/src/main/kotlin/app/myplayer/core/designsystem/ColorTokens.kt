package app.myplayer.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 色板 token —— 取值与 `design/player-ui-mockups.html` 的 CSS 变量一一对应，
 * 权威文档为 `docs/03-UI设计规范.md` §2.2（黄绿系 lime，与 app 图标一致）。
 *
 * ⚠️ 归档初稿里的薄荷绿 `#0FDC78 / #32F08C` 已废弃，不要再引用。
 */

// 亮色
private val BrandLight = Color(0xFF7CB342)
private val OnBrandLight = Color(0xFFFFFFFF)
private val BrandSoftLight = Color(0xFFE8F0DB)

// 暗色
private val BrandDark = Color(0xFFAED581)
private val OnBrandDark = Color(0xFF1B2A10)

/** 暗色 brand-soft 为 `rgba(174,213,129,.14)`：与设计板一致，保留透明度（叠在暗色 surface 上）。 */
private val BrandSoftDark = Color(red = 174f / 255f, green = 213f / 255f, blue = 129f / 255f, alpha = 0.14f)

// 语义色（仅用于状态表达，不做装饰）
val SuccessLight = Color(0xFF2F9E63)
val SuccessDark = Color(0xFF4ED08A)
val WarningLight = Color(0xFFD9822B)
val WarningDark = Color(0xFFE8A33D)
val DangerLight = Color(0xFFD0453F)
val DangerDark = Color(0xFFF2685F)

fun colorSchemeFor(isDark: Boolean): ColorScheme = if (isDark) {
    darkColorScheme(
        primary = BrandDark, onPrimary = OnBrandDark, primaryContainer = BrandSoftDark,
        background = Color(0xFF0B0D11), surface = Color(0xFF14171D), surfaceVariant = Color(0xFF1C2028),
        surfaceContainerHighest = Color(0xFF262B35),
        onSurface = Color(0xFFE8EBF1), onSurfaceVariant = Color(0xFF9AA3B2),
        outline = Color(0xFF262C36), outlineVariant = Color(0xFF333A46),
        error = DangerDark,
    )
} else {
    lightColorScheme(
        primary = BrandLight, onPrimary = OnBrandLight, primaryContainer = BrandSoftLight,
        background = Color(0xFFF4F6F9), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFECEFF4),
        surfaceContainerHighest = Color(0xFFE1E5EC),
        onSurface = Color(0xFF14171D), onSurfaceVariant = Color(0xFF5A6472),
        outline = Color(0xFFDCE0E7), outlineVariant = Color(0xFFC6CCD6),
        error = DangerLight,
    )
}

object Spacing {
    val xs = 4
    val sm = 8
    val md = 12
    val lg = 16
    val xl = 24
}

object Radius {
    val control = 8
    val card = 12
    val pill = 999
}
