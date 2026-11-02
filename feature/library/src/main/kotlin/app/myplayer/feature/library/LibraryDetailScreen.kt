package app.myplayer.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.myplayer.core.designsystem.Dimens
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.designsystem.MyPlayerTheme
import app.myplayer.core.designsystem.PlayerTokens
import app.myplayer.core.designsystem.Spacing
import app.myplayer.core.designsystem.components.CheckIcon
import app.myplayer.core.designsystem.components.LockIcon
import app.myplayer.core.designsystem.components.BackIcon
import app.myplayer.core.designsystem.components.CircleIconButton
import app.myplayer.core.designsystem.components.FilterListIcon
import app.myplayer.core.designsystem.components.VideoIcon
import app.myplayer.core.model.ImageKind
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.ItemQuery
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.MediaSource
import app.myplayer.core.model.SourceType
import app.myplayer.core.model.resumeFraction
import app.myplayer.core.mediaui.PosterCard
import app.myplayer.feature.library.home.rememberHomeImage
import app.myplayer.feature.library.home.rememberImageRequest
import coil3.compose.AsyncImage
import app.myplayer.core.designsystem.AppShape
import app.myplayer.core.mediaui.MediaGrid
import app.myplayer.feature.library.home.HomeSourceType
import app.myplayer.feature.library.home.MockHomeData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---- 数据模型 ----

/**
 * SMB 模式下详情网格里的一项：**底层视频文件**。
 *
 * SMB 的第一层文件夹已经被首页映射成「媒体库」，点进来就是文件本身，
 * **不存在子文件夹嵌套** —— 所以这里没有 `isFolder` 字段，全部按视频渲染。
 * `source:smb` 实现后由扫描链路填充；当前由 [LibraryDetailMock] 提供预览数据。
 */
data class SmbVideoItem(
    /** 完整路径（播放器直接用它，等价于 Emby 条目的 id）。 */
    val path: String,
    /** 文件名（含扩展名，遮罩上显示）。 */
    val name: String,
    val sizeBytes: Long = 0L,
    /** 最后修改时间（epoch ms），对应扫描时的 `LastWriteTime`。 */
    val lastModified: Long = 0L,
    /** 本机播放记录里已看完（Room；Emby 项的已看状态在 `MediaItemDto.userData`）。 */
    val watched: Boolean = false,
    /**
     * 原始条目（`source:smb` 的 DTO）。
     *
     * 抽帧需要它：`LocalThumbnailProvider` 要拿 `container` / `sizeBytes` / `serverId`
     * 才能决定「值不值得试、试哪条播放地址」。Mock 预览数据为 null（不出图）。
     */
    val item: MediaItemDto? = null,
)

/**
 * 多选状态机（纯数据 + 纯函数，单测钉住）。
 *
 * 进入方式只有「长按某个卡片」——单点的普通点击（播放 / 详情）不经过这里；
 * 退出时清空选中集，下次进入从零开始。
 */
data class DetailSelection(
    val active: Boolean = false,
    val ids: Set<String> = emptySet(),
) {
    val count: Int get() = ids.size

    /** 长按进入多选，长按的那一项同时选中。已在多选中时幂等（重复长按不重置）。 */
    fun enter(firstId: String): DetailSelection =
        if (active) this else DetailSelection(active = true, ids = setOf(firstId))

    /** 多选中点卡片 = 切换它的选中态；最后一个被取消后**自动退出**多选（与相册类 App 一致）。 */
    fun toggle(id: String): DetailSelection {
        if (!active) return this
        val next = if (id in ids) ids - id else ids + id
        return if (next.isEmpty()) DetailSelection(active = false) else copy(ids = next)
    }

    /** 顶栏关闭 / 系统返回：退出并清空。 */
    fun exit(): DetailSelection = DetailSelection()

    /** 全选（再次点击 = 反选成空并退出，见 [selectAllOrClear] 的调用方）。 */
    fun selectAll(all: Collection<String>): DetailSelection = copy(ids = all.toSet())
}

/** 详情页 UI 状态。 */
data class LibraryDetailUiState(
    val loading: Boolean = true,
    val error: String? = null,
    /** Emby 模式：该库的全部条目（`ItemQuery.limit = 200` 上限内）。 */
    val embyItems: List<MediaItemDto> = emptyList(),
    /** SMB 模式：该文件夹下的视频文件。 */
    val smbVideos: List<SmbVideoItem> = emptyList(),
    val selection: DetailSelection = DetailSelection(),
    /** 当前生效的排序与筛选（由 [FilterBottomSheet] 应用）。 */
    val filter: FilterState = FilterState(),
) {
    val selectionCount: Int get() = selection.ids.size
}

/**
 * 媒体库详情页 VM：Emby 走 [MediaSource.getItems]；SMB 走 Mock（源未实现）。
 *
 * 「标记已看」目前是**乐观本地更新**（Emby 改 `userData.isPlayed`，SMB 改本地集合），
 * 服务端上报（Emby PlayedItems API）待 `source` 增加对应接口后接入。
 */
class LibraryDetailViewModel(
    private val source: MediaSource?,
    private val serverId: String,
    private val libraryId: String,
    private val smbMode: Boolean,
    /** 档二：文件库存（写 `isPrivate`）。null = 未接（预览 / 单测）。 */
    private val catalog: app.myplayer.core.model.FileCatalogStore? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryDetailUiState())
    val state: StateFlow<LibraryDetailUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /**
     * 重拉列表。
     *
     * [silent] = true 时**不切 loading**：从播放页返回要刷新卡片上的进度条，
     * 若切 loading 就会把已有列表换成转圈再弹回来（闪一下）。静默刷新直接用新数据顶替。
     */
    fun refresh(silent: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(loading = !silent, error = null) }
            try {
                if (smbMode) {
                    // 真实 SMB 列表：一个文件夹里的视频文件（`:source:smb` 的 getItems 只扫一层）
                    val src = source ?: error("数据源未就绪")
                    val page = src.getItems(ItemQuery(libraryId = libraryId, limit = ITEMS_LIMIT))
                    val videos = page.items.map { item ->
                        SmbVideoItem(
                            // 用**条目 id**（SMB 源内部是 URL 安全 Base64）而不是真实路径：
                            // 真实路径带 `/`，拼进导航 route 会让 App 崩溃
                            path = item.id,
                            name = item.title + item.container?.let { ext -> ".$ext" }.orEmpty(),
                            sizeBytes = item.sizeBytes ?: 0L,
                            lastModified = item.dateCreated ?: 0L,
                            item = item,
                        )
                    }
                    _state.update {
                        it.copy(loading = false, smbVideos = videos, embyItems = emptyList())
                    }
                } else {
                    val src = source ?: error("数据源未就绪")
                    val page = src.getItems(ItemQuery(libraryId = libraryId, limit = ITEMS_LIMIT))
                    _state.update { it.copy(loading = false, embyItems = page.items, smbVideos = emptyList()) }
                }
            } catch (t: Throwable) {
                // 静默刷新失败：眼前已经有列表，就别把它换成整页错误（下次进页面会重试）
                if (!silent) _state.update { it.copy(loading = false, error = t.message ?: "加载失败") }
            }
        }
    }

    /** 海报加载（suspend → [rememberHomeImage] 链路）。 */
    suspend fun image(item: MediaItemDto, kind: ImageKind): ImageSource =
        source?.image(item, kind, app.myplayer.core.model.ImageSize.MEDIUM) ?: ImageSource.None

    // ---- 多选 ----

    fun longPressSelect(id: String) = _state.update { it.copy(selection = it.selection.enter(id)) }

    fun toggleSelect(id: String) = _state.update { it.copy(selection = it.selection.toggle(id)) }

    fun exitSelection() = _state.update { it.copy(selection = it.selection.exit()) }

    /** 应用「排序与筛选」面板的结果（网格与「全选」都只看过滤后的列表）。 */
    fun applyFilter(f: FilterState) = _state.update { it.copy(filter = f) }

    fun selectAll() {
        val st = _state.value
        val all = if (st.smbVideos.isNotEmpty()) {
            applySmbFilter(st.smbVideos, st.filter).map { it.path }
        } else {
            applyDetailFilter(st.embyItems, st.filter).map { it.id }
        }
        _state.update { it.copy(selection = it.selection.selectAll(all)) }
    }

    /**
     * 档二：把选中的文件**加入 / 移出隐私**（`docs/隐私模式方案.md` §三）。
     *
     * 判定规则：选中项全都已是私有的 → 移出；否则全部加入。
     * 普通模式下私有文件根本列不出来，所以「移出」只会在隐私模式里发生。
     *
     * 写库是「后台事实」，不受当前模式限制；写完立刻重扫列表 ——
     * 普通模式下加入隐私的那几条会当场从网格消失（这正是预期）。
     */
    fun togglePrivate() {
        val store = catalog ?: return
        val st = _state.value
        val selected = st.selection.ids
        if (selected.isEmpty()) return
        // 多选 id 是 `SmbVideoItem.path`（= 条目 id，URL 安全 Base64）；库存按**真实路径**索引
        val paths = st.smbVideos.filter { it.path in selected }.mapNotNull { it.item?.path }
        if (paths.isEmpty()) {
            _state.update { it.copy(selection = it.selection.exit()) }
            return
        }
        viewModelScope.launch {
            val allPrivate = paths.all { store.entryOf(serverId, it)?.isPrivate == true }
            runCatching { store.setPrivate(serverId, paths, !allPrivate) }
            _state.update { it.copy(selection = it.selection.exit()) }
            refresh()
        }
    }

    /** 标记已看：乐观更新本地状态；服务端上报待接口补齐。 */
    fun markWatched() {
        val selected = _state.value.selection.ids
        if (selected.isEmpty()) return
        _state.update { st ->
            st.copy(
                embyItems = st.embyItems.map {
                    if (it.id in selected) {
                        it.copy(userData = it.userData.copy(isPlayed = true))
                    } else {
                        it
                    }
                },
                smbVideos = st.smbVideos.map { if (it.path in selected) it.copy(watched = true) else it },
                selection = st.selection.exit(),
            )
        }
    }

    private companion object {
        /** 一次拉取的上限：详情页是「该库全部条目」，200 覆盖绝大多数家庭库。 */
        const val ITEMS_LIMIT = 200
    }
}

/**
 * 媒体库详情页（首页「我的媒体库 / 我的文件夹」卡片与「查看全部」的落地页）。
 *
 * **两种源共用同一张 [PosterCard]**（2026-11-02 用户定稿：所有入口的 item 效果必须一致）：
 * - **Emby**（[HomeSourceType.EMBY]）：海报来自服务器，播放键走条目 id；
 * - **SMB**（[HomeSourceType.SMB]）：封面由本地抽帧兜底，播放键走文件路径 ——
 *   第一层文件夹在首页已被映射成库，这里**只可能是视频文件**。
 *
 * 多选：长按任意卡片进入；顶栏切换成「已选择 X 项 + 全选 / 标记已看」；
 * 系统返回优先退出多选而不是退出页面。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryDetailScreen(
    state: LibraryDetailUiState,
    sourceType: HomeSourceType,
    title: String,
    loadImage: suspend (MediaItemDto, ImageKind) -> ImageSource,
    onBack: () -> Unit,
    onOpenDetail: (MediaItemDto) -> Unit,
    /** 播放：Emby 传条目 id，SMB 传文件路径（播放页两种都能吃）。 */
    onPlay: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    onExitSelection: () -> Unit,
    /** 档二：多选态下的「加入 / 移出隐私」（仅 SMB 文件列表显示）。 */
    onTogglePrivate: () -> Unit = {},
    canMarkPrivate: Boolean = false,
    /** 隐私模式：右上角有全局「隐私」胶囊，顶栏右侧按钮需左移让位（BUG-5）。 */
    privacyActive: Boolean = false,
    onSelectAll: () -> Unit,
    onMarkWatched: () -> Unit,
    onApplyFilter: (FilterState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalAppColors.current
    val scheme = MaterialTheme.colorScheme
    val selection = state.selection

    // 排序与筛选面板（右上角 FilterList 触发）
    var showFilterSheet by rememberSaveable { mutableStateOf(false) }

    // 过滤 + 排序只作用于**显示**（原始列表保留 —— 全选 / 数量都按当前可见集合算）
    val displayEmby = applyDetailFilter(state.embyItems, state.filter)
    val displaySmb = applySmbFilter(state.smbVideos, state.filter)
    val displayCount = if (sourceType == HomeSourceType.SMB) displaySmb.size else displayEmby.size

    // 多选态下系统返回 = 退出多选；普通态放行给导航层
    BackHandler(enabled = selection.active) { onExitSelection() }

    Column(modifier.fillMaxSize().background(scheme.background)) {
        DetailTopBar(
            title = if (selection.active) "已选择 ${selection.count} 项" else title,
            // 数量统计：按**当前筛选后**的数量，比总数更有用（筛选生效时一眼看到结果变少）
            subtitle = when {
                selection.active -> null
                sourceType == HomeSourceType.SMB -> "共 $displayCount 个文件"
                else -> "共 $displayCount 个视频"
            },
            selectionMode = selection.active,
            allSelected = selection.active && selection.count == displayCount && displayCount > 0,
            anySelected = selection.count > 0,
            onBack = if (selection.active) onExitSelection else onBack,
            onSelectAll = onSelectAll,
            onMarkWatched = onMarkWatched,
            // 漏了这两个 = 档二的锁图标永远不出现（参数默认 false），设备上才发现
            onTogglePrivate = onTogglePrivate,
            canMarkPrivate = canMarkPrivate,
            privacyActive = privacyActive,
            onShowFilter = { showFilterSheet = true },
        )

        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = scheme.primary)
            }
            state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = app.text3,
                )
            }
            else -> LazyVerticalGrid(
                // 自适应列（UI 优化方案 L2）：原来 `Fixed(3)` 写死手机三列，
                // 换平板 / 折叠屏展开态仍是三列 → 每列被拉得很宽、海报发虚。
                // `Adaptive(92dp)` 实测：411dp 手机 = 3 列（与原来一致），
                // 600dp 平板 = 5 列、800dp 折叠展开 = 7 列，无需再按屏宽写分支。
                columns = GridCells.Adaptive(minSize = MediaGrid.minColumnWidth),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    // 左右 16dp = 首页标题基线（UI 优化方案 L3）：两页共用同一条左边线，
                    // 来回切 tab 时标题与海报边缘不会左右错开。
                    start = Dimens.pagePadding,
                    end = Dimens.pagePadding,
                    top = Spacing.sm.dp,
                    // 页面底部留白 100dp（用户 2026-10-24 定稿）。
                    // **不再叠加 `navigationBars`**：本页是根级页面，外层 `BottomInsetScreen`
                    // 已经 `.navigationBarsPadding()` 让过一次手势条，再叠一份就是双计，
                    // 滚到底会多出约 25dp 空白（UI 优化方案 L4）。
                    bottom = 100.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(MediaGrid.spacing),
                verticalArrangement = Arrangement.spacedBy(MediaGrid.spacing),
            ) {
                if (sourceType == HomeSourceType.SMB) {
                    items(items = displaySmb, key = { it.path }) { video ->
                        val selected = video.path in selection.ids
                        SmbVideoCard(
                            video = video,
                            selected = selected,
                            selectionMode = selection.active,
                            loadImage = loadImage,
                            // 与本机/Emby 同一套两级入口：中央播放按钮播放，卡片其余区域进详情
                            onPlay = { onPlay(video.path) },
                            onOpenDetail = { video.item?.let(onOpenDetail) },
                            onToggleSelect = { onToggleSelect(video.path) },
                            onLongClick = { onLongPress(video.path) },
                        )
                    }
                } else {
                    items(items = displayEmby, key = { it.id }) { item ->
                        PosterCard(
                            item = item,
                            // 在线源只有海报（POSTER），与「媒体库」tab 的网格同一条链路
                            loadImage = { it -> loadImage(it, ImageKind.POSTER) },
                            onPlay = { onPlay(it.id) },
                            onOpenDetail = onOpenDetail,
                            selectionMode = selection.active,
                            selected = item.id in selection.ids,
                            onToggleSelect = { onToggleSelect(item.id) },
                            onLongPress = { onLongPress(item.id) },
                        )
                    }
                }
            }
        }
    }

    // 「排序与筛选」底部弹层：状态挂在 Screen 层，应用后写回 VM 的 state.filter
    FilterBottomSheet(
        showSheet = showFilterSheet,
        onDismiss = { showFilterSheet = false },
        onApplyFilter = onApplyFilter,
    )
}

// ---- 排序与过滤（纯函数，[LibraryDetailViewModel] 与「全选」共用同一口径） ----

/**
 * 应用 [FilterState] 到 Emby 条目列表（过滤 + 排序）。
 *
 * 字段局限（都注明在对应分支）：
 * - `width/height`、`productionYear` 是**详情专属字段**，列表查询常为 null → 条件不匹配（条目被过滤掉）；
 *   若源后续在列表同步携带这些字段，过滤自动生效，无需改这里。
 * - 「电影 / 剧集」用 `seriesId/seasonNumber` 近似（模型没有条目类型字段），剧集库的 Series 本体
 *   可能被归进「电影」—— 数据层补 itemType 后再收敛。
 */
internal fun applyDetailFilter(items: List<MediaItemDto>, filter: FilterState): List<MediaItemDto> {
    val filtered = items.filter {
        matchesWatchStatus(it.userData.isPlayed, it.resumeFraction() > 0f, filter.watchStatus) &&
            when (filter.mediaKind) {
                DetailMediaKind.ALL -> true
                DetailMediaKind.MOVIE -> it.seriesId == null && it.seasonNumber == null
                DetailMediaKind.SERIES -> it.seriesId != null || it.seasonNumber != null
            } &&
            when (filter.minResolution) {
                DetailMinResolution.ANY -> true
                DetailMinResolution.P720 -> (it.height ?: 0) >= 720
                DetailMinResolution.P1080 -> (it.height ?: 0) >= 1080
                DetailMinResolution.P4K -> (it.height ?: 0) >= 2160
            } &&
            when (filter.yearRange) {
                DetailYearRange.ANY -> true
                DetailYearRange.Y2020 -> (it.productionYear ?: Int.MIN_VALUE) >= 2020
                DetailYearRange.Y2010 -> (it.productionYear ?: Int.MIN_VALUE) >= 2010
                DetailYearRange.Y2000 -> (it.productionYear ?: Int.MIN_VALUE) >= 2000
            } &&
            (!filter.favoritesOnly || it.userData.isFavorite)
    }
    return sortDesc(filtered.sortedWith(detailComparator(filter.sortField)), filter.sortDirection)
}

/** SMB 视频：只有排序（时间 / 名字）与观看状态适用；类型 / 分辨率 / 收藏 / 年份无字段可判，一律放行。 */
internal fun applySmbFilter(videos: List<SmbVideoItem>, filter: FilterState): List<SmbVideoItem> {
    val filtered = videos.filter {
        when (filter.watchStatus) {
            DetailWatchStatus.ALL -> true
            DetailWatchStatus.UNWATCHED -> !it.watched
            DetailWatchStatus.WATCHING -> false
            DetailWatchStatus.WATCHED -> it.watched
        }
    }
    // 时长 / 评分对 SMB 文件无意义：维度不适用时连方向也一起忽略，保持扫描顺序
    val comparator = detailSmbComparator(filter.sortField) ?: return filtered
    return sortDesc(filtered.sortedWith(comparator), filter.sortDirection)
}

private fun <T> sortDesc(list: List<T>, direction: DetailSortDirection): List<T> =
    if (direction == DetailSortDirection.DESC) list.asReversed() else list

private fun matchesWatchStatus(isPlayed: Boolean, hasProgress: Boolean, status: DetailWatchStatus): Boolean =
    when (status) {
        DetailWatchStatus.ALL -> true
        DetailWatchStatus.UNWATCHED -> !isPlayed && !hasProgress
        DetailWatchStatus.WATCHING -> !isPlayed && hasProgress
        DetailWatchStatus.WATCHED -> isPlayed
    }

private fun detailComparator(field: DetailSortField): java.util.Comparator<MediaItemDto> =
    when (field) {
        DetailSortField.DATE_ADDED -> compareBy { it.dateCreated ?: 0L }
        DetailSortField.TITLE -> compareBy { it.title }
        DetailSortField.DURATION -> compareBy { it.durationTicks.value }
        DetailSortField.RATING -> compareBy { it.communityRating ?: 0f }
    }

private fun detailSmbComparator(field: DetailSortField): java.util.Comparator<SmbVideoItem>? =
    when (field) {
        DetailSortField.DATE_ADDED -> compareBy { it.lastModified }
        DetailSortField.TITLE -> compareBy { it.name }
        // SMB 文件没有时长 / 评分：保持扫描顺序（asSequence 稳定遍历）
        else -> null
    }

/**
 * 顶栏双态：普通（返回 + 标题 + 排序筛选）／多选（关闭 + 已选择 X 项 + 全选 / 标记已看）。
 *
 * 自绘顶栏而不是 `PageHeader`：多选态需要在标题右侧塞两个图标按钮，
 * PageHeader 的槽位容不下（它只有返回 + 标题 + 副标题）。
 */
@Composable
private fun DetailTopBar(
    title: String,
    /** 标题下方的数量统计（「共 24 个文件」）；多选态传 null（顶栏只留一行）。 */
    subtitle: String?,
    selectionMode: Boolean,
    allSelected: Boolean,
    anySelected: Boolean,
    onBack: () -> Unit,
    onSelectAll: () -> Unit,
    onMarkWatched: () -> Unit,
    onTogglePrivate: () -> Unit = {},
    canMarkPrivate: Boolean = false,
    privacyActive: Boolean = false,
    onShowFilter: () -> Unit,
) {
    val app = LocalAppColors.current
    val scheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(
            start = Spacing.xs.dp,
            end = if (privacyActive) Dimens.privacyBadgeReserved else Spacing.sm.dp,
            top = Spacing.xs.dp,
            bottom = Spacing.xs.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIconButton(onClick = onBack) {
            if (selectionMode) CloseIcon(tint = app.text3) else BackIcon(tint = app.text3)
        }
        Column(Modifier.weight(1f).padding(start = Spacing.xs.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = app.text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        if (selectionMode) {
            if (canMarkPrivate) {
                CircleIconButton(
                    onClick = onTogglePrivate,
                    shape = AppShape.circle,
                    enabled = anySelected,
                    contentDescription = "加入或移出隐私",
                ) { LockIcon(tint = app.text3) }
            }
            CircleIconButton(
                onClick = onSelectAll,
                shape = AppShape.circle,
                enabled = !allSelected,
                contentDescription = "全选",
            ) { SelectAllIcon(tint = app.text3) }
            CircleIconButton(
                onClick = onMarkWatched,
                shape = AppShape.circle,
                enabled = anySelected,
                contentDescription = "标记已看",
            ) { CheckIcon(tint = app.text3) }
        } else {
            CircleIconButton(
                onClick = onShowFilter,
                shape = AppShape.circle,
                contentDescription = "排序与筛选",
            ) { FilterListIcon(tint = app.text3) }
        }
    }
}

// ---- SMB 视频卡 ----

// ---- SMB 视频卡 ----

/**
 * SMB 文件卡：**与本机文件夹、Emby 网格共用同一张 [PosterCard]**（2026-11-02 用户要求统一）。
 *
 * 统一后的布局：2:3 封面 → 文件名（卡片下方）→ 左「体积」右「年份」。
 * 之前这张卡把文件名压在封面底部渐变上、本机卡放在封面下方，同一个 App 里出现两套样式。
 *
 * 缩略图与 Emby 海报走**同一条** `loadImage` 链路：`source.image()` 对 SMB 恒为 None，
 * 于是自动落到调用方注入的 `LocalThumbnailProvider`（见 `ThumbnailFallback.kt`）。
 */
@Composable
internal fun SmbVideoCard(
    video: SmbVideoItem,
    selected: Boolean,
    selectionMode: Boolean,
    loadImage: suspend (MediaItemDto, ImageKind) -> ImageSource,
    onPlay: () -> Unit,
    onOpenDetail: () -> Unit,
    onToggleSelect: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dto = remember(video) { video.asMediaItem() }
    PosterCard(
        item = dto,
        loadImage = { item -> loadImage(item, ImageKind.THUMB) },
        onPlay = { onPlay() },
        onOpenDetail = { onOpenDetail() },
        modifier = modifier,
        selectionMode = selectionMode,
        selected = selected,
        onToggleSelect = onToggleSelect,
        onLongPress = onLongClick,
    )
}

/**
 * [SmbVideoItem] → 统一卡片要的 [MediaItemDto]。
 *
 * 正常路径下 `item` 已由 VM 填好（抽帧需要它）；Mock 预览数据为 null 时用
 * path / name / size / 修改时间兜一个，保证卡片布局不塌、元信息行还能显示体积与年份。
 */
private fun SmbVideoItem.asMediaItem(): MediaItemDto = item ?: MediaItemDto(
    id = path,
    serverId = "",
    sourceType = SourceType.SMB,
    title = name.substringBeforeLast('.', name),
    container = name.substringAfterLast('.', "").ifBlank { null },
    sizeBytes = sizeBytes.takeIf { it > 0L },
    dateCreated = lastModified.takeIf { it > 0L },
    path = path,
    hasPoster = false,
)

// ---- 自绘图标 ----

/**
 * 自绘图标的默认尺寸：**20dp**，与顶栏的 [BackIcon] / `FilterListIcon` / `MoreVerticalIcon` 同口径。
 *
 * ⚠️ `Canvas` 没有 `size` 约束时测量结果是 **0dp** —— 图标画了但完全不可见
 * （2026-10-24 真机：顶栏右侧的「排序与筛选」按钮是空的，就是因为它）。
 *
 * 早先是 24dp，和 20dp 的返回箭头并排时明显偏大（2026-11-01 用户反馈图标大小不一致）。
 */
private fun Modifier.defaultIconSize(): Modifier = this.size(20.dp)

/** 关闭（X）。 */
@Composable
private fun CloseIcon(
    tint: Color,
    modifier: Modifier = Modifier.defaultIconSize(),
) {
    androidx.compose.foundation.Canvas(modifier) {
        val stroke = size.height / 9f
        val inset = size.width * 0.28f
        drawLine(tint, androidx.compose.ui.geometry.Offset(inset, inset),
            androidx.compose.ui.geometry.Offset(size.width - inset, size.height - inset), stroke)
        drawLine(tint, androidx.compose.ui.geometry.Offset(size.width - inset, inset),
            androidx.compose.ui.geometry.Offset(inset, size.height - inset), stroke)
    }
}

/** 全选（虚线方框 + 对勾）。 */
@Composable
private fun SelectAllIcon(
    tint: Color,
    modifier: Modifier = Modifier.defaultIconSize(),
) {
    androidx.compose.foundation.Canvas(modifier) {
        val stroke = size.height / 10f
        val inset = size.width * 0.16f
        // 虚线方框
        val dash = size.width * 0.12f
        val gap = dash * 0.7f
        var t = 0f
        while (t < size.width - inset * 2) {
            val end = (t + dash).coerceAtMost(size.width - inset * 2)
            drawLine(tint, androidx.compose.ui.geometry.Offset(inset + t, inset),
                androidx.compose.ui.geometry.Offset(inset + end, inset), stroke)
            drawLine(tint, androidx.compose.ui.geometry.Offset(inset + t, size.height - inset),
                androidx.compose.ui.geometry.Offset(inset + end, size.height - inset), stroke)
            t = end + gap
        }
        t = 0f
        while (t < size.height - inset * 2) {
            val end = (t + dash).coerceAtMost(size.height - inset * 2)
            drawLine(tint, androidx.compose.ui.geometry.Offset(inset, inset + t),
                androidx.compose.ui.geometry.Offset(inset, inset + end), stroke)
            drawLine(tint, androidx.compose.ui.geometry.Offset(size.width - inset, inset + t),
                androidx.compose.ui.geometry.Offset(size.width - inset, inset + end), stroke)
            t = end + gap
        }
        // 中间对勾
        val check = Path().apply {
            moveTo(size.width * 0.32f, size.height * 0.52f)
            lineTo(size.width * 0.46f, size.height * 0.66f)
            lineTo(size.width * 0.72f, size.height * 0.36f)
        }
        drawPath(check, tint, style = Stroke(width = stroke * 1.2f, join = StrokeJoin.Round))
    }
}

// ---- Mock（SMB 源未实现；详情页的 SMB 模式用它出数） ----

internal object LibraryDetailMock {

    /** 从首页 Mock 的文件夹数据转换：path / size / date 都按真实扫描会给出的形态造。 */
    fun smbVideos(libraryId: String): List<SmbVideoItem> {
        val folderName = libraryId.removePrefix("smb:")
        val folder = MockHomeData.smbFolders.firstOrNull { it.name == folderName } ?: return emptyList()
        return folder.items.mapIndexed { index, item ->
            SmbVideoItem(
                path = "/media/${folder.name}/${item.title}.mkv",
                name = "${item.title}.mkv",
                sizeBytes = 4_700_000_000L + index * 317_000_000L,
                lastModified = item.dateCreated ?: 0L,
            )
        }
    }

    /** Preview 用：SMB「Movies」文件夹。 */
    val previewSmbVideos: List<SmbVideoItem> by lazy { smbVideos("smb:Movies") }
}

// ---- @Preview ----

/** Preview 专用宿主：不依赖 VM，直接喂假状态。 */
@Composable
private fun LibraryDetailPreviewContent(
    sourceType: HomeSourceType,
    state: LibraryDetailUiState,
    darkTheme: Boolean,
) {
    MyPlayerTheme(darkTheme = darkTheme) {
        LibraryDetailScreen(
            state = state,
            sourceType = sourceType,
            title = if (sourceType == HomeSourceType.SMB) "Movies" else "电影",
            loadImage = { item, kind ->
                val (w, h) = if (kind == ImageKind.POSTER) 300 to 450 else 480 to 270
                ImageSource.Remote("https://picsum.photos/seed/${item.id}/$w/$h")
            },
            onBack = {},
            onOpenDetail = {},
            onPlay = {},
            onLongPress = {},
            onToggleSelect = {},
            onExitSelection = {},
            onSelectAll = {},
            onMarkWatched = {},
            onApplyFilter = {},
        )
    }
}

@Preview(name = "媒体库详情 · Emby 海报墙 · 暗色", showBackground = true, backgroundColor = 0xFF0B0D11, heightDp = 900)
@Composable
private fun LibraryDetailEmbyPreview() {
    LibraryDetailPreviewContent(
        sourceType = HomeSourceType.EMBY,
        state = LibraryDetailUiState(
            loading = false,
            embyItems = MockHomeData.libraries[0].items,
        ),
        darkTheme = true,
    )
}

@Preview(name = "媒体库详情 · SMB 视频网格 · 暗色", showBackground = true, backgroundColor = 0xFF0B0D11, heightDp = 900)
@Composable
private fun LibraryDetailSmbPreview() {
    LibraryDetailPreviewContent(
        sourceType = HomeSourceType.SMB,
        state = LibraryDetailUiState(
            loading = false,
            smbVideos = LibraryDetailMock.previewSmbVideos,
        ),
        darkTheme = true,
    )
}

// ---- 常量 ----

/**
 * 网格最小列宽（`GridCells.Adaptive`）。
 *
 * 手机仍是一行 3 个（411dp 屏：`(379+8)/(92+8) = 3.87` → 3 列，与 `Fixed(3)` 完全一致），
 * 平板 / 折叠屏展开态自动加列。
 *
 * ⚠️ 不要把它调回 132dp：那个值在 411dp 屏上算出 **2 列**（`(379+8)/(132+8) = 2.76`），
 * 正是 2026-10-24「一加 Adaptive 会变成 2 列」结论的由来 —— 问题在列宽取值，不在 Adaptive。
 */

/** 网格单元间距（用户 2026-10-24 定稿 8dp）。 */

/** 海报 / 视频卡圆角统一走 `AppShape.card`（12dp）。 */

