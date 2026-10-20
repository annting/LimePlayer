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

    // 退出统一入口：**先解绑视频面再退**。2026-10-20 起视频面是 TextureView（画在窗口里），
    // 但退出前解绑仍然保留：这一帧不该再等 ExoPlayer 收尾，否则会「卡一下」。
    //
    // 注意：横竖屏切换只旋转播放器内容，**系统方向从头到尾没变**，
    // 所以退出时没有任何方向还原动作 —— 不需要黑幕、也没有旋转动画。
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
    // 系统返回键也走同一条退出路径。
    // 弹层打开时让位给弹层自己的返回处理（关弹层而非退页）。
    androidx.activity.compose.BackHandler(enabled = !ui.sheetVisible) { leave() }
    PlayerScreen(
        vm = vm,
        onBack = leave,
        appUnlocked = appUnlocked,
        keepBackgroundPlayback = keepBackgroundPlayback,
    )
}
