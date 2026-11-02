package app.myplayer.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.myplayer.core.designsystem.LocalBottomNavSpace
import app.myplayer.core.designsystem.components.EmptyState
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.mediaui.PosterCard
import app.myplayer.core.mediaui.MediaGrid

/**
 * 本机文件夹内的视频网格（`docs/12-本机视频播放方案.md` 任务 3）。
 *
 * **与「媒体库」完全同构**：复用同一个 [PosterCard]（2:3 圆角、底部 scrim、按压缩放、
 * 两级入口）与同一套 [SortEngine] / [SortFilterSheet]。
 * 放在 `feature:library` 而不是 `:app`，就是为了直接复用 `internal` 的 [PosterCard]，
 * 避免复制一份卡片样式导致两处视觉漂移。
 *
 * 本机场景的兼容处理：
 * - 所有条目都是单个视频文件 → **隐藏「类型」筛选**（电影/剧集/集数无意义）；
 * - 本地文件没有收藏数据 → **隐藏「仅收藏」**；
 * - 其余维度（观看状态、年份、分辨率、排序）与媒体库一致。
 *
 * 预览图由调用方通过 [loadImage] 注入（`:app` 传 `LocalThumbnailProvider` 抽帧）；
 * 生成中是卡片占位块，失败同样是占位块，不会出现空白 / 破图。
 *
 * **排序 / 筛选入口在页面顶栏**（与媒体库详情页同款：右上角 [FilterListIcon]），
 * 网格里**不再**画老版的 `SortFilterBar` 横条（2026-11-01 反馈：那是旧版视觉）。
 * 因此排序/筛选状态由调用方持有，本组件只负责渲染结果与弹层。
 */
@Composable
fun LocalFolderGrid(
    items: List<MediaItemDto>,
    sort: SortSpec,
    filter: FilterSpec,
    sheetVisible: Boolean,
    onDismissSheet: () -> Unit,
    onSort: (SortSpec) -> Unit,
    onFilter: (FilterSpec) -> Unit,
    onReset: () -> Unit,
    loadImage: suspend (MediaItemDto) -> ImageSource,
    onPlay: (MediaItemDto) -> Unit,
    onOpenDetail: (MediaItemDto) -> Unit,
    modifier: Modifier = Modifier,
    /** 切文件夹时列表还没回来：**必须先清空旧列表再显示转圈**，否则会先闪一下上一个文件夹的内容。 */
    loading: Boolean = false,
) {
    // 本地列表上做过滤 + 排序，纯内存、瞬时完成（与媒体库同一条 SortEngine）
    val shown = remember(items, sort, filter) { SortEngine.apply(items, sort, filter) }

    Column(modifier.fillMaxSize()) {
        if (sheetVisible) {
            SortFilterSheet(
                sort = sort,
                filter = filter,
                onDismiss = onDismissSheet,
                onSort = onSort,
                onFilter = onFilter,
                onReset = onReset,
                showKindSection = false,
                showFavorites = false,
            )
        }

        when {
            // 加载中优先于"空"：否则清空列表后那一瞬间会闪一下「这个文件夹里没有视频」
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }

            items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "这个文件夹里没有视频",
                    description = "换一个文件夹，或重新授权其它目录。",
                )
            }

            shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "没有符合条件的视频",
                    description = "当前筛选条件下没有结果（共 ${items.size} 个），试试放宽筛选。",
                )
            }

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = MediaGrid.minColumnWidth),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 0.dp,
                    bottom = LocalBottomNavSpace.current,
                ),
                horizontalArrangement = Arrangement.spacedBy(MediaGrid.spacing),
                verticalArrangement = Arrangement.spacedBy(MediaGrid.spacing),
            ) {
                items(shown, key = { it.id }) { item ->
                    PosterCard(
                        item = item,
                        loadImage = loadImage,
                        onPlay = onPlay,
                        onOpenDetail = onOpenDetail,
                    )
                }
            }
        }
    }
}
