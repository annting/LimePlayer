package app.myplayer.feature.library.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import app.myplayer.core.designsystem.AppShape
import app.myplayer.core.designsystem.Dimens
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.designsystem.LocalBottomNavSpace
import app.myplayer.core.designsystem.MyPlayerTheme
import app.myplayer.core.designsystem.PlayerTokens
import app.myplayer.core.designsystem.Spacing
import app.myplayer.core.designsystem.components.CheckIcon
import app.myplayer.core.designsystem.components.ChevronDownIcon
import app.myplayer.core.designsystem.components.ChevronRightIcon
import app.myplayer.core.designsystem.components.EmptyState
import app.myplayer.core.designsystem.components.PlayIcon
import app.myplayer.core.designsystem.components.ServerIcon
import app.myplayer.core.designsystem.components.ShimmerBox
import app.myplayer.core.model.ImageKind
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.MediaItemDto

/**
 * 首页（`docs/03` §6.0）。
 *
 * ## 结构：**整页只有一个 LazyVerticalGrid**
 * 「继续观看」「各库横滑」都是 `item(span = { GridItemSpan(maxLineSpan) })` 占满整行，
 * 「最近添加」直接 `items(...)` 走默认 3 列 —— 没有外层 LazyColumn / 内层竖向滚动，
 * 所以不存在嵌套滑动冲突（横向 LazyRow 与竖向网格正交，天然不打架）。
 *
 * ## 横向边界回弹
 * 每个 LazyRow 都挂 [bounceOverscroll]，滑到头松手会按 spring 阻尼弹回，
 * 实现与取舍见 `BounceOverscroll.kt` 的类注释。
 *
 * ## 状态机（全部由 [HomeUiState] 推导，页面里不存业务状态）
 * ```
 * isLoading            → 骨架屏（2-3 个横滑占位 + 6 个竖屏占位）
 * hasNoServer          → 空态 1：去添加服务器
 * error != null        → 空态 2：无法连接到服务器 + 重试
 * !hasContent          → 兜底空态：服务器里还没有内容
 * 其它                 → PullToRefreshBox { 网格 }
 * ```
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    loadImage: suspend (MediaItemDto, ImageKind) -> ImageSource,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onSelectServer: (String) -> Unit,
    onAddServer: () -> Unit,
    onPlay: (MediaItemDto) -> Unit,
    onOpenDetail: (MediaItemDto) -> Unit,
    onSeeAllLibrary: (HomeLibrarySection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        HomeTopBar(
            serverName = state.serverName,
            servers = state.servers,
            selectedServerId = state.selectedServerId,
            onSelectServer = onSelectServer,
        )

        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                state.isLoading -> HomeSkeleton()

                state.hasNoServer -> HomeFullState(
                    title = "还没有服务器",
                    description = "添加一台 Emby / Jellyfin / 飞牛影视服务器，才能看到继续观看和最近添加。",
                    actionText = "去添加服务器",
                    onAction = onAddServer,
                    iconContent = { ServerIcon(tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp)) },
                )

                state.isOffline -> HomeFullState(
                    title = "无法连接到服务器",
                    description = state.error,
                    actionText = "重试",
                    onAction = onRetry,
                    iconContent = { OfflineIcon(tint = MaterialTheme.colorScheme.primary) },
                )

                !state.hasContent -> HomeFullState(
                    title = "这里还没有内容",
                    description = "服务器上还没有可展示的影片，添加片源后再下拉刷新。",
                    actionText = "刷新",
                    onAction = onRefresh,
                    iconContent = { ServerIcon(tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp)) },
                )

                else -> PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    HomeContent(
                        state = state,
                        loadImage = loadImage,
                        onPlay = onPlay,
                        onOpenDetail = onOpenDetail,
                        onSeeAllLibrary = onSeeAllLibrary,
                    )
                }
            }
        }
    }
}

// ---- 顶部栏 ----

/** 顶部栏：当前服务器名 + 下拉箭头（点开切换来源）。**没有搜索图标**（搜索是独立 tab）。 */
@Composable
private fun HomeTopBar(
    serverName: String?,
    servers: List<HomeServerOption>,
    selectedServerId: String?,
    onSelectServer: (String) -> Unit,
) {
    val app = LocalAppColors.current
    val scheme = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = Dimens.pagePadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 下拉菜单只锚定到「服务器名 + 箭头」这一块：锚点若包住整条顶栏，
        // 菜单会从顶栏顶端弹出（压住服务器名），看起来像浮在导航上
        Box {
            Row(
                modifier = Modifier
                    .clip(AppShape.control)
                    .clickable(enabled = servers.isNotEmpty()) { expanded = true }
                    .padding(horizontal = Spacing.xs.dp, vertical = Spacing.sm.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = serverName ?: "未连接服务器",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (serverName != null) scheme.onSurface else app.text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 200.dp),
                )
                if (servers.isNotEmpty()) {
                    Spacer(Modifier.width(Spacing.xs.dp))
                    ChevronDownIcon(tint = app.text3, modifier = Modifier.size(14.dp))
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.width(228.dp),
                shape = AppShape.card,
                containerColor = scheme.surfaceContainerHigh,
                tonalElevation = 0.dp,
                shadowElevation = 12.dp,
                border = BorderStroke(Dimens.hairline, app.border),
            ) {
                servers.forEach { server ->
                    val selected = server.id == selectedServerId
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = server.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) scheme.primary else scheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (server.baseUrl.isNotBlank()) {
                                    Text(
                                        text = server.baseUrl,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = app.text3,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        },
                        onClick = {
                            expanded = false
                            if (!selected) onSelectServer(server.id)
                        },
                        trailingIcon = if (selected) {
                            { CheckIcon(tint = scheme.primary, modifier = Modifier.size(16.dp)) }
                        } else {
                            null
                        },
                        modifier = Modifier.heightIn(min = 52.dp),
                        contentPadding = PaddingValues(horizontal = Dimens.pagePadding),
                    )
                }
            }
        }
    }
}

// ---- 内容区（单网格） ----

@Composable
private fun HomeContent(
    state: HomeUiState,
    loadImage: suspend (MediaItemDto, ImageKind) -> ImageSource,
    onPlay: (MediaItemDto) -> Unit,
    onOpenDetail: (MediaItemDto) -> Unit,
    onSeeAllLibrary: (HomeLibrarySection) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        // 左右 16dp 只作用于「海报列」；横滑行靠 Modifier.horizontalBleed 突破到屏幕边缘。
        // 底部 = 悬浮导航占掉的高度（含手势条）+ 页面呼吸留白：视口铺到屏幕底，
        // 只让最后一行停在胶囊上方，滚动时内容从半透明胶囊底下穿过。
        contentPadding = PaddingValues(
            start = Dimens.pagePadding,
            end = Dimens.pagePadding,
            top = Spacing.xs.dp,
            // 底部留白直接用 LocalBottomNavSpace（已含胶囊顶边 + 4dp 呼吸），不再自己叠
            bottom = LocalBottomNavSpace.current,
        ),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg.dp),
    ) {
        if (state.continueWatchingList.isNotEmpty()) {
            item(key = "continue-watching", span = { GridItemSpan(maxLineSpan) }, contentType = "row") {
                Column {
                    SectionHeader("继续观看")
                    Spacer(Modifier.height(10.dp))
                    ContinueWatchingRow(
                        items = state.continueWatchingList,
                        loadImage = loadImage,
                        onPlay = onPlay,
                        onOpenDetail = onOpenDetail,
                    )
                }
            }
        }

        state.libraries.forEach { library ->
            item(key = "library-${library.id}", span = { GridItemSpan(maxLineSpan) }, contentType = "row") {
                LibrarySection(
                    section = library,
                    loadImage = loadImage,
                    onOpenDetail = onOpenDetail,
                    onSeeAll = { onSeeAllLibrary(library) },
                )
            }
        }

        if (state.recentlyAddedList.isNotEmpty()) {
            item(key = "recently-added-title", span = { GridItemSpan(maxLineSpan) }, contentType = "title") {
                SectionHeader("最近添加")
            }
            gridItems(
                items = state.recentlyAddedList,
                key = { "recent-${it.id}" },
                contentType = { "poster" },
            ) { item ->
                RecentlyAddedPosterCard(
                    item = item,
                    loadImage = loadImage,
                    onClick = { onOpenDetail(item) },
                )
            }
        }
    }
}

/** 区块标题（17sp / titleLarge）。 */
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

// ---- 模块一：继续观看 ----

/** 继续观看横滑行：240dp 16:9 卡片 + 边界回弹。 */
@Composable
private fun ContinueWatchingRow(
    items: List<MediaItemDto>,
    loadImage: suspend (MediaItemDto, ImageKind) -> ImageSource,
    onPlay: (MediaItemDto) -> Unit,
    onOpenDetail: (MediaItemDto) -> Unit,
) {
    val bounce = rememberBounceOverscrollState()
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalBleed(Dimens.pagePadding)
            .bounceOverscroll(bounce),
        contentPadding = PaddingValues(horizontal = Dimens.pagePadding),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md.dp),
    ) {
        items(items = items, key = { it.id }) { item ->
            ContinueWatchingCard(
                item = item,
                loadImage = loadImage,
                onClick = { onOpenDetail(item) },
                onPlay = { onPlay(item) },
            )
        }
    }
}

/**
 * 继续观看卡片：16:9 / 宽 240dp / 圆角 12dp。
 *
 * 结构（从下往上）：深灰底 + 截图 → 底部渐变遮罩 → 标题 + 剩余时间 → 中央半透明播放按钮
 * → 最底部 3dp 青柠色进度条。
 */
@Composable
fun ContinueWatchingCard(
    item: MediaItemDto,
    loadImage: suspend (MediaItemDto, ImageKind) -> ImageSource,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalAppColors.current
    val scheme = MaterialTheme.colorScheme
    val image = rememberHomeImage(item.id, ImageKind.THUMB) { loadImage(item, ImageKind.THUMB) }
    val fraction = item.progressFraction()

    Box(
        modifier = modifier
            .width(240.dp)
            .aspectRatio(16f / 9f)
            .clip(AppShape.card)
            .background(app.surface2)
            .clickable(onClick = onClick),
    ) {
        HomeImage(source = image, title = item.title, modifier = Modifier.fillMaxSize())

        // 底部渐变遮罩：保证叠字可读（越往下越实）
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.42f to Color.Transparent,
                        1f to PlayerTokens.scrimBottom,
                    )
                )
        )

        // 中央播放按钮（点它直接播，点卡片其余区域进详情）
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(44.dp)
                .clip(AppShape.circle)
                .background(PlayerTokens.controlScrim)
                .clickable(onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            PlayIcon(tint = PlayerTokens.onMedia, modifier = Modifier.size(22.dp))
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = Spacing.md.dp, end = Spacing.md.dp, bottom = 11.dp),
        ) {
            Text(
                text = item.continueTitle(),
                style = MaterialTheme.typography.titleSmall,
                color = PlayerTokens.onMedia,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.remainingWatchText()?.let { remaining ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = remaining,
                    style = MaterialTheme.typography.labelSmall,
                    color = PlayerTokens.onMediaSecondary,
                    maxLines = 1,
                )
            }
        }

        // 最底部 3dp 进度条：轨道用半透明白，已完成段用品牌青柠
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(3.dp)
                .background(PlayerTokens.track),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(scheme.primary)
            )
        }
    }
}

/** 卡片标题：剧集优先显示「剧名 · 第N集」，避免同一部剧每集看起来一样。 */
private fun MediaItemDto.continueTitle(): String {
    val series = seriesName ?: return title
    val ep = episodeNumber ?: return series
    return "$series · 第 $ep 集"
}

// ---- 模块二：媒体库分类 ----

/**
 * 一个媒体库分类：标题行（左名字 / 右「查看全部」）+ 16:9 横滑（无进度条）。
 *
 * 分类是由服务器返回的库动态生成的，所以这个组件被 `forEach` 逐个塞进网格，
 * 库有几个就渲染几块，没有硬编码的「电影 / 电视剧」。
 */
@Composable
fun LibrarySection(
    section: HomeLibrarySection,
    loadImage: suspend (MediaItemDto, ImageKind) -> ImageSource,
    onOpenDetail: (MediaItemDto) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalAppColors.current
    val bounce = rememberBounceOverscrollState()

    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = section.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(
                modifier = Modifier
                    .clip(AppShape.control)
                    .clickable(onClick = onSeeAll)
                    .padding(horizontal = Spacing.xs.dp, vertical = Spacing.xs.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "查看全部",
                    style = MaterialTheme.typography.labelSmall,
                    color = app.text3,
                )
                Spacer(Modifier.width(2.dp))
                ChevronRightIcon(tint = app.text3, modifier = Modifier.size(12.dp))
            }
        }
        Spacer(Modifier.height(10.dp))

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalBleed(Dimens.pagePadding)
                .bounceOverscroll(bounce),
            contentPadding = PaddingValues(horizontal = Dimens.pagePadding),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md.dp),
        ) {
            items(items = section.items, key = { it.id }) { item ->
                LibraryCard(
                    item = item,
                    loadImage = loadImage,
                    onClick = { onOpenDetail(item) },
                )
            }
        }
    }
}

/** 库分类卡片：16:9 / 宽 184dp / 圆角 8dp / 标题压在遮罩上（无进度条）。 */
@Composable
private fun LibraryCard(
    item: MediaItemDto,
    loadImage: suspend (MediaItemDto, ImageKind) -> ImageSource,
    onClick: () -> Unit,
) {
    val app = LocalAppColors.current
    val image = rememberHomeImage(item.id, ImageKind.THUMB) { loadImage(item, ImageKind.THUMB) }

    Box(
        modifier = Modifier
            .width(184.dp)
            .aspectRatio(16f / 9f)
            .clip(AppShape.control)
            .background(app.surface2)
            .clickable(onClick = onClick),
    ) {
        HomeImage(source = image, title = item.title, modifier = Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to PlayerTokens.scrimBottom))
        )
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall,
            color = PlayerTokens.onMedia,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm.dp, vertical = Spacing.sm.dp),
        )
    }
}

// ---- 模块三：最近添加（网格） ----

/** 最近添加海报：2:3 / 圆角 8dp / Coil 加载 + 单行片名（12.5sp，省略号）。 */
@Composable
fun RecentlyAddedPosterCard(
    item: MediaItemDto,
    loadImage: suspend (MediaItemDto, ImageKind) -> ImageSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalAppColors.current
    val image = rememberHomeImage(item.id, ImageKind.POSTER) { loadImage(item, ImageKind.POSTER) }

    Column(modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(AppShape.control)
                .background(app.surface2),
        ) {
            HomeImage(source = image, title = item.title, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---- 骨架屏 / 空态 ----

/** 首屏骨架：标题条 + 3 个横滑占位（16:9）+ 2×3 竖屏占位。 */
@Composable
private fun HomeSkeleton() {
    Column(Modifier.fillMaxSize().padding(top = Spacing.xs.dp)) {
        ShimmerBox(
            Modifier
                .padding(start = Dimens.pagePadding)
                .width(96.dp)
                .height(20.dp),
            cornerRadius = 4.dp,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = Dimens.pagePadding),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md.dp),
        ) {
            // 占位卡片与真实卡片同尺寸（240×135），数据到位时不跳动
            repeat(3) { ShimmerBox(Modifier.width(240.dp).height(135.dp), cornerRadius = 12.dp) }
        }
        Spacer(Modifier.height(Spacing.xl.dp))
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.pagePadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg.dp),
        ) {
            repeat(2) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md.dp)) {
                    repeat(3) {
                        Column(Modifier.weight(1f)) {
                            ShimmerBox(Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                            Spacer(Modifier.height(6.dp))
                            ShimmerBox(Modifier.fillMaxWidth(0.78f).height(12.dp), cornerRadius = 4.dp)
                        }
                    }
                }
            }
        }
    }
}

/** 全屏空态（复用统一 [EmptyState]，间距与库页 / 搜索空态一致）。 */
@Composable
private fun HomeFullState(
    title: String,
    description: String?,
    actionText: String,
    onAction: () -> Unit,
    iconContent: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            title = title,
            description = description,
            actionText = actionText,
            onAction = onAction,
            iconContent = iconContent,
        )
    }
}

/** 断网图标（自绘：三段 wifi 弧 + 斜杠），与项目其它图标同风格。 */
@Composable
private fun OfflineIcon(tint: Color) {
    Canvas(Modifier.size(26.dp)) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val cy = h * 0.76f
        val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
        listOf(0.46f, 0.30f, 0.14f).forEach { r ->
            drawArc(
                color = tint,
                startAngle = 205f,
                sweepAngle = 130f,
                useCenter = false,
                topLeft = Offset(cx - w * r, cy - h * r),
                size = Size(w * r * 2f, h * r * 2f),
                style = stroke,
            )
        }
        drawCircle(tint, radius = w * 0.045f, center = Offset(cx, cy))
        drawLine(
            color = tint,
            start = Offset(w * 0.16f, h * 0.18f),
            end = Offset(w * 0.84f, h * 0.82f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}

// ---- 图片 ----

/**
 * `MediaSource.image()` 是 suspend，而 Coil 要现成的 model：
 * 先 `remember` 成 [ImageSource]，再转 [ImageRequest] —— 与库页 `PosterCard` 同一套写法，
 * key 用 item.id，列表复用不会串图。
 */
@Composable
private fun rememberHomeImage(
    itemId: String,
    kind: ImageKind,
    load: suspend () -> ImageSource,
): ImageSource {
    var source by remember(itemId, kind) { mutableStateOf<ImageSource>(ImageSource.None) }
    LaunchedEffect(itemId, kind) { source = load() }
    return source
}

/**
 * 图片层：底层永远是「渐变占位 + 首字」，图片到了才盖上去。
 * 这样断网 / 无海报 / 加载中都不会出现白块或空洞。
 */
@Composable
private fun HomeImage(
    source: ImageSource,
    title: String,
    modifier: Modifier = Modifier,
) {
    val app = LocalAppColors.current
    val context = LocalContext.current
    val request: ImageRequest? = remember(source, context) {
        when (source) {
            is ImageSource.Remote -> ImageRequest.Builder(context)
                .data(source.url)
                .httpHeaders( // 令牌走请求头，不进 URL / 缓存键
                    NetworkHeaders.Builder().apply {
                        source.headers.forEach { (k, v) -> set(k, v) }
                    }.build()
                )
                .crossfade(true)
                .build()
            is ImageSource.Local -> ImageRequest.Builder(context)
                .data(source.uri)
                .crossfade(true)
                .build()
            ImageSource.None -> null
        }
    }

    Box(
        modifier = modifier.background(
            Brush.linearGradient(listOf(app.surface2, app.surface3))
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.take(1),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
        )
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 横滑行突破父级 16dp 内边距：测量时多给两侧宽度，再整体左移，滚到边缘才真正贴屏幕边。 */
private fun Modifier.horizontalBleed(bleed: Dp): Modifier = this.layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val px = bleed.roundToPx()
    val widened: Constraints = constraints.copy(
        minWidth = constraints.maxWidth + px * 2,
        maxWidth = constraints.maxWidth + px * 2,
    )
    val placeable = measurable.measure(widened)
    layout(constraints.maxWidth, placeable.height) { placeable.place(-px, 0) }
}

// ---- @Preview ----

private fun previewState(
    error: String? = null,
    noServer: Boolean = false,
): HomeUiState = HomeUiState(
    serverName = if (noServer) null else MockHomeData.EMBY_NAME,
    servers = if (noServer) emptyList() else MockHomeData.servers,
    selectedServerId = if (noServer) null else MockHomeData.EMBY_ID,
    continueWatchingList = if (noServer) emptyList() else MockHomeData.continueWatching,
    libraries = if (noServer) emptyList() else MockHomeData.libraries,
    recentlyAddedList = if (noServer) emptyList() else MockHomeData.recentlyAdded,
    error = error,
)

@Composable
private fun HomePreviewContent(state: HomeUiState, darkTheme: Boolean) {
    MyPlayerTheme(darkTheme = darkTheme) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            HomeScreen(
                state = state,
                // 预览里没有网络：图片走渐变 + 首字占位
                loadImage = { _, _ -> ImageSource.None },
                onRefresh = {},
                onRetry = {},
                onSelectServer = {},
                onAddServer = {},
                onPlay = {},
                onOpenDetail = {},
                onSeeAllLibrary = {},
            )
        }
    }
}

/** 正常运行态（暗色，App 默认）。 */
@Preview(name = "首页 · 暗色", showBackground = true, backgroundColor = 0xFF0B0D11, heightDp = 900)
@Composable
private fun HomeScreenDarkPreview() {
    HomePreviewContent(state = previewState(), darkTheme = true)
}

/** 正常运行态（亮色）。 */
@Preview(name = "首页 · 亮色", showBackground = true, backgroundColor = 0xFFF4F6F9, heightDp = 900)
@Composable
private fun HomeScreenLightPreview() {
    HomePreviewContent(state = previewState(), darkTheme = false)
}

/** 骨架屏。 */
@Preview(name = "首页 · 骨架屏", showBackground = true, backgroundColor = 0xFF0B0D11, heightDp = 900)
@Composable
private fun HomeSkeletonPreview() {
    HomePreviewContent(state = HomeUiState(isLoading = true, serverName = MockHomeData.EMBY_NAME), darkTheme = true)
}

/** 空态 1：无服务器。 */
@Preview(name = "首页 · 无服务器", showBackground = true, backgroundColor = 0xFF0B0D11, heightDp = 900)
@Composable
private fun HomeNoServerPreview() {
    HomePreviewContent(state = previewState(noServer = true), darkTheme = true)
}

/** 空态 2：连不上服务器。 */
@Preview(name = "首页 · 连接失败", showBackground = true, backgroundColor = 0xFF0B0D11, heightDp = 900)
@Composable
private fun HomeOfflinePreview() {
    HomePreviewContent(state = previewState(error = "连接超时：192.168.31.15:8096 无响应"), darkTheme = true)
}
