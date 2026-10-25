package app.myplayer.core.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.myplayer.core.designsystem.AppShape
import app.myplayer.core.designsystem.Dimens
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.designsystem.Spacing

/**
 * 「分组列表」式设置页的通用组件（`docs/03-UI设计规范.md` §7）。
 *
 * 结构约定：
 * ```
 * SettingsGroup(title = "播放") {          // 12sp 灰色小标题 + 12dp 圆角 surface 卡片
 *     SettingsSwitchRow(...)                // 形态 A：右侧开关
 *     SettingsMenuRow(...)                  // 形态 B：右侧当前值 + 下拉箭头（DropdownMenu）
 *     SettingsNavRow(...)                   // 形态 C：右侧箭头，跳二级页
 * }
 * ```
 * 组内每行 [Dimens.rowMinHeight]（56dp）高，行间由 1dp、左缩进 16dp 的发丝线分隔。
 *
 * ⚠️ **最后一行必须显式传 `showDivider = false`**，否则卡片底部会多出一条贴边线。
 */

/** 下拉菜单里的一个选项。 */
data class SettingsOption<T>(val value: T, val label: String)

/**
 * 一个设置分组：小标题 + 12dp 圆角卡片。
 *
 * @param title 12sp 灰色小标题
 * @param content 组内的行（用下面的 `SettingsXxxRow`）
 */
@Composable
fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    /** 卡片底色；默认 surface。传入渐变色可以做「高亮闪烁」（如首页设置定位动画）。 */
    containerColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val app = LocalAppColors.current
    val scheme = MaterialTheme.colorScheme

    Column(modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = app.text3,
            modifier = Modifier.padding(start = Spacing.xs.dp, bottom = Spacing.sm.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 页面底是 background(bg)，卡片压 surface，靠 1dp 描边收边
                .clip(AppShape.card)
                .background(containerColor ?: scheme.surface)
                .border(Dimens.hairline, app.border, AppShape.card),
            content = content,
        )
    }
}

/** 组内行与行之间的发丝分隔线（左缩进 16dp 对齐文字）。 */
@Composable
fun SettingsDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(start = Dimens.pagePadding),
        thickness = Dimens.hairline,
        color = LocalAppColors.current.border,
    )
}

/**
 * 设置列表行的底座 —— 三种形态都由它派生。
 *
 * @param title 主文案
 * @param description 说明文案（可空，占第二行 11.5sp）
 * @param onClick 整行点击；`null` = 不可点
 * @param showDivider 是否在本行下方画分隔线（每组的**最后一行传 false**）
 * @param leading 左侧图标（可空）
 * @param trailing 右侧内容（开关 / 当前值+箭头 / 箭头）
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val app = LocalAppColors.current
    val scheme = MaterialTheme.colorScheme
    val titleColor = if (enabled) scheme.onSurface else app.text3

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier.clickable(enabled = enabled, onClick = onClick)
                    } else {
                        Modifier
                    }
                )
                .heightIn(min = Dimens.rowMinHeight)
                .padding(
                    start = Dimens.pagePadding,
                    end = Spacing.md.dp,
                    top = 9.dp,
                    bottom = 9.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(Spacing.md.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = titleColor,
                )
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.labelSmall,
                        color = app.text3,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Spacer(Modifier.width(Spacing.sm.dp))
            trailing()
        }
        if (showDivider) SettingsDivider()
    }
}

/**
 * 形态 A：开关行。整行可点（点击行 = 切换），右侧 [AppSwitch]。
 *
 * 开关自己会消费点击，所以点开关和点行都只触发一次 [onCheckedChange]。
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    showDivider: Boolean = true,
) {
    SettingsRow(
        title = title,
        description = description,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        showDivider = showDivider,
        modifier = modifier,
        trailing = {
            AppSwitch(
                checked = checked,
                onCheckedChange = { onCheckedChange(it) },
                enabled = enabled,
            )
        },
    )
}

/**
 * 形态 B：下拉菜单行（重点）。
 *
 * 右侧是「当前值 + 向下箭头」，点击整行在**本行下方、右对齐**弹出 [DropdownMenu]；
 * 选中项文字与对勾用品牌色，点选后菜单收起并回写。
 *
 * 单选项驱动：[selected] 是唯一事实来源，行上的文字由 [options] 反查，
 * 因此外部状态一变（含其它页面改动），行文字自动跟着变。
 */
@Composable
fun <T> SettingsMenuRow(
    title: String,
    options: List<SettingsOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    showDivider: Boolean = true,
) {
    val app = LocalAppColors.current
    val scheme = MaterialTheme.colorScheme

    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.value == selected }?.label.orEmpty()

    BoxWithConstraints(modifier.fillMaxWidth()) {
        // 菜单宽度固定，靠「行宽 - 菜单宽」的偏移右对齐到行尾
        val menuWidth = 196.dp
        val offsetX: Dp = (maxWidth - menuWidth).coerceAtLeast(0.dp)
        val scrollState = rememberScrollState()

        SettingsRow(
            title = title,
            description = description,
            enabled = enabled,
            onClick = { if (enabled) expanded = true },
            showDivider = showDivider,
            trailing = {
                Text(
                    text = currentLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (expanded) scheme.primary else app.text3,
                    maxLines = 1,
                )
                // 锁定行（如 SMB 的协议）不画箭头：画了会让人以为还能点
                if (enabled) {
                    Spacer(Modifier.width(Spacing.xs.dp))
                    ChevronDownIcon(tint = app.text3)
                }
            },
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .width(menuWidth)
                // 限制高度：档位多（倍速 8 档、库展示 4 档）时不至于铺满半屏，
                // 超出部分在菜单内滚动
                .heightIn(max = 312.dp),
            offset = DpOffset(x = offsetX, y = 0.dp),
            scrollState = scrollState,
            // 12dp 圆角（禁止胶囊）
            shape = AppShape.card,
            // 浮层比卡片高一档，才能看出它是「浮」在页面之上的
            containerColor = scheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(Dimens.hairline, app.border),
        ) {
            options.forEach { option ->
                val isSelected = option.value == selected
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) scheme.primary else scheme.onSurface,
                        )
                    },
                    onClick = {
                        expanded = false
                        if (!isSelected) onSelect(option.value)
                    },
                    trailingIcon = if (isSelected) {
                        {
                            CheckIcon(
                                tint = scheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else {
                        null
                    },
                    // 行高压到 44dp：8 档倍速也能在一屏里少占 60dp
                    modifier = Modifier.heightIn(min = 44.dp),
                    contentPadding = PaddingValues(horizontal = Dimens.pagePadding),
                )
            }
        }
    }
}

/**
 * 输入行尾部槽位宽度：= 下拉行里「[Spacing.xs] 间隔 + 16dp 箭头」。
 *
 * 这是**右对齐的基准线**：实测（1080px/3x）行内容右缘 996，下拉的 `Emby` 收在 936，
 * 让出 20dp 后输入值同样收在 936 —— 两者一字不差地齐平。
 */
private val RowTrailingSlot = 20.dp

/**
 * 形态 D：输入行 —— 左标题 + 右对齐输入值，**整行就是一条列表行**（56dp / 发丝分隔线）。
 *
 * 视觉（§3.4「完全扁平」）：
 * - 输入框**没有底色、没有边框、没有指示线**，和同一张卡片里的下拉行长得一样
 * - 值一律右对齐（`TextAlign.End`），占位符同样右对齐，右边缘与下拉当前值对齐
 * - 标题取自身宽度（不抢份额），余下宽度全部给输入区 —— 长地址也不会被截断
 * - 密码的显示/隐藏眼睛占用尾部槽位，和下拉箭头同位（紧凑贴边，不额外占宽）
 *
 * 焦点提示只靠青柠色光标（无底色可画，也不该再画 box —— 那正是要去掉的「指示线」）。
 *
 * @param trailingText 输入值右侧的固定后缀（如 `ms` / `px`），可空
 */
@Composable
fun SettingsInputRow(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    showDivider: Boolean = true,
    trailingText: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val app = LocalAppColors.current
    var passwordVisible by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.rowMinHeight)
                .padding(
                    start = Dimens.pagePadding,
                    end = Spacing.md.dp,
                    top = 8.dp,
                    bottom = 8.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) scheme.onSurface else app.text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // 只给上限、不给 weight：wrap-content 才不会把「未用满的份额」留在行尾
                // （weight(fill = false) 会让输入区只拿到一半宽度，真机实测 text 停在行中间）
                modifier = Modifier.widthIn(max = 132.dp),
            )
            // 标题与输入值之间固定 16dp，避免长地址看起来粘在标题上
            Spacer(Modifier.width(Spacing.md.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = if (enabled) scheme.onSurface else app.text3,
                    textAlign = TextAlign.End,
                ),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = imeAction,
                ),
                visualTransformation = if (isPassword && !passwordVisible) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        if (value.isEmpty() && enabled) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodySmall,
                                color = app.text3,
                                textAlign = TextAlign.End,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            if (trailingText != null) {
                Spacer(Modifier.width(Spacing.xs.dp))
                Text(
                    text = trailingText,
                    style = MaterialTheme.typography.labelSmall,
                    color = app.text3,
                )
            }
            // 密码行的值额外让出 8dp：否则密码点会贴着眼睛（真机距离只有 2dp）
            if (isPassword) Spacer(Modifier.width(Spacing.sm.dp))
            // 尾部槽位：密码放眼睛，其余留空 —— 和下拉箭头共用同一条右边线
            Box(
                modifier = Modifier
                    .width(RowTrailingSlot)
                    .height(40.dp)
                    .then(
                        if (isPassword) {
                            Modifier.clickable { passwordVisible = !passwordVisible }
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isPassword) {
                    // 16dp：正好占满槽位，眼睛和箭头的墨迹落在同一列
                    EyeIcon(
                        visible = passwordVisible,
                        tint = app.text3,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        if (showDivider) SettingsDivider()
    }
}

/** 形态 C：跳转行。右侧「当前值（可选）+ 右箭头」。 */
@Composable
fun SettingsNavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    value: String? = null,
    showDivider: Boolean = true,
) {
    val app = LocalAppColors.current
    SettingsRow(
        title = title,
        description = description,
        onClick = onClick,
        showDivider = showDivider,
        modifier = modifier,
        trailing = {
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = app.text3,
                    maxLines = 1,
                )
                Spacer(Modifier.width(Spacing.xs.dp))
            }
            ChevronRightIcon(tint = app.text3)
        },
    )
}

/**
 * 连续值行：滑杆（字幕字号 / 描边这类不枚举的参数）。
 *
 * 这类值是连续量，枚举成下拉项会丢精度，所以保留滑杆；它仍然是「组内一行」，
 * 不会破坏分组列表的版式。
 */
@Composable
fun SettingsSliderRow(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    valueText: String,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
) {
    val app = LocalAppColors.current
    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.pagePadding, vertical = Spacing.sm.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelMedium,
                    color = app.text3,
                )
            }
            // 统一用 AppSlider：圆形 Thumb，避免 M3 1.4 默认的竖条手柄
            AppSlider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
            )
        }
        if (showDivider) SettingsDivider()
    }
}
