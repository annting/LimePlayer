package app.myplayer.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

    // 退出统一入口：**先解绑视频面再退**。SurfaceView 是独立窗口图层，
    // Compose 的转场动画管不到它 —— 直接 pop 会看到视频残留在列表页上方，
    // 且这一帧还要等 ExoPlayer 收尾才刷新（2026-10-19 真机 bug：卡一下 + 重叠）。
    var leaving by remember { mutableStateOf(false) }
    val leave: () -> Unit = {
        if (!leaving) {
            leaving = true
            vm.attachVideoSurface(null)
            onBack()
        }
    }
    LaunchedEffect(ui.finished) {
        if (ui.finished) leave()
    }
    // 系统返回键也走同一条退出路径（先解绑 SurfaceView 再 pop）。
    // 弹层打开时让位给 ModalBottomSheet 自己的返回处理（关弹层而非退页）。
    androidx.activity.compose.BackHandler(enabled = !ui.sheetVisible) { leave() }
    PlayerScreen(
        vm = vm,
        onBack = leave,
        appUnlocked = appUnlocked,
        keepBackgroundPlayback = keepBackgroundPlayback,
    )
}
