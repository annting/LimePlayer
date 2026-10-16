package app.myplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.myplayer.core.datastore.ServerConfigStore
import app.myplayer.core.datastore.SettingsStore
import app.myplayer.core.designsystem.MyPlayerTheme
import app.myplayer.core.model.LibraryRepository
import app.myplayer.core.security.PatternCredentialStore
import app.myplayer.feature.lock.LockStatus
import app.myplayer.feature.lock.LockViewModel
import app.myplayer.feature.lock.PatternLockScreen
import app.myplayer.nav.AppNavHost
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsStore: SettingsStore
    @Inject lateinit var credentials: PatternCredentialStore
    @Inject lateinit var libraryRepository: LibraryRepository
    @Inject lateinit var serverConfigStore: ServerConfigStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyPlayerTheme {
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
                    AppNavHost(
                        repository = libraryRepository,
                        serverId = servers.firstOrNull()?.id,
                        source = null,                       // ← 任务 1-14 换成 SourceFactory 造出的真实 source
                        onAddServer = { /* ← 任务 1-13/1-14 接上 Routes.ADD_SERVER */ },
                    )
                }
            }
        }
    }
}
