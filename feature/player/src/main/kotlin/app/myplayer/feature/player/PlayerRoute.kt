package app.myplayer.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.myplayer.core.model.ClientCapabilities
import app.myplayer.core.model.MediaSource

/**
 * 播放页入口：使用进程级共享引擎（由前台服务持有，见 [engine] 注入方），
 * 启动装载（详情 → 协商 → prepare → play），渲染 [PlayerScreen]。
 */
@Composable
fun PlayerRoute(
    itemId: String,
    source: MediaSource,
    caps: ClientCapabilities,
    engine: app.myplayer.core.playback.PlaybackEngine,
    onBack: () -> Unit,
    appUnlocked: kotlinx.coroutines.flow.StateFlow<Boolean>? = null,
    keepBackgroundPlayback: Boolean = false,
    autoPlayNext: Boolean = true,
) {
    val vm: PlayerViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                PlayerViewModel(engine)
            }
        },
    )
    LaunchedEffect(itemId, source) {
        vm.start(itemId, source, caps, autoPlayNext)
    }
    // 播完且无下一集 → 回列表（`03` §5.7）
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.finished) {
        if (ui.finished) onBack()
    }
    PlayerScreen(
        vm = vm,
        onBack = onBack,
        appUnlocked = appUnlocked,
        keepBackgroundPlayback = keepBackgroundPlayback,
    )
}
