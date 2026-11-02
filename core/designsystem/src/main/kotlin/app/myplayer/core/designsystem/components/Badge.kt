package app.myplayer.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import app.myplayer.core.designsystem.Dimens
import app.myplayer.core.designsystem.LocalAppColors

/**
 * 徽章色调。
 *
 * 全站只有这三种语义，新增状态先想清楚属于哪一类，不要复制一份新的 `Row + clip + background`。
 */
enum class AppBadgeTone {
    /** 中性标签（「隐私」「已忽略」）：surface2 底 + 次要文字色，安静地贴在行尾。 */
    Neutral,

    /** 强调标签（「当前」）：品牌色淡底 + 品牌色文字，表示"这是正在生效的那一项"。 */
    Accent,

    /** 描边胶囊（屏幕右上角的隐私模式标识）：surface2 底 + 1dp 描边，压在任意页面上都能收边。 */
    Outline,
}

/**
 * 状态徽章：**胶囊 + 一行小字**，可选前置圆点与点击。
 *
 * 2026-11-02 抽（`docs/13-组件统一修复任务.md` T11）：此前三处各写一遍
 * `clip(RoundedCornerShape(50)) + background + padding`——
 * `PrivacyActiveBadge`（右上角隐私标识，带描边和圆点）、
 * 服务器行的「隐私」标签、「当前」标签（纯文字、无底色）。
 * 三者的差别只有色调、描边、圆点，于是收成同一份：
 *
 * | 场景 | 传参 |
 * |---|---|
 * | 右上角隐私标识 | `tone = Outline, dot = true, onClick = ...` |
 * | 服务器行「隐私」 | 默认（`tone = Neutral`） |
 * | 服务器行「当前」 | `tone = Accent` |
 *
 * @param dot 前置 6dp 圆点（品牌色）；用于"正在直播/正在生效"这类需要更强存在感的标识
 * @param onClick 非空 = 可点（点击整枚徽章）
 */
@Composable
fun AppBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: AppBadgeTone = AppBadgeTone.Neutral,
    dot: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val app = LocalAppColors.current
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)

    val container = when (tone) {
        AppBadgeTone.Neutral -> app.surface2
        AppBadgeTone.Accent -> scheme.primary.copy(alpha = 0.14f)
        AppBadgeTone.Outline -> app.surface2
    }
    val contentColor = when (tone) {
        AppBadgeTone.Neutral -> app.text3
        AppBadgeTone.Accent -> scheme.primary
        AppBadgeTone.Outline -> app.text3
    }

    Row(
        modifier = modifier
            .clip(shape)
            .background(container)
            .then(
                if (tone == AppBadgeTone.Outline) {
                    Modifier.border(Dimens.hairline, app.borderStrong, shape)
                } else {
                    Modifier
                }
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(shape)
                    .background(scheme.primary),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (tone == AppBadgeTone.Accent) FontWeight.SemiBold else FontWeight.Normal,
            color = contentColor,
            maxLines = 1,
        )
    }
}
