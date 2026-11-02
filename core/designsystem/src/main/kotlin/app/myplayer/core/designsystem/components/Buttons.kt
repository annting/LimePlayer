package app.myplayer.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.myplayer.core.designsystem.AppShape
import app.myplayer.core.designsystem.Dimens
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.designsystem.Spacing

/**
 * 按钮层级（`docs/03-UI设计规范.md` §3.2）。
 *
 * - [Primary]：Filled，主操作（下一步 / 继续播放 / 登录 / 测试成功后的前进）。
 * - [Outlined]：描边，次操作（测试连接 / 标记已播放 / 取消）。
 * - [Text]：文字按钮，低强调（删除确认里的「取消」等）。
 */
enum class AppButtonStyle {
    Primary,
    Outlined,
    Text,
}

/**
 * 全站唯一按钮。
 *
 * 规格：高 48dp、圆角 **8dp**、Press 缩放 0.95（§4）。
 * [leadingIcon] 的颜色由调用方按样式给（通常是 `onPrimary` / `onSurface`）。
 *
 * @param destructive 危险操作（删除 / 移除 / 忽略）—— true 时强调色换成 `error`。
 *   2026-11-02 补：此前弹窗里的「删除 / 移除」都绕开本组件裸用 `TextButton`，
 *   正是因为 `AppButton` 没有危险色变体（见 `docs/13-组件统一修复任务.md` T2）。
 * @param fillMaxWidth 默认铺满；弹窗按钮行、行内文字按钮传 false 改为 wrap-content。
 * @param height 默认 48dp（规范按钮高）；弹窗底部动作行传 40dp 与 `AppConfirmDialog` 对齐。
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
    destructive: Boolean = false,
    fillMaxWidth: Boolean = true,
    height: Dp = Dimens.button,
    contentPadding: PaddingValues = PaddingValues(horizontal = Spacing.lg.dp),
) {
    val scheme = MaterialTheme.colorScheme
    val app = LocalAppColors.current
    val interaction = remember { MutableInteractionSource() }

    val active = enabled && !loading
    // 危险操作统一走 error 色；其余走品牌色
    val accent = if (destructive) scheme.error else scheme.primary
    val onAccent = if (destructive) scheme.onError else scheme.onPrimary
    val container: Color = when (style) {
        AppButtonStyle.Primary -> if (active) accent else app.surface3
        AppButtonStyle.Outlined -> Color.Transparent
        AppButtonStyle.Text -> Color.Transparent
    }
    val content: Color = when (style) {
        AppButtonStyle.Primary -> if (active) onAccent else app.text3
        AppButtonStyle.Outlined -> when {
            !active -> app.text3
            destructive -> scheme.error
            else -> scheme.onSurface
        }
        AppButtonStyle.Text -> if (active) accent else app.text3
    }
    val borderColor: Color = when (style) {
        AppButtonStyle.Primary -> Color.Transparent
        AppButtonStyle.Outlined -> when {
            !active -> app.border
            destructive -> scheme.error
            else -> app.borderStrong
        }
        AppButtonStyle.Text -> Color.Transparent
    }

    Box(
        modifier = modifier
            .then(if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier)
            .height(height)
            .pressScale(interaction, pressedScale = 0.95f)
            .clip(AppShape.control)
            .background(container)
            .then(
                if (style == AppButtonStyle.Primary) {
                    Modifier
                } else {
                    Modifier.border(Dimens.hairline, borderColor, AppShape.control)
                }
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = active,
                onClick = onClick,
            )
            // 内边距放在 clickable 之后：可点区域仍是整块，只有内容被收进来
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = content,
                strokeWidth = 2.dp,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (leadingIcon != null) {
                    leadingIcon()
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = text,
                    color = content,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
