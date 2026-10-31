package app.myplayer.core.designsystem.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.myplayer.core.designsystem.LocalAppColors

/**
 * 应用统一的开关。
 *
 * ⚠️ **不要直接用 M3 `Switch`**：`SwitchDefaults` 的「关闭」态取
 * `thumbColor = outline`、`trackColor = surfaceContainerHighest`，
 * 而本项目的 `outline`（#262C36）与 `surfaceContainerHighest`（#262B35）几乎同色，
 * 于是**关闭时看不到圆点**（真机复现，用户 2026-10-22 反馈）。
 * 这里显式指定四组颜色，保证：
 * - 关闭态：`text2` 圆点 + `surface3` 轨道 + `borderStrong` 描边 —— 圆点始终可见；
 * - 打开态：品牌色轨道 + `brandInk` 圆点（与主按钮同口径，对比度按 §2.1 保证）。
 *
 * **亮色主题的关闭态单独处理（用户 2026-10-28 反馈）**：原来两套主题共用
 * 「`text2` 圆点 + `surface3` 轨道」，在暗色下是对的（浅灰点 + 深轨道），
 * 到亮色就变成「`#5A6472` 近黑圆点压在 `#E1E5EC` 浅轨道上」，整颗开关读起来很重。
 * 亮色改为「白色圆点 + `borderStrong`(#C6CCD6) 轨道」：白点在白卡片上不会消失，
 * 因为轨道比卡片暗一档（1.55:1），形状边界靠轨道本身给出，不再用深色圆点撑。
 *
 * @param checked 当前状态
 * @param onCheckedChange 状态回调；传 `null` 表示只读展示
 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val app = LocalAppColors.current
    // 关闭态两套主题分开取值，理由见上方注释
    val offThumbColor = if (app.isDark) scheme.onSurfaceVariant else Color.White
    val offTrackColor = if (app.isDark) app.surface3 else app.borderStrong
    val offBorderColor = app.borderStrong

    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            // 打开
            checkedThumbColor = app.brandInk,
            checkedTrackColor = scheme.primary,
            checkedBorderColor = scheme.primary,
            checkedIconColor = scheme.primary,
            // 关闭 —— 圆点必须比轨道亮/暗一档，否则会「消失」
            uncheckedThumbColor = offThumbColor,
            uncheckedTrackColor = offTrackColor,
            uncheckedBorderColor = offBorderColor,
            uncheckedIconColor = offTrackColor,
            // 禁用
            disabledCheckedThumbColor = app.brandInk.copy(alpha = 0.72f),
            disabledCheckedTrackColor = scheme.primary.copy(alpha = 0.38f),
            disabledCheckedBorderColor = Color.Transparent,
            disabledUncheckedThumbColor = if (app.isDark) app.text3.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.72f),
            disabledUncheckedTrackColor = app.surface2,
            disabledUncheckedBorderColor = app.border,
            disabledUncheckedIconColor = app.surface2,
        ),
    )
}
