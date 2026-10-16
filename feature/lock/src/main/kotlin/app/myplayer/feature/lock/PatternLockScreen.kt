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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

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

        // 必须在 Composable 作用域读取：Canvas 的绘制 lambda（DrawScope）不是 Composable，不能读 MaterialTheme
        val errorColor = MaterialTheme.colorScheme.error
        val activeColor = MaterialTheme.colorScheme.primary
        val idleColor = MaterialTheme.colorScheme.outline

        Canvas(
            modifier = Modifier
                .size(300.dp)                 // 正方形：width == height == 边长，命中与绘制同源
                .pointerInput(state.status) {
                    sidePx = size.width.toFloat()
                    // 单个手势循环同时覆盖「轻点选中一个点」与「按住拖动连线」。
                    // 不用 detectDragGestures：它要等超过 touch slop 才回调，轻点单个点会毫无反应。
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        hit.indexAt(down.position.x, down.position.y)?.let(onPointTouched)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUpIgnoreConsumed()) { onDrawEnd(); break }
                            if (change.positionChanged()) {
                                hit.indexAt(change.position.x, change.position.y)?.let(onPointTouched)
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
