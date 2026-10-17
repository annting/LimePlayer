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

    // 注：回桌面**不再自动进画中画**（2026-10-18 用户要求）——
    // 自动 PiP 会让「切后台自动暂停」（任务 2-5）永远不触发，两者语义冲突。
    // 画中画改由播放控制层的按钮手动进入（feature:player 的 PlayerScreen）。

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
        var unlocked by remember { mutableStateOf(false) }
        var lastUnlockAt by remember { mutableStateOf<Long?>(null) }

        // 回前台判定：仅在应用锁开启且超过宽限期时重新上锁
        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner, settings) {
            val observer = LifecycleEventObserver { _, event ->
                val current = settings   // 委托属性不能智能转换，先落成局部值
                if (event == Lifecycle.Event.ON_START && current?.appLockEnabled == true) {
                    unlocked = !shouldLock(true, lastUnlockAt, System.currentTimeMillis(), current.unlockGraceMillis)
                }
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }

        LaunchedEffect(lockState.status) {
            if (lockState.status == LockStatus.UNLOCKED) {
                unlocked = true
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
                serverId = if (showWelcome) null else activeConfig.id,
                source = source,
            )
            if (needsLock) {
                PatternLockScreen(lockState, lockVm::onPointTouched, lockVm::onDrawEnd)
            }
        }
    }
}
