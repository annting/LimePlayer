package app.myplayer.core.model

/**
 * Emby / Jellyfin 的时间单位：1 tick = 100ns，1 秒 = 10,000,000 ticks。
 *
 * **单位口径（docs/02-技术规范.md §4.4）**：
 * - 领域层一律用 `Ticks`（`MediaItemDto.durationTicks`、`UserData.resumePositionTicks`、进度计算）；
 * - 只在两个边界转成 `Long` / ms：
 *   1. **Room 边界**——实体字段仍是 `Long`，在 Mappers 中显式 `.value` / `Ticks(...)`；
 *   2. **播放层边界**——`reportProgress(pos: Duration)` 面向 ExoPlayer（ms）。
 * - 禁止在跨模块传值时用裸 `Long` 表示时间；必须用时写单位注释。
 */
@JvmInline
value class Ticks(val value: Long) {
    /** 向下取整到毫秒（与 ExoPlayer 的 ms 口径对齐）。 */
    val millis: Long get() = value / 10_000L

    /** 向下取整到秒。 */
    val seconds: Long get() = value / 10_000_000L

    companion object {
        val ZERO = Ticks(0L)

        fun ofSeconds(s: Long) = Ticks(s * 10_000_000L)

        fun ofMillis(ms: Long) = Ticks(ms * 10_000L)
    }
}
