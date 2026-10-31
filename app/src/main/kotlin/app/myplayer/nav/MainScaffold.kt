package app.myplayer.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.myplayer.core.datastore.ServerConfigStore
import app.myplayer.core.designsystem.Dimens
import app.myplayer.core.designsystem.LocalBottomNavSpace
import app.myplayer.core.designsystem.Spacing
import app.myplayer.core.designsystem.components.EmptyState
import app.myplayer.core.designsystem.components.FloatingBottomNav
import app.myplayer.core.designsystem.components.NavItemSpec
import app.myplayer.core.model.ClientCapabilities
import app.myplayer.core.model.LibraryRepository
import app.myplayer.core.model.MediaSource

/**
 * 登录后的主界面壳。
 *
 * 导航已改为**悬浮胶囊**（`docs/03-UI设计规范.md` §3.1）：不再用 M3 `NavigationBar` 贴底，
 * 而是浮在内容上方 16dp。因此：
 * - 内容区底部预留 `Dimens.navReserved`（导航高 + 上下留白），避免最后一行被挡住；
 * - 导航自己处理 `navigationBars` inset。
 */
@Composable
fun MainScaffold(
    repository: LibraryRepository,
    serverConfigStore: ServerConfigStore,
    source: MediaSource?,
    serverId: String,
    caps: ClientCapabilities,
    localThumbnails: Boolean,
    settingsStore: app.myplayer.core.datastore.SettingsStore,
    tokenStore: app.myplayer.core.datastore.TokenStore,
    onAddServer: () -> Unit,
    onEditServer: (String) -> Unit,
    onPlay: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    /** 打开媒体库详情页（首页「我的媒体库 / 我的文件夹」卡片、「查看全部」）。 */
    onOpenLibraryDetail: (libraryId: String, title: String, smb: Boolean) -> Unit,
    /** 是否已录过解锁图案：设置页据此提示「应用锁是否真的生效」。 */
    patternConfigured: Boolean = false,
    /** 打开图案设置页（由 MainActivity 覆盖层承载）。 */
    onSetupPattern: () -> Unit = {},
    /** 关于页版本号连点 3 下：打开隐私门（隐藏入口）。 */
    onPrivacyEntry: () -> Unit = {},
    /**
     * 隐私会话结束计数。收到就**把 tab 切回首页** —— 会话结束时用户可能正停在
     * 「设置 → 关于」这类深层页面（隐私入口就在那儿），退回普通模式后不该还留在那。
     */
    privacyResetSignal: Int = 0,
    /**
     * 隐私会话**进入成功**的计数。入口在「设置 → 关于」页，验完停在那儿会让人以为没进去 ——
     * 收到就切回首页 tab（顺带把设置页的二级页（关于/字幕设置）从组合里移走，
     * 下次再进设置就是干净的列表页）。
     */
    privacyEnterSignal: Int = 0,
    /** 是否处于隐私模式（服务器行隐私标识 / 移入隐私入口）。 */
    privacyActive: Boolean = false,
    /** 私有源敏感字段加密区（「移入隐私」写凭据用）。 */
    privateSecrets: app.myplayer.core.datastore.PrivacyServerSecretStore? = null,
    /** 档二库存（首页文件夹/视频卡「设为隐私」）；SMB/LOCAL 源才有值。 */
    fileCatalog: app.myplayer.core.model.FileCatalogStore? = null,
    /** 播放页返回计数：首页据此重拉「继续观看」（播放中写的进度要重新 load 才反映）。 */
    homeRefreshSignal: Int = 0,
) {
    // 启动落点 = 首页（聚合页）：一进来就该看到继续观看与最近添加，而不是库页的筛选器。
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.HOME) }
    // 「查看全部」要**带着那个库**一起跳过去：只切 tab 会让库页停在它自己上次选中的库
    var pendingLibraryId by rememberSaveable { mutableStateOf<String?>(null) }
    // 首页「全关」空态的「去设置开启」：切 SETTINGS tab 后自动滚动定位「首页」分组并高亮
    var pendingHomeSettings by rememberSaveable { mutableStateOf(false) }

    // 设置 tab 的二级页（字幕设置 / 关于）盖住整块区域，**不该再显示悬浮导航**。
    // 状态放在这一层而不是让子页自己管：切走 tab 再切回来（子导航会恢复路由）也不会漏；
    // 而 `hideNav` 又限定在 SETTINGS tab，别的 tab 永远不会被这个状态影响。
    var settingsSubPage by rememberSaveable { mutableStateOf(false) }
    val hideNav = selectedTab == MainTab.SETTINGS && settingsSubPage

    // 设置 tab 的 saveable 槽版本号。**光切 tab 清不掉子导航的返回栈**：
    // `subNav` 的栈是 `rememberNavController` 用 `rememberSaveable` 存进 `tabStates` 的，
    // 切走再切回来会被原样恢复 —— 而隐私会话的入口就在「设置 → 关于」，进入/退出后
    // 下次点「设置」会直接落回关于页（2026-10-29 真机，问题 5 修完才暴露）。
    // 每经历一次隐私会话就换一个槽，旧槽（连同「列表 → 关于」的返回栈）一起丢弃。
    // ViewModel 挂在 MAIN 的 store 上，不受换槽影响。
    // 用 `rememberSaveable`：旋转/深色切换重建后版本号要跟着回来，否则会在 #0 / #n 之间多跳一次。
    var settingsSlot by rememberSaveable { mutableStateOf(0) }

    // 隐私会话结束 → 回首页 tab + 丢弃设置子导航的返回栈
    LaunchedEffect(privacyResetSignal) {
        if (privacyResetSignal > 0) {
            selectedTab = MainTab.HOME
            settingsSlot++
            // 换槽后新子导航落在列表页，这里同步把「二级页」标记归零：
            // 否则下次进设置 tab 会先按旧标记把悬浮导航藏起来再放回来（闪一下）
            settingsSubPage = false
        }
    }

    // 进入隐私模式 → 同样回首页 tab（入口在「设置 → 关于」，验完不该还停在那儿）
    LaunchedEffect(privacyEnterSignal) {
        if (privacyEnterSignal > 0) {
            selectedTab = MainTab.HOME
            settingsSlot++
            settingsSubPage = false
        }
    }

    // 服务器配置在**这一层**收集：`when (selectedTab)` 会把上一个 tab 的整棵内容树移出组合，
    // 若下游页面自己收流，每次切回来首帧还是 `initialValue = null`（首页会因此闪一下骨架屏，
    // BUG-2026-002）。放在 MainScaffold 里 → 整段 tab 生命周期内配置流是连续的。
    val configs by serverConfigStore.observeAll().collectAsStateWithLifecycle(initialValue = null)

    // 每个 tab 一个 SaveableState 槽：`rememberSaveable`（LazyVerticalGrid / LazyRow 的滚动位置
    // 都是 saveable 的）在内容被移出组合时保存、切回来时还原 —— 否则切 tab 回来永远是顶部。
    val tabStates = rememberSaveableStateHolder()

    // 悬浮导航的几何（都以「屏幕底边」为基准算）：
    //   胶囊底边 = navBars + navMargin
    //   胶囊顶边 = navBars + navMargin + navHeight   ← 内容最底下一行必须停在这条线以上
    val navBarsBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val navTopEdge = navBarsBottom + Dimens.navMargin + Dimens.navHeight

    // 下发给页面的底部留白 = 胶囊顶边 + 呼吸。
    // 之前是 navReserved(92) + navBars + 各页自己的 24dp ≈ 141dp，滚到底会空出一大截。
    // **不能真的压到 80dp**：80 < 101（一加上手势条 25 + 下边距 16 + 胶囊 60），
    // 最后一行会被胶囊压掉 20dp。按几何算下来 105dp 才是「贴着胶囊但不重叠」。
    //
    // 2026-10-29：呼吸从 4dp 加到 14dp（1080 宽下 +30px）。真机上「滑到底最后一行贴着胶囊」
    // 太挤，四个 tab 页（首页 / 媒体库 / 搜索 / 设置）共用这一个值。
    val navSpace = navTopEdge + Spacing.xs.dp + 10.dp

    // 导航被隐藏时（设置二级页），页面底部只需要让出手势条 ——
    // 否则整屏内容底下会空出 105dp 的空白，看着像被截了一段。
    val contentBottom = if (hideNav) navBarsBottom + Spacing.lg.dp else navSpace

    // 底部渐变遮罩的高度：从胶囊下半部一直盖到屏幕底。
    // 顶边 = navBars + navMargin + navHeight / 2.6（≈ 23dp，比胶囊中心再低 7dp）——
    // 用 / 2 时起点正好在胶囊中心，渐变上半段会"白"到胶囊腰部，看着像一层雾；
    // 下压之后透明区收在胶囊下半部，过渡更贴底、更含蓄。
    val scrimHeight = navBarsBottom + Dimens.navMargin + Dimens.navHeight / 2.6f

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 注意：这里**不加** `padding(bottom = navReserved)`。
        // 加了等于把内容 viewport 裁在导航栏上方，滚动时底下永远是空的（塌回"不透明黑边"）。
        // 内容铺满到屏幕底，底部留白交给各页面的 contentPadding（LocalBottomNavSpace）。
        CompositionLocalProvider(LocalBottomNavSpace provides contentBottom) {
            // tab 之间用 Crossfade 过渡（220ms 淡入淡出）：原来的 when 硬切是
            // 「去设置开启」跳转闪烁的根因 —— HOME 内容瞬间消失、SETTINGS 瞬间出现。
            // SaveableStateProvider 移进 Crossfade lambda：过渡期两个 tab 同时组合，状态互不串。
            Crossfade(
                targetState = selectedTab,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                label = "mainTab",
            ) { tab ->
                // 设置 tab 的槽带版本号（见 settingsSlot 注释）：隐私会话进出后换新槽，
                // 子导航回列表页；其它 tab 保持固定槽，切来切去仍恢复滚动位置。
                tabStates.SaveableStateProvider(tabStateSlotKey(tab, settingsSlot)) {
                    when (tab) {
                    MainTab.HOME -> HomeDestination(
                        serverId = serverId,
                        source = source,
                        caps = caps,
                        localThumbnails = localThumbnails,
                        configs = configs,
                        settingsStore = settingsStore,
                        // 档二库存：首页文件夹/视频卡「设为隐私」写这里（SMB/LOCAL）
                        fileCatalog = fileCatalog,
                        onAddServer = onAddServer,
                        onPlay = onPlay,
                        onOpenDetail = onOpenDetail,
                        // 「我的媒体库」卡片与「查看全部」都进**媒体库详情页**（2026-10-24 改版）；
                        // 旧的「切库 tab」路径（pendingLibraryId）保留给库 tab 自己用
                        onOpenLibrary = onOpenLibraryDetail,
                        onGoToHomeSettings = {
                            pendingHomeSettings = true
                            selectedTab = MainTab.SETTINGS
                        },
                        // 退出隐私 → 首页重拉：否则屏幕上会留着刚看过的私有条目
                        resetSignal = privacyResetSignal,
                        // 进入隐私 → 首页重拉：否则标隐私的文件夹/视频仍在隐藏态，看不到「取消隐私」
                        enterSignal = privacyEnterSignal,
                        // 播放返回 → 首页重拉：播放中写的进度不重拉不会出现在「继续观看」
                        refreshSignal = homeRefreshSignal,
                        // 首页卡片三点菜单（设为 / 取消隐私）只在隐私模式显示
                        privacyActive = privacyActive,
                    )
                    MainTab.LIBRARY -> LibraryDestination(
                        repository = repository,
                        serverConfigStore = serverConfigStore,
                        source = source,
                        serverId = serverId,
                        caps = caps,
                        localThumbnails = localThumbnails,
                        onPlay = onPlay,
                        onOpenDetail = onOpenDetail,
                        openLibraryId = pendingLibraryId,
                        onLibraryOpened = { pendingLibraryId = null },
                    )
                    MainTab.SEARCH -> SearchDestination(
                        repository = repository,
                        source = source,
                        serverId = serverId,
                        caps = caps,
                        localThumbnails = localThumbnails,
                        onOpenDetail = onOpenDetail,
                        privacyResetSignal = privacyResetSignal,
                    )
                    MainTab.SETTINGS -> SettingsDestination(
                        settingsStore = settingsStore,
                        serverConfigStore = serverConfigStore,
                        tokenStore = tokenStore,
                        onAddServer = onAddServer,
                        onEditServer = onEditServer,
                        highlightHomeSettings = pendingHomeSettings,
                        onHomeSettingsHighlightHandled = { pendingHomeSettings = false },
                        onSubPageChange = { settingsSubPage = it },
                        patternConfigured = patternConfigured,
                        onSetupPattern = onSetupPattern,
                        onPrivacyEntry = onPrivacyEntry,
                        // 这两个必须一路传到 SettingsScreen：漏一个则普通模式也会显示「移入隐私」，
                        // 或点了写不进加密区（VM 里 privateSecrets == null 直接短路）
                        privacyActive = privacyActive,
                        privateSecrets = privateSecrets,
                    )
                }
                }
            }
        }

        // 底部渐变遮罩（支付宝同款）：**压在内容上、垫在导航下面**。
        // 从胶囊垂直中心开始由透明渐到纯背景色，最后 20% 已经完全不透明 ——
        // 手势条那一条区域被彻底盖住，内容滑到下面时是"淡出"而不是和手势条叠在一起。
        //
        // 二级页（设置 → 字幕设置 / 关于）整屏内容铺到底，导航与遮罩一起**淡出**：
        // 只做透明度、不做位移 —— 二级页本身是滑入的，导航再滑一次会很乱；
        // 纯淡化正好藏在滑入的页面后面。
        AnimatedVisibility(
            visible = !hideNav,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(140)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(scrimHeight)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.5f to MaterialTheme.colorScheme.background.copy(alpha = 0.78f),
                            0.8f to MaterialTheme.colorScheme.background,
                            1f to MaterialTheme.colorScheme.background,
                        ),
                    ),
            )
        }

        AnimatedVisibility(
            visible = !hideNav,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(140)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            FloatingBottomNav(
                items = MainTab.entries.map { tab ->
                    NavItemSpec(label = tab.label) { tint -> tab.icon(tint) }
                },
                selectedIndex = selectedTab.ordinal,
                onSelect = { index -> selectedTab = MainTab.entries[index] },
            )
        }
    }
}

/**
 * tab 内容在 `rememberSaveableStateHolder` 里的槽 key。
 *
 * **只有「设置」带版本号**：它的子导航（列表 → 关于）会把返回栈通过 `rememberSaveable`
 * 存进槽里，而隐私模式的入口就在「关于」页 —— 进出隐私后不换槽的话，下次点「设置」
 * 会直接落回关于页（2026-10-29 真机，入口跳首页之后暴露）。
 *
 * 其它 tab 用固定 key：切走再切回来照常恢复滚动位置。
 */
internal fun tabStateSlotKey(tab: MainTab, settingsSlot: Int): String =
    if (tab == MainTab.SETTINGS) "${tab.name}#$settingsSlot" else tab.name
