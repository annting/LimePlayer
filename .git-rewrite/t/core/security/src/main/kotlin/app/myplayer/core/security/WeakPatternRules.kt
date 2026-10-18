package app.myplayer.core.security

/**
 * 弱图案规则（docs/02-技术规范.md §6 应用锁）：
 * 少于 4 点，或所有点共线（同一行 / 同一列 / 同一对角线）→ 拒绝保存。
 */
object WeakPatternRules {
    private const val MIN_POINTS = 4

    fun isWeak(points: List<Int>): Boolean {
        if (points.size < MIN_POINTS) return true
        val coords = points.map { it / 3 to it % 3 }
        val first = coords.first()
        return coords.all { (r, c) -> r == first.first } ||
            coords.all { (r, c) -> c == first.second } ||
            coords.all { (r, c) -> r - c == first.first - first.second } ||
            coords.all { (r, c) -> r + c == first.first + first.second }
    }
}
