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

    /**
     * 回桌面 / 切应用时若正在播放 → 自动进画中画（任务 2-4）。
     * 宽高比先固定 16:9；等 [app.myplayer.core.playback.EngineState.videoWidth] 接入 UI 后按视频实际比例。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val es = engineHolder.engine.state.value
        if (es.isPlaying && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val ratio = if (es.videoWidth > 0 && es.videoHeight > 0) {
                android.util.Rational(es.videoWidth, es.videoHeight).apply {
                    // PiP 限制 2.39:1 ～ 1:2.39，越界时收敛
                    if (toFloat() > 2.39f) android.util.Rational(239, 100)
                    else if (toFloat() < 1f / 2.39f) android.util.Rational(100, 239)
                    else this
                }
            } else {
                android.util.Rational(16, 9)
            }
            val params = android.app.PictureInPictureParams.Builder()
                .setAspectRatio(ratio)
                .build()
            enterPictureInPictureMode(params)
        }
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
        if (needsLock) {
            PatternLockScreen(lockState, lockVm::onPointTouched, lockVm::onDrawEnd)
        } else {
            val activeConfig = servers.firstOrNull()
            // 无服务器 → null（落引导页）；有配置但令牌缺失 → 工厂返回 null，同样落引导页等重新登录
            val source: MediaSource? = remember(activeConfig) { activeConfig?.let(sourceFactory::create) }
            val showWelcome = activeConfig == null || source == null

            AppNavHost(
                repository = libraryRepository,
                serverConfigStore = serverConfigStore,
                tokenStore = tokenStore,
                client = client,
                clientCapabilities = clientCapabilities,
                engineHolder = engineHolder,
                serverId = if (showWelcome) null else activeConfig.id,
                source = source,
            )
        }
    }
}
