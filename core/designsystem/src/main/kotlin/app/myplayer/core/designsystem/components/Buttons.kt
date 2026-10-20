package app.myplayer.core.designsystem.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myplayer.core.designsystem.AppRadius

/** 按钮形态：主（brand 填充）· 次（surface-2 填充）· 文字。 */
enum class AppButtonStyle { Primary, Tonal, Text }

/**
 * 全站统一按钮。带按压缩放微动画（1.0 → 0.97，`spring(dampingRatio = 0.7)`，`docs/03` §4）。
 *
 * [leadingIcon] 为可选前置图标插槽（如播放按钮的播放图标）—— 颜色由调用方按 [style] 的前景色给。
 */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: AppButtonStyle = AppButtonStyle.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 900f),
        label = "btn-scale",
    )

    val scheme = MaterialTheme.colorScheme
    val bg = when (style) {
        AppButtonStyle.Primary -> if (enabled) scheme.primary else scheme.surfaceContainerHighest
        AppButtonStyle.Tonal -> scheme.surfaceVariant
        AppButtonStyle.Text -> Color.Transparent
    }
    val fg = when (style) {
        AppButtonStyle.Primary -> if (enabled) scheme.onPrimary else scheme.onSurfaceVariant
        AppButtonStyle.Tonal -> scheme.onSurface
        AppButtonStyle.Text -> scheme.onSurfaceVariant
    }
    val shape = RoundedCornerShape(AppRadius.button.dp)
    val height = if (style == AppButtonStyle.Text) 44.dp else 50.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .scale(scale)
            .clip(shape)
            .background(bg)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled && !loading,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = fg,
                strokeWidth = 2.5.dp,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (leadingIcon != null) {
                    leadingIcon()
                    Spacer(Modifier.width(8.dp))
                }
                Text(text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
