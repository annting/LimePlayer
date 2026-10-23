package app.myplayer.core.designsystem.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.myplayer.core.designsystem.AppShape
import app.myplayer.core.designsystem.LocalAppColors

/**
 * 骨架屏方块（§3「加载态用骨架屏 shimmer，不用全屏转圈」）。
 *
 * 20dp 圆角 / 胶囊骨架块在规范里属于「装饰性大圆角」，所以这里统一用 **8dp/12dp**：
 * 方块默认 8dp（[AppShape.control]），卡片级骨架传 `cornerRadius = 12.dp`。
 */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
) {
    val base = LocalAppColors.current.surface3
    val highlight = MaterialTheme.colorScheme.surfaceContainerHighest
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer-progress",
    )
    Box(
        modifier = modifier
            .clip(if (cornerRadius >= 12.dp) AppShape.card else AppShape.control)
            .background(
                Brush.linearGradient(
                    colors = listOf(base, highlight, base),
                    start = Offset(progress * 600f - 300f, 0f),
                    end = Offset(progress * 600f, 0f),
                )
            )
    ) {
        Box(Modifier.fillMaxSize())
    }
}
