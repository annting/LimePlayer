package app.myplayer.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.designsystem.MyPlayerTheme
import app.myplayer.core.designsystem.Spacing
import app.myplayer.core.designsystem.components.SettingsGroup
import app.myplayer.core.designsystem.components.SettingsMenuRow
import app.myplayer.core.designsystem.components.SettingsOption
import app.myplayer.core.designsystem.components.SettingsSwitchRow

// ---- 筛选状态 ----

/** 排序维度。 */
enum class DetailSortField { DATE_ADDED, TITLE, DURATION, RATING }

/** 排序方向。 */
enum class DetailSortDirection { ASC, DESC }

/** 观看状态。 */
enum class DetailWatchStatus { ALL, UNWATCHED, WATCHING, WATCHED }

/** 媒体类型。 */
enum class DetailMediaKind { ALL, MOVIE, SERIES }

/** 分辨率下限。 */
enum class DetailMinResolution { ANY, P720, P1080, P4K }

/** 年份范围。 */
enum class DetailYearRange { ANY, Y2020, Y2010, Y2000 }

/**
 * 详情页「排序与筛选」面板的状态（[FilterBottomSheet] 的唯一数据契约）。
 *
 * 各字段默认值就是「重置」的落点 —— 重置 = 换成全新 [FilterState]，**就地清空**。
 * [resultCount] 是「查看 X 个结果」里的 X：面板自身不查数据，页面在上一轮应用后
 * 可回填预估数；`null` 时按钮退化为「查看结果」。
 */
data class FilterState(
    val sortField: DetailSortField = DetailSortField.DATE_ADDED,
    val sortDirection: DetailSortDirection = DetailSortDirection.DESC,
    val watchStatus: DetailWatchStatus = DetailWatchStatus.ALL,
    val mediaKind: DetailMediaKind = DetailMediaKind.ALL,
    val minResolution: DetailMinResolution = DetailMinResolution.ANY,
    val yearRange: DetailYearRange = DetailYearRange.ANY,
    /** 仅显示收藏（Emby `isFavorite`；SMB 无收藏概念，该条件不生效）。 */
    val favoritesOnly: Boolean = false,
    /** 应用后预估的结果数（页面回填；null = 未知）。 */
    val resultCount: Int? = null,
) {
    companion object {
        /**
         * `rememberSaveable` 的 Saver：枚举按**名字**存（ordinal 在枚举演化时会漂移）。
         * [resultCount] 用 -1 表达 null（保存列表里不存 null）。
         */
        val Saver: Saver<FilterState, Any> = listSaver(
            save = { listOf(
                it.sortField.name,
                it.sortDirection.name,
                it.watchStatus.name,
                it.mediaKind.name,
                it.minResolution.name,
                it.yearRange.name,
                it.favoritesOnly,
                it.resultCount ?: -1,
            ) },
            restore = { list ->
                FilterState(
                    sortField = DetailSortField.valueOf(list[0] as String),
                    sortDirection = DetailSortDirection.valueOf(list[1] as String),
                    watchStatus = DetailWatchStatus.valueOf(list[2] as String),
                    mediaKind = DetailMediaKind.valueOf(list[3] as String),
                    minResolution = DetailMinResolution.valueOf(list[4] as String),
                    yearRange = DetailYearRange.valueOf(list[5] as String),
                    favoritesOnly = list[6] as Boolean,
                    resultCount = (list[7] as Int).takeIf { it >= 0 },
                )
            },
        )
    }
}

// ---- 下拉选项 ----

private val SortFieldOptions = listOf(
    SettingsOption(DetailSortField.DATE_ADDED, "添加时间"),
    SettingsOption(DetailSortField.TITLE, "标题"),
    SettingsOption(DetailSortField.DURATION, "时长"),
    SettingsOption(DetailSortField.RATING, "评分"),
)

private val SortDirectionOptions = listOf(
    SettingsOption(DetailSortDirection.ASC, "升序"),
    SettingsOption(DetailSortDirection.DESC, "降序"),
)

private val WatchStatusOptions = listOf(
    SettingsOption(DetailWatchStatus.ALL, "全部"),
    SettingsOption(DetailWatchStatus.UNWATCHED, "未看"),
    SettingsOption(DetailWatchStatus.WATCHING, "在看"),
    SettingsOption(DetailWatchStatus.WATCHED, "已看"),
)

private val MediaKindOptions = listOf(
    SettingsOption(DetailMediaKind.ALL, "全部"),
    SettingsOption(DetailMediaKind.MOVIE, "电影"),
    SettingsOption(DetailMediaKind.SERIES, "剧集"),
)

private val ResolutionOptions = listOf(
    SettingsOption(DetailMinResolution.ANY, "不限"),
    SettingsOption(DetailMinResolution.P720, "720p"),
    SettingsOption(DetailMinResolution.P1080, "1080p"),
    SettingsOption(DetailMinResolution.P4K, "4K"),
)

private val YearRangeOptions = listOf(
    SettingsOption(DetailYearRange.ANY, "不限"),
    SettingsOption(DetailYearRange.Y2020, "2020+"),
    SettingsOption(DetailYearRange.Y2010, "2010+"),
    SettingsOption(DetailYearRange.Y2000, "2000+"),
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
/**
 * 详情页的「排序与筛选」底部弹层（右上角 FilterList 图标触发）。
 *
 * 结构：顶栏（标题 + 重置）→ 排序规则分组 → 筛选条件分组 → **固定在底部**的主按钮。
 * 所有枚举选项收进 [SettingsMenuRow] 的 DropdownMenu（**禁止平铺 Chip**），
 * 开关走 [SettingsSwitchRow] —— 与设置页同一套组件语言。
 *
 * 状态策略：[FilterState] 用 `rememberSaveable` + [FilterState.Saver] 挂在**外层组合**
 * （不在 `if (showSheet)` 里面）—— 弹层关掉再打开，上次的选择还在；「重置」才清空。
 * 点「查看结果」→ [onApplyFilter] 把整份状态交回页面并关闭。
 *
 * @param showSheet 是否显示（由页面持有）
 * @param onDismiss 点遮罩 / 下滑关闭
 * @param onApplyFilter 应用筛选（页面收到后负责查询与关闭）
 */
@Composable
fun FilterBottomSheet(
    showSheet: Boolean,
    onDismiss: () -> Unit,
    onApplyFilter: (FilterState) -> Unit,
) {
    // 状态挂在 if 外：ModalBottomSheet 隐藏时内容离开组合，rememberSaveable 若在
    // sheet 内容里会随组合销毁 —— 放外层才能「关闭再开还在」
    var state by rememberSaveable(stateSaver = FilterState.Saver) { mutableStateOf(FilterState()) }

    if (!showSheet) return

    val app = LocalAppColors.current
    val scheme = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        containerColor = scheme.surface,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // 限高：正常内容一屏放下；小屏 / 大字体时列表内部滚动
                .heightIn(max = 620.dp),
        ) {
            // ── 顶栏：标题 + 重置（固定，不随列表滚动） ──
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md.dp, vertical = Spacing.xs.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "排序与筛选",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                // 重置 = 换成全新 FilterState（所有条件回默认）
                Text(
                    text = "重置",
                    style = MaterialTheme.typography.labelLarge,
                    color = app.text3,
                    modifier = Modifier
                        .clickable { state = FilterState() }
                        .padding(horizontal = Spacing.sm.dp, vertical = Spacing.xs.dp),
                )
            }

            // ── 可滚动主体（内容不足时不撑满） ──
            LazyColumn(
                Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(start = Spacing.md.dp, end = Spacing.md.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg.dp),
            ) {
                item {
                    SettingsGroup(title = "排序规则") {
                        SettingsMenuRow(
                            title = "排序维度",
                            options = SortFieldOptions,
                            selected = state.sortField,
                            onSelect = { state = state.copy(sortField = it) },
                        )
                        SettingsMenuRow(
                            title = "排序方向",
                            options = SortDirectionOptions,
                            selected = state.sortDirection,
                            onSelect = { state = state.copy(sortDirection = it) },
                            showDivider = false,
                        )
                    }
                }
                item {
                    SettingsGroup(title = "筛选条件") {
                        SettingsMenuRow(
                            title = "观看状态",
                            options = WatchStatusOptions,
                            selected = state.watchStatus,
                            onSelect = { state = state.copy(watchStatus = it) },
                        )
                        SettingsMenuRow(
                            title = "媒体类型",
                            options = MediaKindOptions,
                            selected = state.mediaKind,
                            onSelect = { state = state.copy(mediaKind = it) },
                        )
                        SettingsMenuRow(
                            title = "分辨率下限",
                            options = ResolutionOptions,
                            selected = state.minResolution,
                            onSelect = { state = state.copy(minResolution = it) },
                        )
                        SettingsMenuRow(
                            title = "年份",
                            options = YearRangeOptions,
                            selected = state.yearRange,
                            onSelect = { state = state.copy(yearRange = it) },
                        )
                        SettingsSwitchRow(
                            title = "仅显示收藏",
                            checked = state.favoritesOnly,
                            onCheckedChange = { state = state.copy(favoritesOnly = it) },
                            showDivider = false,
                        )
                    }
                }
            }

            // ── 底部固定主按钮 ──
            Button(
                onClick = {
                    onApplyFilter(state)
                    onDismiss()
                },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = scheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md.dp, vertical = Spacing.md.dp)
                    .heightIn(min = 48.dp),
            ) {
                Text(
                    // 面板自身不查数据：页面回填 resultCount 后显示「查看 X 个结果」，
                    // 否则退化为「查看结果」
                    text = state.resultCount?.let { "查看 $it 个结果" } ?: "查看结果",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

// ---- @Preview ----

@Preview(name = "筛选面板 · 暗色", showBackground = true, backgroundColor = 0xFF0B0D11, heightDp = 820)
@Composable
private fun FilterBottomSheetPreview() {
    MyPlayerTheme(darkTheme = true) {
        FilterBottomSheet(
            showSheet = true,
            onDismiss = {},
            onApplyFilter = {},
        )
    }
}

@Preview(name = "筛选面板 · 亮色", showBackground = true, backgroundColor = 0xFFF4F6F9, heightDp = 820)
@Composable
private fun FilterBottomSheetLightPreview() {
    MyPlayerTheme(darkTheme = false) {
        FilterBottomSheet(
            showSheet = true,
            onDismiss = {},
            onApplyFilter = {},
        )
    }
}
