package app.myplayer.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.myplayer.core.designsystem.AppShape
import app.myplayer.core.designsystem.LocalAppColors

/**
 * 全站统一滑杆（`docs/03-UI设计规范.md` §3.4）。
 *
 * **为什么要自己画 Thumb / Track**：Material 3 1.4 起 `Slider` 默认改用
 * 「竖条手柄 + 波浪轨道」的 Expressive 样式，在设置页里看起来就像一根奇怪的竖线
 * （用户 2026-10-22 反馈）。这里显式提供：
 * - **圆形** Thumb（20dp、品牌色、外圈 2dp 页面底色，压在轨道上时边界清晰）；
 * - 标准圆头轨道：激活段 `primary`、未激活段 `surface3`，高 6dp。
 *
 * 手势、无障碍与键盘操作仍由 M3 [Slider] 负责，这里只替换视觉。
 *
 * @param onValueChangeFinished 松手回调。连续拖动的值建议在这里再落盘，
 *        避免每一帧都写 DataStore（设置是整份 JSON，写入成本不低）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val app = LocalAppColors.current

    val activeColor = if (enabled) scheme.primary else app.text3
    val inactiveColor = if (enabled) app.surface3 else app.surface2
    val ringColor = scheme.surface

    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        colors = SliderDefaults.colors(
            thumbColor = activeColor,
            activeTrackColor = activeColor,
            inactiveTrackColor = inactiveColor,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
        ),
        thumb = {
            Box(
                Modifier
                    .size(20.dp)
                    .shadow(elevation = 2.dp, shape = CircleShape)
                    .clip(CircleShape)
                    .background(activeColor)
                    .border(width = 2.dp, color = ringColor, shape = CircleShape),
            )
        },
        track = { state ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(AppShape.nav)          // 3dp 圆头的标准轨道
                    .background(inactiveColor),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(state.coercedValueAsFraction)
                        .fillMaxHeight()
                        .background(activeColor),
                )
            }
        },
    )
}
