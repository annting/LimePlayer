package app.myplayer.feature.detail

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import android.widget.Toast
import app.myplayer.core.designsystem.AppRadius
import app.myplayer.core.designsystem.AppShape
import app.myplayer.core.designsystem.Dimens
import app.myplayer.core.designsystem.PlayerTokens
import app.myplayer.core.designsystem.Spacing
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.designsystem.MonoLabelStyle
import app.myplayer.core.designsystem.components.AppButton
import app.myplayer.core.designsystem.components.AppButtonStyle
import app.myplayer.core.designsystem.components.AppChip
import app.myplayer.core.designsystem.components.BackIcon
import app.myplayer.core.designsystem.components.CheckIcon
import app.myplayer.core.designsystem.components.DeleteIcon
import app.myplayer.core.designsystem.components.EmptyState
import app.myplayer.core.designsystem.components.LockIcon
import app.myplayer.core.designsystem.components.LockOpenIcon
import app.myplayer.core.designsystem.components.PlayIcon
import app.myplayer.core.designsystem.components.VideoIcon
import app.myplayer.core.model.CastMember
import app.myplayer.core.model.ImageKind
import app.myplayer.core.model.ImageSize
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.SeasonGroup
import app.myplayer.core.model.formatDuration
import app.myplayer.core.model.resumeFraction

/**
 * 详情页（`docs/03` §6.3 / 详情页二期）：
 * 海报背景 + 标题行 → 主按钮 + 操作行（已播对勾 / 删除 / 隐私）→ 分季剧集列表 → 简介 → 演职员 → 信息分区。
 *
 * - **无折叠**：简介与信息分区直接展示（页面可滚动）。
 * - 操作行（2026-10-29）：对勾仅在 `canMarkPlayed` 时渲染；删除仅在 `item.canDelete` 时渲染
 *   （SMB/LOCAL 源恒可删 → 物理删除）；隐私开关仅在 `isPrivate != null`（隐私模式 + 档二源）时渲染。
 * - 演职员头像点击进人物页（§6.3.1）。
 */
@Composable
fun DetailScreen(
    state: DetailUiState,
    loadImage: suspend (MediaItemDto, ImageKind) -> ImageSource,
    loadPersonImage: suspend (String, ImageSize) -> ImageSource,
    canMarkPlayed: Boolean,
    onBack: () -> Unit,
    onPlay: (MediaItemDto) -> Unit,
    onRetry: () -> Unit,
    onMarkPlayed: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onPersonClick: (CastMember) -> Unit,
    userMessage: String?,
    onMessageShown: () -> Unit,
    onTogglePrivate: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 一次性提示（`03` §6.3：写操作失败仅提示，不改本地状态）
    val context = LocalContext.current
    LaunchedEffect(userMessage) {
        userMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            onMessageShown()
        }
    }

    val scrollState = rememberScrollState()
    // 只在「越过阈值」这一刻重组一次，不要每次滚动都重组（derivedStateOf 包一层）
    val scrolled by remember { derivedStateOf { scrollState.value > 24 } }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (state) {
            DetailUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            is DetailUiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "加载失败",
                    description = state.message,
                    actionText = "重试",
                    onAction = onRetry,
                )
            }

            is DetailUiState.Content -> DetailContent(
                scrollState = scrollState,
                item = state.item,
                seasons = state.seasons,
                isPrivate = state.isPrivate,
                loadImage = loadImage,
                loadPersonImage = loadPersonImage,
                canMarkPlayed = canMarkPlayed,
                onPlay = onPlay,
                onMarkPlayed = onMarkPlayed,
                onDelete = onDelete,
                onTogglePrivate = onTogglePrivate,
                onPersonClick = onPersonClick,
            )
        }
        // 滚起来之后给返回键垫一层顶部渐变：内容是**从返回键底下穿过**的，
        // 不垫的话「文件信息」这类分区标题会被返回键的半透明圆底直接压住（真机 23:30 复现）。
        if (scrolled) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(DetailTopScrimHeight)
                    .background(
                        Brush.verticalGradient(
                            0f to MaterialTheme.colorScheme.background,
                            0.55f to MaterialTheme.colorScheme.background.copy(alpha = 0.86f),
                            1f to Color.Transparent,
                        )
                    ),
            )
        }
        // 返回键常驻在 hero 之上（加载 / 错误态也要能返回）
        BackButton(onBack = onBack, modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
    }
}

@Composable
private fun DetailContent(
    scrollState: ScrollState,
    item: MediaItemDto,
    seasons: List<SeasonGroup>,
    isPrivate: Boolean?,
    loadImage: suspend (MediaItemDto, ImageKind) -> ImageSource,
    loadPersonImage: suspend (String, ImageSize) -> ImageSource,
    canMarkPlayed: Boolean,
    onPlay: (MediaItemDto) -> Unit,
    onMarkPlayed: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onTogglePrivate: () -> Unit,
    onPersonClick: (CastMember) -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            // MainActivity 只统一避让 Top + Horizontal（首页悬浮导航要透底），
            // 详情页没有悬浮导航，手势条得自己让出来。放在 verticalScroll **之后**：
            // padding 落进滚动内容里，滚到底时末尾留白；若放在前面等于裁 viewport，
            // 滚动过程中底部会一直空一条。
            .navigationBarsPadding(),
    ) {
        HeroHeader(
            item = item,
            poster = rememberItemImage(item, ImageKind.POSTER, loadImage),
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.pagePadding)) {
            Spacer(Modifier.height(Dimens.pagePadding))
            val resume = item.resumeState()
            AppButton(
                text = primaryActionLabel(item),
                onClick = { onPlay(item) },
                leadingIcon = { PlayIcon(tint = MaterialTheme.colorScheme.onPrimary) },
                modifier = Modifier.fillMaxWidth(),
            )
            // 续播行（`03` §6.3）：看了一半才有 —— 按钮下方进度条 + 剩余时长
            resume?.let { ResumeRow(fraction = it.fraction, remaining = it.remaining) }

            // 操作行（2026-10-29）：已播对勾 / 删除 / 隐私开关，按可用性逐个渲染。
            // 整体水平居中（BUG-6）：Row 需 fillMaxWidth + Center，否则 wrap 宽贴左。
            if (canMarkPlayed || item.canDelete || isPrivate != null) {
                Spacer(Modifier.height(Spacing.md.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md.dp, Alignment.CenterHorizontally),
                ) {
                    if (canMarkPlayed) {
                        DetailActionButton(
                            filled = item.userData.isPlayed,
                            contentDescription = if (item.userData.isPlayed) "取消已播放" else "标记已播放",
                            onClick = { onMarkPlayed(!item.userData.isPlayed) },
                            icon = { tint -> CheckIcon(tint = tint, modifier = Modifier.size(20.dp)) },
                        )
                    }
                    if (item.canDelete) {
                        DetailActionButton(
                            filled = false,
                            danger = true,
                            contentDescription = "删除",
                            onClick = { showDeleteDialog = true },
                            icon = { tint -> DeleteIcon(tint = tint, modifier = Modifier.size(20.dp)) },
                        )
                    }
                    if (isPrivate != null) {
                        DetailActionButton(
                            filled = isPrivate,
                            contentDescription = if (isPrivate) "取消隐私" else "设为隐私",
                            onClick = onTogglePrivate,
                            icon = { tint ->
                                if (isPrivate) {
                                    LockIcon(tint = tint, modifier = Modifier.size(20.dp))
                                } else {
                                    LockOpenIcon(tint = tint, modifier = Modifier.size(20.dp))
                                }
                            },
                        )
                    }
                }
            }

            if (seasons.isNotEmpty()) {
                Spacer(Modifier.height(Dimens.sectionGap))
                SeasonSection(seasons, loadImage, onPlay)
            }

            item.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Spacer(Modifier.height(Dimens.sectionGap))
                SectionTitle("简介")
                Spacer(Modifier.height(8.dp))
                Text(
                    overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (item.people.isNotEmpty()) {
                Spacer(Modifier.height(Dimens.sectionGap))
                SectionTitle("演职员")
                Spacer(Modifier.height(8.dp))
                CastAvatarGrid(item.people, loadPersonImage, onPersonClick)
            } else if (item.cast.isNotEmpty()) {
                Spacer(Modifier.height(Dimens.sectionGap))
                SectionTitle("演职员")
                Spacer(Modifier.height(8.dp))
                CastFlow(item.cast)
            }

            Spacer(Modifier.height(Dimens.sectionGap))
            InfoSections(item)

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDeleteDialog) {
        // SMB / 本地 = 物理删除文件本身；Emby 系 = 服务端接口删除
        val where = when (item.sourceType) {
            app.myplayer.core.model.SourceType.SMB,
            app.myplayer.core.model.SourceType.LOCAL,
            -> "从磁盘永久删除"
            else -> "从服务器永久删除"
        }
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除条目") },
            text = { Text("确定要$where「${item.title}」吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDelete() }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 操作行的圆形图标按钮（对勾 / 删除 / 隐私）。
 *
 * - `filled = true`：实心主色圆 + 反色图标（激活态，如已播放 / 已私有）；
 * - `filled = false`：1dp 描边圆 + 常规图标；
 * - `danger`：描边与图标用 error 色，让删除在三个按钮里一眼可辨。
 */
@Composable
private fun DetailActionButton(
    filled: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    icon: @Composable (tint: Color) -> Unit,
    danger: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val accent = if (danger) scheme.error else scheme.primary
    Box(
        modifier = modifier
            .size(Dimens.touchTarget)
            .semantics { this.contentDescription = contentDescription }
            .clip(AppShape.circle)
            .then(
                if (filled) {
                    Modifier.background(accent)
                } else {
                    Modifier.border(1.dp, if (danger) accent else scheme.outlineVariant, AppShape.circle)
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        icon(if (filled) scheme.onPrimary else accent)
    }
}

/**
 * 续播行（`03` §6.3）：全宽进度条 + 「剩余 1小时25分钟」。
 * 颜色沿用播放页进度条的主色，轨道用弱化色，避免在详情页里过于抢眼。
 */
@Composable
private fun ResumeRow(fraction: Float, remaining: String) {
    Column(Modifier.fillMaxWidth()) {
        // 16dp：与主按钮拉开。旧值 10dp + 与按钮等宽时，这条 3dp 细线会被读成「按钮的下边框」
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .fillMaxWidth()
                // 左右各内缩 8dp：不再与按钮同宽，进一步把「进度」和「按钮」区分开
                .padding(horizontal = 8.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "剩余 $remaining",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- Hero：海报背景 + scrim + 标题行 ----

@Composable
private fun HeroHeader(item: MediaItemDto, poster: ImageSource) {
    val app = LocalAppColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(300.dp)
            .background(Brush.linearGradient(listOf(app.surface2, app.surface3))),
    ) {
        val request = rememberImageRequest(poster)
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // 无海报 / 无预览图（SMB 视频抽不出帧）：居中视频图标占位（2026-10-30）
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                VideoIcon(
                    tint = app.text3.copy(alpha = 0.6f),
                    modifier = Modifier.size(48.dp),
                )
            }
        }
        // 双段 scrim：底部渐深保证叠字可读（`03` §6.3 海报背景）
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to MaterialTheme.colorScheme.scrim.copy(alpha = 0.60f),
                        0.35f to MaterialTheme.colorScheme.scrim.copy(alpha = 0.20f),
                        0.62f to Color.Transparent,
                        1f to MaterialTheme.colorScheme.scrim.copy(alpha = 0.90f),
                    )
                )
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = Dimens.pagePadding, vertical = 14.dp),
        ) {
            item.seriesName?.takeIf { it.isNotBlank() && it != item.title }?.let { series ->
                Text(series, style = MonoLabelStyle, color = PlayerTokens.onMediaSecondary, maxLines = 1)
                Spacer(Modifier.height(4.dp))
            }
            Text(
                item.title,
                style = MaterialTheme.typography.displaySmall,
                color = PlayerTokens.onMedia,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.metaLine()?.let { meta ->
                Spacer(Modifier.height(6.dp))
                Text(meta, style = MonoLabelStyle, color = PlayerTokens.onMediaSecondary, maxLines = 1)
            }
        }
    }
}

/** 标题行元信息：评分 · 年份 · 时长 · 官方分级（有则拼；时长格式见 `03` §6.5）。 */
private fun MediaItemDto.metaLine(): String? {
    val parts = mutableListOf<String>()
    communityRating?.takeIf { it > 0f }?.let { parts += String.format(java.util.Locale.US, "★ %.1f", it) }
    productionYear?.let { parts += it.toString() }
    formatDuration(durationTicks)?.let { parts += it }
    officialRating?.takeIf { it.isNotBlank() }?.let { parts += it }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    // 外层 48dp 是**触控热区**（规范 §5），内层 40dp 才是视觉尺寸。
    // 外层比视觉大 4dp/边，配合调用处的 padding(12dp)，视觉左缘正好落在 16dp 页面基准线上。
    Box(
        modifier = modifier
            .size(Dimens.touchTarget)
            .clickable(onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(Dimens.iconButton)
                .clip(AppShape.control)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f)),
            contentAlignment = Alignment.Center,
        ) {
            BackIcon(tint = Color.White)
        }
    }
}

// ---- 分季剧集 ----

@Composable
private fun SeasonSection(
    seasons: List<SeasonGroup>,
    loadImage: suspend (MediaItemDto, ImageKind) -> ImageSource,
    onPlay: (MediaItemDto) -> Unit,
) {
    var selected by remember(seasons) { mutableStateOf(0) }
    val current = seasons.getOrNull(selected) ?: seasons.first()

    Column(Modifier.fillMaxWidth()) {
        SectionTitle("剧集")
        if (seasons.size > 1) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                seasons.forEachIndexed { index, group ->
                    AppChip(
                        text = seasonLabel(group),
                        selected = index == selected,
                        onClick = { selected = index },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // key 保证切季时每集的图片状态不串
            key(current.seasonNumber, current.episodes.size) {
                current.episodes.forEach { episode ->
                    EpisodeRow(
                        episode = episode,
                        thumb = rememberItemImage(episode, ImageKind.THUMB, loadImage),
                        onClick = { onPlay(episode) },
                    )
                }
            }
        }
    }
}

private fun seasonLabel(group: SeasonGroup): String =
    group.seasonNumber?.let { "第 $it 季" } ?: "其他"

@Composable
private fun EpisodeRow(episode: MediaItemDto, thumb: ImageSource, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.card.dp))
            .clickable(onClick = onClick)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(112.dp)
                .aspectRatio(16f / 9f)
                .clip(AppShape.control)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val request = rememberImageRequest(thumb)
            if (request != null) {
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            val fraction = episode.resumeFraction()
            if (fraction > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(fraction)
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    episode.episodeNumber?.let { "$it. " }.orEmpty() + episode.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (episode.userData.isPlayed) {
                    Spacer(Modifier.width(8.dp))
                    Text("已看", style = MonoLabelStyle, color = LocalAppColors.current.text3)
                }
            }
            episode.overview?.takeIf { it.isNotBlank() }?.let { text ->
                Spacer(Modifier.height(3.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalAppColors.current.text3,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---- 演职员 / 信息分区 / 删除 ----

/** 演职员头像网格（`03` §6.3）：48dp 圆头像 + 姓名 + 角色；无头像用姓名首字占位；点击进人物页。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CastAvatarGrid(
    people: List<CastMember>,
    loadPersonImage: suspend (String, ImageSize) -> ImageSource,
    onPersonClick: (CastMember) -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        people.take(MAX_CAST).forEach { person ->
            Column(
                Modifier.width(64.dp).clickable { onPersonClick(person) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AvatarBox(person, rememberPersonImage(person.id, loadPersonImage))
                Spacer(Modifier.height(4.dp))
                Text(
                    person.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                person.role?.takeIf { it.isNotBlank() }?.let { role ->
                    Text(
                        role,
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalAppColors.current.text3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** 圆头像：图片就绪则裁圆显示，否则姓名首字占位（surface-2 底）。 */
@Composable
private fun AvatarBox(person: CastMember, source: ImageSource) {
    val request = rememberImageRequest(source)
    Box(
        Modifier
            .size(48.dp)
            .clip(AppShape.circle)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = person.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                person.name.firstOrNull()?.toString() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CastFlow(cast: List<String>) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        cast.take(MAX_CAST).forEach { name ->
            Text(
                name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(AppShape.control)
                    .background(LocalAppColors.current.surface2)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

/**
 * 信息分区（`03` §6.3，取代旧「技术信息」折叠区）：文件 / 视频 / 音频 / 字幕，
 * **有内容才渲染**；全部为空时给一句弱化说明（保留排查线索）。
 */
@Composable
private fun InfoSections(item: MediaItemDto) {
    val sections = listOf(
        "文件信息" to item.fileInfoRows(),
        "视频信息" to item.videoInfoPairs(),
        "音频信息" to item.audioInfoPairs(),
        "字幕信息" to item.subtitleInfoPairs(),
    ).filter { it.second.isNotEmpty() }

    Column(Modifier.fillMaxWidth()) {
        if (sections.isEmpty()) {
            Text(
                "服务器未下发技术信息",
                style = MaterialTheme.typography.bodySmall,
                color = LocalAppColors.current.text3,
            )
        }
        sections.forEachIndexed { index, (title, rows) ->
            if (index > 0) Spacer(Modifier.height(Dimens.itemGap))
            SectionTitle(title)
            Spacer(Modifier.height(Dimens.itemGap))
            InfoCard(rows)
        }
    }
}

/**
 * 一组信息行 = 一张 12dp 卡片（与设置页 `SettingsGroup` 同一套语言）。
 *
 * 之前是「裸表格」：没有容器、行间也没有分割线，`37分12秒` 这种短行会和下一行糊在一起，
 * 而且和设置页的卡片列表长得完全是两种东西。现在卡片 + 1dp 发丝线（左缩进 16dp 对齐标签）。
 */
@Composable
private fun InfoCard(rows: List<Pair<String, String>>) {
    val app = LocalAppColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(AppShape.card)
            .background(MaterialTheme.colorScheme.surface)
            .border(Dimens.hairline, app.border, AppShape.card),
    ) {
        rows.forEachIndexed { index, (label, value) ->
            // ⚠️ 左右 16dp 内边距放在 LabelValueRow 内部（clickable 之后），不要包一层 Box：
            // 包了 Box 以后可点区域跟着被收窄 16dp，点击高亮只在中间一截，两侧留白（用户 2026-10-28 反馈）。
            LabelValueRow(label, value)
            if (index != rows.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = Dimens.pagePadding),
                    thickness = Dimens.hairline,
                    color = app.border,
                )
            }
        }
    }
}

/**
 * 一行「标签 / 值」（文件 · 视频 · 音频 · 字幕四类分区共用）。
 *
 * 排版口径（UI 优化方案 L5）：标签固定 [InfoLabelWidth] 起排，值**右对齐**贴齐内容区右边缘。
 * 原来值紧跟标签左对齐 —— 短标签（「容器」）后面空 40px，长值只能靠左堆，
 * 一屏里所有值起点一致、终点参差，扫读路径最长。
 *
 * 值默认单行省略（路径这类超长值不再把一行撑成三行）；**点击整行展开 / 收起全文**。
 * 只有真被截断的行才可点：`truncated` 只在收起态由 `onTextLayout` 更新，
 * 展开后保持 true，否则「展开 → 不再溢出 → 失去点击 → 收不回去」。
 *
 * 点击高亮必须**铺满整行**：左右内边距写在 `clickable` 之后，可点区域才会通到卡片两边；
 * 圆角交给外层 `InfoCard` 的 `clip(AppShape.card)` 统一裁，这里不再单独 clip，
 * 否则每行各自 8dp 圆角，高亮在卡片上下边缘会凹进去一块。
 */
@Composable
private fun LabelValueRow(label: String, value: String) {
    var expanded by remember(label, value) { mutableStateOf(false) }
    var truncated by remember(label, value) { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (truncated) Modifier.clickable { expanded = !expanded } else Modifier)
            // 12dp：一行 ≈ 42dp，接近 44dp 的列表行口径（原来 6dp 只有 30dp，挤成一团）
            .padding(horizontal = Dimens.pagePadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = LocalAppColors.current.text3,
            modifier = Modifier.width(InfoLabelWidth),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),   // 长路径（文件信息）自动换行，不再被挤出屏幕
            textAlign = if (expanded) TextAlign.Start else TextAlign.End,
            maxLines = if (expanded) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) truncated = it.hasVisualOverflow },
        )
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, modifier = modifier)
}

private const val MAX_CAST = 20

/** 详情页顶部渐变遮罩高度（覆盖状态栏下沿 + 返回键 + 一行标题）。 */
private val DetailTopScrimHeight = 72.dp

/**
 * 信息行的标签列宽：够放最长标签「文件大小 / 视频编码」（12sp × 4 字 ≈ 48dp）并留一档间距，
 * 值一律右对齐 ——— 标签列再窄就会换行，再宽则值列被挤。
 */
private val InfoLabelWidth = 76.dp
