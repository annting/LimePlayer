package app.myplayer.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.designsystem.MonoLabelStyle
import app.myplayer.core.designsystem.components.AppButton
import app.myplayer.core.designsystem.components.AppButtonStyle
import app.myplayer.core.designsystem.components.AppChip
import app.myplayer.core.designsystem.components.BackIcon
import app.myplayer.core.designsystem.components.EmptyState
import app.myplayer.core.designsystem.components.PlayIcon
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
 * 海报背景 + 标题行 → 主按钮 + 次级行（标记已播放）→ 分季剧集列表 → 简介 → 演职员 → 信息分区 → 删除。
 *
 * - **无折叠**：简介与信息分区直接展示（页面可滚动）。
 * - 次级「标记已播放」仅在 `canMarkPlayed == true` 时渲染；「删除」仅在 `item.canDelete == true` 时渲染。
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
                item = state.item,
                seasons = state.seasons,
                loadImage = loadImage,
                loadPersonImage = loadPersonImage,
                canMarkPlayed = canMarkPlayed,
                onPlay = onPlay,
                onMarkPlayed = onMarkPlayed,
                onDelete = onDelete,
                onPersonClick = onPersonClick,
            )
        }
        // 返回键常驻在 hero 之上（加载 / 错误态也要能返回）
        BackButton(onBack = onBack, modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
    }
}

@Composable
private fun DetailContent(
    item: MediaItemDto,
    seasons: List<SeasonGroup>,
    loadImage: suspend (MediaItemDto, ImageKind) -> ImageSource,
    loadPersonImage: suspend (String, ImageSize) -> ImageSource,
    canMarkPlayed: Boolean,
    onPlay: (MediaItemDto) -> Unit,
    onMarkPlayed: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onPersonClick: (CastMember) -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
            if (canMarkPlayed) {
                Spacer(Modifier.height(8.dp))
                AppButton(
                    text = markPlayedLabel(item),
                    onClick = { onMarkPlayed(!item.userData.isPlayed) },
                    style = AppButtonStyle.Outlined,
                    modifier = Modifier.fillMaxWidth(),
                )
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
                Spacer(Modifier.height(10.dp))
                CastAvatarGrid(item.people, loadPersonImage, onPersonClick)
            } else if (item.cast.isNotEmpty()) {
                Spacer(Modifier.height(Dimens.sectionGap))
                SectionTitle("演职员")
                Spacer(Modifier.height(8.dp))
                CastFlow(item.cast)
            }

            Spacer(Modifier.height(Dimens.sectionGap))
            InfoSections(item)

            if (item.canDelete) {
                Spacer(Modifier.height(28.dp))
                DangerTextButton("删除该条目") { showDeleteDialog = true }
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除条目") },
            text = { Text("确定要从服务器永久删除「${item.title}」吗？此操作不可恢复。") },
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
 * 续播行（`03` §6.3）：全宽进度条 + 「剩余 1小时25分钟」。
 * 颜色沿用播放页进度条的主色，轨道用弱化色，避免在详情页里过于抢眼。
 */
@Composable
private fun ResumeRow(fraction: Float, remaining: String) {
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
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
        Spacer(Modifier.height(6.dp))
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
    Box(
        Modifier
            .fillMaxWidth()
            .height(300.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val request = rememberImageRequest(poster)
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
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
    Box(
        modifier = modifier
            .size(Dimens.iconButton)
            .clip(AppShape.control)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f))
            .clickable(onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        BackIcon(tint = Color.White)
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
            Spacer(Modifier.height(10.dp))
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
    val fileRows = item.fileInfoRows()
    val videoRows = item.videoInfoPairs()
    val audioRows = item.audioInfoPairs()
    val subtitleRows = item.subtitleInfoPairs()

    Column(Modifier.fillMaxWidth()) {
        if (fileRows.isNotEmpty()) {
            SectionTitle("文件信息")
            Spacer(Modifier.height(8.dp))
            fileRows.forEach { (label, value) -> LabelValueRow(label, value) }
            Spacer(Modifier.height(12.dp))
        }
        if (videoRows.isNotEmpty()) {
            SectionTitle("视频信息")
            Spacer(Modifier.height(8.dp))
            videoRows.forEach { (label, value) -> LabelValueRow(label, value) }
            Spacer(Modifier.height(12.dp))
        }
        if (audioRows.isNotEmpty()) {
            SectionTitle("音频信息")
            Spacer(Modifier.height(8.dp))
            audioRows.forEach { (label, value) -> LabelValueRow(label, value) }
            Spacer(Modifier.height(12.dp))
        }
        if (subtitleRows.isNotEmpty()) {
            SectionTitle("字幕信息")
            Spacer(Modifier.height(8.dp))
            subtitleRows.forEach { (label, value) -> LabelValueRow(label, value) }
            Spacer(Modifier.height(12.dp))
        }
        if (fileRows.isEmpty() && videoRows.isEmpty() && audioRows.isEmpty() && subtitleRows.isEmpty()) {
            Text(
                "服务器未下发技术信息",
                style = MaterialTheme.typography.bodySmall,
                color = LocalAppColors.current.text3,
            )
        }
    }
}

@Composable
private fun LabelValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = LocalAppColors.current.text3,
            modifier = Modifier.width(72.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),   // 长路径（文件信息）自动换行，不再被挤出屏幕
        )
    }
}

/** 危险色文字按钮（删除；`03` §6.3）。 */
@Composable
private fun DangerTextButton(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShape.control)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, modifier = modifier)
}

private const val MAX_CAST = 20
