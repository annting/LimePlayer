package app.myplayer.core.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.myplayer.core.designsystem.AppShape
import app.myplayer.core.designsystem.Dimens
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.designsystem.Spacing

/**
 * 紧凑型二次确认弹窗 —— 替代 Material 3 的 `AlertDialog`。
 *
 * ## 为什么不用 `AlertDialog`
 * M3 版的 title / text / button 三段**各带一份 24dp 内边距**，标题与正文之间还有
 * `text` 的上下留白、按钮区再叠一层 24dp；标题只有 4 个字、正文只有一行时，
 * 上下全是虚空，弹窗看着"很空"。而且圆角固定 28dp，比全站 12dp 的卡片圆得多。
 *
 * ## 这里的手工排版
 * ```
 * ┌────────────────────────────────┐  20dp 圆角（AppShape.dialog）
 * │ 清空搜索历史              ↕20dp │
 * │ ↕8dp                            │
 * │ 确定要清空所有搜索历史吗？        │  ← 正文，次级色
 * │ ↕12dp                           │
 * │                    [取消] [清空] │  ← 靠右；按钮内边距 12/8 → 视觉间隙 16dp
 * └────────────────────────────────┘
 * ```
 * 三段间距 20 / 8 / 12dp，总高度比 `AlertDialog` 少约 60dp。
 *
 * @param destructive 主操作是否危险操作（清空 / 删除）—— true 时用 `error` 色，
 *   否则用品牌色。确认键永远在右侧，符合「左取消右确认」的肌肉记忆。
 */
@Composable
fun AppConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissText: String = "取消",
    destructive: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val app = LocalAppColors.current

    Dialog(
        onDismissRequest = onDismiss,
        // 关掉平台默认宽度（约 65% 屏宽且随窗口变化），下面自己控宽度：
        // 窄屏留 40dp 边、平板/折叠屏最宽 340dp —— 避免大屏上被拉成一张横条
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = modifier
                .padding(horizontal = 40.dp)
                .widthIn(max = 340.dp)
                .fillMaxWidth(),
            shape = AppShape.dialog,
            // 弹窗用 surface（用户要求），不叠 tonalElevation —— 深色主题下抬高会发灰
            color = scheme.surface,
            tonalElevation = 0.dp,
        ) {
            Column(Modifier.padding(Dimens.dialogPadding)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                )
                Spacer(Modifier.height(Spacing.sm.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.md.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        contentPadding = DialogButtonPadding,
                    ) {
                        Text(
                            text = dismissText,
                            style = MaterialTheme.typography.labelLarge,
                            color = app.text3,
                        )
                    }
                    TextButton(
                        onClick = onConfirm,
                        contentPadding = DialogButtonPadding,
                    ) {
                        Text(
                            text = confirmText,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (destructive) scheme.error else scheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** 按钮内边距：横向 12dp（保证两个按钮的文字不会挤在一起）、纵向 8dp（不吃额外高度）。 */
private val DialogButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
