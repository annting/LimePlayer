package app.myplayer.core.designsystem.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.designsystem.Spacing

/**
 * 分区小标题的档位。
 *
 * 历史问题（`docs/相似组件审查报告.md` 组 B）：`SortFilterSheet` 与 `PlayerSettingsSheet`
 * 各有一份**函数体逐字相同**的实现（`labelLarge` + `text3` + 上下 18/10），
 * `LocalScreen` 里还有第三份（同字号同色，只有 padding 不同），全是拷贝。
 */
enum class SectionLabelVariant {
    /**
     * 分组档：弹层 / 页面里「上一段」与「这一段的标题」之间的分隔。
     *
     * 上下 24（`Spacing.xl` = 分组之间）/ 8（`Spacing.sm` = 标题→内容）。
     */
    Group,

    /**
     * 紧凑档：卡片或弹窗内部的次级分组，跟随父容器的密排节奏（如 `spacedBy(4)` 的列表）。
     *
     * 上下 4（`Spacing.xs`）/ 0 —— 下间距交给父容器，避免与 `spacedBy` 叠加成两倍。
     */
    Compact,
}

/**
 * 分区小标题（**全站唯一实现**，2026-11-02 统一）。
 *
 * 视觉：`labelLarge` + `text3`。层级比页面标题低一档：
 * 大标题（`titleLarge` / `onSurface`）走各页面自己的 `SectionTitle`（如详情页的「简介 / 演职员」）。
 *
 * @param text 标题文字
 * @param variant 间距档位，见 [SectionLabelVariant]
 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    variant: SectionLabelVariant = SectionLabelVariant.Group,
) {
    val padding = when (variant) {
        SectionLabelVariant.Group -> PaddingValues(top = Spacing.xl.dp, bottom = Spacing.sm.dp)
        SectionLabelVariant.Compact -> PaddingValues(top = Spacing.xs.dp)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = LocalAppColors.current.text3,
        modifier = modifier.padding(padding),
    )
}
