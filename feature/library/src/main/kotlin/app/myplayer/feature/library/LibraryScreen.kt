package app.myplayer.feature.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.MediaItemDto

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    loadImage: suspend (MediaItemDto) -> ImageSource,
    onSelectLibrary: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        val selectedIndex = state.libraries.indexOfFirst { it.id == state.selectedLibraryId }.coerceAtLeast(0)
        PrimaryScrollableTabRow(selectedTabIndex = selectedIndex, edgePadding = 16.dp) {
            state.libraries.forEach { lib ->
                Tab(
                    selected = lib.id == state.selectedLibraryId,
                    onClick = { onSelectLibrary(lib.id) },
                    text = { Text(lib.name) },
                )
            }
        }
        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 120.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
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

@Composable
private fun PosterCard(
    item: MediaItemDto,
    loadImage: suspend (MediaItemDto) -> ImageSource,
    modifier: Modifier = Modifier,
) {
    val image = rememberImageSource(item.id) { loadImage(item) }
    val context = LocalContext.current
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

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 2.dp,
            modifier = Modifier.aspectRatio(2f / 3f).fillMaxWidth(),
        ) {
            if (request != null) {
                AsyncImage(
                    model = request,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize())   // 无海报占位，不发必然 404 的请求
            }
        }
        Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
    }
}
