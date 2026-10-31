package app.myplayer.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
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
import app.myplayer.core.datastore.ServerConfig
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
import app.myplayer.feature.library.home.HomeDataSource
import app.myplayer.feature.library.home.HomeScreen
import app.myplayer.feature.library.home.HomeServerOption
import app.myplayer.feature.library.home.HomeSourceType
import app.myplayer.feature.library.home.HomeViewModel
import app.myplayer.feature.library.home.OfflineHomeDataSource
import app.myplayer.feature.library.home.ServerHomeDataSource
import app.myplayer.feature.player.PlayerRoute
import app.myplayer.feature.server.AddServerForm
import app.myplayer.feature.server.AddServerScreen
import app.myplayer.feature.server.AddServerViewModel
import app.myplayer.feature.server.AuthMode
import app.myplayer.feature.server.LoginStatus
import app.myplayer.feature.server.LoginViewModel
import app.myplayer.feature.server.ProbeStatus
import app.myplayer.feature.server.ServerAuthGateway
import app.myplayer.feature.server.ServerKind
import app.myplayer.feature.server.SignInRequest
import app.myplayer.feature.server.SignInResult
import app.myplayer.feature.server.SubmitStatus
import app.myplayer.feature.server.fnosLogin
import app.myplayer.feature.server.probeFnos
import app.myplayer.feature.server.probeServer
import app.myplayer.feature.server.probeSmb
import app.myplayer.source.httpapi.AuthHeaderStyle
import app.myplayer.source.httpapi.AuthResultDto
import app.myplayer.source.httpapi.HttpMediaApi
import app.myplayer.source.httpapi.SourceConfig
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * 登录 + 落盘的唯一生产实现：新增 / 修改页拿到它，**不再跳二次登录页**。
 *
 * - 账号密码 / 无密码 → 复用 [LoginViewModel]（错误文案、令牌落盘、当前服务器切换都在里面）
 * - API Key → 把 key 当令牌查 `/Users/Me`：既校验 key 有效，又取到取数接口必需的 `userId`
 */
private fun buildAuthGateway(
    client: OkHttpClient,
    configStore: ServerConfigStore,
    tokenStore: TokenStore,
    settingsStore: app.myplayer.core.datastore.SettingsStore,
): ServerAuthGateway = ServerAuthGateway { req ->
    try {
        // SMB 没有令牌交换：账号密码验证通过后直接把凭据（加密）存进 TokenStore
        if (req.sourceType == SourceType.SMB) {
            val smbConfig = app.myplayer.source.smb.SmbConfig.parse(
                rawUrl = req.baseUrl,
                domain = req.domain,
                username = req.username,
                password = req.password,
            ) ?: return@ServerAuthGateway SignInResult.Failed("请填写共享目录，例如 smb://192.168.31.15/video")

            val info = app.myplayer.feature.server.testSmbConnection(smbConfig)
            // 先配置后令牌：私有源的凭据要落 DEK 加密区，而加密条目随配置落盘才建立
            configStore.upsert(
                ServerConfig(
                    id = req.serverId,
                    sourceType = req.sourceType,
                    name = req.displayName ?: "${smbConfig.share} (${smbConfig.host})",
                    baseUrl = req.baseUrl,
                    // SMB 没有 userId；SourceFactory 的 SMB 分支不读它
                    userId = null,
                    isPrivate = req.isPrivate,
                )
            )
            tokenStore.put(
                req.serverId,
                app.myplayer.feature.server.SmbCredentials(
                    username = req.username,
                    password = req.password,
                    domain = req.domain,
                ).encode(),
            )
            android.util.Log.i("LimeSMB", "登录成功：$info")
            if (req.activate) settingsStore.setActiveServerId(req.serverId)
            return@ServerAuthGateway SignInResult.Ok
        }
        if (req.authMode == AuthMode.API_KEY) {
            val api = HttpMediaApi(
                client = client,
                config = SourceConfig(
                    serverId = req.serverId,
                    baseUrl = req.baseUrl,
                    token = req.apiKey,
                    authHeaderStyle = if (req.sourceType == SourceType.JELLYFIN) {
                        AuthHeaderStyle.MEDIABROWSER_AUTHORIZATION
                    } else {
                        AuthHeaderStyle.X_EMBY_TOKEN
                    },
                ),
            )
            val user = api.getCurrentUser()
            configStore.upsert(
                ServerConfig(
                    id = req.serverId,
                    sourceType = req.sourceType,
                    // 自定义名（修改服务器）> 服务端账号名 > 地址兜底
                    name = req.displayName ?: user.name ?: req.baseUrl,
                    baseUrl = req.baseUrl,
                    userId = user.id,
                    isPrivate = req.isPrivate,
                )
            )
            tokenStore.put(req.serverId, req.apiKey)
            if (req.activate) settingsStore.setActiveServerId(req.serverId)
            SignInResult.Ok
        } else {
            val api = HttpMediaApi(
                client = client,
                config = SourceConfig(serverId = req.serverId, baseUrl = req.baseUrl),
            )
            // 登录实现按源分派：飞牛影视用自己的 /v/api/v1/login（返回 token，无 userId 概念）
            val loginCall: suspend (String, String) -> AuthResultDto =
                if (req.sourceType == SourceType.FNOS) {
                    { username, password -> fnosLogin(req.baseUrl, username, password) }
                } else {
                    api::authenticateByName
                }
            val loginVm = LoginViewModel(
                serverId = req.serverId,
                sourceType = req.sourceType,
                baseUrl = req.baseUrl,
                login = loginCall,
                configStore = configStore,
                tokenStore = tokenStore,
                // 重新登录不改「当前服务器」——用户只是修了地址，不该顺带切换服务器
                settingsStore = if (req.activate) settingsStore else null,
                displayName = req.displayName,
                isPrivate = req.isPrivate,
            )
            loginVm.onUsernameChange(req.username)
            loginVm.onPasswordChange(req.password)
            loginVm.submit()
            when (loginVm.state.value.status) {
                LoginStatus.SUCCESS -> SignInResult.Ok
                else -> SignInResult.Failed(loginVm.state.value.message ?: "登录失败")
            }
        }
    } catch (e: Exception) {
        SignInResult.Failed(e.message ?: "登录失败")
    }
}

/** 探活按服务端类型分派：SMB 走 445 TCP，飞牛走免签名版本端点，其余走 `/System/Info/Public`。 */
private suspend fun probeFor(client: OkHttpClient, kind: ServerKind, url: String): ProbeStatus = when (kind) {
    ServerKind.SMB -> probeSmb(url)
    ServerKind.FNOS -> probeFnos(client, url)
    else -> probeServer(client, url, "")
}

/** 把 VM 的全部回调装进页面需要的那个不可变对象。 */
private fun addServerForm(vm: AddServerViewModel, scope: kotlinx.coroutines.CoroutineScope): AddServerForm =
    AddServerForm(
        onSelectKind = vm::onSelectKind,
        onSelectProtocol = vm::onSelectProtocol,
        onAddressChange = vm::onAddressChange,
        onPortChange = vm::onPortChange,
        onDomainChange = vm::onDomainChange,
        onShareChange = vm::onShareChange,
        onAuthModeChange = vm::onAuthModeChange,
        onUsernameChange = vm::onUsernameChange,
        onPasswordChange = vm::onPasswordChange,
        onApiKeyChange = vm::onApiKeyChange,
        onNameChange = vm::onNameChange,
        onSaveAsPrivateChange = vm::onSaveAsPrivateChange,
        onTest = { scope.launch { vm.onTest() } },
        onSubmit = { scope.launch { vm.onSubmit() } },
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
    /** 本地播放进度（文件型源）；null = 不写本地（预览 / 测试）。 */
    playbackStore: app.myplayer.core.model.PlaybackStore? = null,
    appUnlocked: kotlinx.coroutines.flow.StateFlow<Boolean>,
    keepBackgroundPlayback: Boolean,
    autoPlayNext: Boolean,
    /** 应用锁开关（设置 → 隐私）：决定「解锁回前台是否自动续播」。 */
    appLockEnabled: Boolean = true,
    /** 是否已录过解锁图案：没录过时应用锁不生效（主界面据此决定要不要盖锁屏）。 */
    patternConfigured: Boolean = false,
    /** 打开图案设置（绘制 → 确认），由 MainActivity 以全屏覆盖层承载。 */
    onSetupPattern: () -> Unit = {},
    localThumbnails: Boolean,
    serverId: String?,
    source: MediaSource?,
    /** 设置页「画面 · 方向」：播放页的默认方向（AUTO / 横屏 / 竖屏）。 */
    defaultOrientation: app.myplayer.feature.player.PlayerOrientation =
        app.myplayer.feature.player.PlayerOrientation.AUTO,
    /** 设置页「播放 · 默认倍速」：起播速度。 */
    defaultSpeed: Float = 1f,
    /** 设置页「播放 · 双击快进」：双击 / 快进快退按钮的步长（ms）。 */
    doubleTapSeekMs: Long = 10_000L,
    /** 设置页「画面 · 画面比例」：起播时的初始缩放模式。 */
    initialScaleMode: app.myplayer.feature.player.ScaleMode =
        app.myplayer.feature.player.ScaleMode.FIT,
    /** 设置页「字幕设置」：字号 / 颜色 / 描边 / 位置。 */
    subtitleStyle: app.myplayer.feature.player.PlayerSubtitleStyle =
        app.myplayer.feature.player.PlayerSubtitleStyle(),
    /** 是否处于画中画（PiP 里播放页要取消假横屏）。 */
    inPictureInPicture: Boolean = false,
    /** 关于页版本号连点 3 下触发：打开隐私门（由 MainActivity 以全屏覆盖层承载）。 */
    onPrivacyEntry: () -> Unit = {},
    /**
     * 隐私会话结束的计数。每次从「隐私中」退回普通模式 +1 —— 收到就**把导航栈弹回主界面**，
     * 否则用户会停在刚才那个隐私源的详情页/播放页上（返回普通模式后那里已经没有数据了）。
     */
    privacyResetSignal: Int = 0,
    /**
     * 隐私会话**进入成功**的计数。入口在「设置 → 关于」页，验证完不该还停在那儿 ——
     * 收到就把 tab 切回首页（`MainScaffold`）并把主栈弹回 [Routes.MAIN]。
     */
    privacyEnterSignal: Int = 0,
    /**
     * 当前是否处于隐私模式。决定：
     * · 「添加服务器」页是否出现「存为隐私源」；
     * · 设置页服务器行是否显示隐私标识与「移入隐私」动作。
     * 这两处都只在隐私模式下可用 —— 普通模式看不到私有源，也就无从操作它。
     */
    privacyActive: Boolean = false,
    /** 私有源敏感字段加密区：设置页「移入隐私」要用它写凭据。null = 未接（测试/预览）。 */
    privateSecrets: app.myplayer.core.datastore.PrivacyServerSecretStore? = null,
    /** 文件库存（SMB 条目的 `isPrivate` 标记写入口）。null = 未接（预览/测试）。 */
    fileCatalog: app.myplayer.core.model.FileCatalogStore? = null,
) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    /**
     * 播放页返回计数：pop 回主界面时 +1，首页据此重拉「继续观看」。
     * 首页数据是缓存的，播放时写的进度要等重新 load 才会反映出来（2026-10-31 用户反馈）。
     */
    val homeRefreshSignal = remember { mutableIntStateOf(0) }
    /**
     * 库详情页静默刷新计数：同样在播放页返回时 +1。
     *
     * 媒体库列表要显示**卡片进度条**（`userData.resumePositionTicks`），而列表是进页面时拉一次的；
     * 播完返回若不重拉，进度条就停在进去之前的旧值（用户 2026-10-31 反馈「看不见进度」的配套问题）。
     */
    val listRefreshSignal = remember { mutableIntStateOf(0) }

    // 隐私会话结束 → 回主界面。`inclusive = false`：保留 MAIN 本身，只弹掉它之上的页面。
    // 这个 effect 在首次组合时也会跑一次，但信号为 0 时直接返回，不会误弹。
    LaunchedEffect(privacyResetSignal) {
        if (privacyResetSignal > 0) {
            nav.popBackStack(Routes.MAIN, inclusive = false)
        }
    }

    // 进入隐私模式 → 同样回主界面：门是从「设置 → 关于」拉起来的，
    // 验完还停在关于页会让人以为没进去（tab 由 MainScaffold 切回首页）。
    LaunchedEffect(privacyEnterSignal) {
        if (privacyEnterSignal > 0) {
            nav.popBackStack(Routes.MAIN, inclusive = false)
        }
    }
    val authGateway = remember(client, serverConfigStore, tokenStore, settingsStore) {
        buildAuthGateway(client, serverConfigStore, tokenStore, settingsStore)
    }
    val probe: suspend (ServerKind, String) -> ProbeStatus = remember(client) {
        { kind, url -> probeFor(client, kind, url) }
    }

    // 页面转场：整套参数集中在 NavTransitions（唯一真相），改一处全 App 生效。
    // 曾经的坑（都写进了 NavTransitions 的注释）：navigation 默认 700ms 淡化会让 pop 之后
    // 播放页定格 731ms 才销毁；播放页单独配了下滑、设置子导航没配 —— 于是一台手机三种退场。
    //
    // 播放页的「横屏」**不是设备方向**：它是把内容整体转 90°（PlayerScreen 的 rotationZ），
    // 系统方向始终是竖屏。所以这里既不能捕获 Boolean，也不能现读 resources.configuration ——
    // 两者都恒为 false（BUG-2026-003 真因，2026-10-24 真机第二次复现后定位）。
    // 唯一可靠的来源是播放页自己的上报：由 PlayerScreen 通过 `onLandscapeChange` 写进下面这个
    // MutableState，转场 lambda 捕获**状态对象**、在真正开始时读 `.value`，拿到的就是实时值。
    val playerLandscape = remember { mutableStateOf(false) }
    // 这个横向是不是「假横屏」（窗口竖着、内容转 90°）：退场方向要按它换算
    val playerFakeRotation = remember { mutableStateOf(false) }

    NavHost(
        nav,
        startDestination = if (serverId == null) Routes.WELCOME else Routes.MAIN,
        enterTransition = { NavTransitions.enter() },
        // 推入新页：旧页统一左移 1/4 做视差（播放页入场也是从右滑入，与其它页面一致）
        exitTransition = { NavTransitions.exit() },
        popEnterTransition = {
            NavTransitions.popEnterFor(
                fromRoute = initialState.destination.route,
                playerLandscape = playerLandscape.value,
            )
        },
        popExitTransition = { NavTransitions.popExit() },
    ) {

        composable(Routes.WELCOME) {
            BottomInsetScreen {
                NoServerScreen(onAddServer = { nav.navigate(Routes.ADD_SERVER) })
            }
        }

        // 新增服务器：地址 + 账号在**同一页**填完，点「登录」直接落盘并入主界面。
        composable(Routes.ADD_SERVER) {
            val vm = remember(authGateway, probe, privacyActive) {
                AddServerViewModel(probe = probe, auth = authGateway, allowPrivate = privacyActive)
            }
            val state by vm.state.collectAsStateWithLifecycle()
            // 登录已把 ServerConfig / Token 落盘并设为当前服务器 → 回根级，MainActivity 重新造出 source
            LaunchedEffect(state.submitStatus) {
                if (state.submitStatus == SubmitStatus.Done) {
                    nav.navigate(Routes.MAIN) { popUpTo(0) }
                }
            }
            BottomInsetScreen {
                AddServerScreen(
                    state = state,
                    form = remember(vm) { addServerForm(vm, scope) },
                    onBack = { nav.popBackStack() },
                )
            }
        }

        // 修改服务器：同一页，按「端点是否改动」分流 ——
        // 只改名称直接落盘返回；改了地址 / 类型 / 凭据则就地重新登录（不再跳登录页）。
        composable(
            route = "${Routes.EDIT_SERVER}/{serverId}",
            arguments = listOf(navArgument("serverId") { type = NavType.StringType }),
        ) { entry ->
            val editingId = entry.arguments?.getString("serverId").orEmpty()
            val configFlow = remember { serverConfigStore.observeAll() }
            // initialValue = null 表示「配置还在读盘」：不能拿空列表当「服务器已删除」，
            // 否则首帧就会 popBackStack，点了修改直接闪回设置页（2026-10-22 真机）。
            val configs by configFlow.collectAsStateWithLifecycle(initialValue = null)
            val config = configs?.firstOrNull { it.id == editingId }
            if (configs == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (config == null) {
                // 服务器已被删除 / 参数异常：不留空白页
                LaunchedEffect(Unit) { nav.popBackStack() }
            } else {
                // key **只放 id**：登录成功后配置会被自己 upsert（baseUrl 变了），
                // 若把 baseUrl 放进 key，VM 会被重建、状态回 Idle，Done 就永远到不了导航。
                val vm = remember(config.id) {
                    AddServerViewModel(
                        probe = probe,
                        auth = authGateway,
                        // 「只改名」不需要重新登录，本地落盘即可
                        saveLocal = { name -> serverConfigStore.upsert(config.copy(name = name)) },
                        initial = config,
                        allowPrivate = privacyActive,
                    )
                }
                val state by vm.state.collectAsStateWithLifecycle()
                // 重新登录成功 → 弹掉修改页，回到主界面（设置 tab 选中态由 rememberSaveable 保留）
                LaunchedEffect(state.submitStatus) {
                    if (state.submitStatus == SubmitStatus.Done) {
                        nav.popBackStack(Routes.MAIN, inclusive = false)
                    }
                }
                BottomInsetScreen {
                    AddServerScreen(
                        state = state,
                        form = remember(vm) { addServerForm(vm, scope) },
                        onBack = { nav.popBackStack() },
                    )
                }
            }
        }

        composable(Routes.MAIN) {
            val sid = serverId
            if (sid == null) {
                // 没有**可见**的服务器：可能是还没添加，也可能是刚退出隐私模式、
                // 唯一一台服务器是隐私源（被策略过滤掉了）。两种情况都该看到空态，
                // 而不是一个永远转下去的圈 —— 首次进入时 startDestination 是 WELCOME，
                // 走不到这里；能走到这里说明是从「有源」变成了「无源」。
                NoServerScreen(onAddServer = { nav.navigate(Routes.ADD_SERVER) })
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
                    onEditServer = { id -> nav.navigate("${Routes.EDIT_SERVER}/$id") },
                    onPlay = { itemId -> nav.navigate("${Routes.PLAYER}/$itemId") },
                    onOpenDetail = { itemId -> nav.navigate("${Routes.DETAIL}/$itemId") },
                    onOpenLibraryDetail = { libraryId, title, smb ->
                        nav.navigate(
                            "${Routes.LIBRARY_DETAIL}/$libraryId?title=" +
                                android.net.Uri.encode(title) + "&smb=$smb",
                        )
                    },
                    patternConfigured = patternConfigured,
                    onSetupPattern = onSetupPattern,
                    onPrivacyEntry = onPrivacyEntry,
                    privacyResetSignal = privacyResetSignal,
                    privacyEnterSignal = privacyEnterSignal,
                    privacyActive = privacyActive,
                    privateSecrets = privateSecrets,
                    fileCatalog = fileCatalog,
                    homeRefreshSignal = homeRefreshSignal.intValue,
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
                    // 档二隐私开关（2026-10-29）：SMB/LOCAL + 隐私模式才可用；
                    // Emby 系 catalog 未接入 / privacyActive=false → 按钮不渲染
                    catalog = fileCatalog,
                    privacyActive = privacyActive,
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
                    onBack = {
                        nav.popBackStack()
                        // 详情页能改「已看」状态（标记已播放 / 删除）：返回后首页与媒体库都要重读，
                        // 否则卡片上的进度条、已看态还是进去之前的样子（2026-10-31）
                        homeRefreshSignal.intValue++
                        listRefreshSignal.intValue++
                    },
                    onPlay = { item -> nav.navigate("${Routes.PLAYER}/${item.id}") },
                    // 标记已看成功 → 服务端权威 UserData 写回本地缓存（`02` §5.1）
                    onUserDataChanged = { sid, iid, ud -> repository.updateUserData(sid, iid, ud) },
                    // 服务端删除成功 → 清理本地缓存并返回上一页（`03` §6.3）
                    onDeleted = { item ->
                        scope.launch {
                            repository.deleteItem(item.serverId, item.id)
                            nav.popBackStack()
                            // 删掉的卡片要立刻从列表消失（SMB 列表是实时列目录，重拉即可）
                            homeRefreshSignal.intValue++
                            listRefreshSignal.intValue++
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
            route = "${Routes.LIBRARY_DETAIL}/{libraryId}?title={title}&smb={smb}",
            arguments = listOf(
                navArgument("libraryId") { type = NavType.StringType },
                navArgument("title") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("smb") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { entry ->
            val libraryId = entry.arguments?.getString("libraryId").orEmpty()
            val title = android.net.Uri.decode(entry.arguments?.getString("title").orEmpty())
            val smb = entry.arguments?.getBoolean("smb") ?: false
            val src = source
            if (src != null && libraryId.isNotEmpty() || smb) {
                BottomInsetScreen {
                    LibraryDetailDestination(
                        source = src,
                        serverId = serverId.orEmpty(),
                        libraryId = libraryId,
                        title = title,
                        smb = smb,
                        caps = clientCapabilities,
                        localThumbnails = localThumbnails,
                        onPlay = { itemId -> nav.navigate("${Routes.PLAYER}/$itemId") },
                        onOpenDetail = { item -> nav.navigate("${Routes.DETAIL}/${item.id}") },
                        onBack = { nav.popBackStack() },
                        catalog = fileCatalog,
                        // 档二标记只在隐私模式下开放：普通模式标成隐私后会立刻从列表消失，
                        // 而取消标记又必须进隐私模式，等于把内容藏进一个用户进不去的地方
                        privacyActive = privacyActive,
                        refreshSignal = listRefreshSignal.intValue,
                    )
                }
            } else {
                LaunchedEffect(Unit) { nav.popBackStack() }
            }
        }

        composable(
            route = "${Routes.PLAYER}/{itemId}",
            arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
            // 播放页退场按「画面是否横向」分流（用户 2026-10-23 定）：
            //   横向画面 = 向下滑出（对齐 VidHub 的「移出滑动」，下层页面不动）
            //   非横向   = 向右滑出（与其他页面完全一致）
            // 入场一律从右滑入 —— VidHub 就是这个组合（横向推入、纵向移出），
            // 也避免「点开播放时整屏从下弹上来」那种夸张感。
            enterTransition = { NavTransitions.enter() },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = {
                NavTransitions.playerPopExit(
                    landscape = playerLandscape.value,
                    fakeRotation = playerFakeRotation.value,
                )
            },
        ) { entry ->
            val itemId = entry.arguments?.getString("itemId").orEmpty()
            val src = source
            if (src != null && itemId.isNotEmpty()) {
                PlayerRoute(
                    itemId = itemId,
                    source = src,
                    caps = clientCapabilities,
                    engine = engineHolder.engine,
                    onBack = {
                        nav.popBackStack()
                        // 两个都要刷：首页「继续观看」+ 库详情页的卡片进度条
                        homeRefreshSignal.intValue++
                        listRefreshSignal.intValue++
                    },
                    appUnlocked = appUnlocked,
                    keepBackgroundPlayback = keepBackgroundPlayback,
                    appLockEnabled = appLockEnabled,
                    autoPlayNext = autoPlayNext,
                    defaultSpeed = defaultSpeed,
                    doubleTapSeekMs = doubleTapSeekMs,
                    initialScaleMode = initialScaleMode,
                    subtitleStyle = subtitleStyle,
                    defaultOrientation = defaultOrientation,
                    inPictureInPicture = inPictureInPicture,
                    playbackStore = playbackStore,
                    privacyMode = privacyActive,
                    onLandscapeChange = { landscape, fakeRotation ->
                        playerLandscape.value = landscape
                        playerFakeRotation.value = fakeRotation
                    },
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
): ImageSource = loadImageWithFallback(item, ImageKind.POSTER, source, caps, thumbs)

/**
 * 通用图片加载（海报 / 剧照 / 抽帧）。
 *
 * 只有「源自己也没有 + 条目声明没有海报」时才落到本地抽帧 —— 否则每张图都会多读一次文件头。
 * `hasPoster = false` 是 SMB / 本地文件这类无元数据源的常态，因此 SMB 的卡片预览图实际总走 [thumbs]。
 */
private suspend fun loadImageWithFallback(
    item: MediaItemDto,
    kind: ImageKind,
    source: MediaSource?,
    caps: ClientCapabilities,
    thumbs: LocalThumbnailProvider?,
): ImageSource {
    val size = if (kind == ImageKind.POSTER) ImageSize.MEDIUM else ImageSize.LARGE
    val remote = source?.image(item, kind, size)
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
    onEditServer: (String) -> Unit,
    /** 首页「全关」空态请求定位「首页设置」：设置列表自动滚动到该分组并高亮闪烁。 */
    highlightHomeSettings: Boolean = false,
    onHomeSettingsHighlightHandled: () -> Unit = {},
    /** 二级页开关：进/出「字幕设置」「关于」时通知外层隐藏悬浮导航（tab 根页才该有导航）。 */
    onSubPageChange: (Boolean) -> Unit = {},
    /** 是否已录过解锁图案（没录过时应用锁不生效）。 */
    patternConfigured: Boolean = false,
    /** 打开图案设置页（绘制 → 确认），由 MainActivity 用全屏覆盖层承载。 */
    onSetupPattern: () -> Unit = {},
    /** 关于页版本号连点 3 下：打开隐私门（隐藏入口，页面上没有任何可见按钮）。 */
    onPrivacyEntry: () -> Unit = {},
    /** 是否处于隐私模式（服务器行的隐私标识 / 移入隐私入口）。 */
    privacyActive: Boolean = false,
    /** 私有源敏感字段加密区（「移入隐私」写凭据用）。 */
    privateSecrets: app.myplayer.core.datastore.PrivacyServerSecretStore? = null,
) {
    val vm: app.myplayer.feature.settings.SettingsViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                app.myplayer.feature.settings.SettingsViewModel(
                    settingsStore,
                    serverConfigStore,
                    tokenStore,
                    privateSecrets,
                ) as T
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val subNav = rememberNavController()

    // 二级页（字幕设置 / 关于）不属于 tab 的内容层，它盖住整块区域，**不应再显示悬浮导航**。
    // 用当前路由上报，而不是让每页自己写：返回、系统返回手势、页面切换都会经过这里，不会漏。
    val subEntry by subNav.currentBackStackEntryAsState()
    val subRoute = subEntry?.destination?.route
    LaunchedEffect(subRoute) {
        onSubPageChange(subRoute != null && subRoute != SUB_ROUTE_LIST)
    }

    // 设置子导航（列表 → 字幕设置 / 关于）：
    // · **只让子页滑动，父列表静止**。这层导航嵌在 tab 里，父页再横移 1/4 会让
    //   「标题 + 列表」整体跟着晃，两层同时位移（2026-10-24 真机反馈）。
    // · 子页必须有**不透明背景**（[OpaqueScreen]），否则滑动过程中能透视到父页，
    //   两层文字叠在一起（同一轮反馈）。
    androidx.navigation.compose.NavHost(
        subNav,
        startDestination = SUB_ROUTE_LIST,
        enterTransition = { NavTransitions.enter() },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { NavTransitions.popExit() },
    ) {
        composable(SUB_ROUTE_LIST) {
            app.myplayer.feature.settings.SettingsScreen(
                state = state,
                onSetActiveServer = vm::setActiveServer,
                onEditServer = onEditServer,
                onDeleteServer = vm::deleteServer,
                onAddServer = onAddServer,      // 复用外层 ADD_SERVER 路由（含探测 + 登录）
                onThemeMode = vm::setThemeMode,
                onDefaultSpeed = vm::setDefaultSpeed,
                onKeepBackgroundPlayback = vm::setKeepBackgroundPlayback,
                onDoubleTapSeek = vm::setDoubleTapSeekMillis,
                onAutoPlayNext = vm::setAutoPlayNext,
                onScreenOrientation = vm::setScreenOrientation,
                onDefaultAspectRatio = vm::setDefaultAspectRatio,
                onSubtitleSettings = { subNav.navigate(SUB_ROUTE_SUBTITLE) },
                onAppLockEnabled = vm::setAppLockEnabled,
                onUnlockGrace = vm::setUnlockGraceMillis,
                onHideRecentsSnapshot = vm::setHideRecentsSnapshot,
                onLocalThumbnails = vm::setLocalThumbnails,
                onSearchFuzzyPinyin = vm::setSearchFuzzyPinyin,
                onSearchTraditionalSimplified = vm::setSearchTraditionalSimplified,
                onAllowCleartextHttp = vm::setAllowCleartextHttp,
                onTrustSelfSignedCert = vm::setTrustSelfSignedCert,
                onHomeSettings = { subNav.navigate(SUB_ROUTE_HOME) },
                onAbout = { subNav.navigate(SUB_ROUTE_ABOUT) },
                onBack = { /* tab 页无返回 */ },
                patternConfigured = patternConfigured,
                onSetupPattern = onSetupPattern,
                highlightHomeSettings = highlightHomeSettings,
                onHighlightHandled = onHomeSettingsHighlightHandled,
                privacyActive = privacyActive,
                onMoveToPrivacy = vm::moveToPrivacy,
            )
        }
        composable(SUB_ROUTE_SUBTITLE) {
            OpaqueScreen {
                app.myplayer.feature.settings.SubtitleSettingsScreen(
                    style = state.settings.subtitleStyle,
                    onStyleChange = vm::setSubtitleStyle,
                    onBack = { subNav.popBackStack() },
                )
            }
        }
        composable(SUB_ROUTE_HOME) {
            OpaqueScreen {
                app.myplayer.feature.settings.HomeSettingsPage(
                    emby = state.settings.embyHomeSettings,
                    smb = state.settings.smbHomeSettings,
                    onEmbyChange = vm::setEmbyHomeSettings,
                    onSmbChange = vm::setSmbHomeSettings,
                    onBack = { subNav.popBackStack() },
                )
            }
        }
        composable(SUB_ROUTE_ABOUT) {
            OpaqueScreen {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                app.myplayer.feature.settings.AboutScreen(
                    versionName = runCatching {
                        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0.1.0"
                    }.getOrDefault("0.1.0"),
                    onBack = { subNav.popBackStack() },
                    onVersionTap = onPrivacyEntry,
                )
            }
        }
    }
}

/**
 * 首页 tab：继续观看 + 动态媒体库横滑 + 最近添加网格（单 LazyVerticalGrid，见 `HomeScreen`）。
 *
 * 数据走**真实媒体源**：`getLibraries()` + `getResumeItems()` + `getLatestItems()` +
 * 每库一次 `getItems()`，编排与降级策略见 [ServerHomeDataSource]。`source` 为 null
 * （配置在但登录态失效）时用 [OfflineHomeDataSource] 直接进「无法连接」空态，
 * 而不是假装「这里还没有内容」。
 *
 * 顶部栏的服务器名字与列表来自 `ServerConfigStore`；切源写 `SettingsStore.activeServerId`
 * —— 与设置页「设为当前」同一条路径，`activeConfig` 变化后 `source` 重建、首页整体重新取数。
 */
/**
 * 媒体库详情页（`Routes.LIBRARY_DETAIL`）。
 *
 * - **Emby**：[MediaSource.getItems] 按 [libraryId] 拉该库全部条目（≤200），2:3 海报网格；
 * - **SMB**（[smb] = true）：源未实现，由 `LibraryDetailMock` 按文件夹名出数 ——
 *   第一层文件夹已被首页映射成库，这里只渲染视频文件（16:9）。
 */
@Composable
internal fun LibraryDetailDestination(
    source: MediaSource?,
    serverId: String,
    libraryId: String,
    title: String,
    smb: Boolean,
    caps: ClientCapabilities,
    /** 设置 → 预览 · 本地生成缩略图（关掉时 SMB 卡片只剩 FileIcon 占位）。 */
    localThumbnails: Boolean,
    onPlay: (String) -> Unit,
    onOpenDetail: (item: app.myplayer.core.model.MediaItemDto) -> Unit,
    onBack: () -> Unit,
    catalog: app.myplayer.core.model.FileCatalogStore? = null,
    /** 是否处于隐私模式：档二「标记为隐私」只在隐私模式开放（见调用点注释）。 */
    privacyActive: Boolean = false,
    /** 播放页返回计数：变了就静默重拉列表，刷新卡片进度条（不闪 loading）。 */
    refreshSignal: Int = 0,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // SMB 没有海报服务，格子里的预览图**全靠**这一条抽帧链路（见 ThumbnailFallback.kt）
    val thumbs = remember(source, serverId, localThumbnails) {
        if (localThumbnails && source != null) LocalThumbnailProvider(context, source, caps) else null
    }
    val vm: app.myplayer.feature.library.LibraryDetailViewModel = viewModel(
        key = "libdetail-$libraryId-$smb",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                app.myplayer.feature.library.LibraryDetailViewModel(
                    source,
                    serverId,
                    libraryId,
                    smb,
                    catalog,
                ) as T
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()

    // 从播放页返回 → 静默重拉一次：进度条的数据源是列表项自带的断点，
    // 不重拉就会一直显示进去之前的旧进度（2026-10-31）。
    LaunchedEffect(refreshSignal) {
        if (refreshSignal > 0) vm.refresh(silent = true)
    }

    app.myplayer.feature.library.LibraryDetailScreen(
        state = state,
        sourceType = if (smb) {
            app.myplayer.feature.library.home.HomeSourceType.SMB
        } else {
            app.myplayer.feature.library.home.HomeSourceType.EMBY
        },
        title = title,
        loadImage = { item, kind -> loadImageWithFallback(item, kind, source, caps, thumbs) },
        onBack = onBack,
        onOpenDetail = onOpenDetail,
        // SMB 视频没有条目 id，播放器拿路径当 id（source:smb 接入后走本地播放链路）
        onPlayPath = onPlay,
        onLongPress = vm::longPressSelect,
        onToggleSelect = vm::toggleSelect,
        onExitSelection = vm::exitSelection,
        onSelectAll = vm::selectAll,
        onMarkWatched = vm::markWatched,
        onApplyFilter = vm::applyFilter,
        onTogglePrivate = vm::togglePrivate,
        // 档二只对文件型条目（SMB / 本地）有意义；在线源靠「藏整源」
        canMarkPrivate = smb && catalog != null && privacyActive,
        privacyActive = privacyActive,
    )
}

@Composable
internal fun HomeDestination(
    serverId: String,
    source: MediaSource?,
    /** 客户端能力：SMB 的抽帧要拿它去协商播放地址。 */
    caps: ClientCapabilities,
    /** 设置 → 预览 · 本地生成缩略图（SMB 文件夹封面靠它）。 */
    localThumbnails: Boolean,
    /**
     * 服务器配置列表（`null` = 还在读盘）。
     *
     * **由 [MainScaffold] 收集后传进来，而不是在这里 `collectAsStateWithLifecycle`**：
     * 首页 tab 切走时本 composable 会退出组合，在里面收集的话每次切回来首帧都是
     * `initialValue = null` → 被判成「配置未就绪」→ 骨架屏闪一下（BUG-2026-002 次因）。
     * `MainScaffold` 在 tab 切换时不会离开组合，配置流因此是连续的。
     */
    configs: List<ServerConfig>?,
    settingsStore: app.myplayer.core.datastore.SettingsStore,
    /** 档二库存（首页卡片「设为 / 取消隐私」写这里）；null = 未接 → 卡片不出三点。 */
    fileCatalog: app.myplayer.core.model.FileCatalogStore? = null,
    onAddServer: () -> Unit,
    onPlay: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenLibrary: (libraryId: String, title: String, smb: Boolean) -> Unit,
    /** 首页全关空态的「去设置开启」：切到设置 tab 并直接打开「首页设置」。 */
    onGoToHomeSettings: () -> Unit,
    /**
     * 隐私会话结束信号（递增计数）。收到就**重拉一次首页** ——
     * 首页数据是缓存的，退出隐私后如果源没变（比如一直是 SMB），
     * 屏幕上会留着刚从隐私模式里看到的条目，等于退出时还在展示。
     */
    resetSignal: Int = 0,
    /**
     * 隐私会话**进入**信号（递增计数）。进入隐私模式后也要重拉 —— 否则首页仍显示
     * 普通模式的缓存（被标隐私的文件夹/视频还在隐藏态），用户看不到「取消隐私」入口
     * （2026-10-29 真机）。
     */
    enterSignal: Int = 0,
    /** 播放页返回信号（递增计数）。返回时重拉一次首页，让播放中写的进度出现在「继续观看」。 */
    refreshSignal: Int = 0,
    /**
     * 是否处于隐私模式。传给 [HomeScreen]：首页卡片的三点菜单（设为 / 取消隐私）与隐私标志
     * 只在隐私模式显示，普通模式不暴露隐私管理入口。
     */
    privacyActive: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // SMB（以及将来的本地文件夹）没有海报服务：文件夹封面 / 最近添加的预览图全靠本地抽帧
    val thumbs = remember(source, serverId, localThumbnails) {
        if (localThumbnails && source != null) LocalThumbnailProvider(context, source, caps) else null
    }
    // 起始值用 null 而不是 emptyList()：DataStore 首帧还没读出来时不能当成「没有服务器」，
    // 否则首页会闪一下「还没有服务器」空态（编辑服务器页踩过同一个坑）
    val servers = remember(configs) {
        configs.orEmpty().map { HomeServerOption(it.id, it.name, it.baseUrl, it.sourceType) }
    }
    val serverName = servers.firstOrNull { it.id == serverId }?.name

    // 首页形态跟着「当前服务器是什么源」走：
    // · 文件类（SMB / LOCAL）→ 文件夹形态（顶层非空文件夹即分类）
    // · 其余（Emby / Jellyfin / 飞牛）→ 媒体库形态
    val homeSourceType = when (configs?.firstOrNull { it.id == serverId }?.sourceType) {
        // 2026-10-25：SMB 之前漏在这里（只有 LOCAL），导致 SMB 服务器首页仍按「媒体库」形态渲染
        app.myplayer.core.model.SourceType.SMB,
        app.myplayer.core.model.SourceType.LOCAL,
        -> HomeSourceType.SMB
        else -> HomeSourceType.EMBY
    }

    // 数据源跟着 source 走：改端点 / 重新登录后 source 是新实例，旧的地址与令牌都不能再用
    val data: HomeDataSource = remember(source, serverName) {
        source?.let { ServerHomeDataSource(it, serverName.orEmpty()) }
            ?: OfflineHomeDataSource("服务器连接尚未就绪，请到设置页检查登录状态")
    }

    val vm: HomeViewModel = viewModel(
        key = "home-$serverId",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HomeViewModel(
                    serverId,
                    servers,
                    data,
                    // 首页设置：与设置页「首页设置」共用同一份 DataStore（实时联动）
                    app.myplayer.feature.library.home.HomeSettingsRepository(settingsStore),
                    // 档二库存：首页卡片「设为 / 取消隐私」的数据源（SMB/LOCAL 才有值）
                    fileCatalog,
                ) as T
        },
    )
    LaunchedEffect(data) { vm.rebind(data) }
    LaunchedEffect(resetSignal) { if (resetSignal > 0) vm.refresh() }
    LaunchedEffect(enterSignal) { if (enterSignal > 0) vm.refresh() }
    // 播放页返回：重拉一次，让「继续观看」立刻反映播放时的进度
    LaunchedEffect(refreshSignal) { if (refreshSignal > 0) vm.refresh() }
    val state by vm.state.collectAsStateWithLifecycle()

    HomeScreen(
        state = state.copy(
            // 配置还没读出来时继续显示骨架屏，别让空态抢在数据前面
            isLoading = state.isLoading || configs == null,
            sourceType = homeSourceType,
            servers = servers,
            selectedServerId = serverId,
            serverName = serverName ?: state.serverName,
        ),
        loadImage = { item, kind -> loadImageWithFallback(item, kind, source, caps, thumbs) },
        onRefresh = vm::refresh,
        onRetry = vm::refresh,
        // 切源 = 改「当前服务器」，与设置页同一个写入口；serverId 变化后整个主界面会重建
        onSelectServer = { id -> scope.launch { settingsStore.setActiveServerId(id) } },
        onAddServer = onAddServer,
        onPlay = { item -> onPlay(item.id) },
        onOpenDetail = { item -> onOpenDetail(item.id) },
        onOpenLibrary = onOpenLibrary,
        onGoToHomeSettings = onGoToHomeSettings,
        onToggleCardPrivate = vm::toggleCardPrivate,
        privacyActive = privacyActive,
    )
}

/** 搜索 tab：本地索引 + 输入即搜（任务 3-5 / 3-6）。 */@Composable
internal fun SearchDestination(
    repository: LibraryRepository,
    source: MediaSource?,
    serverId: String,
    caps: ClientCapabilities,
    localThumbnails: Boolean,
    onOpenDetail: (String) -> Unit,
    /** 隐私会话结束计数：非 0 变化时清掉搜索历史与当前查询（历史里可能是隐私标题）。 */
    privacyResetSignal: Int = 0,
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
    // 隐私会话结束：搜索历史与输入框里的关键词都可能含私有标题，一律清掉
    LaunchedEffect(privacyResetSignal) {
        if (privacyResetSignal > 0) {
            vm.clearRecent()
            vm.clearQuery()
        }
    }
    app.myplayer.feature.library.SearchScreen(
        state = state,
        onQueryChange = vm::onQueryChange,
        onSubmit = { vm.rememberQuery() },
        onSelectRecent = vm::selectRecent,
        onClear = vm::clearQuery,
        onClearHistory = vm::clearRecent,
        onToggleHistoryExpanded = vm::toggleHistoryExpanded,
        loadImage = { item -> loadPosterWithFallback(item, source, caps, thumbs) },
        // 实时搜索下用户通常不按回车，直接把「点开结果」作为有效搜索的完成信号写入历史
        onOpenDetail = { item ->
            vm.rememberQuery()
            onOpenDetail(item.id)
        },
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
    /** 首页「查看全部」带过来的库 id；选中后调 [onLibraryOpened] 清掉，避免下次进 tab 又被强行切库。 */
    openLibraryId: String? = null,
    onLibraryOpened: () -> Unit = {},
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

    // 「查看全部」带过来的库：库列表可能还在从 Room/服务端来的路上，到位后再选中
    LaunchedEffect(openLibraryId, state.libraries) {
        val target = openLibraryId ?: return@LaunchedEffect
        if (state.libraries.any { it.id == target }) {
            vm.selectLibrary(target)
            onLibraryOpened()
        }
    }

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

/**
 * 「不在 [MainScaffold] 里」的整屏页面统一容器：**不透明背景 + 让出底部手势条**。
 *
 * 背景（2026-10-24 补）：转场是滑动叠加，新页必须自己画满背景，否则滑动过程中会透出
 * 下面那页（真机：「设置 → 添加服务器」两层内容重叠）。这些页面原来是裸 `Column`，
 * 背景全靠窗口，滑动时就成了半透明。
 *
 * 底部 insets：`MainActivity` 只统一避让 `Top + Horizontal`（为了让首页内容能铺到屏幕底、
 * 实现悬浮导航的透底效果），所以没有悬浮导航的页面必须自己补 ——
 * 否则「登录」按钮会被系统手势条压住。
 *
 * **播放页不要用**：它需要满窗口（黑边自己控制）。
 */
@Composable
private fun BottomInsetScreen(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding(),
    ) { content() }
}

/**
 * 给子页面铺一层**不透明背景**。
 *
 * 滑动转场时新页是盖在旧页上面的：页面自己不画背景，滑动过程中就会直接透出下面那一页
 * （2026-10-24 真机：「设置 → 字幕设置」能看到两层内容重叠）。
 *
 * 根级页面各自有背景（[MainScaffold] 的 `Box.background`、列表页自己的容器），
 * 设置子导航这几页是「裸列」，所以必须在导航层补上。
 */
@Composable
private fun OpaqueScreen(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) { content() }
}

/** 页面转场时长与曲线见 [NavTransitions]，不要在这里另写常量。 */

/** 设置子导航的三个路由（tab 根页 + 两个二级页）。 */
private const val SUB_ROUTE_LIST = "list"
private const val SUB_ROUTE_HOME = "home"
private const val SUB_ROUTE_SUBTITLE = "subtitle"
private const val SUB_ROUTE_ABOUT = "about"
