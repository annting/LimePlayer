package app.myplayer.core.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.myplayer.core.designsystem.LocalAppColors

/**
 * 步骤指示条（§9 添加服务器向导）：`total` 段 3dp 条，已完成 / 当前段为品牌色，过渡 200ms。
 *
 * 3dp 高的条用 8dp 圆角在几何上就是圆头，不需要额外使用胶囊形去"凑"端点。
 */
@Composable
fun StepsIndicator(
    total: Int,
    current: Int,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val idle = LocalAppColors.current.surface3
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(total) { index ->
            val on = index < current
            val color by animateColorAsState(
                targetValue = if (on) scheme.primary else idle,
                animationSpec = tween(durationMillis = 200),
                label = "step-color",
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(color)
            )
        }
    }
}
