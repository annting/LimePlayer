package app.myplayer.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.myplayer.core.designsystem.AppRadius
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.designsystem.PlayerTokens
import app.myplayer.core.designsystem.components.CheckIcon

/**
 * 播放层快捷菜单的固定行高。
 *
 * 菜单高度 = 行高 × 可见行数，所以这个值必须由「行」自己拥有 —— 之前写在 `PlayerScreen.kt`
 * 里给布局算高度，行组件另写一遍 36dp，两处各改一半就会错位。
 */
internal val QuickRowHeight = 36.dp

/**
 * 可选中行的两种观感。
 *
 * 为什么要有 variant 而不是一套样式打天下：这两处分别在**设置面板**（浅色卡面）与
 * **播放层快捷菜单**（压在视频画面上的深色浮层）里，前者的对勾在右、行高由内容撑开，
 * 后者对勾在左（未选中也占位，保证文字左边缘对齐）、行高必须固定成 36dp 才能算出
 * 「可见行数」。硬合成一种会牺牲其中一边的可读性，所以保留两种预设，
 * 但**只在这一处实现**（2026-11-02 组件统一，`docs/13-组件统一修复任务.md` T10）。
 */
internal enum class SelectableRowVariant {
    /** 设置面板里的音轨/字幕轨行：右侧对勾，可带副标题。 */
    Sheet,

    /** 播放层快捷菜单行：左侧对勾占位，固定 36dp，白色系文字。 */
    Quick,
}

/**
 * 可选中行（[label] + 可选副标题 [sub] + 选中态）。
 *
 * @param onClick null = 不可点（如设备不支持的音轨）
 * @param enabled false = 变灰且不可点
 */
@Composable
internal fun SelectableRow(
    label: String,
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    sub: String? = null,
    enabled: Boolean = true,
    variant: SelectableRowVariant = SelectableRowVariant.Sheet,
) {
    val scheme = MaterialTheme.colorScheme
    val app = LocalAppColors.current
    val clickable = Modifier.clickable(enabled = enabled && onClick != null) { onClick?.invoke() }

    when (variant) {
        SelectableRowVariant.Sheet -> Row(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppRadius.card.dp))
                .then(clickable)
                .padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) scheme.onSurface else app.text3,
                )
                if (sub != null) {
                    Text(sub, style = MaterialTheme.typography.labelSmall, color = app.text3)
                }
            }
            if (selected) CheckIcon(tint = scheme.primary)
        }

        SelectableRowVariant.Quick -> Row(
            modifier
                .height(QuickRowHeight)
                .then(clickable)
                .padding(start = 12.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 对号占位：未选中也留同样宽度，文字左边缘保持对齐
            Box(Modifier.width(18.dp)) {
                if (selected) {
                    CheckIcon(tint = PlayerTokens.onMedia, modifier = Modifier.size(14.dp))
                }
            }
            Text(
                label,
                color = if (selected) PlayerTokens.onMedia else PlayerTokens.onMedia.copy(alpha = 0.68f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}
