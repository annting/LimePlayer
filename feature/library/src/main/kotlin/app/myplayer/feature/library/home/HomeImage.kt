package app.myplayer.feature.library.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.model.ImageKind
import app.myplayer.core.model.ImageSource

/**
 * 首页 / 媒体库详情页共用的图片加载（原在 `HomeScreen.kt` 内私有，详情页海报墙复用后提升）。
 *
 * `MediaSource.image()` 是 suspend，而 Coil 要现成的 model：
 * 先 `remember` 成 [ImageSource]，再转 [ImageRequest] —— 与库页 `PosterCard` 同一套写法，
 * key 用 item.id，列表复用不会串图。
 */
@Composable
internal fun rememberHomeImage(
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
internal fun HomeImage(
    source: ImageSource,
    title: String,
    modifier: Modifier = Modifier,
) {
    val app = LocalAppColors.current
    val request: ImageRequest? = rememberImageRequest(source)

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

/**
 * [ImageSource] → Coil [ImageRequest]；`None` 返回 null（调用方自己决定占位长什么样）。
 *
 * 抽出来给 SMB 视频卡复用：那边不要「渐变 + 首字」这层占位，只要一张铺满的缩略图，
 * 但请求构造（令牌走请求头、crossfade）必须与海报完全一致，否则同一张图会进两份缓存。
 */
@Composable
internal fun rememberImageRequest(source: ImageSource): ImageRequest? {
    val context = LocalContext.current
    return remember(source, context) {
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
}
