package app.myplayer.nav

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.myplayer.core.datastore.ServerConfigStore
import app.myplayer.core.datastore.TokenStore
import app.myplayer.core.designsystem.components.EmptyState
import app.myplayer.LocalThumbnailProvider
import app.myplayer.core.model.ClientCapabilities
import app.myplayer.core.model.ImageKind
import app.myplayer.core.model.ImageSize
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.LibraryRepository
import app.myplayer.core.model.MediaSource
import app.myplayer.core.model.SourceType
import app.myplayer.feature.detail.DetailRoute
import app.myplayer.feature.detail.PersonRoute
import app.myplayer.feature.library.LibraryScreen
import app.myplayer.feature.library.LibraryViewModel
import app.myplayer.feature.player.PlayerRoute
import app.myplayer.feature.server.AddServerScreen
import app.myplayer.feature.server.AddServerViewModel
import app.myplayer.feature.server.LoginScreen
import app.myplayer.feature.server.LoginViewModel
import app.myplayer.feature.server.ProbeStatus
import app.myplayer.feature.server.defaultPort
import app.myplayer.feature.server.fnosLogin
import app.myplayer.feature.server.normalizeBaseUrl
import app.myplayer.feature.server.probeFnos
import app.myplayer.feature.server.probeServer
import app.myplayer.feature.server.sourceDisplayName
import app.myplayer.source.httpapi.AuthResultDto
import app.myplayer.source.httpapi.HttpMediaApi
import app.myplayer.source.httpapi.SourceConfig
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.UUID

/** 选源结果在两页之间传递（避免把状态编码进路由参数）。 */
private data class PendingServer(
    val serverId: String,
    val sourceType: SourceType,
    val baseUrl: String,
)

/**
 * 只做「路由 + 屏幕装配」，不含业务逻辑。
 *
 * - [serverId] 为 null → 落到引导页；非 null → 直接进库页。
 * - [source] 由 `SourceFactory` 提供；为 null 时库页只读 Room（图/数据都还是空的），不崩。
 */
@Composable
fun AppNavHost(
    repository: LibraryRepository,
    serverConfigStore: ServerConfigStore,
    tokenStore: TokenStore,
    settingsStore: app.myplayer.core.datastore.SettingsStore,
    client: OkHttpClient,
    clientCapabilities: ClientCapabilities,
    engineHolder: app.myplayer.playback.PlaybackEngineHolder,
    appUnlocked: kotlinx.coroutines.flow.StateFlow<Boolean>,
    keepBackgroundPlayback: Boolean,
    autoPlayNext: Boolean,
    localThumbnails: Boolean,
    serverId: String?,
    source: MediaSource?,
    /** 播放页要占满整个窗口（不避让系统栏），由 MainActivity 按此状态去掉根部的 insets 内边距。 */
    onFullscreenChange: (Boolean) -> Unit = {},
) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<PendingServer?>(null) }

    // 播放页是唯一需要「贴满窗口」的页面：横竖屏切换靠整体旋转播放器内容实现，
    // 舞台必须能拿到完整窗口尺寸，否则会被 safeDrawing 的内边距挤成小方块（2026-10-20 真机反馈）。
    val backStackEntry by nav.currentBackStackEntryAsState()
    val fullscreen = backStackEntry?.destination?.route?.startsWith(Routes.PLAYER) == true
    LaunchedEffect(fullscreen) { onFullscreenChange(fullscreen) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { onFullscreenChange(false) }
    }

    NavHost(nav, startDestination = if (serverId == null) Routes.WELCOME else Routes.MAIN) {

        composable(Routes.WELCOME) {
            NoServerScreen(onAddServer = { nav.navigate(Routes.ADD_SERVER) })
        }

        composable(Routes.ADD_SERVER) {
            val vm = remember {
                AddServerViewModel(
                    probe = { type, base ->
                        // 飞牛影视没有 /System/Info/Public，走它自己的免签名版本端点（`docs/05` §6.9）
                        if (type == SourceType.FNOS) probeFnos(client, base) else probeServer(client, base, "")
                    }
                )
            }
            val state by vm.state.collectAsStateWithLifecycle()
            AddServerScreen(
                state = state,
                onSelectSource = vm::onSelectSource,
                onAddressChange = vm::onAddressChange,
                onPortChange = vm::onPortChange,
                onTest = { scope.launch { vm.onTest() } },
                onProceed = {
                    val s = state
                    if (s.probeStatus is ProbeStatus.Reachable) {
                        pending = PendingServer(
                            serverId = UUID.randomUUID().toString(),
                            sourceType = s.sourceType,
                            baseUrl = normalizeBaseUrl(
                                s.address,
                                s.port.toIntOrNull() ?: defaultPort(s.sourceType),
                            ),
                        )
                        nav.navigate(Routes.LOGIN)
                    }
                },
                onBack = { nav.popBackStack() },
            )
        }

        composable(Routes.LOGIN) {
            val target = pending
            if (target == null) {
                // 异常路径（例如深链直达）：退回引导页，不显示空白页
                LaunchedEffect(Unit) { nav.popBackStack(Routes.WELCOME, inclusive = false) }
            } else {
                val api = remember(target) {
                    HttpMediaApi(
                        client = client,
                        config = SourceConfig(serverId = target.serverId, baseUrl = target.baseUrl),
                    )
                }
                // 登录实现按源分派：飞牛影视用自己的 /v/api/v1/login（返回 token，无 userId 概念）
                val loginCall: suspend (String, String) -> AuthResultDto = remember(target) {
                    if (target.sourceType == SourceType.FNOS) {
                        { username, password -> fnosLogin(target.baseUrl, username, password) }
                    } else {
                        api::authenticateByName
                    }
                }
                val loginVm = remember(target) {
                    LoginViewModel(
                        serverId = target.serverId,
                        sourceType = target.sourceType,
                        baseUrl = target.baseUrl,
                        login = loginCall,
                        configStore = serverConfigStore,
                        tokenStore = tokenStore,
                        settingsStore = settingsStore,
                    )
                }
                val loginState by loginVm.state.collectAsStateWithLifecycle()
                LoginScreen(
                    state = loginState,
                    onUsernameChange = loginVm::onUsernameChange,
                    onPasswordChange = loginVm::onPasswordChange,
                    onSubmit = { scope.launch { loginVm.submit() } },
                    onLoggedIn = {
                        // 登录已把 ServerConfig/Token 落盘 → 回到根级，MainActivity 会重新造出 source
                        nav.navigate(Routes.MAIN) { popUpTo(0) }
                    },
                    serverLabel = target.baseUrl.removePrefix("http://").removePrefix("https://"),
                    sourceName = sourceDisplayName(target.sourceType),
                    onBack = { nav.popBackStack() },
                )
            }
        }

        composable(Routes.MAIN) {
            val sid = serverId
            if (sid == null) {
                // 配置尚未就绪（登录落盘与重组的短暂过渡态）：避免 serverId!! 崩溃
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                MainScaffold(
                    repository = repository,
                    serverConfigStore = serverConfigStore,
                    source = source,
                    serverId = sid,
                    caps = clientCapabilities,
                    localThumbnails = localThumbnails,
                    settingsStore = settingsStore,
                    tokenStore = tokenStore,
                    onAddServer = { nav.navigate(Routes.ADD_SERVER) },
                    onPlay = { itemId -> nav.navigate("${Routes.PLAYER}/$itemId") },
                    onOpenDetail = { itemId -> nav.navigate("${Routes.DETAIL}/$itemId") },
                )
            }
        }

        composable(
            route = "${Routes.DETAIL}/{itemId}",
            arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
        ) { entry ->
            val itemId = entry.arguments?.getString("itemId").orEmpty()
            val src = source
            if (src != null && itemId.isNotEmpty()) {
                val context = androidx.compose.ui.platform.LocalContext.current
                // 缺海报时的本地抽帧兜底（任务 3-7）：与库页同一策略
                val thumbs = remember(src, serverId, localThumbnails) {
                    if (localThumbnails) LocalThumbnailProvider(context, src, clientCapabilities) else null
                }
                DetailRoute(
                    itemId = itemId,
                    source = src,
                    loadImage = { item, kind ->
                        val remote = src.image(item, kind, ImageSize.MEDIUM)
                        if (kind == ImageKind.POSTER && remote is ImageSource.None &&
                            !item.hasPoster && thumbs != null
                        ) {
                            thumbs.image(item)
                        } else {
                            remote
                        }
                    },
                    onBack = { nav.popBackStack() },
                    onPlay = { item -> nav.navigate("${Routes.PLAYER}/${item.id}") },
                    // 标记已看成功 → 服务端权威 UserData 写回本地缓存（`02` §5.1）
                    onUserDataChanged = { sid, iid, ud -> repository.updateUserData(sid, iid, ud) },
                    // 服务端删除成功 → 清理本地缓存并返回上一页（`03` §6.3）
                    onDeleted = { item ->
                        scope.launch {
                            repository.deleteItem(item.serverId, item.id)
                            nav.popBackStack()
                        }
                    },
                    onPersonClick = { person ->
                        val name = android.net.Uri.encode(person.name)
                        nav.navigate("${Routes.PERSON}/${person.id}?name=$name")
                    },
                )
            } else {
                // 源未就绪（正常流程到不了）：不做空页，直接退回
                LaunchedEffect(Unit) { nav.popBackStack() }
            }
        }

        composable(
            route = "${Routes.PERSON}/{personId}?name={name}",
            arguments = listOf(
                navArgument("personId") { type = NavType.StringType },
                navArgument("name") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { entry ->
            val personId = entry.arguments?.getString("personId").orEmpty()
            val personName = android.net.Uri.decode(entry.arguments?.getString("name").orEmpty())
            val src = source
            if (src != null && personId.isNotEmpty()) {
                val context = androidx.compose.ui.platform.LocalContext.current
                val thumbs = remember(src, serverId, localThumbnails) {
                    if (localThumbnails) LocalThumbnailProvider(context, src, clientCapabilities) else null
                }
                PersonRoute(
                    personId = personId,
                    personName = personName.ifBlank { "演员" },
                    source = src,
                    loadImage = { item, kind ->
                        val remote = src.image(item, kind, ImageSize.MEDIUM)
                        if (kind == ImageKind.POSTER && remote is ImageSource.None &&
                            !item.hasPoster && thumbs != null
                        ) {
                            thumbs.image(item)
                        } else {
                            remote
                        }
                    },
                    onBack = { nav.popBackStack() },
                    onItemClick = { item -> nav.navigate("${Routes.DETAIL}/${item.id}") },
                )
            } else {
                // 源未就绪（正常流程到不了）：不做空页，直接退回
                LaunchedEffect(Unit) { nav.popBackStack() }
            }
        }

        composable(
            route = "${Routes.PLAYER}/{itemId}",
            arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
            // 播放页进出**不做淡入淡出**：视频面是 SurfaceView（独立窗口图层），
            // 不参与 Compose 的 alpha 转场 —— 淡出期间画面仍不透明地盖在列表上方，
            // 看起来就是「视频与列表重叠」（2026-10-19 真机 bug）。瞬时切换最干净。
            enterTransition = { androidx.compose.animation.EnterTransition.None },
            exitTransition = { androidx.compose.animation.ExitTransition.None },
            popEnterTransition = { androidx.compose.animation.EnterTransition.None },
            popExitTransition = { androidx.compose.animation.ExitTransition.None },
        ) { entry ->
            val itemId = entry.arguments?.getString("itemId").orEmpty()
            val src = source
            if (src != null && itemId.isNotEmpty()) {
                PlayerRoute(
                    itemId = itemId,
                    source = src,
                    caps = clientCapabilities,
                    engine = engineHolder.engine,
                    onBack = { nav.popBackStack() },
                    appUnlocked = appUnlocked,
                    keepBackgroundPlayback = keepBackgroundPlayback,
                    autoPlayNext = autoPlayNext,
                )
                // 挂载即连接媒体会话：启动 PlaybackService，通知栏/锁屏控制才存在
                app.myplayer.playback.BindMediaSessionEffect()
            } else {
                // 源未就绪（正常流程到不了）：不做空页，直接退回
                LaunchedEffect(Unit) { nav.popBackStack() }
            }
        }
    }
}

@Composable
private fun NoServerScreen(onAddServer: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            title = "还没有添加服务器",
            description = "添加一台 Emby 服务器后即可开始浏览媒体库。",
            actionText = "添加服务器",
            onAction = onAddServer,
        )
    }
}

/**
 * 海报加载：服务端有海报走服务端；**缺海报**时用本地抽帧缩略图兜底（任务 3-7）。
 */
private suspend fun loadPosterWithFallback(
    item: MediaItemDto,
    source: MediaSource?,
    caps: ClientCapabilities,
    thumbs: LocalThumbnailProvider?,
): ImageSource {
    val remote = source?.image(item, ImageKind.POSTER, ImageSize.MEDIUM)
    if (remote is ImageSource.None && !item.hasPoster && thumbs != null) {
        return thumbs.image(item)
    }
    return remote ?: ImageSource.None
}

/** 设置 tab：服务器管理 + 全部设置项 + 关于（`01` §6 / 任务 5-1 ~ 5-3）。 */
@Composable
internal fun SettingsDestination(
    settingsStore: app.myplayer.core.datastore.SettingsStore,
    serverConfigStore: ServerConfigStore,
    tokenStore: TokenStore,
    onAddServer: () -> Unit,
) {
    val vm: app.myplayer.feature.settings.SettingsViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                app.myplayer.feature.settings.SettingsViewModel(settingsStore, serverConfigStore, tokenStore) as T
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val subNav = rememberNavController()

    androidx.navigation.compose.NavHost(subNav, startDestination = "list") {
        composable("list") {
            app.myplayer.feature.settings.SettingsScreen(
                state = state,
                onSetActiveServer = vm::setActiveServer,
                onDeleteServer = vm::deleteServer,
                onAddServer = onAddServer,      // 复用外层 ADD_SERVER 路由（含探测 + 登录）
                onThemeMode = vm::setThemeMode,
                onDefaultSpeed = vm::setDefaultSpeed,
                onKeepBackgroundPlayback = vm::setKeepBackgroundPlayback,
                onDoubleTapSeek = vm::setDoubleTapSeekMillis,
                onAutoPlayNext = vm::setAutoPlayNext,
                onScreenOrientation = vm::setScreenOrientation,
                onDefaultAspectRatio = vm::setDefaultAspectRatio,
                onSubtitleStyle = vm::setSubtitleStyle,
                onAppLockEnabled = vm::setAppLockEnabled,
                onUnlockGrace = vm::setUnlockGraceMillis,
                onHideRecentsSnapshot = vm::setHideRecentsSnapshot,
                onLocalThumbnails = vm::setLocalThumbnails,
                onSearchFuzzyPinyin = vm::setSearchFuzzyPinyin,
                onSearchTraditionalSimplified = vm::setSearchTraditionalSimplified,
                onAllowCleartextHttp = vm::setAllowCleartextHttp,
                onTrustSelfSignedCert = vm::setTrustSelfSignedCert,
                onLibraryDisplayMode = vm::setLibraryDisplayMode,
                onAbout = { subNav.navigate("about") },
                onBack = { /* tab 页无返回 */ },
            )
        }
        composable("about") {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            app.myplayer.feature.settings.AboutScreen(
                versionName = runCatching {
                    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0.1.0"
                }.getOrDefault("0.1.0"),
                onBack = { subNav.popBackStack() },
            )
        }
    }
}

/** 搜索 tab：本地索引 + 输入即搜（任务 3-5 / 3-6）。 */
@Composable
internal fun SearchDestination(
    repository: LibraryRepository,
    source: MediaSource?,
    serverId: String,
    caps: ClientCapabilities,
    localThumbnails: Boolean,
    onPlay: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val thumbs = remember(source, serverId, localThumbnails) {
        if (localThumbnails && source != null) LocalThumbnailProvider(context, source, caps) else null
    }
    val pinyin = remember { app.myplayer.feature.library.IcuPinyinConverter() }
    val vm: app.myplayer.feature.library.LibrarySearchViewModel = viewModel(
        key = "search-$serverId",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                app.myplayer.feature.library.LibrarySearchViewModel(repository, serverId, pinyin) as T
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    app.myplayer.feature.library.SearchScreen(
        state = state,
        onQueryChange = vm::onQueryChange,
        onSubmit = { vm.rememberQuery() },
        onSelectRecent = vm::selectRecent,
        onClear = vm::clearQuery,
        loadImage = { item -> loadPosterWithFallback(item, source, caps, thumbs) },
        onPlay = { item -> onPlay(item.id) },
        onOpenDetail = { item -> onOpenDetail(item.id) },
    )
}

/** 媒体库 tab：库页（库 Tab + 海报网格）。ViewModel 以 serverId 为 key 持久，切换 tab 不丢状态。 */
@Composable
internal fun LibraryDestination(
    repository: LibraryRepository,
    serverConfigStore: ServerConfigStore,
    source: MediaSource?,
    serverId: String,
    caps: ClientCapabilities,
    localThumbnails: Boolean,
    onPlay: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 缺海报时的本地抽帧兜底（任务 3-7）：开关关闭或没有源时不构造
    val thumbs = remember(source, serverId, localThumbnails) {
        if (localThumbnails && source != null) LocalThumbnailProvider(context, source, caps) else null
    }
    val vm: LibraryViewModel = viewModel(
        key = "library-$serverId",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LibraryViewModel(repository, serverId) as T
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()

    // 首次进入即同步一次（分页落 Room，UI 只读 Room）
    LaunchedEffect(serverId, source) { source?.let { vm.refresh(it) } }

    val configs by serverConfigStore.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val serverLabel = configs.firstOrNull { it.id == serverId }?.name

    LibraryScreen(
        state = state,
        loadImage = { item -> loadPosterWithFallback(item, source, caps, thumbs) },
        onSelectLibrary = vm::selectLibrary,
        onPlay = { item -> onPlay(item.id) },
        onOpenDetail = { item -> onOpenDetail(item.id) },
        onSort = vm::setSort,
        onFilter = vm::setFilter,
        onResetFilter = vm::resetFilter,
        serverLabel = serverLabel,
    )
}
