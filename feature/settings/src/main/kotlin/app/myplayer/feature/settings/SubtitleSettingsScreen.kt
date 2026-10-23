package app.myplayer.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myplayer.core.datastore.SubtitleStyle
import app.myplayer.core.designsystem.AppShape
import app.myplayer.core.designsystem.Dimens
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.designsystem.LocalBottomNavSpace
import app.myplayer.core.designsystem.MyPlayerTheme
import app.myplayer.core.designsystem.PlayerTokens
import app.myplayer.core.designsystem.Spacing
import app.myplayer.core.designsystem.components.AppSlider
import app.myplayer.core.designsystem.components.CheckIcon
import app.myplayer.core.designsystem.components.ChevronDownIcon
import app.myplayer.core.designsystem.components.PageHeader
import app.myplayer.core.designsystem.components.SettingsDivider
import app.myplayer.core.designsystem.components.SettingsGroup
import app.myplayer.core.designsystem.components.SettingsRow
import kotlin.math.roundToInt

/**
 * 字幕设置二级页（`docs/03-UI设计规范.md` §7.1）。
 *
 * 结构：返回栏 → **实时预览卡** → 文字样式（字号 / 描边 / 颜色）→ 位置。
 *
 * 状态策略：
 * - 四个值先落在 `rememberSaveable` 的**草稿态**里，拖动时预览立即跟着变（零延迟）；
 * - 只在滑杆**松手**或**选完颜色**时才通过 [onStyleChange] 写回 `SettingsStore`。
 *   设置是整份 JSON 落盘，若每帧写入会带来几十次磁盘 IO 和状态回流。
 * - 屏幕重建（旋转 / 进程恢复）由 `rememberSaveable` 保住草稿，不会跳回旧值。
 */
@Composable
fun SubtitleSettingsScreen(
    style: SubtitleStyle,
    onStyleChange: ((SubtitleStyle) -> SubtitleStyle) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 草稿态：初始值取自已持久化的设置。
    var fontSize by rememberSaveable { mutableStateOf(style.fontSizeSp) }
    var outlineWidth by rememberSaveable { mutableStateOf(style.outlineWidthDp) }
    var colorArgb by rememberSaveable { mutableStateOf(style.textColorArgb) }
    var verticalPosition by rememberSaveable { mutableStateOf(style.verticalPosition) }

    /** 把四个草稿值一次性写回 Store（滑杆松手 / 颜色选定）。 */
    fun commit() {
        onStyleChange {
            it.copy(
                fontSizeSp = fontSize,
                outlineWidthDp = outlineWidth,
                textColorArgb = colorArgb,
                verticalPosition = verticalPosition,
            )
        }
    }

    Column(modifier.fillMaxSize()) {
        PageHeader(
            title = "字幕设置",
            subtitle = "字体、颜色与位置",
            onBack = onBack,
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.pagePadding)
                // 底部留白 = 呼吸间距 + 悬浮导航高度：MainScaffold 不再裁内容区，
                // 这层 padding 在 verticalScroll 之后 → 滚到底时末尾才留白，不会被胶囊压住
                .padding(
                    top = Spacing.sm.dp,
                    bottom = LocalBottomNavSpace.current,
                ),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl.dp),
        ) {
            SubtitlePreviewCard(
                fontSize = fontSize,
                outlineWidth = outlineWidth,
                textColor = Color(colorArgb),
                outlineColor = Color(style.outlineColorArgb),
                verticalPosition = verticalPosition,
            )

            SettingsGroup(title = "文字样式") {
                SubtitleSliderRow(
                    title = "字号",
                    valueText = "${fontSize.roundToInt()}sp",
                    value = fontSize,
                    valueRange = 12f..32f,
                    onValueChange = { fontSize = it },
                    onValueChangeFinished = { commit() },
                    startLabel = { EndpointLabel("A", fontSize = 12.sp) },
                    endLabel = { EndpointLabel("A", fontSize = 22.sp) },
                )
                SubtitleSliderRow(
                    title = "描边粗细",
                    valueText = "${outlineWidth.roundToInt()}dp",
                    value = outlineWidth,
                    valueRange = 0f..5f,
                    onValueChange = { outlineWidth = it },
                    onValueChangeFinished = { commit() },
                    startLabel = { EndpointLabel("细") },
                    endLabel = { EndpointLabel("粗") },
                )
                ColorMenuRow(
                    selectedArgb = colorArgb,
                    onSelect = {
                        colorArgb = it
                        commit()
                    },
                    showDivider = false,
                )
            }

            SettingsGroup(title = "位置") {
                SubtitleSliderRow(
                    title = "垂直位置",
                    valueText = "${(verticalPosition * 100f).roundToInt()}%",
                    value = verticalPosition,
                    valueRange = 0.1f..0.9f,
                    onValueChange = { verticalPosition = it },
                    onValueChangeFinished = { commit() },
                    startLabel = { EndpointLabel("上") },
                    endLabel = { EndpointLabel("下") },
                    showDivider = false,
                )
            }
        }
    }
}

// ---- 实时预览 ----

private const val PREVIEW_TEXT = "这是一段字幕预览效果"

/**
 * 预览卡：16:9 的「视频画面」，字幕按 [verticalPosition] 落在画面中。
 *
 * 描边用「两层 Text」实现：底层画 `Stroke`（黑色），上层画填充色。
 * Compose 的 `drawStyle = Stroke` 只描边不填充，单层会变成空心字。
 */
@Composable
private fun SubtitlePreviewCard(
    fontSize: Float,
    outlineWidth: Float,
    textColor: Color,
    outlineColor: Color,
    verticalPosition: Float,
) {
    val app = LocalAppColors.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val outlinePx = with(density) { outlineWidth.dp.toPx() }
    val safePosition = verticalPosition.coerceIn(0.1f, 0.9f)

    Column(Modifier.fillMaxWidth()) {
        // 标题放在卡片外：字幕落在 0.1（靠上）时不会和「实时预览」字样叠在一起
        Text(
            text = "实时预览",
            style = MaterialTheme.typography.labelMedium,
            color = app.text3,
            modifier = Modifier.padding(start = Spacing.xs.dp, bottom = Spacing.sm.dp),
        )

        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(AppShape.card)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(PlayerTokens.panel, PlayerTokens.stage),
                    )
                )
                .border(Dimens.hairline, app.border, AppShape.card),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                val baseStyle = TextStyle(
                    fontSize = fontSize.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = (fontSize * 1.3f).sp,
                    textAlign = TextAlign.Center,
                )

                // 字幕块在可用高度内按 verticalPosition 定位（中心点 = 帧高 × 百分比），
                // 测到文字高度后夹在上下边界内，0.9 会贴到画面底部（留 12dp 呼吸），
                // 0.1 会贴到顶部，而不是 BiasAlignment 那种「按剩余空间百分比」的偏差。
                Box(
                    Modifier
                        .fillMaxWidth()
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(
                                constraints.copy(minWidth = 0, minHeight = 0),
                            )
                            val frameHeight = constraints.maxHeight
                            val maxTop = (frameHeight - placeable.height).coerceAtLeast(0).toFloat()
                            val top = (frameHeight * safePosition - placeable.height / 2f)
                                .coerceIn(minimumValue = 0f, maximumValue = maxTop)
                            layout(constraints.maxWidth, frameHeight) {
                                placeable.place(0, top.roundToInt())
                            }
                        },
                ) {
                    // 描边层：宽度取 2 倍，视觉外扩正好是 outlineWidth（一半被填充层盖住）
                    if (outlinePx > 0f) {
                        Text(
                            text = PREVIEW_TEXT,
                            style = baseStyle.copy(
                                color = outlineColor,
                                drawStyle = Stroke(
                                    width = outlinePx * 2f,
                                    join = StrokeJoin.Round,
                                ),
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        text = PREVIEW_TEXT,
                        style = baseStyle.copy(color = textColor),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

// ---- 滑杆行 ----

/**
 * 字幕页的滑杆行：标题 + 当前值，下面一行是「左端点提示 + 圆形滑块 + 右端点提示」。
 *
 * 端点提示用 12sp / 22sp 的 “A” 表达字号大小，用「细 / 粗」「上 / 下」表达量级方向；
 * 它们只是视觉刻度，不参与语义（TalkBack 读滑杆自身的百分比）。
 */
@Composable
private fun SubtitleSliderRow(
    title: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
    startLabel: @Composable () -> Unit,
    endLabel: @Composable () -> Unit,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) { startLabel() }
                AppSlider(
                    value = value,
                    onValueChange = onValueChange,
                    onValueChangeFinished = onValueChangeFinished,
                    valueRange = valueRange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = Spacing.sm.dp),
                )
                Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) { endLabel() }
            }
        }
        if (showDivider) SettingsDivider()
    }
}

@Composable
private fun EndpointLabel(text: String, fontSize: androidx.compose.ui.unit.TextUnit = 11.sp) {
    Text(
        text = text,
        fontSize = fontSize,
        fontWeight = FontWeight.SemiBold,
        color = LocalAppColors.current.text3,
        maxLines = 1,
    )
}

// ---- 颜色下拉 ----

/** 常用字幕色（ARGB + 名称）。 */
private data class SubtitleColorOption(val argb: Int, val label: String)

private val SubtitleColorOptions = listOf(
    SubtitleColorOption(0xFFFFFFFF.toInt(), "白色"),
    SubtitleColorOption(0xFFFDD835.toInt(), "黄色"),
    SubtitleColorOption(0xFF4DD0E1.toInt(), "青色"),
    SubtitleColorOption(0xFFAED581.toInt(), "浅绿"),
    SubtitleColorOption(0xFFFFB74D.toInt(), "橙色"),
)

@Composable
private fun ColorMenuRow(
    selectedArgb: Int,
    onSelect: (Int) -> Unit,
    showDivider: Boolean,
) {
    val app = LocalAppColors.current
    val scheme = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = SubtitleColorOptions.firstOrNull { it.argb == selectedArgb }?.label ?: "自定义"

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 菜单宽度固定，靠「行宽 - 菜单宽」的偏移右对齐到行尾（与设置主页一致）
        val menuWidth = 200.dp
        val offsetX = (maxWidth - menuWidth).coerceAtLeast(0.dp)

        SettingsRow(
            title = "文字颜色",
            onClick = { expanded = true },
            showDivider = showDivider,
            trailing = {
                ColorDot(selectedArgb)
                Spacer(Modifier.width(Spacing.sm.dp))
                Text(
                    text = selectedLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = app.text3,
                    maxLines = 1,
                )
                Spacer(Modifier.width(Spacing.xs.dp))
                ChevronDownIcon(tint = app.text3)
            },
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(menuWidth),
            offset = DpOffset(x = offsetX, y = 0.dp),
            shape = AppShape.card,
            containerColor = scheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(Dimens.hairline, app.border),
        ) {
            SubtitleColorOptions.forEach { option ->
                val isSelected = option.argb == selectedArgb
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ColorDot(option.argb)
                            Spacer(Modifier.width(Spacing.md.dp))
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) scheme.primary else scheme.onSurface,
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        if (!isSelected) onSelect(option.argb)
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
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = Dimens.pagePadding),
                )
            }
        }
    }
}

@Composable
private fun ColorDot(argb: Int) {
    Box(
        Modifier
            .size(16.dp)
            .clip(AppShape.circle)
            .background(Color(argb))
            .border(Dimens.hairline, LocalAppColors.current.borderStrong, AppShape.circle),
    )
}

// ---- @Preview ----

@Preview(
    name = "字幕设置 · 暗色",
    showBackground = true,
    backgroundColor = 0xFF0B0D11,
    heightDp = 900,
)
@Composable
private fun SubtitleSettingsDarkPreview() {
    MyPlayerTheme(darkTheme = true) {
        SubtitleSettingsScreen(
            style = SubtitleStyle(),
            onStyleChange = {},
            onBack = {},
        )
    }
}

@Preview(
    name = "字幕设置 · 亮色",
    showBackground = true,
    backgroundColor = 0xFFF4F6F9,
    heightDp = 900,
)
@Composable
private fun SubtitleSettingsLightPreview() {
    MyPlayerTheme(darkTheme = false) {
        SubtitleSettingsScreen(
            style = SubtitleStyle(),
            onStyleChange = {},
            onBack = {},
        )
    }
}
