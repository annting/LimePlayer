package app.myplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import app.myplayer.core.datastore.ServerConfigStore
import app.myplayer.core.datastore.SettingsStore
import app.myplayer.core.datastore.TokenStore
import app.myplayer.core.designsystem.MyPlayerTheme
import app.myplayer.core.model.ClientCapabilities
import app.myplayer.core.model.LibraryRepository
import app.myplayer.core.model.MediaSource
import app.myplayer.core.security.PatternCredentialStore
import app.myplayer.feature.lock.LockStatus
import app.myplayer.feature.lock.LockViewModel
import app.myplayer.feature.lock.PatternLockScreen
import app.myplayer.feature.server.SourceFactory
import app.myplayer.nav.AppNavHost
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.OkHttpClient
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsStore: SettingsStore
    @Inject lateinit var credentials: PatternCredentialStore
    @Inject lateinit var libraryRepository: LibraryRepository
    @Inject lateinit var serverConfigStore: ServerConfigStore
    @Inject lateinit var tokenStore: TokenStore
    @Inject lateinit var sourceFactory: SourceFactory
    @Inject lateinit var client: OkHttpClient
    @Inject lateinit var clientCapabilities: ClientCapabilities
    @Inject lateinit var engineHolder: app.myplayer.playback.PlaybackEngineHolder

    /** 解锁状态提升到 Activity 级：`onUserLeaveHint` 也要能在离开瞬间预锁（见下）。 */
    private val unlockedState = androidx.compose.runtime.mutableStateOf(false)

    /**
     * 离开应用（Home / 最近任务）的瞬间**预锁**（`02` §6.7）：
     * 此刻窗口仍可见、能重绘一帧，系统保存的任务快照于是就是锁屏本身 ——
     * 否则恢复时系统先显示「离开前的视频快照」，真机可见「先闪一下视频再出现锁屏」。
     *
     * 画中画不锁：PiP 小窗里应继续显示视频（且进 PiP 本身不触发 onUserLeaveHint）。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isInPictureInPictureMode) unlockedState.value = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // 主题三态（`01` §6 外观）：跟随系统 / 亮 / 暗 —— 设置页改动即时生效
            val themeSettings by settingsStore.settings.collectAsStateWithLifecycle(initialValue = null)
            val darkTheme = when (themeSettings?.themeMode) {
                app.myplayer.core.datastore.ThemeMode.LIGHT -> false
                app.myplayer.core.datastore.ThemeMode.DARK -> true
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            MyPlayerTheme(darkTheme = darkTheme) {
                // 显式主题背景 + safeDrawing insets：edge-to-edge 下所有页面统一避让
                // 状态栏/导航条，否则顶部内容会被状态栏挤压（2026-10-17 真机实测）。
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        AppContent()
                    }
                }
            }
        }
    }

    @Composable
    private fun AppContent() {
        val settings by settingsStore.settings.collectAsStateWithLifecycle(initialValue = null)
        val servers by serverConfigStore.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
        val lockVm = remember { LockViewModel(credentials) }
        val lockState by lockVm.state.collectAsStateWithLifecycle()
        var lastUnlockAt by remember { mutableStateOf<Long?>(null) }
        // 解锁状态在 Activity 级（onUserLeaveHint 预锁需要），这里直接读写同一份状态
        val unlocked by unlockedState

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

        // 设置尚未加载完（settings == null）时按「锁着」处理：宁可多解一次，也不要漏锁一帧
        val needsLock = settings?.appLockEnabled != false && !unlocked

        // 应用锁状态流（任务 2-5）：播放页据此「先解锁、再恢复播放」
        val unlockedFlow = remember { kotlinx.coroutines.flow.MutableStateFlow(false) }
        androidx.compose.runtime.SideEffect { unlockedFlow.value = !needsLock }

        // 锁屏**覆盖**在内容上而非替换内容（`02` §6.7）：NavHost 常驻，
        // 否则回前台重新上锁会把导航栈卸载重建 → 丢失播放页（回到起始页）。
        // 解锁前设置可能未加载，NavHost 先按无服务器渲染，被锁屏挡住即可。
        // 当前服务器：优先 settings.activeServerId（新增服务器后会自动指向新的），
        // 未设置或指向已删除的服务器时回落到列表第一个。
        val activeConfig = servers.firstOrNull { it.id == settings?.activeServerId }
            ?: servers.firstOrNull()
        val source: MediaSource? = remember(activeConfig) { activeConfig?.let(sourceFactory::create) }
        val showWelcome = activeConfig == null || source == null

        Box(Modifier.fillMaxSize()) {
            AppNavHost(
                repository = libraryRepository,
                serverConfigStore = serverConfigStore,
                tokenStore = tokenStore,
                settingsStore = settingsStore,
                client = client,
                clientCapabilities = clientCapabilities,
                engineHolder = engineHolder,
                appUnlocked = unlockedFlow,
                keepBackgroundPlayback = settings?.keepBackgroundPlayback ?: false,
                autoPlayNext = settings?.autoPlayNext ?: true,
                localThumbnails = settings?.localThumbnails ?: true,
                serverId = if (showWelcome) null else activeConfig.id,
                source = source,
            )
            if (needsLock) {
                PatternLockScreen(lockState, lockVm::onPointTouched, lockVm::onDrawEnd)
            }
        }
    }
}
