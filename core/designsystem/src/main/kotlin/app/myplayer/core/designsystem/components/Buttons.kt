package app.myplayer.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import app.myplayer.core.designsystem.AppShape
import app.myplayer.core.designsystem.Dimens
import app.myplayer.core.designsystem.LocalAppColors

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
    val scheme = MaterialTheme.colorScheme
    val app = LocalAppColors.current
    val interaction = remember { MutableInteractionSource() }

    val active = enabled && !loading
    val container: Color = when (style) {
        AppButtonStyle.Primary -> if (active) scheme.primary else app.surface3
        AppButtonStyle.Outlined -> Color.Transparent
        AppButtonStyle.Text -> Color.Transparent
    }
    val content: Color = when (style) {
        AppButtonStyle.Primary -> if (active) scheme.onPrimary else app.text3
        AppButtonStyle.Outlined -> if (active) scheme.onSurface else app.text3
        AppButtonStyle.Text -> if (active) scheme.primary else app.text3
    }
    val borderColor: Color = when (style) {
        AppButtonStyle.Primary -> Color.Transparent
        AppButtonStyle.Outlined -> if (active) app.borderStrong else app.border
        AppButtonStyle.Text -> Color.Transparent
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.button)
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
            ),
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
