package app.myplayer.feature.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import app.myplayer.core.model.ImageKind
import app.myplayer.core.model.ImageSize
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.MediaItemDto

/**
 * 详情页 / 人物页共用的图片装载（`MediaSource` 的 image 系列是 suspend，Coil 需要已就绪的 model）。
 * internal：同包 [DetailScreen] 与 [PersonScreen] 复用，不外泄到模块外。
 */

@Composable
internal fun rememberItemImage(
    item: MediaItemDto,
    kind: ImageKind,
    load: suspend (MediaItemDto, ImageKind) -> ImageSource,
): ImageSource {
    var source by remember(item.id, kind) { mutableStateOf<ImageSource>(ImageSource.None) }
    LaunchedEffect(item.id, kind) { source = load(item, kind) }
    return source
}

@Composable
internal fun rememberPersonImage(
    personId: String,
    load: suspend (String, ImageSize) -> ImageSource,
): ImageSource {
    var source by remember(personId) { mutableStateOf<ImageSource>(ImageSource.None) }
    LaunchedEffect(personId) { source = load(personId, ImageSize.SMALL) }
    return source
}

/** 令牌走请求头，不进 URL / 缓存键；`None` 返回 null 由调用方渲染占位。 */
@Composable
internal fun rememberImageRequest(source: ImageSource): coil3.request.ImageRequest? {
    val context = LocalContext.current
    return remember(source) {
        when (source) {
            is ImageSource.Remote -> ImageRequest.Builder(context)
                .data(source.url)
                .httpHeaders(
                    NetworkHeaders.Builder().apply {
                        source.headers.forEach { (k, v) -> set(k, v) }
                    }.build()
                )
                .crossfade(true)
                .build()
            is ImageSource.Local -> ImageRequest.Builder(context).data(source.uri).build()
            ImageSource.None -> null
        }
    }
}
