package app.myplayer.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.myplayer.core.designsystem.AppShape
import app.myplayer.core.designsystem.Dimens
import app.myplayer.core.designsystem.LocalAppColors

/**
 * 页面顶栏（§6 AppBar）：返回按钮 + 标题（19sp/600）+ 副标题（11.5sp `text3`）+ 尾部动作。
 *
 * 高 54dp，不画背景（跟随页面底色），保证与内容同层。
 */
@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(start = if (onBack != null) 4.dp else Dimens.pagePadding, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            CircleIconButton(onClick = onBack) {
                BackIcon(tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (onBack != null) 4.dp else 0.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalAppColors.current.text3,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        actions()
    }
}

/**
 * 图标按钮：视觉 40dp、圆角 8dp、按压缩放 0.95，**触控热区 48dp**（规范 §5）。
 *
 * 外层 48dp 承担点击与热区，内层 40dp 负责视觉与缩放 —— 只放大热区、不放大视觉，
 * 40dp 的比例才不会被破坏。`wrapContent` 语义不变：外层仍按内容居中，不影响父级对齐。
 *
 * 2026-11-02 扩展（`docs/13-组件统一修复任务.md` T5）：此前全站有 5 套"带热区的可点图标"
 * 各自实现（规范版 + 详情操作行 + 播放层 + 库详情顶栏 + 详情返回），差异只有形状 / 背景 /
 * 描边 / 禁用表达 / 按压缩放 —— 现在全部由下面这些参数表达：
 *
 * | 场景 | 传参 |
 * |---|---|
 * | 顶栏 40dp 透明 | 默认值 |
 * | 库详情顶栏（圆形、禁用变淡） | `shape = AppShape.circle, enabled = false` |
 * | 详情操作行（48dp 实心/描边/危险色） | `shape = AppShape.circle, size = Dimens.touchTarget, background/borderColor` |
 * | 详情返回（scrim 底） | `background = scheme.scrim.copy(alpha = .42f)` |
 * | 播放层（44dp 半透明圆底 + 描边 + 0.9 缩放） | `size = 44.dp, shape = CircleShape, background, borderColor, pressScale = .9f` |
 *
 * @param shape 形状；玩家页 / 详情操作行传 `AppShape.circle`
 * @param size 视觉尺寸（热区恒为至少 [Dimens.touchTarget] 48dp）
 * @param background 内层底色，默认透明
 * @param borderColor 非空时描 1dp 边（[Dimens.hairline]）
 * @param enabled false = 不可点 + 整体 35% 透明度（顶栏多选态的不可用按钮）
 * @param contentDescription 无障碍描述；非空时挂到热区上
 * @param pressScale 按压缩放，播放层用 0.9，其余 0.95
 */
@Composable
fun CircleIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = AppShape.control,
    size: Dp = Dimens.iconButton,
    background: Color = Color.Transparent,
    borderColor: Color? = null,
    enabled: Boolean = true,
    contentDescription: String? = null,
    pressScale: Float = 0.95f,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            // 热区不小于 48dp：视觉 40/44dp 时靠外层补足，48dp 视觉时两者相等
            .size(maxOf(Dimens.touchTarget, size))
            .alpha(if (enabled) 1f else 0.35f)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .pressScale(interaction, pressedScale = pressScale)
                .clip(shape)
                .then(if (background == Color.Transparent) Modifier else Modifier.background(background))
                .then(if (borderColor == null) Modifier else Modifier.border(Dimens.hairline, borderColor, shape)),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}
