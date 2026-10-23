package app.myplayer.core.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.myplayer.core.designsystem.AppShape
import app.myplayer.core.designsystem.Dimens
import app.myplayer.core.designsystem.LocalAppColors

/**
 * 标签 / 选项 Chip（`docs/03-UI设计规范.md` §3.3）。
 *
 * **重大变更**：不再是胶囊。高 32dp、圆角 **8dp**。
 * - 选中：底色 `primary`，文字 `brandInk`；
 * - 未选中：底色 `surface2`（深灰），文字 `onSurfaceVariant`，无描边。
 *
 * 颜色过渡 200ms，按压 0.95。
 */
@Composable
fun AppChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val app = LocalAppColors.current
    val interaction = remember { MutableInteractionSource() }

    val background by animateColorAsState(
        targetValue = when {
            !enabled -> app.surface3
            selected -> scheme.primary
            else -> app.surface2
        },
        animationSpec = tween(durationMillis = 200),
        label = "chip-bg",
    )
    val content by animateColorAsState(
        targetValue = when {
            !enabled -> app.text3
            selected -> app.brandInk
            else -> scheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 200),
        label = "chip-fg",
    )
    // 选中态用同色描边加粗轮廓（§3.3「描边变粗」）——用 border 而非 scale，避免布局抖动
    val borderColor by animateColorAsState(
        targetValue = if (selected && enabled) scheme.primary else Color.Transparent,
        animationSpec = tween(durationMillis = 200),
        label = "chip-border",
    )

    Box(
        modifier = modifier
            .height(Dimens.chip)
            .pressScale(interaction, pressedScale = 0.95f)
            .clip(AppShape.control)
            .background(background)
            .border(if (selected) 1.5.dp else Dimens.hairline, borderColor, AppShape.control)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp)
            .widthIn(min = 44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = content,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}
