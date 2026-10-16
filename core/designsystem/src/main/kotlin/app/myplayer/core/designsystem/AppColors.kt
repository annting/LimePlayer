package app.myplayer.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Material 3 `ColorScheme` 之外的扩展 token（对齐 `docs/03-UI设计规范.md` §2.2 与设计板）。
 * 取值与 `design/player-ui-mockups.html` 的 CSS 变量一一对应。
 */
@Immutable
data class AppColors(
    val isDark: Boolean,
) {
    /** `--text-3` 弱化说明文字。 */
    val text3: Color = if (isDark) Color(0xFF6B7484) else Color(0xFF8B94A2)

    /** `--brand-glow` 品牌色光晕（聚焦圈 / 抬高阴影的替代）。 */
    val brandGlow: Color = if (isDark) Color(0x42AED581) else Color(0x337CB342)

    /** Chip 选中态：反色胶囊（`--text` 底 / `--bg` 字），与设计板一致。 */
    val chipSelectedBg: Color = if (isDark) Color(0xFFE8EBF1) else Color(0xFF14171D)
    val chipSelectedFg: Color = if (isDark) Color(0xFF0B0D11) else Color(0xFFF4F6F9)

    /** 锁屏光晕（设计板为双主题共用固定的暖 / 冷径向渐变）。 */
    val lockGlowWarm: Color = Color(0x1FF0A63C)
    val lockGlowCool: Color = Color(0x295078B4)
}

val LocalAppColors = staticCompositionLocalOf { AppColors(isDark = true) }

/**
 * 界面尺寸 token（设计板取值的 dp 近似）。
 * 规范里的 8 / 12 / 999 为下限口径，设计板渲染为大圆角派系 —— 以设计板为渲染基准。
 */
object AppRadius {
    val control = 12
    val field = 14
    val card = 14
    val poster = 14
    val button = 15
    val sourceCard = 18
    val pill = 999
}
