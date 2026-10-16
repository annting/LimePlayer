package app.myplayer.feature.player

/** 毫秒 → `"01:23:45"` / `"23:45"`（`03` §5.2 的等宽时间显示）。 */
internal fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0L) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/**
 * 纵向拖动 → 亮度 / 音量值（`03` §5.5：全屏高度 = 全量程，**向上滑增大**）。
 *
 * @param startValue 手势起点时的值（0..1）
 * @param deltaY 累计纵向位移（px，向上为负）
 * @param viewHeightPx 手势区高度（px）
 */
internal fun verticalDragValue(startValue: Float, deltaY: Float, viewHeightPx: Int): Float {
    if (viewHeightPx <= 0) return startValue.coerceIn(0f, 1f)
    return (startValue - deltaY / viewHeightPx).coerceIn(0f, 1f)
}

/** 双击 seek 的增量（`03` §5.5 默认 10s；设置项「5 / 10 / 30」在 P5 接入）。 */
internal const val DOUBLE_TAP_SEEK_MS = 10_000L
