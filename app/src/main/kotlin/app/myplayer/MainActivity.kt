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
            MyPlayerTheme {
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
        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner, settings) {
            val observer = LifecycleEventObserver { _, event ->
                val current = settings   // 委托属性不能智能转换，先落成局部值
                if (current?.appLockEnabled != true) return@LifecycleEventObserver
                when (event) {
                    Lifecycle.Event.ON_STOP -> if (!isInPictureInPictureMode) unlockedState.value = false
                    Lifecycle.Event.ON_START -> unlockedState.value =
                        !shouldLock(true, lastUnlockAt, System.currentTimeMillis(), current.unlockGraceMillis)
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
        val activeConfig = servers.firstOrNull()
        val source: MediaSource? = remember(activeConfig) { activeConfig?.let(sourceFactory::create) }
        val showWelcome = activeConfig == null || source == null

        Box(Modifier.fillMaxSize()) {
            AppNavHost(
                repository = libraryRepository,
                serverConfigStore = serverConfigStore,
                tokenStore = tokenStore,
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
