package app.myplayer.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.designsystem.AppShape
import app.myplayer.core.designsystem.components.AppChip
import app.myplayer.core.designsystem.components.SectionLabel

/**
 * 排序 / 筛选合并弹层（`02` §6.3 / 任务 3-4）：一个入口，两段内容 ——
 * 排序（9 个维度 + 升降序）与筛选（类型 / 观看状态 / 收藏 / 年份 / 分辨率）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SortFilterSheet(
    sort: SortSpec,
    filter: FilterSpec,
    onDismiss: () -> Unit,
    onSort: (SortSpec) -> Unit,
    onFilter: (FilterSpec) -> Unit,
    onReset: () -> Unit,
    /**
     * 本机视频场景：所有条目都是单个视频文件，没有「电影 / 剧集 / 集数」之分，
     * 也没有收藏数据 → 隐藏这两个维度（`docs/12` §3 兼容处理），其余维度与媒体库一致。
     */
    showKindSection: Boolean = true,
    showFavorites: Boolean = true,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scheme = MaterialTheme.colorScheme
    val app = LocalAppColors.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = scheme.surface,
        contentColor = scheme.onSurface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("排序与筛选", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    "重置",
                    color = scheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clip(AppShape.control)
                        .clickable(onClick = onReset)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            SectionLabel("排序")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortKey.entries.forEach { key ->
                    AppChip(
                        text = key.label,
                        selected = sort.key == key,
                        onClick = { onSort(sort.copy(key = key)) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppChip(
                    text = "升序 ↑",
                    selected = sort.ascending,
                    onClick = { onSort(sort.copy(ascending = true)) },
                )
                Spacer(Modifier.width(8.dp))
                AppChip(
                    text = "降序 ↓",
                    selected = !sort.ascending,
                    onClick = { onSort(sort.copy(ascending = false)) },
                )
            }

            if (showKindSection) {
                SectionLabel("类型")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ItemKind.entries.forEach { kind ->
                        AppChip(
                            text = kind.label,
                            selected = kind in filter.kinds,
                            onClick = {
                                val next = if (kind in filter.kinds) filter.kinds - kind else filter.kinds + kind
                                // 全不选等于不限（避免出现空列表的死状态）
                                onFilter(filter.copy(kinds = next.ifEmpty { ItemKind.entries.toSet() }))
                            },
                        )
                    }
                }
            }

            SectionLabel("观看状态")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WatchState.entries.forEach { ws ->
                    AppChip(
                        text = ws.label,
                        selected = filter.watchState == ws,
                        onClick = { onFilter(filter.copy(watchState = ws)) },
                    )
                }
                if (showFavorites) {
                    AppChip(
                        text = "仅收藏",
                        selected = filter.favoritesOnly,
                        onClick = { onFilter(filter.copy(favoritesOnly = !filter.favoritesOnly)) },
                    )
                }
            }

            SectionLabel("分辨率下限")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null to "不限", 720 to "720p", 1080 to "1080p", 2160 to "4K").forEach { (h, label) ->
                    AppChip(
                        text = label,
                        selected = filter.minHeight == h,
                        onClick = { onFilter(filter.copy(minHeight = h)) },
                    )
                }
            }

            SectionLabel("年份")
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf(null to "不限", 2020 to "2020+", 2010 to "2010+", 2000 to "2000+").forEach { (y, label) ->
                    AppChip(
                        text = label,
                        selected = filter.yearFrom == y && filter.yearTo == null,
                        onClick = { onFilter(filter.copy(yearFrom = y, yearTo = null)) },
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "生效筛选 ${filter.activeCount} 项" + if (filter.isDefault) "（未筛选）" else "",
                style = MaterialTheme.typography.labelSmall,
                color = app.text3,
            )
        }
    }
}


/** 排序 + 筛选入口按钮（带徽标）。 */
@Composable
fun SortFilterBar(
    sort: SortSpec,
    filter: FilterSpec,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .clip(AppShape.control)
                .background(scheme.surfaceVariant.copy(alpha = 0.6f))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "排序：${sort.key.label} ${if (sort.ascending) "↑" else "↓"}",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurface,
            )
        }
        Row(
            Modifier
                .clip(AppShape.control)
                .background(
                    if (filter.isDefault) scheme.surfaceVariant.copy(alpha = 0.6f)
                    else scheme.primary.copy(alpha = 0.18f)
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "筛选" + if (filter.isDefault) "" else " · ${filter.activeCount}",
                style = MaterialTheme.typography.labelMedium,
                color = if (filter.isDefault) scheme.onSurface else scheme.primary,
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

/**
 * 排序与筛选图标已统一到设计系统 [app.myplayer.core.designsystem.components.FilterListIcon]
 * （Material `FilterList`，顶栏统一 20dp）。这里不再自绘：自绘版本必须写死 24dp，
 * 和 20dp 的返回/更多并排时大小不一致（2026-11-01 用户反馈）。
 */
