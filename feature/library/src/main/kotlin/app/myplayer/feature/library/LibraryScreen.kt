package app.myplayer.feature.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.designsystem.LocalBottomNavSpace
import app.myplayer.core.designsystem.AppShape
import app.myplayer.core.designsystem.PlayerTokens
import app.myplayer.core.designsystem.RatingGold
import app.myplayer.core.designsystem.MonoLabelStyle
import app.myplayer.core.designsystem.components.AppChip
import app.myplayer.core.designsystem.components.EmptyState
import app.myplayer.core.designsystem.components.PageHeader
import app.myplayer.core.designsystem.components.PlayIcon
import app.myplayer.core.designsystem.components.ShimmerBox
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.formatDuration
import app.myplayer.core.model.formatFileSize

/**
 * 库浏览页（`docs/03` §6.1 / 设计板 ① 的视觉基线）：
 * PageHeader（源信息）→ 库胶囊 chips → 海报网格；加载态为骨架屏，空态走统一 EmptyState。
 */
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    loadImage: suspend (MediaItemDto) -> ImageSource,
    onSelectLibrary: (String) -> Unit,
    onPlay: (MediaItemDto) -> Unit,
    onOpenDetail: (MediaItemDto) -> Unit,
    onSort: (SortSpec) -> Unit = {},
    onFilter: (FilterSpec) -> Unit = {},
    onResetFilter: () -> Unit = {},
    modifier: Modifier = Modifier,
    serverLabel: String? = null,
) {
    Column(modifier.fillMaxSize()) {
        PageHeader(
            title = "媒体库",
            subtitle = serverLabel?.let { "已连接 · $it" } ?: "本机缓存",
        )

        // ---- 库切换：胶囊 chips（横向滚动） ----
        if (state.libraries.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.libraries.forEach { lib ->
                    AppChip(
                        text = lib.name,
                        selected = lib.id == state.selectedLibraryId,
                        onClick = { onSelectLibrary(lib.id) },
                    )
                }
            }
        }

        // ---- 排序 / 筛选入口（`02` §6.3；一个弹层收两段） ----
        var sheetVisible by remember { mutableStateOf(false) }
        SortFilterBar(sort = state.sort, filter = state.filter, onClick = { sheetVisible = true })
        if (sheetVisible) {
            SortFilterSheet(
                sort = state.sort,
                filter = state.filter,
                onDismiss = { sheetVisible = false },
                onSort = onSort,
                onFilter = onFilter,
                onReset = onResetFilter,
            )
        }

        // ---- 内容区三态：骨架屏 / 空态 / 网格 ----
        when {
            state.loading -> SkeletonGrid()

            state.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (state.libraries.isEmpty()) {
                    EmptyState(
                        title = "还没有媒体库",
                        description = "服务器上没有可见的媒体库，或同步尚未完成。",
                    )
                } else if (state.totalCount > 0) {
                    EmptyState(
                        title = "没有符合条件的条目",
                        description = "当前筛选条件下没有结果（共 ${state.totalCount} 条），试试放宽筛选。",
                    )
                } else {
                    EmptyState(
                        title = "这个库还没有内容",
                        description = "在服务器端向该媒体库添加视频后再回来看看，或切换到其它库。",
                    )
                }
            }

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 132.dp),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 0.dp,
                    // 悬浮导航高度（含手势条 + 呼吸）：见 `LocalBottomNavSpace` 注释
                    bottom = LocalBottomNavSpace.current,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(state.items, key = { it.id }) { item ->
                    PosterCard(item = item, loadImage = loadImage, onPlay = onPlay, onOpenDetail = onOpenDetail)
                }
            }
        }
    }
}

/**
 * `MediaSource.image()` 是 **suspend** 的（本地源以后还要抽帧），而 Coil 需要已就绪的 model，
 * 所以「suspend 加载 + per-item LaunchedEffect 落成 UI 状态」：先渲染占位，拿到结果再切图；
 * key 用 item.id，列表复用不会串图。
 */
@Composable
private fun rememberImageSource(itemId: String, load: suspend () -> ImageSource): ImageSource {
    var source by remember(itemId) { mutableStateOf<ImageSource>(ImageSource.None) }
    LaunchedEffect(itemId) { source = load() }
    return source
}

/**
 * 海报卡（设计板 `.poster`）：2:3 圆角、底部 scrim、评分角标、标题 + 单行元信息；按压缩放 0.96。
 *
 * **两级入口**（`03` §6.1）：中央播放按钮 → [onPlay]（直接播放）；卡片其余区域 → [onOpenDetail]。
 * 内层按钮的 `clickable` 会消费点击，不会冒泡到外层卡片的进详情回调。
 */
@Composable
internal fun PosterCard(
    item: MediaItemDto,
    loadImage: suspend (MediaItemDto) -> ImageSource,
    onPlay: (MediaItemDto) -> Unit,
    onOpenDetail: (MediaItemDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val image = rememberImageSource(item.id) { loadImage(item) }
    val context = LocalContext.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 900f),
        label = "poster-scale",
    )

    val request: ImageRequest? = when (image) {
        is ImageSource.Remote -> ImageRequest.Builder(context)
            .data(image.url)
            .httpHeaders(                                  // 令牌走请求头，不进 URL / 缓存键
                NetworkHeaders.Builder().apply {
                    image.headers.forEach { (k, v) -> set(k, v) }
                }.build()
            )
            .crossfade(true)
            .build()
        is ImageSource.Local -> ImageRequest.Builder(context).data(image.uri).build()
        ImageSource.None -> null
    }

    Column(
        modifier = modifier
            .scale(scale)
            .clickable(interactionSource = interaction, indication = null, onClick = { onOpenDetail(item) }),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(AppShape.control)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (request != null) {
                AsyncImage(
                    model = request,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // 无海报占位：surfaceVariant 空白块 + 居中品牌角标（不发必然 404 的请求）
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        item.title.take(1),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
            }
            // 底部 scrim：保证叠字可读（设计板 .poster .art::after）
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.58f to Color.Transparent,
                            1f to PlayerTokens.controlScrim,
                        )
                    )
            )
            // 中央播放按钮（`03` §6.1 两级入口）：点它直接播放；热区 48dp。
            // 叠在 scrim 之上、独立 clickable —— 内层消费点击，不冒泡到卡片的「进详情」。
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp)
                    .clip(AppShape.circle)
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                    .clickable(onClick = { onPlay(item) })
                    .semantics { contentDescription = "播放 ${item.title}" },
                contentAlignment = Alignment.Center,
            ) {
                PlayIcon(tint = PlayerTokens.onMedia)
            }
            item.communityRating?.takeIf { it > 0f }?.let { rating ->
                Text(
                    String.format(java.util.Locale.US, "%.1f", rating),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                    color = RatingGold,
                    style = MonoLabelStyle,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        item.metaLine()?.let { meta ->
            Spacer(Modifier.height(3.dp))
            Text(meta, style = MonoLabelStyle, color = LocalAppColors.current.text3, maxLines = 1)
        }
    }
}

/** 卡片第二行元信息：时长 · 体积（有则拼；格式见 `03` §6.5，与详情页共用 `:core:model` 实现）。 */
private fun MediaItemDto.metaLine(): String? {
    val parts = listOfNotNull(
        formatDuration(durationTicks),
        sizeBytes?.let(::formatFileSize),
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** 网格骨架屏（首屏同步中）：两列海报 + 标题占位。 */
@Composable
private fun SkeletonGrid() {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(4) { ShimmerBox(Modifier.width(72.dp).height(34.dp), cornerRadius = 8.dp) }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            repeat(3) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(2) {
                        Column(Modifier.weight(1f)) {
                            ShimmerBox(Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                            Spacer(Modifier.height(8.dp))
                            ShimmerBox(Modifier.fillMaxWidth(0.72f).height(12.dp), cornerRadius = 6.dp)
                            Spacer(Modifier.height(6.dp))
                            ShimmerBox(Modifier.fillMaxWidth(0.42f).height(10.dp), cornerRadius = 5.dp)
                        }
                    }
                }
            }
        }
    }
}
