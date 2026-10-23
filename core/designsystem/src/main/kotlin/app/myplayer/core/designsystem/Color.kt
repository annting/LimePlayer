package app.myplayer.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 色板 Token —— 唯一事实来源 `docs/03-UI设计规范.md` §2.1。
 *
 * 规则：
 * 1. 只做亮 / 暗两套，**不做动态取色**。
 * 2. 品牌色（青柠绿）允许大面积使用（选中卡片、导航选中项），但其上的文字/图标必须用
 *    [AppColors.brandInk]，否则对比度不足。
 * 3. 业务代码**不允许**出现 `Color(0x…)` 字面量；需要新颜色先在这里加 token。
 */

// ---- 品牌 ----

/** 亮色主题主色。 */
val BrandLight = Color(0xFF7CB342)

/** 暗色主题主色。 */
val BrandDark = Color(0xFFAED581)

/** brand-ink（亮色）：品牌色之上的文字/图标。 */
val BrandInkLight = Color(0xFFFFFFFF)

/** brand-ink（暗色）：品牌色之上的文字/图标（深墨绿，9:1 对比度）。 */
val BrandInkDark = Color(0xFF1B2A10)

private val BrandSoftLight = Color(0xFFE8F0DB)
private val BrandSoftDark = Color(red = 174f / 255f, green = 213f / 255f, blue = 129f / 255f, alpha = 0.14f)

// ---- 中性面（亮） ----

val BgLight = Color(0xFFF4F6F9)
val SurfaceLight = Color(0xFFFFFFFF)
val Surface2Light = Color(0xFFECEFF4)
val Surface3Light = Color(0xFFE1E5EC)
val BorderLight = Color(0xFFDCE0E7)
val BorderStrongLight = Color(0xFFC6CCD6)
val TextLight = Color(0xFF14171D)
val Text2Light = Color(0xFF5A6472)
val Text3Light = Color(0xFF8B94A2)

// ---- 中性面（暗） ----

val BgDark = Color(0xFF0B0D11)
val SurfaceDark = Color(0xFF14171D)
val Surface2Dark = Color(0xFF1C2028)
val Surface3Dark = Color(0xFF262B35)
val BorderDark = Color(0xFF262C36)
val BorderStrongDark = Color(0xFF333A46)
val TextDark = Color(0xFFE8EBF1)
val Text2Dark = Color(0xFF9AA3B2)
val Text3Dark = Color(0xFF6B7484)

// ---- 语义色（仅状态表达，不做装饰） ----

val SuccessLight = Color(0xFF2F9E63)
val SuccessDark = Color(0xFF4ED08A)
val WarningLight = Color(0xFFD9822B)
val WarningDark = Color(0xFFE8A33D)
val DangerLight = Color(0xFFD0453F)
val DangerDark = Color(0xFFF2685F)

/** 评分角标（海报右下角的金色数字，双主题共用）。 */
val RatingGold = Color(0xFFF5C869)

/** 构建 Material 3 [ColorScheme]（§2.1）。 */
fun colorSchemeFor(isDark: Boolean): ColorScheme = if (isDark) {
    darkColorScheme(
        primary = BrandDark,
        onPrimary = BrandInkDark,
        primaryContainer = BrandSoftDark,
        onPrimaryContainer = BrandDark,
        background = BgDark,
        onBackground = TextDark,
        surface = SurfaceDark,
        onSurface = TextDark,
        surfaceVariant = Surface2Dark,
        onSurfaceVariant = Text2Dark,
        surfaceContainerLowest = BgDark,
        surfaceContainerLow = SurfaceDark,
        surfaceContainer = Surface2Dark,
        surfaceContainerHigh = Surface3Dark,
        surfaceContainerHighest = Surface3Dark,
        outline = BorderDark,
        outlineVariant = BorderStrongDark,
        error = DangerDark,
        onError = BrandInkDark,
    )
} else {
    lightColorScheme(
        primary = BrandLight,
        onPrimary = BrandInkLight,
        primaryContainer = BrandSoftLight,
        onPrimaryContainer = TextLight,
        background = BgLight,
        onBackground = TextLight,
        surface = SurfaceLight,
        onSurface = TextLight,
        surfaceVariant = Surface2Light,
        onSurfaceVariant = Text2Light,
        surfaceContainerLowest = SurfaceLight,
        surfaceContainerLow = SurfaceLight,
        surfaceContainer = Surface2Light,
        surfaceContainerHigh = Surface3Light,
        surfaceContainerHighest = Surface3Light,
        outline = BorderLight,
        outlineVariant = BorderStrongLight,
        error = DangerLight,
        onError = BrandInkLight,
    )
}

/**
 * `ColorScheme` 之外的扩展 token。
 *
 * 只放 M3 槽位装不下的语义（`brandInk` / `text3` / `surface2` / `border`），
 * 一律通过 [LocalAppColors] 取，不要在外面 `if (isDark)`。
 */
@Immutable
data class AppColors(val isDark: Boolean) {
    /** 品牌色之上的文字与图标（§2.1）—— 所有 `primary` 背景上的内容都用它。 */
    val brandInk: Color = if (isDark) BrandInkDark else BrandInkLight

    /** 弱化说明文字（第三级）。 */
    val text3: Color = if (isDark) Text3Dark else Text3Light

    /** 二级面：Chip 未选中底、输入框底、列表行底。 */
    val surface2: Color = if (isDark) Surface2Dark else Surface2Light

    /** 三级面：禁用态、骨架屏底色。 */
    val surface3: Color = if (isDark) Surface3Dark else Surface3Light

    /** 极细描边（1dp）。 */
    val border: Color = if (isDark) BorderDark else BorderLight

    /** 强描边（分隔线、Outlined 按钮边框）。 */
    val borderStrong: Color = if (isDark) BorderStrongDark else BorderStrongLight

    /** 成功态（连接探测通过、已完成）。 */
    val success: Color = if (isDark) SuccessDark else SuccessLight

    /** 警告态（可达但未识别、需用户确认）。 */
    val warning: Color = if (isDark) WarningDark else WarningLight

    /** 品牌色光晕（锁屏选中点外环）。 */
    val brandGlow: Color = if (isDark) Color(0x42AED581) else Color(0x337CB342)

    /** 锁屏背景的双色径向光斑（双主题共用固定值）。 */
    val lockGlowWarm: Color = Color(0x1FF0A63C)
    val lockGlowCool: Color = Color(0x295078B4)
}

val LocalAppColors = staticCompositionLocalOf { AppColors(isDark = true) }

/**
 * 播放器页专用 token（§5）。
 *
 * 播放页永远叠在视频画面上，**不能跟随主题反转**：亮色主题下若把控制栏做成浅色，
 * 会直接盖住画面。所以这里是独立的一组固定值，与 `ColorScheme` 无关。
 */
object PlayerTokens {
    /** 视频舞台底板（纯黑，避免解码间隙露白）。 */
    val stage = Color(0xFF000000)

    /** 浮层 / 弹层的实心底（近黑石墨）。 */
    val panel = Color(0xFF2B2B2F)

    /** 压在视频上的浮层底（速率 / 字幕面板）：需保证白字可读，又不能糊死画面。 */
    val panelScrim = Color(0xD1000000)

    /** 画面上的主前景（标题、时间码、主图标）。 */
    val onMedia = Color(0xFFFFFFFF)

    /** 次级前景（副标题、说明）。 */
    val onMediaSecondary = Color(0xB8FFFFFF)

    /** 弱化前景（未选中的选项、次要提示）。 */
    val onMediaMuted = Color(0x8AFFFFFF)

    /** 顶部 / 底部渐变遮罩的起止色。 */
    val scrimTop = Color(0xA6000000)
    val scrimBottom = Color(0xC7000000)

    /** 常驻控件底（顶部按钮、悬浮小胶囊）。 */
    val controlScrim = Color(0xB8000000)

    /** 进度条未播放轨道。 */
    val track = Color(0x40FFFFFF)

    /** 玻璃感小胶囊底（图标按钮）。 */
    val chipScrim = Color(0x1FFFFFFF)
}
