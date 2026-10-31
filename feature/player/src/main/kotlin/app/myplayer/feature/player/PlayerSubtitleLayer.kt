package app.myplayer.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myplayer.core.designsystem.PlayerTokens
import kotlin.math.roundToInt

/**
 * 自绘字幕层（2026-10-26 取代 media3 的 `SubtitleView`）。
 *
 * 换掉它的原因只有两条，但都是 SubtitleView 做不到的：
 *
 * 1. **换行不能被顶出屏幕**。SubtitleView 只能「整块贴在某条基线上」——它算的是
 *    `bottomPaddingFraction`，块从下往上长。位置调到靠上时，两行字幕会继续向上溢出屏幕。
 *    这里改成：**上半区按上边线对齐**（向下长）、**下半区按下边线对齐**（向上长）。
 * 2. **竖屏看横屏视频时，字幕应该落到画面外的黑边里**。SubtitleView 与视频同尺寸，
 *    永远不可能画到黑边上去。这里拿到视频区在舞台里的矩形，有黑边就贴着画面放到黑边里。
 *
 * 排版交给 [Layout]：先量文字块高度，再按规则算 y —— 一行/两行、字号大小都不用手算。
 */
@Composable
internal fun SubtitleLayer(
    cues: List<String>,
    style: PlayerSubtitleStyle,
    /**
     * 视频画面在舞台容器里的矩形（`boundsInParent`）。未测量出来时传 [Rect.Zero]，
     * 此时按「画面铺满舞台」处理（等价于没有黑边）。
     */
    videoRect: Rect,
    modifier: Modifier = Modifier,
) {
    if (cues.isEmpty()) return
    val gapPx = with(LocalDensity.current) { SUBTITLE_GAP.toPx() }
    val maxWidthPx = with(LocalDensity.current) { SUBTITLE_MAX_WIDTH.toPx() }

    Layout(
        content = { SubtitleTextBlock(cues = cues, style = style) },
        modifier = modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val stageW = constraints.maxWidth
        val stageH = constraints.maxHeight
        // 文字最宽 = 画面宽度的 92%（留出左右呼吸），再夹两道：舞台宽 + SUBTITLE_MAX_WIDTH。
        // 后者是给「横屏超宽 + 短句」兜底的 —— 一行拉满 1280px 时眼睛要横扫整块屏。
        val hasVideoRect = videoRect.width > 1f && videoRect.height > 1f
        val widthCap = minOf(stageW, maxWidthPx.roundToInt()).coerceAtLeast(1)
        val maxTextWidth = if (hasVideoRect) {
            (videoRect.width * 0.92f).roundToInt().coerceIn(1, widthCap)
        } else {
            (stageW * 0.92f).roundToInt().coerceIn(1, widthCap)
        }
        val placeable = measurables.first().measure(Constraints(maxWidth = maxTextWidth))

        val videoTop = if (hasVideoRect) videoRect.top else 0f
        val videoBottom = if (hasVideoRect) videoRect.bottom else stageH.toFloat()
        val y = subtitleBlockTop(
            stageHeight = stageH.toFloat(),
            videoTop = videoTop,
            videoBottom = videoBottom,
            blockHeight = placeable.height,
            verticalPosition = style.verticalPosition,
            gapPx = gapPx,
        )

        layout(stageW, stageH) {
            placeable.place(x = (stageW - placeable.width) / 2, y = y.roundToInt())
        }
    }
}

/**
 * 字幕块左上角的 y（px）。
 *
 * 规则（纯函数，单测钉住）：
 * - **上半区**（`verticalPosition < 0.5`）：上边线对齐 —— 多行往下长，永远不会溢出屏幕顶部；
 *   上方黑边够高就贴在黑边里（贴着画面上沿），否则落在画面内部 `v × 画面高` 处。
 * - **下半区**：下边线对齐 —— 多行往上长；
 *   下方黑边够高就贴在黑边里（贴着画面下沿），否则落在画面内部 `v × 画面高` 处。
 * - 最后统一夹到 `[0, 舞台高 - 块高]`，任何输入都不会把文字画到屏幕外。
 */
internal fun subtitleBlockTop(
    stageHeight: Float,
    videoTop: Float,
    videoBottom: Float,
    blockHeight: Int,
    verticalPosition: Float,
    gapPx: Float,
): Float {
    val v = verticalPosition.coerceIn(0f, 1f)
    val videoHeight = (videoBottom - videoTop).coerceAtLeast(0f)
    val topBand = videoTop.coerceAtLeast(0f)
    val bottomBand = (stageHeight - videoBottom).coerceAtLeast(0f)
    val upperHalf = v < 0.5f

    val raw = if (upperHalf) {
        // 黑边能完整放下整块文字 → 放进黑边，底边贴着画面上沿
        if (topBand >= blockHeight + gapPx) videoTop - gapPx - blockHeight
        else videoTop + v * videoHeight
    } else {
        if (bottomBand >= blockHeight + gapPx) videoBottom + gapPx
        else videoTop + v * videoHeight - blockHeight
    }
    return raw.coerceIn(0f, (stageHeight - blockHeight).coerceAtLeast(0f))
}

/** 一条 cue 一行；多条 cue（双字幕）逐行排开，整体在水平方向居中。 */
@Composable
private fun SubtitleTextBlock(cues: List<String>, style: PlayerSubtitleStyle) {
    val textColor = Color(style.textColorArgb)
    val userOutlineDp = style.outlineWidthDp.coerceAtLeast(0f)
    // 0 描边 ≠ 不要可读性：补一层「淡描边」（粗细 1.2dp、透明度 60%）。
    // 依据：橙色字幕压浅色画面实测 1.08–1.48:1（一加横屏真机），横屏没有黑边可躲。
    val fallbackOutline = userOutlineDp <= 0.05f
    val outlineDp = if (fallbackOutline) PlayerTokens.SUBTITLE_FALLBACK_OUTLINE_DP else userOutlineDp
    val outlineColor = Color(style.outlineColorArgb).let {
        if (fallbackOutline) it.copy(alpha = it.alpha * PlayerTokens.SUBTITLE_FALLBACK_OUTLINE_ALPHA) else it
    }
    val outlinePx = with(LocalDensity.current) { outlineDp.dp.toPx() }

    val baseStyle = TextStyle(
        fontSize = style.fontSizeSp.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        // 行高给足：中文字幕紧贴在一起很难读
        lineHeight = (style.fontSizeSp * 1.32f).sp,
        // 均衡断行：默认策略下「【默认字幕】起播即显示，无需手动开 / 启」会把最后一个字
        // 甩到第二行（孤字行，sub-2-portrait-top 实测）。Balanced 会把两行长度拉平。
        // 断行策略是纯排版参数，不改变字色/字号，也不会让短句变宽。
        lineBreak = LineBreak.Heading,
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        cues.forEach { text ->
            Box {
                // 描边 = 先用 Stroke 画一遍同位置文字，再盖上填充色。
                // （Compose 没有原生的「文字描边」属性，这是标准做法）
                if (outlineDp > 0.05f) {
                    Text(
                        text = text,
                        style = baseStyle.copy(
                            color = outlineColor,
                            drawStyle = Stroke(width = outlinePx, join = StrokeJoin.Round),
                        ),
                    )
                }
                Text(text = text, style = baseStyle.copy(color = textColor))
            }
        }
    }
}

/** 字幕与画面边缘的间距（落进黑边时使用）。 */
private val SUBTITLE_GAP = 10.dp

/**
 * 字幕单行最大宽度上限。
 *
 * 手机竖屏用不到（画面宽 360dp × 0.92 = 331dp 已经先夹住）；这一条是给平板 / 横屏兜底的：
 * 1280dp 宽的画面上，「好的」俩字如果按 92% 排，字符间会被拉得很散或者一行拉满整屏。
 */
private val SUBTITLE_MAX_WIDTH = 480.dp
