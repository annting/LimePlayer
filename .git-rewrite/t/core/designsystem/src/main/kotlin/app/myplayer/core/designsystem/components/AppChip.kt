package app.myplayer.core.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myplayer.core.designsystem.LocalAppColors

/**
 * 胶囊标签（设计板 `.chip`）：库切换 / 筛选用。
 * 选中态为**反色胶囊**（`--text` 底、`--bg` 字），颜色过渡 200ms；按压缩放 0.94。
 */
@Composable
fun AppChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val app = LocalAppColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 900f),
        label = "chip-scale",
    )
    val bg by animateColorAsState(
        targetValue = if (selected) app.chipSelectedBg else scheme.surface,
        animationSpec = tween(200),
        label = "chip-bg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) app.chipSelectedFg else scheme.onSurfaceVariant,
        animationSpec = tween(200),
        label = "chip-fg",
    )
    val border by animateColorAsState(
        targetValue = if (selected) app.chipSelectedBg else scheme.outline,
        animationSpec = tween(200),
        label = "chip-border",
    )

    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = modifier
            .scale(scale)
            .height(34.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = fg,
            fontSize = 13.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        )
    }
}
