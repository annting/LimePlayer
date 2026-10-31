package app.myplayer

import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import app.myplayer.core.datastore.ServerConfig
import app.myplayer.core.datastore.ServerConfigStore
import app.myplayer.core.datastore.SettingsStore
import app.myplayer.core.datastore.TokenStore
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.designsystem.MyPlayerTheme
import app.myplayer.core.model.ClientCapabilities
import app.myplayer.core.model.LibraryRepository
import app.myplayer.core.model.MediaSource
import app.myplayer.core.security.PatternCredentialStore
import app.myplayer.core.security.privacy.PrivacyUnlocker
import app.myplayer.feature.lock.LockStatus
import app.myplayer.feature.lock.LockViewModel
import app.myplayer.feature.lock.PatternLockScreen
import app.myplayer.feature.lock.PatternSetupScreen
import app.myplayer.feature.privacy.AndroidPrivacyKeystore
import app.myplayer.feature.privacy.PrivacyAccessCoordinator
import app.myplayer.feature.privacy.PrivacyBiometricGate
import app.myplayer.feature.privacy.PrivacyGateScreen
import app.myplayer.feature.privacy.PrivacyLockState
import app.myplayer.feature.privacy.PrivacySessionManager
import app.myplayer.feature.server.SourceFactory
import app.myplayer.source.smb.SmbMediaSource
import app.myplayer.nav.AppNavHost
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * 应用唯一的 Activity。
 *
 * 基类是 **`FragmentActivity`** 而不是 `ComponentActivity`：隐私模式的生物识别/设备密码
 * 走 `BiometricPrompt`，它内部要挂一个 Fragment，只接受 `FragmentActivity`。
 * 其余能力（`setContent` / `enableEdgeToEdge` / `lifecycleScope`）都由它继承自
 * `ComponentActivity`，没有损失。
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var settingsStore: SettingsStore
    @Inject lateinit var credentials: PatternCredentialStore
    @Inject lateinit var libraryRepository: LibraryRepository
    @Inject lateinit var serverConfigStore: ServerConfigStore
    @Inject lateinit var tokenStore: TokenStore
    @Inject lateinit var sourceFactory: SourceFactory
    @Inject lateinit var client: OkHttpClient
    @Inject lateinit var clientCapabilities: ClientCapabilities
    @Inject lateinit var engineHolder: app.myplayer.playback.PlaybackEngineHolder
    /** 本地播放进度（文件型源）：播放页写、库页/首页读。 */
    @Inject lateinit var playbackStore: app.myplayer.core.model.PlaybackStore

    /** 文件库存（SMB/本地条目的档二隐私标记写入口）。 */
    @Inject lateinit var fileCatalog: app.myplayer.core.model.FileCatalogStore

    /** 隐私会话（唯一持有者）：状态驱动可见性策略，也决定 FLAG_SECURE 与重锁。 */
    @Inject lateinit var privacySessionManager: PrivacySessionManager

    @Inject lateinit var privacyUnlocker: PrivacyUnlocker

    /** 私有源敏感字段加密区（设置页「移入隐私」用）。 */
    @Inject lateinit var privacySecrets: app.myplayer.core.datastore.PrivacyServerSecretStore

    /**
     * 隐私门的认证编排。
     *
     * `by lazy` 而不是在 `onCreate` 里 new：它的构造会拿 `this` 当 `FragmentActivity`，
     * 而 BiometricPrompt 内部要 attach Fragment —— 必须等 Activity 真正可用。
     */
    private val privacyGate by lazy { PrivacyBiometricGate(this, privacyUnlocker) }

    private val privacyCoordinator by lazy {
        PrivacyAccessCoordinator(privacySessionManager, privacyGate, AndroidPrivacyKeystore)
    }

    /**
     * 隐私门是否显示。放 Activity 级而非 Composable 内：`onStop` 里也要能把它关掉
     * （用户在门里切后台 → 回来不该还停在门里，而是直接回普通首页）。
     */
    private val privacyGateVisible = androidx.compose.runtime.mutableStateOf(false)

    /**
     * 隐私会话结束的计数。每次「隐私中 → 普通模式」+1，
     * 导航据此把栈弹回主界面 ——**这是「回前台回普通首页」的落地方式**：
     * 否则用户回来会停在刚才那个隐私源的详情页 / 播放页上。
     */
    private val privacyResetSignal = androidx.compose.runtime.mutableIntStateOf(0)

    /**
     * 「刚进入隐私模式」的次数。隐私入口藏在「设置 → 关于」，验证完还停在那儿很怪 ——
     * 进成功就回首页 tab（见 `AppNavHost` / `MainScaffold` 的同名参数）。
     */
    private val privacyEnterSignal = androidx.compose.runtime.mutableIntStateOf(0)

    /** 解锁状态提升到 Activity 级：`onUserLeaveHint` 也要能在离开瞬间预锁（见下）。 */
    private val unlockedState = androidx.compose.runtime.mutableStateOf(false)

    /**
     * 是否处于画中画。播放页据此**取消假横屏**（PiP 窗口按 Activity 内容渲染，
     * 内容转了 90° 会让小窗画面侧躺），回到普通布局后再恢复。
     */
    private val pipState = androidx.compose.runtime.mutableStateOf(false)

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipState.value = isInPictureInPictureMode
    }

    /**
     * 离开应用（Home / 最近任务）的瞬间**预锁**（`02` §6.7）：
     * 此刻窗口仍可见、能重绘一帧，系统保存的任务快照于是就是锁屏本身 ——
     * 否则恢复时系统先显示「离开前的视频快照」，真机可见「先闪一下视频再出现锁屏」。
     *
     * 画中画不锁：PiP 小窗里应继续显示视频（且进 PiP 本身不触发 onUserLeaveHint）。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isInPictureInPictureMode) {
            unlockedState.value = false
            // 隐私模式同样「离开即失效」：此刻窗口还能重绘一帧，任务快照于是是普通界面，
            // 而不是刚才那屏隐私内容（`onStop` 里也会锁，这里是为了抢在快照之前）。
            endPrivacySession()
        }
    }

    /**
     * 退到后台即注销隐私会话（`docs/隐私模式方案.md` §P0#1）。
     *
     * 只靠 `onUserLeaveHint` 不够：下拉通知栏再切走、被其它 App 完全覆盖、分屏切换都可能
     * 不触发它，但一定会 `onStop`。这里再兜一次，且顺手取消可能还挂着的认证框。
     *
     * 回到前台**不自动重开**：导航栈被弹回主界面，要再进隐私模式得重新验证一次。
     */
    override fun onStop() {
        super.onStop()
        endPrivacySession()
    }

    /**
     * 回到前台时的兜底：正常情况下 `onStop` 已经把隐私会话注销掉，能走到这里还是激活态
     * 只剩两种可能 —— 那次 `lock()` 没落地（中途抛异常），或这台 ROM 没回调 `onStop`。
     *
     * 症状是右上角还挂着「隐私」，而首页显示的其实是普通服务器（2026-10-29 用户反馈）。
     * 无论哪种，回到前台都不该再是隐私态 —— 直接补一次注销（幂等）。
     *
     * 画中画不处理：进 PiP 本身就不触发 `onStop`，会话应当继续。
     */
    override fun onStart() {
        super.onStart()
        if (!isInPictureInPictureMode && privacySessionManager.isPrivate) {
            android.util.Log.w("Privacy", "onStart 时仍是隐私态（onStop 未注销成功）→ 兜底注销")
            endPrivacySession()
        }
    }

    /** 注销隐私会话；只有**确实曾经在隐私中**才递增复位信号（避免每次切后台都重置导航）。 */
    private fun endPrivacySession() {
        val wasPrivate = privacySessionManager.isPrivate
        val gateWasVisible = privacyGateVisible.value

        privacySessionManager.lock()
        privacyGateVisible.value = false
        if (gateWasVisible) privacyGate.cancel()

        if (wasPrivate) privacyResetSignal.intValue += 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 未闭合会话修复（docs/10 §8）：进程被杀 / 被系统回收时 watch_history 会留下
        // `endedAt = null` 的行。启动时补一次，用该条目最新断点作为结束位置。
        // 放 IO 线程 + 只做一次 SELECT+UPDATE，不阻塞首帧。
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { playbackStore.repairOpenSessions(System.currentTimeMillis()) }
        }
        setContent {
            // 主题三态（`01` §6 外观）：跟随系统 / 亮 / 暗 —— 设置页改动即时生效
            val themeSettings by settingsStore.settings.collectAsStateWithLifecycle(initialValue = null)
            val darkTheme = when (themeSettings?.themeMode) {
                app.myplayer.core.datastore.ThemeMode.LIGHT -> false
                app.myplayer.core.datastore.ThemeMode.DARK -> true
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            MyPlayerTheme(darkTheme = darkTheme) {
                // 状态栏 / 导航条图标颜色跟随主题（§2.1）。
                // 不设的话系统一直是白图标，亮色主题下压在浅色背景上完全看不见（2026-10-22 真机）。
                val view = androidx.compose.ui.platform.LocalView.current
                androidx.compose.runtime.SideEffect {
                    val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
                    androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = !darkTheme
                        isAppearanceLightNavigationBars = !darkTheme
                    }
                }
                // 显式主题背景 + insets：edge-to-edge 下所有页面统一避让状态栏与刘海，
                // **但底部导航条不一刀切避让**（2026-10-23 改）。
                //
                // 原因：整棵树用 safeDrawing 避让底部，内容就永远画不到导航条那一带，
                // 那块区域只能露出 Surface 的纯色背景 —— 也就是「底部一整块不透明黑边、
                // 把页面内容切断」的根源。而悬浮导航要的正是**内容从导航栏底下穿过去**。
                //
                // 现在的分工：
                // · 顶部 / 左右：仍在这里统一避让（所有页面都不用自己管状态栏与刘海）；
                // · 底部：放开。由「内容页的 contentPadding」+「悬浮导航自己的 navigationBarsPadding」
                //   各管一头 —— 列表最后一行停在导航栏上方，滚动中内容从半透明胶囊下穿过。
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(
                                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                                ),
                            ),
                    ) {
                        AppContent()
                    }
                }
            }
        }
    }

    @Composable
    private fun AppContent() {
        val settings by settingsStore.settings.collectAsStateWithLifecycle(initialValue = null)
        // null = DataStore 首帧还没读到（**区别于**「确实一台服务器都没有」的空列表）。
        // 这个区分是冷启动不闪转场的关键，见下面 AppNavHost 的注释。
        val servers by serverConfigStore.observeAll()
            .collectAsStateWithLifecycle<List<ServerConfig>?>(initialValue = null)
        val lockVm = remember { LockViewModel(credentials) }
        val lockState by lockVm.state.collectAsStateWithLifecycle()
        var lastUnlockAt by remember { mutableStateOf<Long?>(null) }
        // 解锁状态在 Activity 级（onUserLeaveHint 预锁需要），这里直接读写同一份状态
        val unlocked by unlockedState

        // ── 隐私会话 ──
        // 三处消费：FLAG_SECURE（禁截图/录屏/任务快照）、右上角标识、以及门本身。
        val privacyState by privacySessionManager.state.collectAsStateWithLifecycle()
        val privacyActive = privacyState == PrivacyLockState.Unlocked
        val gateVisible by privacyGateVisible

        // 隐私模式：媒体会话只暴露占位元数据（标题/ID 不含真实条目与路径）。
        // 装在这里而不是播放页：引擎是进程级单例，锁屏/通知读的是它，跟哪个页面在播无关。
        LaunchedEffect(privacyActive) {
            engineHolder.engine.setPrivacyMode(privacyActive)
        }

        // 自动填充（方案 §9.5）：隐私模式整窗禁用 Autofill。否则密码管理器会把
        // 私有源的地址/账号/密码收进它自己的库（那是 App 管不到的地方，等于绕过了加密区）。
        // 会话结束立刻恢复 —— 普通模式下用户仍可正常使用密码管理器。
        LaunchedEffect(privacyActive) {
            window.decorView.importantForAutofill = if (privacyActive) {
                android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            } else {
                android.view.View.IMPORTANT_FOR_AUTOFILL_AUTO
            }
        }

        // 旧数据惰性迁移：把仍留在 servers.bin 里的私有源地址/账号搬进加密区（幂等）。
        LaunchedEffect(privacyActive) {
            if (privacyActive) {
                withContext(Dispatchers.IO) { runCatching { serverConfigStore.migratePrivateSecrets() } }
            }
        }

        // ── 隐私：遮挡任务快照（`02` §6.7）──
        // 「是否已录过解锁图案」贯穿三处：FLAG_SECURE 判定、是否盖锁屏、设置页提示
        var patternConfigured by remember { mutableStateOf(credentials.isConfigured()) }
        var showPatternSetup by remember { mutableStateOf(false) }

        // FLAG_SECURE 一旦挂上，系统任务快照 / 最近任务缩略图 / 录屏、截图全部变黑，
        // 这是 Android 上唯一「真的能挡住」的做法（改 contentDescription 之类都是假的）。
        //
        // 两个触发条件：
        // 1. 用户在「设置 → 隐私」里主动打开「遮挡任务快照」；
        // 2. 应用锁开着、**且真的录过图案**、且当前处于锁定态 —— 否则锁屏（含图案轨迹）本身
        //    会被存进最近任务卡片，等于把解锁凭证拍下来给旁边的人看。
        //    注意要带上「已录图案」：没录图案时永远不会进锁定态，若不加这一条，
        //    FLAG_SECURE 会一直挂着 → 用户连截图/录屏都用不了（2026-10-25 自测踩到）。
        val secureWindow = settings?.hideRecentsSnapshot == true ||
            (settings?.appLockEnabled == true && patternConfigured && !unlocked) ||
            // 隐私模式**无条件**禁截图/录屏/任务缩略图：这是隐私功能的硬承诺，
            // 不能因为用户在设置里关掉了「遮挡任务快照」就漏出去（方案 §P0#1）。
            privacyActive ||
            // 验证界面（PIN 键盘 / 首次向导 / 恢复流程）同样要挡：
            // 用户正在输入 PIN，此时被截屏或进最近任务卡片等于把凭证拍下来（方案 §9.6）。
            gateVisible
        LaunchedEffect(secureWindow) {
            if (secureWindow) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

        // 应用锁时序（`02` §6.7）：
        // · **离开瞬间预锁**（onUserLeaveHint，见 Activity 字段说明）—— 让任务快照就是锁屏，
        //   消除「回前台先闪一下视频再出现锁屏」。
        // · ON_STOP 兜底（锁屏键 / 其它 app 完全覆盖 · 画中画不会触发 onStop）。
        // · ON_START 按宽限期决定是否免解锁。
        //
        // ⚠️ key 只放 `owner`，**不能**放 `settings`：LifecycleRegistry.addObserver 会
        // 立刻向新观察者回放一遍 ON_CREATE → ON_START。若把 settings 当 key，
        // 用户每改一个设置项（写 DataStore → settings 变化 → effect 重建）都会收到一次
        // 伪 ON_START，从而在设置页里被反复要求画图案解锁（2026-10-19 真机 bug）。
        // 需要最新设置时用 rememberUpdatedState 读取，而非重建 effect。
        val latestSettings by rememberUpdatedState(settings)
        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner) {
            // 只有真正「进过后台」再回来才重新判定；首次注册的回放不算
            var wentToBackground = false
            val observer = LifecycleEventObserver { _, event ->
                val current = latestSettings   // 委托属性不能智能转换，先落成局部值
                if (current?.appLockEnabled != true) return@LifecycleEventObserver
                when (event) {
                    Lifecycle.Event.ON_STOP -> {
                        wentToBackground = true
                        if (!isInPictureInPictureMode) unlockedState.value = false
                    }
                    Lifecycle.Event.ON_START -> if (wentToBackground) {
                        wentToBackground = false
                        unlockedState.value = !shouldLock(
                            true, lastUnlockAt, System.currentTimeMillis(), current.unlockGraceMillis,
                        )
                    }
                    else -> Unit
                }
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }

        LaunchedEffect(lockState.status) {
            if (lockState.status == LockStatus.UNLOCKED) {
                unlockedState.value = true
                lastUnlockAt = System.currentTimeMillis()
            }
        }

        // 应用锁只在**明确开启**且已录图案时才盖锁屏。
        //
        // 这里曾经写的是 `settings?.appLockEnabled != false`（设置没读出来时按「锁着」处理）：
        // 冷启动头一两帧 settings 还是 null，只要录过图案就会先盖一屏锁屏，等 DataStore
        // 回值发现开关是关的再撤掉 —— 用户看到的就是「没开应用锁却闪一下解锁界面」。
        // 现在改成「未知 ⇒ 不加锁」，并把 NavHost 的渲染也一起等到 settings 到位
        // （见下方 `servers != null && settings != null`），所以不会出现「锁屏没盖住、
        // 内容先露一帧」的反向漏锁。
        val needsLock = shouldShowAppLock(
            appLockEnabled = settings?.appLockEnabled,
            patternConfigured = patternConfigured,
            unlocked = unlocked,
        )

        // 应用锁状态流（任务 2-5）：播放页据此「先解锁、再恢复播放」
        val unlockedFlow = remember { kotlinx.coroutines.flow.MutableStateFlow(false) }
        androidx.compose.runtime.SideEffect { unlockedFlow.value = !needsLock }

        // 锁屏**覆盖**在内容上而非替换内容（`02` §6.7）：NavHost 常驻，
        // 否则回前台重新上锁会把导航栈卸载重建 → 丢失播放页（回到起始页）。
        // 解锁前设置可能未加载，NavHost 先按无服务器渲染，被锁屏挡住即可。
        // 当前服务器：优先 settings.activeServerId（新增服务器后会自动指向新的），
        // 未设置或指向已删除的服务器时回落到列表第一个。
        val activeConfig = servers.orEmpty().firstOrNull { it.id == settings?.activeServerId }
            ?: servers.orEmpty().firstOrNull()
        val source: MediaSource? = remember(activeConfig) { activeConfig?.let(sourceFactory::create) }
        // P1-5：源重建（切服务器 / 删服务器）时释放旧的 SMB 流代理端口，避免端口泄漏
        androidx.compose.runtime.DisposableEffect(source) {
            onDispose { (source as? SmbMediaSource)?.close() }
        }
        val showWelcome = activeConfig == null || source == null

        Box(Modifier.fillMaxSize()) {
            // 服务器列表首帧未到（`servers == null`）时**先不建 NavHost**。
            //
            // 否则会先按 `serverId = null` 建出 `startDestination = WELCOME` 的图，
            // 等 DataStore 回值后 `startDestination` 变成 `MAIN` —— 图被重建，
            // 新的首屏会被播一次「从右滑入」。冷启动时看起来就像首页被推进来的
            // （2026-10-24 真机反馈：「很明显首页是从右侧滑入的」）。
            //
            // 代价是一帧左右的纯背景（DataStore 首帧通常 <50ms），比一次假的页面转场好得多。
            //
            // `settings` 也要等：锁屏是否该盖由它决定（`needsLock`），
            // 只等 servers 的话，开着应用锁的用户可能先看到一帧首页再被锁屏盖住。
            if (servers != null && settings != null) {
                AppNavHost(
                    repository = libraryRepository,
                    serverConfigStore = serverConfigStore,
                    tokenStore = tokenStore,
                    settingsStore = settingsStore,
                    client = client,
                    clientCapabilities = clientCapabilities,
                    engineHolder = engineHolder,
                    playbackStore = playbackStore,
                    appUnlocked = unlockedFlow,
                    keepBackgroundPlayback = settings?.keepBackgroundPlayback ?: false,
                    // 解锁回前台时是否自动续播由它决定（开启 → 停在暂停帧，等用户手动播）
                    appLockEnabled = settings?.appLockEnabled ?: true,
                    patternConfigured = patternConfigured,
                    onSetupPattern = { showPatternSetup = true },
                    autoPlayNext = settings?.autoPlayNext ?: true,
                    localThumbnails = settings?.localThumbnails ?: true,
                    // ── 播放参数（2026-10-25 接线）──
                    // 这几项此前只在设置页展示，播放器全走硬编码（默认倍速 1x、双击 10s、画幅 FIT、
                    // 字幕 16sp），用户改了完全没反应。映射统一放在这里（同下面的方向映射）。
                    defaultSpeed = settings?.defaultSpeed ?: 1f,
                    doubleTapSeekMs = settings?.doubleTapSeekMillis ?: 10_000L,
                    initialScaleMode = when (settings?.defaultAspectRatio) {
                        app.myplayer.core.datastore.AspectRatio.FILL ->
                            app.myplayer.feature.player.ScaleMode.FILL
                        app.myplayer.core.datastore.AspectRatio.CROP ->
                            app.myplayer.feature.player.ScaleMode.CROP
                        app.myplayer.core.datastore.AspectRatio.ORIGINAL ->
                            app.myplayer.feature.player.ScaleMode.ORIGINAL
                        else -> app.myplayer.feature.player.ScaleMode.FIT
                    },
                    subtitleStyle = (settings?.subtitleStyle
                        ?: app.myplayer.core.datastore.SubtitleStyle()).let { st ->
                        app.myplayer.feature.player.PlayerSubtitleStyle(
                            fontSizeSp = st.fontSizeSp,
                            textColorArgb = st.textColorArgb,
                            outlineColorArgb = st.outlineColorArgb,
                            outlineWidthDp = st.outlineWidthDp,
                            verticalPosition = st.verticalPosition,
                        )
                    },
                    serverId = if (showWelcome) null else activeConfig.id,
                    source = source,
                    // 关于页版本号连点 3 下 → 打开隐私门（无任何可见按钮）。
                    // 已经在隐私模式里时忽略：那时再弹一次「输 PIN」既没意义也会把人拦在门外。
                    onPrivacyEntry = { if (!privacyActive) privacyGateVisible.value = true },
                    // 会话结束 → 导航栈弹回主界面（「回前台回普通首页」的落地）
                    privacyResetSignal = privacyResetSignal.intValue,
                    // 进入成功 → 回首页 tab（入口在「关于」页，验完不该还停在那儿）
                    privacyEnterSignal = privacyEnterSignal.intValue,
                    privacyActive = privacyActive,
                    privateSecrets = privacySecrets,
                    fileCatalog = fileCatalog,
                    // datastore 的 ScreenOrientation → 播放模块的 PlayerOrientation（分层映射）
                    inPictureInPicture = pipState.value,
                    defaultOrientation = when (settings?.screenOrientation) {
                        app.myplayer.core.datastore.ScreenOrientation.LANDSCAPE ->
                            app.myplayer.feature.player.PlayerOrientation.LANDSCAPE
                        app.myplayer.core.datastore.ScreenOrientation.PORTRAIT ->
                            app.myplayer.feature.player.PlayerOrientation.PORTRAIT
                        else -> app.myplayer.feature.player.PlayerOrientation.AUTO
                    },
                )
            }
            if (needsLock) {
                PatternLockScreen(lockState, lockVm::onPointTouched, lockVm::onDrawEnd)
            }
            // 图案设置（绘制 → 确认）：全屏覆盖，盖在导航与锁屏之上。
            // 保存成功即视为「已解锁」，避免刚设完图案又被自己拦一次。
            if (showPatternSetup) {
                PatternSetupScreen(
                    credentials = credentials,
                    onDone = {
                        patternConfigured = credentials.isConfigured()
                        unlockedState.value = true
                        lastUnlockAt = System.currentTimeMillis()
                        showPatternSetup = false
                    },
                    onCancel = { showPatternSetup = false },
                )
            }

            // 隐私中标识：右上角一枚小胶囊。刻意不做动画、不闪 ——
            // 它的作用是让机主一眼知道「现在不是普通模式」，而不是抢注意力。
            // 点击即退出（2026-10-29 用户要求去掉确认框：多一步确认反而像在拦人）。
            // 外层 56dp 高容器 + 垂直居中：与各页面 56dp 顶栏（首页服务器名 / 库详情标题）
            // 对齐。原来直接 top=6dp 会明显偏上（2026-10-31 用户反馈）。
            if (privacyActive) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .height(56.dp)
                        .padding(end = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    PrivacyActiveBadge(onClick = { endPrivacySession() })
                }
            }

            // 隐私门：盖在导航、应用锁、图案设置之上的最顶层。
            // 它出现时下面那层可以是任何界面 —— 包括隐私内容本身，因为门只在
            // 尚未进入隐私模式时出现，此刻底层已按普通模式过滤完毕。
            if (gateVisible) {
                PrivacyGateScreen(
                    coordinator = privacyCoordinator,
                    onEntered = {
                        privacyGateVisible.value = false
                        // 回首页：入口在「设置 → 关于」，验完停在那儿会让人以为没进去
                        privacyEnterSignal.intValue += 1
                    },
                    onDismiss = { privacyGateVisible.value = false },
                )
            }
        }
    }
}

/**
 * 隐私模式的身份标识（方案 §A–F 的 E 条：屏幕上要有明确的中标识）。
 *
 * 之所以放在右上角而不是状态栏：这里由应用自己绘制，`FLAG_SECURE` 生效时它仍然可见，
 * 且不会因为系统状态栏图标被折叠而消失。
 */
@Composable
private fun PrivacyActiveBadge(
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val app = LocalAppColors.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(app.surface2)
            .border(1.dp, app.borderStrong, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "隐私",
            style = MaterialTheme.typography.labelSmall,
            color = app.text3,
        )
    }
}
