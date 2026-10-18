package app.myplayer.feature.lock

/** 3x3 九宫格命中判定；坐标以画布左上角为原点。命中与绘制共用本类，保证两套口径一致。 */
class PatternHitTest(private val canvasSize: Float, private val hitRadius: Float) {
    private val margin = canvasSize / 5f   // 60 at 300

    fun pointCenter(index: Int): Pair<Float, Float> {
        val row = index / 3
        val col = index % 3
        val spacing = (canvasSize - margin * 2f) / 2f
        return (margin + col * spacing) to (margin + row * spacing)
    }

    fun indexAt(x: Float, y: Float): Int? {
        for (i in 0 until 9) {
            val (cx, cy) = pointCenter(i)
            val dx = x - cx
            val dy = y - cy
            if (dx * dx + dy * dy <= hitRadius * hitRadius) return i
        }
        return null
    }
}
