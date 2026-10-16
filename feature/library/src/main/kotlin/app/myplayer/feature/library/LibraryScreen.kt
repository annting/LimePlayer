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
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.designsystem.MonoLabelStyle
import app.myplayer.core.designsystem.components.AppChip
import app.myplayer.core.designsystem.components.EmptyState
import app.myplayer.core.designsystem.components.PageHeader
import app.myplayer.core.designsystem.components.ShimmerBox
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.MediaItemDto

/**
 * 库浏览页（`docs/03` §6.1 / 设计板 ① 的视觉基线）：
 * PageHeader（源信息）→ 库胶囊 chips → 海报网格；加载态为骨架屏，空态走统一 EmptyState。
 */
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    loadImage: suspend (MediaItemDto) -> ImageSource,
    onSelectLibrary: (String) -> Unit,
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

        // ---- 内容区三态：骨架屏 / 空态 / 网格 ----
        when {
            state.loading -> SkeletonGrid()

            state.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (state.libraries.isEmpty()) {
                    EmptyState(
                        title = "还没有媒体库",
                        description = "服务器上没有可见的媒体库，或同步尚未完成。",
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
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(state.items, key = { it.id }) { item ->
                    PosterCard(item = item, loadImage = loadImage)
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

/** 海报卡（设计板 `.poster`）：2:3 圆角、底部 scrim、评分角标、标题 + 单行元信息；按压缩放 0.96。 */
@Composable
private fun PosterCard(
    item: MediaItemDto,
    loadImage: suspend (MediaItemDto) -> ImageSource,
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
            .clickable(interactionSource = interaction, indication = null, onClick = { /* TODO(M2): 详情页 */ }),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(14.dp))
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
                            1f to Color(0xB8000000),
                        )
                    )
            )
            item.communityRating?.takeIf { it > 0f }?.let { rating ->
                Text(
                    String.format(java.util.Locale.US, "%.1f", rating),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                    color = Color(0xFFF5C869),
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

/** 卡片第二行元信息：时长 · 体积（有则拼）。 */
private fun MediaItemDto.metaLine(): String? {
    val parts = mutableListOf<String>()
    val minutes = durationTicks.seconds / 60
    if (minutes > 0) parts += "$minutes 分钟"
    sizeBytes?.takeIf { it > 0 }?.let {
        parts += String.format(java.util.Locale.US, "%.1f GB", it / 1024.0 / 1024.0 / 1024.0)
    }
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
            repeat(4) { ShimmerBox(Modifier.width(72.dp).height(34.dp), cornerRadius = 999.dp) }
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
