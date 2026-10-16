package app.myplayer.feature.lock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** UI 测试定位用：Canvas 本身不含语义，需要 testTag 才能被测试框架找到。 */
const val PatternLockScreenTestTag = "pattern-lock"

@Composable
fun PatternLockScreen(
    state: LockUiState,
    onPointTouched: (Int) -> Unit,
    onDrawEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 命中半径与点半径都按 dp → px 换算（点直径 12dp、命中半径 24dp）。
    // 不要直接用裸像素值：在高密度屏上点会明显偏小。
    val density = LocalDensity.current
    val hitRadiusPx = with(density) { 24.dp.toPx() }
    val dotRadiusPx = with(density) { 6.dp.toPx() }

    Column(
        modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("绘制解锁图案", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(24.dp))

        var sidePx by remember { mutableStateOf(0f) }
        val hit = remember(sidePx, hitRadiusPx) { PatternHitTest(sidePx, hitRadiusPx) }

        // 手势协程（pointerInput(Unit)）在整块生命周期内只启动一次，闭包捕获的是首次组合
        // 时的实例；命中器会在拿到真实尺寸后重建、回调也可能随重组更新 —— 所以必须经
        // rememberUpdatedState 读最新值，否则手势仍在用 PatternHitTest(0f) 这类旧实例。
        val latestHit by rememberUpdatedState(hit)
        val latestOnPointTouched by rememberUpdatedState(onPointTouched)
        val latestOnDrawEnd by rememberUpdatedState(onDrawEnd)

        // 必须在 Composable 作用域读取：Canvas 的绘制 lambda（DrawScope）不是 Composable，不能读 MaterialTheme
        val errorColor = MaterialTheme.colorScheme.error
        val activeColor = MaterialTheme.colorScheme.primary
        val idleColor = MaterialTheme.colorScheme.outline

        Canvas(
            modifier = Modifier
                .size(300.dp)                 // 正方形：width == height == 边长，命中与绘制同源
                .testTag(PatternLockScreenTestTag)
                // 尺寸从布局回调写入：pointerInput 块启动时布局可能尚未完成（size 为 0），
                // 在那里读 size 会让命中器以 0 边长构造，所有触摸全部 miss（历史 bug）。
                .onSizeChanged { sidePx = it.width.toFloat() }
                // key 用 Unit 而非 state.status：手势进行中状态必然变化（IDLE→DRAWING），
                // 以状态为 key 会取消并重启手势协程，正在进行的拖动被掐断、抬手事件丢失
                // （历史 bug：真机表现为「九宫格无法滑动」）。
                .pointerInput(Unit) {
                    // 单个手势循环同时覆盖「轻点选中一个点」与「按住拖动连线」。
                    // 不用 detectDragGestures：它要等超过 touch slop 才回调，轻点单个点会毫无反应。
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        latestHit.indexAt(down.position.x, down.position.y)?.let(latestOnPointTouched)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUpIgnoreConsumed()) { latestOnDrawEnd(); break }
                            if (change.positionChanged()) {
                                latestHit.indexAt(change.position.x, change.position.y)?.let(latestOnPointTouched)
                            }
                        }
                    }
                }
        ) {
            for (i in 0 until 9) {
                val (cx, cy) = hit.pointCenter(i)
                drawCircle(
                    color = if (i in state.selected) activeColor else idleColor,
                    radius = dotRadiusPx,
                    center = Offset(cx, cy),
                )
            }
            state.selected.zipWithNext { a, b ->
                val (ax, ay) = hit.pointCenter(a)
                val (bx, by) = hit.pointCenter(b)
                val lineColor = if (state.status == LockStatus.ERROR) errorColor else activeColor
                drawLine(lineColor, Offset(ax, ay), Offset(bx, by), strokeWidth = 4f, cap = StrokeCap.Round)
            }
        }

        Spacer(Modifier.height(16.dp))
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
