package app.myplayer.feature.library.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
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
 * [ImageSource] → Coil [ImageRequest]；`None` 返回 null（调用方自己决定占位长什么样）。
 *
 * **占位统一在卡片层**（2026-11-02）：这里原来还有一个 `HomeImage`，画的是「渐变 + 文件名首字」。
 * Emby 条目缺图或图片 404 时，首页卡片就会露出一个大字母，与 SMB 抽出帧的卡片完全两种观感。
 * 现在三类卡片统一成「渐变 + 类型图标 → 图到了盖上去」（`CardPlaceholder` / `VideoPlaceholder`
 * 在 `core:media-ui`），`HomeImage` 已删除 —— 不要再在这里加文字占位。
 *
 * 请求构造（令牌走请求头、crossfade）仍集中在这里，保证同一张图不会进两份缓存。
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
