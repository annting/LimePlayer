package app.myplayer.feature.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * 播放进度条（`03` §5.4 核心规格）。
 *
 * - 轨道 3dp，按下展开 6dp；已播放品牌色 / 已缓冲加深 / 未播放 20% 白；
 * - **提交策略**：轻点立即提交一次；拖动全程只更新预览（[PlayerUiState.scrubPositionMs]），
 *   松手才发**一次** seek —— 拖动中反复起流正是「点进度条卡 1 秒」的根因。
 * - 触摸热区 32dp；外层保证 ≥48dp 可点区。
 */
@Composable
internal fun PlayerProgressBar(
    state: PlayerUiState,
    onScrubStart: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubCommit: () -> Unit,
    onScrubCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val duration = state.durationMs
    val enabled = duration > 0L
    val brand = MaterialTheme.colorScheme.primary

    val trackHeight by animateDpAsState(if (state.scrubbing) 6.dp else 3.dp, label = "trackHeight")
    val thumbRadius by animateDpAsState(if (state.scrubbing) 8.dp else 6.dp, label = "thumbRadius")

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)               // 触摸热区（03 §5.4）
            .pointerInput(enabled, duration) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    val p = (offset.x / size.width).coerceIn(0f, 1f)
                    onScrubStart()
                    onScrub((p * duration).toLong())
                    onScrubCommit()      // 轻点 = 立即提交（乐观更新由状态流回灌）
                }
            }
            .pointerInput(enabled, duration) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        onScrubStart()
                        onScrub(((offset.x / size.width).coerceIn(0f, 1f) * duration).toLong())
                    },
                    onHorizontalDrag = { change, _ ->
                        onScrub(((change.position.x / size.width).coerceIn(0f, 1f) * duration).toLong())
                    },
                    onDragEnd = { onScrubCommit() },
                    onDragCancel = { onScrubCancel() },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxWidth().height(18.dp)) {
            val cy = size.height / 2f
            val th = trackHeight.toPx()
            val r = CornerRadius(th / 2f)
            val progressPx = size.width * state.progress
            val bufferedPx = (state.bufferedMs.toFloat() / duration).coerceIn(0f, 1f) * size.width

            // 未播放轨道
            drawRoundRect(Color.White.copy(alpha = 0.20f), Offset(0f, cy - th / 2f), Size(size.width, th), r)
            // 已缓冲（中性加深）
            drawRoundRect(Color.White.copy(alpha = 0.35f), Offset(0f, cy - th / 2f), Size(bufferedPx, th), r)
            // 已播放（品牌色）
            drawRoundRect(brand, Offset(0f, cy - th / 2f), Size(progressPx, th), r)
            // 拖动柄
            val thumbR = thumbRadius.toPx()
            if (enabled) {
                drawCircle(
                    color = brand,
                    radius = thumbR,
                    center = Offset(progressPx.coerceIn(thumbR, size.width - thumbR), cy),
                )
            }
        }
    }
}
