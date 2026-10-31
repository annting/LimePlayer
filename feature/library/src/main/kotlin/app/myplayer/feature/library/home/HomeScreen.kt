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
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import app.myplayer.feature.library.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import app.myplayer.core.designsystem.components.LockIcon
import app.myplayer.core.designsystem.components.LockOpenIcon
import app.myplayer.core.designsystem.components.MoreVerticalIcon
import app.myplayer.core.designsystem.components.PlayIcon
import app.myplayer.core.designsystem.components.ServerIcon
import app.myplayer.core.designsystem.components.VideoIcon
import app.myplayer.core.designsystem.components.ShimmerBox
import app.myplayer.core.model.ImageKind
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.MediaItemDto
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade

/**
 * 首页（`docs/03` §6.0）—— **单 LazyColumn + 全 16:9 横滑卡片**（2026-10-24 重构）。
 *
 * ## 结构
 * ```
 * LazyColumn                      ← 整页唯一竖向滚动容器
 *   ├─ 继续观看        Row(16:9 卡片 · 播放键 · 进度条)
 *   ├─ 我的媒体库      Row(16:9 卡片 · 库名 + 影片数)          ← 新增模块
 *   ├─ 电影 / 电视剧 … Row(16:9 卡片 · 标题)   ← 由服务器库列表动态生成，带「查看全部 >」
 *   └─ 最近添加        Row(16:9 卡片 · 标题)
 * ```
 * 所有横滑行都是同一个组件 [StandardHorizontalCard]，差异全靠参数（进度条 / 播放键 / 副标题）。
 *
 * ## 为什么从网格改成列表
 * 竖屏海报网格和 16:9 横卡混排时，视觉重心会跳（一行矮一行高）；统一宽高比之后
 * 每块的节奏一致，且「最近添加」不再换行成 3 列，横向滑动的信息密度更高。
 *
 * ## 横向边界回弹
 * 每行都挂 [bounceOverscroll]（`BounceOverscroll.kt`），滑到头松手按 spring 阻尼弹回。
 *
 * ## 状态机（全部由 [HomeUiState] 推导，页面里不存业务状态）
 * ```
 * isLoading       → 骨架屏（各模块同尺寸 16:9 占位卡）
 * hasNoServer     → 空态 1：去添加服务器
 * error != null   → 空态 2：无法连接到服务器 + 重试
 * !hasContent     → 兜底空态：服务器里还没有内容
 * 其它            → PullToRefreshBox { LazyColumn }
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
    /**
     * 打开某个媒体库的详情页（「我的媒体库」卡片 / 各分类的「查看全部」都走这里）。
     * [smb] = 该库是 SMB 文件夹（详情页按视频文件渲染而不是海报）。
     */
    onOpenLibrary: (libraryId: String, title: String, smb: Boolean) -> Unit,
    /** 「首页内容已隐藏」空态的按钮：跳到设置 → 首页设置。 */
    onGoToHomeSettings: () -> Unit,
    /**
     * 卡片三点菜单 →「设为 / 取消隐私」（档二，2026-10-29）。
     * [key]：文件夹卡传库 id（`smb:<相对路径>`），视频卡传真实文件路径。
     * 仅 SMB/LOCAL 形态下卡片才会出三点（Emby 无档二）。
     */
    onToggleCardPrivate: (key: String, isFolder: Boolean) -> Unit = { _, _ -> },
    /**
     * 是否处于隐私模式。三点菜单（设为 / 取消隐私）与隐私标志**只在隐私模式显示**，
     * 普通模式不暴露任何隐私管理入口（2026-10-30 修复：此前只判 sourceType，导致普通模式也出三点）。
     */
    privacyActive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val canManagePrivate = privacyActive && state.sourceType == HomeSourceType.SMB
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

                // 首页设置把 4 个模块全关了：与其渲染一片空白，不如告诉用户去哪打开
                state.allModulesHidden -> EmptyHomeState(onGoToHomeSettings = onGoToHomeSettings)

                !state.hasContent -> HomeFullState(
                    title = if (state.onlyRecentlyAdded) "最近添加里没有影片" else "这里还没有内容",
                    description = if (state.onlyRecentlyAdded) {
                        "最近 ${state.settings.recentlyAddedDays} 天没有新入库的影片。" +
                            "可以到「首页设置」放宽天数，或下拉刷新。"
                    } else {
                        "服务器上还没有可展示的影片，添加片源后再下拉刷新。"
                    },
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
                        onOpenLibrary = onOpenLibrary,
                        canManagePrivate = canManagePrivate,
                        onToggleCardPrivate = onToggleCardPrivate,
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
    // 当前来源的类型（决定顶栏那枚小 logo）；未连接时为 null，不画
    val selected = servers.firstOrNull { it.id == selectedServerId }

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
                if (selected != null) {
                    ServerLogo(selected.sourceType, size = 22.dp)
                    Spacer(Modifier.width(Spacing.sm.dp))
                }
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
                // 带 logo 后文字区变窄：宽度从 228 加到 252，长服务器名不至于被截得太狠
                modifier = Modifier.width(252.dp),
                shape = AppShape.card,
                containerColor = scheme.surfaceContainerHigh,
                tonalElevation = 0.dp,
                shadowElevation = 12.dp,
                border = BorderStroke(Dimens.hairline, app.border),
            ) {
                servers.forEach { server ->
                    val isSelected = server.id == selectedServerId
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = server.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) scheme.primary else scheme.onSurface,
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
                        // 每台服务器前面挂它自己的类型 logo（Emby/Jellyfin/飞牛/SMB 各不相同）
                        leadingIcon = { ServerLogo(server.sourceType, size = 26.dp) },
                        onClick = {
                            expanded = false
                            if (!isSelected) onSelectServer(server.id)
                        },
                        trailingIcon = if (isSelected) {
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

        // 右上角只放切换器本身：观看历史入口按需求删掉了（页面一并移除）
        Spacer(Modifier.weight(1f))
    }
}

// ---- 内容区（单 LazyColumn） ----

@Composable
private fun HomeContent(
    state: HomeUiState,
    loadImage: suspend (MediaItemDto, ImageKind) -> ImageSource,
    onPlay: (MediaItemDto) -> Unit,
    onOpenDetail: (MediaItemDto) -> Unit,
    onOpenLibrary: (libraryId: String, title: String, smb: Boolean) -> Unit,
    /** SMB/LOCAL 形态才为 true：卡片右上角出三点菜单。 */
    canManagePrivate: Boolean,
    onToggleCardPrivate: (key: String, isFolder: Boolean) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // 底部 = 悬浮导航占掉的高度（含手势条）+ 呼吸留白：
        // 视口铺到屏幕底，最后一行停在胶囊上方，滚动时内容从半透明胶囊底下穿过。
        contentPadding = PaddingValues(
            top = Spacing.xs.dp,
            bottom = LocalBottomNavSpace.current,
        ),
        verticalArrangement = Arrangement.spacedBy(Dimens.sectionGap),
    ) {
        // 模块显隐 = **设置开关 AND 数据非空**（首页设置页实时生效，见 HomeViewModel 的 combine）
        if (state.settings.showContinueWatching && state.continueWatchingList.isNotEmpty()) {
            item(key = "continue-watching", contentType = "section") {
                HomeSection(title = "继续观看") {
                    ContinueWatchingRow(
                        items = state.continueWatchingList,
                        loadImage = loadImage,
                        onPlay = onPlay,
                        onOpenDetail = onOpenDetail,
                        canManagePrivate = canManagePrivate,
                        privateKeys = state.privateKeys,
                        onToggleCardPrivate = onToggleCardPrivate,
                    )
                }
            }
        }

        if (state.settings.showLibraryEntries && state.myLibrariesOrFolders.isNotEmpty()) {
            item(key = "my-libraries", contentType = "section") {
                val smb = state.sourceType == HomeSourceType.SMB
                HomeSection(title = if (smb) "我的文件夹" else "我的媒体库") {
                    MyLibrariesRow(
                        libraries = state.myLibrariesOrFolders,
                        folderMode = smb,
                        loadImage = loadImage,
                        onOpenLibrary = onOpenLibrary,
                        canManagePrivate = canManagePrivate,
                        privateKeys = state.privateKeys,
                        onToggleCardPrivate = onToggleCardPrivate,
                    )
                }
            }
        }

        if (state.settings.showDynamicSections) state.dynamicSections.forEach { library ->
            item(key = "library-${library.id}", contentType = "section") {
                HomeSection(
                    title = library.name,
                    onSeeAll = { onOpenLibrary(library.id, library.name, state.sourceType == HomeSourceType.SMB) },
                ) {
                    LibraryItemsRow(
                        items = library.items,
                        loadImage = loadImage,
                        onOpenDetail = onOpenDetail,
                        canManagePrivate = canManagePrivate,
                        privateKeys = state.privateKeys,
                        onToggleCardPrivate = onToggleCardPrivate,
                    )
                }
            }
        }

        if (state.settings.showRecentlyAdded && state.recentlyAddedList.isNotEmpty()) {
            item(key = "recently-added", contentType = "section") {
                HomeSection(title = "最近添加") {
                    RecentlyAddedRow(
                        items = state.recentlyAddedList,
                        loadImage = loadImage,
                        onOpenDetail = onOpenDetail,
                        canManagePrivate = canManagePrivate,
                        privateKeys = state.privateKeys,
                        onToggleCardPrivate = onToggleCardPrivate,
                    )
                }
            }
        }
    }
}

/** 一个模块：标题行（可选「查看全部 >」）+ 一行横滑。 */
@Composable
private fun HomeSection(
    title: String,
    onSeeAll: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val app = LocalAppColors.current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.pagePadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onSeeAll != null) {
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
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/**
 * 一行横滑（带边界回弹）。
 *
 * 整行铺满窗口宽度、只靠 `contentPadding` 让首尾卡片离边 16dp ——
 * 这样卡片能滑到屏幕边缘之外，视觉上才是「无限滑」而不是「框在页面里」。
 */
@Composable
private fun BounceRow(
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val bounce = rememberBounceOverscrollState()
    LazyRow(
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            .bounceOverscroll(bounce),
        contentPadding = PaddingValues(horizontal = Dimens.pagePadding),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md.dp),
        content = content,
    )
}

// ---- 通用 16:9 横卡 ----

/**
 * 首页所有模块共用的 16:9 横卡。
 *
 * 差异全部由参数表达，避免每个模块各写一套 Box + 渐变 + 文字：
 * | 模块 | progress | onPlay | subtitle |
 * |---|---|---|---|
 * | 继续观看 | 0..1 | 有 | 「剩余 23 分钟」 |
 * | 我的媒体库 | null | null | 「486 部影片」 |
 * | 库分类 / 最近添加 | null | null | null |
 *
 * @param progress 非 null 时在**卡片内侧**画进度条（左右 12dp、底部 8dp 内缩）。
 *   旧实现把进度条贴在卡片最底边，被 12dp 圆角切掉两头（用户报的 bug），内缩后完整显示。
 */
@Composable
fun StandardHorizontalCard(
    title: String,
    image: ImageSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = CardWidth,
    subtitle: String? = null,
    progress: Float? = null,
    onPlay: (() -> Unit)? = null,
    /** 非空时用「深灰底 + 居中图标」代替封面（SMB 文件夹卡）。 */
    centeredIcon: (@Composable () -> Unit)? = null,
    /**
     * 档二（2026-10-29）：非 null = 右上角出三点菜单，点开「设为 / 取消隐私」；
     * [isPrivate] 同时决定左上角的标志与菜单文案。Emby 卡传 null（无档二）。
     */
    onTogglePrivate: (() -> Unit)? = null,
    isPrivate: Boolean = false,
    /** 视频卡（非文件夹、无封面）：无预览图时用「渐变底 + 视频图标」占位，而非首字（2026-10-30）。 */
    videoPlaceholder: Boolean = false,
) {
    val app = LocalAppColors.current
    val scheme = MaterialTheme.colorScheme

    Box(
        modifier = modifier
            .width(width)
            .aspectRatio(CardAspect)
            .clip(AppShape.card)
            .background(app.surface2)
            .clickable(onClick = onClick),
    ) {
        if (centeredIcon != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(listOf(app.surface2, app.surface3))),
                contentAlignment = Alignment.Center,
            ) { centeredIcon() }
        } else if (videoPlaceholder && image is ImageSource.None) {
            // 视频卡无预览图（AVI 等抽不出帧）：渐变底 + 居中视频图标（无首字）
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(listOf(app.surface2, app.surface3))),
                contentAlignment = Alignment.Center,
            ) {
                VideoIcon(tint = app.text3.copy(alpha = 0.6f), modifier = Modifier.size(44.dp))
            }
        } else {
            HomeImage(source = image, title = title, modifier = Modifier.fillMaxSize())
        }

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
        if (onPlay != null) {
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
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(
                    start = Spacing.md.dp,
                    end = Spacing.md.dp,
                    // 有进度条时把文字抬到条子上方（8dp 底距 + 3dp 条 + 7dp 间隔）
                    bottom = if (progress != null) 18.dp else Spacing.md.dp,
                ),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = PlayerTokens.onMedia,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = PlayerTokens.onMediaSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (progress != null) {
            // 进度条：**内缩**在卡片里，不再贴边 —— 贴边会被 12dp 圆角裁掉两头
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(PlayerTokens.track),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .background(scheme.primary)
                )
            }
        }

        // 档二：左上角隐私标志（半透明黑底小胶囊，白描边锁）
        if (isPrivate) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(AppShape.control)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LockIcon(tint = PlayerTokens.onMedia, modifier = Modifier.size(12.dp))
            }
        }

        // 档二：右上角三点菜单（点开只有一项：设为 / 取消隐私）
        if (onTogglePrivate != null) {
            var menuOpen by remember { mutableStateOf(false) }
            Box(modifier = Modifier.align(Alignment.TopEnd)) {
                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .size(30.dp)
                        .clip(AppShape.circle)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable { menuOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    MoreVerticalIcon(tint = PlayerTokens.onMedia, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(if (isPrivate) "取消隐私" else "设为隐私") },
                        leadingIcon = {
                            if (isPrivate) {
                                LockOpenIcon(tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                            } else {
                                LockIcon(tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                            }
                        },
                        onClick = {
                            menuOpen = false
                            onTogglePrivate()
                        },
                    )
                }
            }
        }
    }
}

// ---- 模块一：继续观看 ----

@Composable
private fun ContinueWatchingRow(
    items: List<MediaItemDto>,
    loadImage: suspend (MediaItemDto, ImageKind) -> ImageSource,
    onPlay: (MediaItemDto) -> Unit,
    onOpenDetail: (MediaItemDto) -> Unit,
    canManagePrivate: Boolean,
    privateKeys: Set<String>,
    onToggleCardPrivate: (key: String, isFolder: Boolean) -> Unit,
) {
    // 卡宽按屏宽算一次，整行统一（骨架屏与真卡同宽，数据到位不跳动）
    val cardW = heroCardWidth(items.size)
    val listState = rememberLazyListState()
    // 数据更新（新续播排到最前）→ 滚回最左：否则第一个条目在视口外，看得见列表却看不见它
    //（2026-10-31 用户反馈：播放返回后手动刷新，新条目在第一个但横滑位置没回来）
    val firstId = items.firstOrNull()?.id
    LaunchedEffect(firstId) {
        if (firstId != null) listState.animateScrollToItem(0)
    }
    BounceRow(state = listState) {
        items(items = items, key = { it.id }) { item ->
            val manageKey = item.path ?: item.id
            StandardHorizontalCard(
                title = item.continueTitle(),
                subtitle = item.remainingWatchText(),
                image = rememberHomeImage(item.id, ImageKind.THUMB) {
                    loadImage(item, ImageKind.THUMB)
                },
                // 只在「看了一部分」时传非 null：0f 会画出空轨道并白占文字位
                progress = item.progressFraction().takeIf { it > 0f && it < 1f },
                onPlay = { onPlay(item) },
                onClick = { onOpenDetail(item) },
                width = cardW,
                onTogglePrivate = if (canManagePrivate) {
                    { onToggleCardPrivate(manageKey, false) }
                } else {
                    null
                },
                isPrivate = canManagePrivate && manageKey in privateKeys,
                videoPlaceholder = true,
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

// ---- 模块二：我的媒体库 ----

/**
 * 模块二：Emby = 库封面卡；SMB = 深灰底 + 文件夹图标。
 *
 * 样式判据是 [folderMode] 或卡片自身的 [HomeLibraryCard.isFolder] —— 后者让「同一份数据
 * 换个渲染」也能成立（例如将来 SMB 与网盘混排）。
 */
@Composable
private fun MyLibrariesRow(
    libraries: List<HomeLibraryCard>,
    folderMode: Boolean,
    loadImage: suspend (MediaItemDto, ImageKind) -> ImageSource,
    onOpenLibrary: (libraryId: String, title: String, smb: Boolean) -> Unit,
    canManagePrivate: Boolean,
    privateKeys: Set<String>,
    onToggleCardPrivate: (key: String, isFolder: Boolean) -> Unit,
) {
    val app = LocalAppColors.current
    val cardW = heroCardWidth(libraries.size)
    BounceRow {
        items(items = libraries, key = { "mylib-${it.id}" }) { library ->
            val asFolder = folderMode || library.isFolder
            // 文件夹卡的可标隐私 key = 库 id 剥掉 `smb:` 前缀后的相对路径；
            // 非文件夹卡（Emby 库）没有档二，不出菜单
            val manageKey = library.id.removePrefix("smb:")
            val manage = canManagePrivate && asFolder
            StandardHorizontalCard(
                title = library.name,
                subtitle = if (asFolder) {
                    "${library.itemCount} 个视频"
                } else {
                    "${library.itemCount} 部影片"
                },
                image = if (asFolder) {
                    ImageSource.None
                } else {
                    library.cover?.let { cover ->
                        rememberHomeImage(cover.id, ImageKind.THUMB) {
                            loadImage(cover, ImageKind.THUMB)
                        }
                    } ?: ImageSource.None
                },
                // 文件夹卡：渐变底 + 居中图标（与视频无图占位同一套底色/图标尺寸，不加载封面）
                centeredIcon = if (asFolder) {
                    { FolderIcon(tint = app.text3.copy(alpha = 0.6f), modifier = Modifier.size(44.dp)) }
                } else {
                    null
                },
                onClick = { onOpenLibrary(library.id, library.name, folderMode || library.isFolder) },
                width = cardW,
                onTogglePrivate = manage.takeIf { it }?.let { { onToggleCardPrivate(manageKey, true) } },
                isPrivate = manage && manageKey in privateKeys,
            )
        }
    }
}

// ---- 模块三：动态库分类 ----

@Composable
private fun LibraryItemsRow(
    items: List<MediaItemDto>,
    loadImage: suspend (MediaItemDto, ImageKind) -> ImageSource,
    onOpenDetail: (MediaItemDto) -> Unit,
    canManagePrivate: Boolean,
    privateKeys: Set<String>,
    onToggleCardPrivate: (key: String, isFolder: Boolean) -> Unit,
) {
    val cardW = heroCardWidth(items.size)
    BounceRow {
        items(items = items, key = { it.id }) { item ->
            val manageKey = item.path ?: item.id
            StandardHorizontalCard(
                title = item.title,
                // 续播进度条（2026-10-31）：`StandardHorizontalCard` 早就支持 progress，
                // 动态库这处调用漏传 → 看过的片子在这一行里永远没有进度条。
                // 只在「看了一部分」时传非 null：0 会画出空轨道（一条灰线）并白占文字位。
                progress = item.progressFraction().takeIf { it > 0f && it < 1f },
                image = rememberHomeImage(item.id, ImageKind.THUMB) {
                    loadImage(item, ImageKind.THUMB)
                },
                onClick = { onOpenDetail(item) },
                width = cardW,
                onTogglePrivate = if (canManagePrivate) {
                    { onToggleCardPrivate(manageKey, false) }
                } else {
                    null
                },
                isPrivate = canManagePrivate && manageKey in privateKeys,
                videoPlaceholder = true,
            )
        }
    }
}

// ---- 模块四：最近添加 ----

@Composable
private fun RecentlyAddedRow(
    items: List<MediaItemDto>,
    loadImage: suspend (MediaItemDto, ImageKind) -> ImageSource,
    onOpenDetail: (MediaItemDto) -> Unit,
    canManagePrivate: Boolean,
    privateKeys: Set<String>,
    onToggleCardPrivate: (key: String, isFolder: Boolean) -> Unit,
) {
    val cardW = heroCardWidth(items.size)
    BounceRow {
        items(items = items, key = { "recent-${it.id}" }) { item ->
            val manageKey = item.path ?: item.id
            StandardHorizontalCard(
                title = item.title,
                // 同上（动态库）：最近添加的卡片也要看得到续播进度
                progress = item.progressFraction().takeIf { it > 0f && it < 1f },
                image = rememberHomeImage(item.id, ImageKind.THUMB) {
                    loadImage(item, ImageKind.THUMB)
                },
                onClick = { onOpenDetail(item) },
                width = cardW,
                onTogglePrivate = if (canManagePrivate) {
                    { onToggleCardPrivate(manageKey, false) }
                } else {
                    null
                },
                isPrivate = canManagePrivate && manageKey in privateKeys,
                videoPlaceholder = true,
            )
        }
    }
}

// ---- 骨架屏 / 空态 ----

/** 首屏骨架：4 个模块 × （标题条 + 3 张 16:9 占位卡），尺寸与真卡片一致，数据到位不跳动。 */
@Composable
private fun HomeSkeleton() {
    // 骨架屏固定每行 3 张，所以永远按「多卡」口径取宽度（不能走单卡满宽分支，否则数据到位会跳一下）
    val cardW = heroCardWidth(3)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = Spacing.xs.dp,
            bottom = LocalBottomNavSpace.current,
        ),
        verticalArrangement = Arrangement.spacedBy(Dimens.sectionGap),
    ) {
        items(count = 4) {
            Column {
                ShimmerBox(
                    Modifier
                        .padding(start = Dimens.pagePadding)
                        .width(96.dp)
                        .height(20.dp),
                    cornerRadius = 4.dp,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.padding(start = Dimens.pagePadding),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md.dp),
                ) {
                    repeat(3) {
                        ShimmerBox(
                            Modifier.width(cardW).height(cardW * 9f / 16f),
                            cornerRadius = 12.dp,
                        )
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

/**
 * 首页「全关」空态（2026-10-24 用户定稿）：首页设置把 4 个模块全关时替代空白页。
 *
 * 与其它三个空态（[HomeFullState] → 统一 `EmptyState`）**有意不同**：
 * - 图标用 **App 桌面图标**（`ic_app_launcher`，合成好的完整 launcher 图标，自带青柠绿主色），76dp；
 * - 整体在「去掉底部悬浮导航一半高度」后的剩余区域里居中 —— 几何居中会被
 *   底部 105dp 的导航区压得视觉偏下，这里按半高补偿（问题一的根因）；
 * - 主按钮走 Material 3 `Button`（青柠绿），与详情页筛选弹层的按钮同一款。
 */
@Composable
private fun EmptyHomeState(
    onGoToHomeSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalAppColors.current
    val scheme = MaterialTheme.colorScheme

    // 再上移屏高的 5%（2026-10-24 用户二次微调）
    val extraLift = LocalConfiguration.current.screenHeightDp.dp * 0.05f

    Box(
        modifier = modifier
            .fillMaxSize()
            // 视觉重心上移：底部悬浮导航占掉 ≈105dp，几何居中会显得偏下；
            // 按「导航区半高」抬升内容（问题一，2026-10-24 用户反馈）
            .padding(bottom = LocalBottomNavSpace.current / 2 + extraLift),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_app_launcher),
                contentDescription = null,
                modifier = Modifier.size(76.dp),
            )
            Text(
                text = "首页内容已隐藏",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 20.dp),
            )
            Text(
                text = "你已关闭了首页的所有展示模块",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = app.text3,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
            Button(
                onClick = onGoToHomeSettings,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = scheme.primary),
                modifier = Modifier.padding(top = 24.dp).heightIn(min = 44.dp),
            ) {
                Text(text = "去设置开启", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** 空态单独预览：直接看 Logo、文案与按钮（不经 VM）。 */
@Preview(name = "首页 · 全关空态", showBackground = true, backgroundColor = 0xFF0B0D11, heightDp = 800)
@Composable
private fun EmptyHomeStatePreview() {
    MyPlayerTheme(darkTheme = true) {
        EmptyHomeState(onGoToHomeSettings = {})
    }
}

/** 文件夹图标（自绘实心轮廓，SMB 文件夹卡专用）。 */
@Composable
private fun FolderIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.10f, h * 0.28f)
            lineTo(w * 0.42f, h * 0.28f)
            lineTo(w * 0.50f, h * 0.38f)
            lineTo(w * 0.90f, h * 0.38f)
            lineTo(w * 0.90f, h * 0.76f)
            lineTo(w * 0.10f, h * 0.76f)
            close()
        }
        drawPath(path, color = tint)
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

// ---- 尺寸 ----

/**
 * 首页横卡的**兜底**宽度：只在没有窗口环境时用到（正常路径走 [heroCardWidth]）。
 *
 * 固定 240dp 的老做法在 1080px 屏上只占 51%，右侧空出 40%、每行只露 1.2 张，
 * 「还能横滑」的暗示几乎消失（UI 优化方案 L1）。
 */
private val CardWidth = 240.dp

/** 横卡宽度 = 屏宽 × 该比例：1080px(411dp) 屏约 300dp，右侧露出下一张的 ~20%。 */
private const val HERO_CARD_WIDTH_RATIO = 0.73f

/** 横卡宽度上下限：太窄回到「一屏 1.2 张」，太宽（平板）一张就顶满。 */
private val HeroCardMinWidth = 220.dp
private val HeroCardMaxWidth = 340.dp

/** 16:9。 */
private const val CardAspect = 16f / 9f

/**
 * 按当前窗口宽度算横卡宽度（读 [LocalConfiguration]，旋转 / 分屏后会跟着变）。
 *
 * 统一固定 0.73 倍宽（上下限夹住）：不管一行有几张都同宽，避免「单卡铺满整行、
 * 多卡缩小」的尺寸跳动（2026-10-31 用户反馈页面显得混乱）。单卡时右侧留白是刻意取舍。
 */
@Composable
private fun heroCardWidth(itemCount: Int): Dp {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    return (screenWidth * HERO_CARD_WIDTH_RATIO).coerceIn(HeroCardMinWidth, HeroCardMaxWidth)
}

// ---- @Preview ----

private fun previewState(
    error: String? = null,
    noServer: Boolean = false,
    smb: Boolean = false,
): HomeUiState {
    if (noServer) {
        return HomeUiState(serverName = null, servers = emptyList(), error = error)
    }
    return if (smb) {
        // SMB 形态：空文件夹（Downloads / Music）已经在 toFolderCards/toFolderSections 里被过滤
        HomeUiState(
            sourceType = HomeSourceType.SMB,
            serverName = MockHomeData.SMB_NAME,
            servers = MockHomeData.servers,
            selectedServerId = MockHomeData.SMB_ID,
            continueWatchingList = MockHomeData.smbContinueWatching,
            myLibrariesOrFolders = MockHomeData.smbFolders.toFolderCards(),
            dynamicSections = MockHomeData.smbFolders.toFolderSections(),
            recentlyAddedList = MockHomeData.smbRecentlyAdded,
            error = error,
        )
    } else {
        HomeUiState(
            sourceType = HomeSourceType.EMBY,
            serverName = MockHomeData.EMBY_NAME,
            servers = MockHomeData.servers,
            selectedServerId = MockHomeData.EMBY_ID,
            continueWatchingList = MockHomeData.continueWatching,
            myLibrariesOrFolders = MockHomeData.libraryCards,
            dynamicSections = MockHomeData.libraries,
            recentlyAddedList = MockHomeData.recentlyAdded,
            error = error,
        )
    }
}

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
                onOpenLibrary = { _, _, _ -> },
                onGoToHomeSettings = {},
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

/** 骨架屏：所有模块都是 16:9 占位卡。 */
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

/** SMB 形态：模块二叫「我的文件夹」，卡片是深灰底 + 文件夹图标，空文件夹已被过滤。 */
@Preview(name = "首页 · SMB 模式", showBackground = true, backgroundColor = 0xFF0B0D11, heightDp = 900)
@Composable
private fun HomeScreenSmbPreview() {
    HomePreviewContent(state = previewState(smb = true), darkTheme = true)
}

/** 空态 2：连不上服务器。 */
@Preview(name = "首页 · 连接失败", showBackground = true, backgroundColor = 0xFF0B0D11, heightDp = 900)
@Composable
private fun HomeOfflinePreview() {
    HomePreviewContent(state = previewState(error = "连接超时：192.168.31.15:8096 无响应"), darkTheme = true)
}
