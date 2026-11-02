package app.myplayer.core.mediaui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import app.myplayer.core.designsystem.AppShape
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.designsystem.MonoLabelStyle
import app.myplayer.core.designsystem.PlayerTokens
import app.myplayer.core.designsystem.RatingGold
import app.myplayer.core.designsystem.components.CheckIcon
import app.myplayer.core.designsystem.components.PlayIcon
import app.myplayer.core.designsystem.components.ThinProgressBar
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.formatFileSize
import app.myplayer.core.model.formatYear
import app.myplayer.core.model.resumeFraction

/**
 * `MediaSource.image()` 是 **suspend** 的（本地源以后还要抽帧），而 Coil 需要已就绪的 model，
 * 所以「suspend 加载 + per-item LaunchedEffect 落成 UI 状态」：先渲染占位，拿到结果再切图；
 * key 用 item.id，列表复用不会串图。
 */
@Composable
internal fun rememberImageSource(itemId: String, load: suspend () -> ImageSource): ImageSource {
    var source by remember(itemId) { mutableStateOf<ImageSource>(ImageSource.None) }
    LaunchedEffect(itemId) { source = load() }
    return source
}

/**
 * 统一文件卡（**全站唯一一种「一条视频」的呈现**，2026-11-02 用户定稿）。
 *
 * 用户要求「所有入口的 item 效果必须一致」：只要页面在列「一条视频 / 一部作品」，
 * 就用这张卡 —— 顶栏 / 网格列数 / 间距可以按页面调整，卡片本身只有这一种长相。
 * 当前共有 6 个入口：媒体库 tab、SMB 库详情、Emby 库详情、搜索结果、
 * 本机文件夹、演员作品页。
 *
 * 布局（标题在卡片外）：
 * ```
 * ┌──────────┐
 * │   2:3    │  ← 抽帧 / 海报（Crop），中央播放按钮，底部续播进度条
 * └──────────┘
 *  文件名                     ← 卡片下方，单行 + 省略号
 *  122KB                 2024 ← 左：体积；右：文件添加年份（两者都可缺）
 * ```
 *
 * 无预览图（服务端没海报 / 抽不出帧）时，封面位置画「渐变底 + 居中视频图标」
 * （[VideoPlaceholder]），**不画文件名首字** —— 首字母会让缺图卡片和抽出帧的卡片
 * 看起来是两套东西，而 Emby 里恰好有一批无水印海报的条目。
 *
 * **两级入口**（`03` §6.1）：中央播放按钮 → [onPlay]；卡片其余区域 → [onOpenDetail]。
 * 两个回调都可为 null：演员作品页只给 [onOpenDetail]（那里点卡片是进详情，播放在详情页里），
 * 此时不画中央播放按钮 —— 画一个点了没反应的按钮比不画更糟。
 *
 * 多选态下点击 = 切换选中，长按 = 进入多选（[onLongPress]），此时不显示播放按钮。
 *
 * 历史：SMB 卡把文件名压在封面底部渐变上、本机卡放在封面下方，Emby 又是第三套
 * （标题压在封面上、无体积/年份），同一个 App 里三套样式（2026-11-02 用户两次反馈）。
 * 2026-11-02 从 `:feature:library` 下沉到 `:core:media-ui`（`docs/13-...` T7），
 * 让 `:feature:detail` 的演员作品页也能复用同一张卡。
 *
 * @param onPlay 中央播放按钮；null = 不渲染播放按钮
 * @param onOpenDetail 卡片点击（非多选态）；null = 不可点
 * @param progress 播放进度（0..1）。默认取条目自身的续播占比；搜索结果可显式传入（与列表状态同源）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(
    item: MediaItemDto,
    loadImage: suspend (MediaItemDto) -> ImageSource,
    modifier: Modifier = Modifier,
    onPlay: ((MediaItemDto) -> Unit)? = null,
    onOpenDetail: ((MediaItemDto) -> Unit)? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    progress: Float = item.resumeFraction(),
) {
    val image = rememberImageSource(item.id) { loadImage(item) }
    val context = LocalContext.current
    val app = LocalAppColors.current
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 900f),
        label = "poster-scale",
    )

    // 续播进度：0 = 没看过、1 = 已看完，两种都不画
    val inProgress = progress > 0f && progress < 1f

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
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                enabled = selectionMode || onOpenDetail != null,
                onClick = { if (selectionMode) onToggleSelect?.invoke() else onOpenDetail?.invoke(item) },
                onLongClick = onLongPress,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(AppShape.control)
                // 加载底色与占位渐变同源（surface2），图片没到时不闪白块
                .background(app.surface2)
                .then(if (selected) Modifier.border(2.dp, scheme.primary, AppShape.control) else Modifier),
        ) {
            if (request != null) {
                AsyncImage(
                    model = request,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // 无预览图：渐变底 + 居中视频图标（不再画文件名首字）。
                // Emby 缺海报的条目原先显示成一个大字母，与本机/SMB 抽出帧的卡片是两种观感；
                // 现在与首页视频卡共用同一个 [VideoPlaceholder]（2026-11-02 用户定稿）。
                //
                // 尺寸 44dp 是有意的：卡片有中央播放键时（网格常规态）两者同心，播放徽章
                // 正好盖住图标 —— 那一眼看到的就是「渐变底 + 播放」，不会出现两个三角叠在一起；
                // 多选态 / 没有播放回调（演员作品页）时播放键不画，图标才单独显示。
                VideoPlaceholder(Modifier.fillMaxSize())
            }
            // 底部 scrim：保证叠字/角标可读（设计板 .poster .art::after）
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
            // 多选态 / 没有播放回调时不显示 —— 那时整张卡是「选中」语义或压根不能播放。
            if (!selectionMode && onPlay != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(48.dp)
                        .clip(AppShape.circle)
                        .background(scheme.scrim.copy(alpha = 0.55f))
                        .clickable(onClick = { onPlay.invoke(item) })
                        .semantics { contentDescription = "播放 ${item.title}" },
                    contentAlignment = Alignment.Center,
                ) {
                    PlayIcon(tint = PlayerTokens.onMedia)
                }
            }
            item.communityRating?.takeIf { it > 0f }?.let { rating ->
                Text(
                    String.format(java.util.Locale.US, "%.1f", rating),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                    color = RatingGold,
                    style = MonoLabelStyle,
                )
            }
            // 续播进度条：贴卡片底边（内缩见 ThinProgressBar 注释）
            if (inProgress) {
                ThinProgressBar(progress = progress, modifier = Modifier.align(Alignment.BottomStart))
            }
            if (selected) {
                Box(Modifier.fillMaxSize().background(scheme.primary.copy(alpha = 0.25f)))
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .clip(AppShape.circle)
                        .background(scheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    CheckIcon(tint = PlayerTokens.onMedia, modifier = Modifier.size(14.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.titleSmall,
            color = scheme.onSurface,
            // 单行 + 省略号（2026-11-02 用户定稿）：两行会让同一行卡片高低不齐，
            // 长片名把网格撑成「阶梯」，统一成一行后所有入口的卡片高度完全一致。
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // 第二行：左「体积」右「年份」（两者都可能缺失，缺一个就只显示另一个）
        val sizeText = item.sizeBytes?.let(::formatFileSize)
        val yearText = formatYear(item.dateCreated)
        if (sizeText != null || yearText != null) {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    sizeText.orEmpty(),
                    style = MonoLabelStyle,
                    color = app.text3,
                    maxLines = 1,
                )
                Text(
                    yearText.orEmpty(),
                    style = MonoLabelStyle,
                    color = app.text3,
                    maxLines = 1,
                )
            }
        }
    }
}
