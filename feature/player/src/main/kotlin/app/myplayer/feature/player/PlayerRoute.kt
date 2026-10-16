package app.myplayer.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.myplayer.core.model.ClientCapabilities
import app.myplayer.core.model.MediaSource
import app.myplayer.core.playback.Media3PlaybackEngine

/**
 * 播放页入口：创建会话级 [Media3PlaybackEngine]（随 ViewModel 释放），
 * 启动装载（详情 → 协商 → prepare → play），渲染 [PlayerScreen]。
 */
@Composable
fun PlayerRoute(
    itemId: String,
    source: MediaSource,
    caps: ClientCapabilities,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val vm: PlayerViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                PlayerViewModel(Media3PlaybackEngine(context.applicationContext))
            }
        },
    )
    LaunchedEffect(itemId, source) {
        vm.start(itemId, source, caps)
    }
    PlayerScreen(vm = vm, onBack = onBack)
}
