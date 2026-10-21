package app.myplayer.feature.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
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

    // ── 退出流程（2026-10-20 真机反馈后重做）──
    //
    // 旧实现是「先 `attachVideoSurface(null)` 再立刻 pop」，两个毛病：
    // ① 解绑会让 TextureView 定格在最后一帧，并重算一次 surface 变换 → 画面**整体下移约 5dp**；
    // ② Navigation-Compose 默认 popEnterTransition 是 700ms，返回后播放页要等转场跑完才销毁，
    //    定格的最后一帧就在屏幕上停了 700ms（「卡几百毫秒」）。
    //
    // 现在：**不解绑、不拖延**，立刻 pop。退场交给导航的「交叉淡化」（见 AppNavHost 里
    // 播放页的 `popExitTransition`）—— 视频在淡出的 220ms 里继续播放，
    // 列表页同时在这段时间完成组合，既没有定格帧也没有全黑空档。
    // 解绑与引擎收尾交给 `PlayerViewModel.onCleared → engine.stopSession()`，
    // 那时播放页已经不在屏幕上，用户看不到任何残留。
    var leaving by remember { mutableStateOf(false) }
    val leave: () -> Unit = {
        if (!leaving) {
            leaving = true
            onBack()
        }
    }
    LaunchedEffect(ui.finished) {
        if (ui.finished) leave()
    }
    // 系统返回键也走同一条退出路径。
    // 弹层打开时让位给弹层自己的返回处理（关弹层而非退页）。
    androidx.activity.compose.BackHandler(enabled = !ui.sheetVisible, onBack = leave)

    // 播放页要「满窗口」：MainActivity 给所有页面加了 safeDrawing 内边距（避开状态栏 / 导航条），
    // 这里反向抵消 —— 用窗口尺寸测量后按负偏移摆放，等价于贴满整个窗口。
    // 之所以不用全局开关：切页时翻转开关会让整棵树重排，正在淡出的播放页会被挤一下
    // （2026-10-20 真机 bug：「视频往下挪几个像素」）。
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val left = with(density) { insets.calculateLeftPadding(layoutDirection).roundToPx() }
    val top = with(density) { insets.calculateTopPadding().roundToPx() }
    val right = with(density) { insets.calculateRightPadding(layoutDirection).roundToPx() }
    val bottom = with(density) { insets.calculateBottomPadding().roundToPx() }
    Box(
        Modifier
            .fillMaxSize()

            .layout { measurable, constraints ->
                val placeable = measurable.measure(
                    Constraints.fixed(
                        constraints.maxWidth + left + right,
                        constraints.maxHeight + top + bottom,
                    )
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(-left, -top)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        PlayerScreen(
            vm = vm,
            onBack = leave,
            appUnlocked = appUnlocked,
            keepBackgroundPlayback = keepBackgroundPlayback,
        )
    }
}
