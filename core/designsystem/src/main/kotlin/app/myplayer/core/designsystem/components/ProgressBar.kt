package app.myplayer.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.myplayer.core.designsystem.LocalAppColors

/**
 * 细进度条（3dp、2dp 端点圆角）：**全站唯一实现**（2026-11-02 统一，见 `docs/13` T6）。
 *
 * 统一前有 3 份各自内联重画的拷贝，只有轨道色与内缩不同：
 *
 * | 调用方 | 轨道色 | 内缩（左右 / 底） |
 * |---|---|---|
 * | 统一文件卡（海报/视频卡底部） | `surface3` | 8 / 4dp |
 * | 详情页续播行 | `onSurface·0.16` | 8 / 0dp |
 * | 首页 16:9 横卡 | `PlayerTokens.track` | 12 / 8dp |
 *
 * 内缩不是装饰：贴底边画的话两端会被卡片 8–12dp 圆角切掉一截（2026-10-24 真机反馈）。
 *
 * @param progress 0..1，超出范围自动夹紧
 * @param trackColor 轨道色（各页面语义不同，必须显式给或走默认 surface3）
 * @param insetHorizontal 左右内缩
 * @param insetBottom 底部内缩
 * @param barHeight 条高，默认 3dp
 */
@Composable
fun ThinProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    trackColor: Color = LocalAppColors.current.surface3,
    insetHorizontal: Dp = 8.dp,
    insetBottom: Dp = 4.dp,
    barHeight: Dp = 3.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(2.dp)
    val fraction = progress.coerceIn(0f, 1f)
    Box(
        modifier
            .padding(start = insetHorizontal, end = insetHorizontal, bottom = insetBottom)
            .fillMaxWidth()
            .height(barHeight)
            .clip(shape)
            .background(trackColor),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(barHeight)
                .clip(shape)
                .background(scheme.primary),
        )
    }
}
