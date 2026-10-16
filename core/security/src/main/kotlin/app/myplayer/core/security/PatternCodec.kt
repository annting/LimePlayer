package app.myplayer.core.security

/** 图案轨迹 → 稳定字符串（用于派生哈希）。 */
object PatternCodec {
    fun encode(points: List<Int>): String = points.joinToString("-")
}
