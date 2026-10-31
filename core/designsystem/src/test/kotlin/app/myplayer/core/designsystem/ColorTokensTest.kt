package app.myplayer.core.designsystem

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 取值以 `docs/03-UI设计规范.md` §2.2 为准（黄绿系 lime，与 app 图标一致）。 */
class ColorTokensTest {
    @Test fun `dark scheme uses dark background`() {
        val scheme = colorSchemeFor(isDark = true)
        assertThat(scheme.background).isEqualTo(Color(0xFF0B0D11))
    }

    @Test fun `light scheme uses light background`() {
        assertThat(colorSchemeFor(isDark = false).background).isEqualTo(Color(0xFFF4F6F9))
    }

    @Test fun `brand primary differs between themes`() {
        assertThat(colorSchemeFor(false).primary).isEqualTo(Color(0xFF4D7C0F))
        assertThat(colorSchemeFor(true).primary).isEqualTo(Color(0xFFAED581))
    }

    @Test fun `error follows theme danger palette`() {
        assertThat(colorSchemeFor(false).error).isEqualTo(DangerLight)
        assertThat(colorSchemeFor(true).error).isEqualTo(DangerDark)
    }

    // ---- WCAG 对比度守门（UI 优化方案 §1.1：三处不达标已修正，改回去要立刻失败） ----

    @Test fun `light primary text on brand fill meets AA`() {
        // 品牌色底 + 白字（选中 chip / 服务器行 / 播放按钮）：白字是 onPrimary
        val scheme = colorSchemeFor(isDark = false)
        assertThat(contrast(scheme.onPrimary, scheme.primary)).isAtLeast(AA_TEXT)
        // 品牌色**当文字**压在页面底上（「查看全部」「已连接」等）
        assertThat(contrast(scheme.primary, scheme.background)).isAtLeast(AA_TEXT)
    }

    @Test fun `dark primary text on brand fill meets AA`() {
        val scheme = colorSchemeFor(isDark = true)
        assertThat(contrast(scheme.onPrimary, scheme.primary)).isAtLeast(AA_TEXT)
        assertThat(contrast(scheme.primary, scheme.background)).isAtLeast(AA_TEXT)
    }

    @Test fun `tertiary text meets AA on every surface of its theme`() {
        // text3 是所有次级说明文字的默认色，必须在其主题的每一层底上都达标
        val light = AppColors(isDark = false)
        assertThat(contrast(light.text3, BgLight)).isAtLeast(AA_TEXT)
        assertThat(contrast(light.text3, SurfaceLight)).isAtLeast(AA_TEXT)
        assertThat(contrast(light.text3, Surface2Light)).isAtLeast(AA_TEXT)
        assertThat(contrast(light.text3, Surface3Light)).isAtLeast(AA_TEXT)

        val dark = AppColors(isDark = true)
        assertThat(contrast(dark.text3, BgDark)).isAtLeast(AA_TEXT)
        assertThat(contrast(dark.text3, SurfaceDark)).isAtLeast(AA_TEXT)
        assertThat(contrast(dark.text3, Surface2Dark)).isAtLeast(AA_TEXT)
        assertThat(contrast(dark.text3, Surface3Dark)).isAtLeast(AA_TEXT)
    }

    @Test fun `secondary text meets AA on every surface of its theme`() {
        assertThat(contrast(Text2Light, BgLight)).isAtLeast(AA_TEXT)
        assertThat(contrast(Text2Light, Surface2Light)).isAtLeast(AA_TEXT)
        assertThat(contrast(Text2Dark, BgDark)).isAtLeast(AA_TEXT)
        assertThat(contrast(Text2Dark, Surface2Dark)).isAtLeast(AA_TEXT)
    }

    /**
     * 播放页悬浮按钮：白图标压在「石墨玻璃衬底」上，衬底最坏情况是压在纯白画面上。
     *
     * 把衬底按 alpha 合成到白底后再算 —— 这正是 UI 优化方案 §2.6 说的「任何画面下都可读」。
     */
    @Test fun `player floating button icon stays readable over the brightest frame`() {
        val overWhite = composite(PlayerTokens.floatingScrim, Color.White)
        assertThat(contrast(PlayerTokens.onMedia, overWhite)).isAtLeast(AA_TEXT)
        // 压在最暗的纯黑画面上时衬底几乎不可见是正常的（黑底白图标本来就清楚），
        // 这里只守「衬底不能比画面更亮」这条：合成后必须比纯白暗，否则亮画面下会消失
        val overBlack = composite(PlayerTokens.floatingScrim, Color.Black)
        assertThat(contrast(PlayerTokens.onMedia, overBlack)).isAtLeast(AA_TEXT)
    }
}

/** WCAG 2.1 AA 正文文字：4.5:1。 */
private const val AA_TEXT = 4.5

/**
 * WCAG 2.1 相对亮度对比度：`CR = (L1 + 0.05) / (L2 + 0.05)`。
 *
 * 逐通道先线性化（sRGB → 线性光），再加权求和 —— 不能直接用 0~255 求亮度，
 * 那样算出来的数值会偏乐观（旧 `#7CB342` 会被算成 3.1:1，而真实值是 2.50:1）。
 */
internal fun contrast(a: Color, b: Color): Double {
    val la = luminance(a)
    val lb = luminance(b)
    val (hi, lo) = if (la >= lb) la to lb else lb to la
    return (hi + 0.05) / (lo + 0.05)
}

private fun luminance(c: Color): Double =
    0.2126 * linear(c.red) + 0.7152 * linear(c.green) + 0.0722 * linear(c.blue)

private fun linear(channel: Float): Double {
    val c = channel.toDouble()
    return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
}

/** 把带 alpha 的前景合成到不透明背景上（source-over）。 */
internal fun composite(foreground: Color, background: Color): Color {
    val a = foreground.alpha
    return Color(
        red = foreground.red * a + background.red * (1 - a),
        green = foreground.green * a + background.green * (1 - a),
        blue = foreground.blue * a + background.blue * (1 - a),
        alpha = 1f,
    )
}
