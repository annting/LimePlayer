package app.myplayer.feature.library.home

import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.Ticks

/**
 * 首页（`docs/03` §6.0）的状态契约。
 *
 * 只有一个 [HomeUiState]，页面里**不允许**再散落 `remember { mutableStateOf }` 的业务状态：
 * 首屏 / 下拉刷新 / 失败 / 空态全部由这里的字段推导（见 [hasNoServer] / [isOffline] / [hasContent]）。
 */
data class HomeUiState(
    /** 首屏加载（骨架屏）。下拉刷新走 [isRefreshing]，不会让内容消失。 */
    val isLoading: Boolean = false,
    /** 下拉刷新中（顶部转圈，内容保留）。 */
    val isRefreshing: Boolean = false,
    /** 加载失败 / 离线的一句话原因；非 null 即进入全屏空态 2。 */
    val error: String? = null,
    /** 当前服务器名（顶部栏左侧）。null = 没有可用服务器。 */
    val serverName: String? = null,
    val continueWatchingList: List<MediaItemDto> = emptyList(),
    val libraries: List<HomeLibrarySection> = emptyList(),
    val recentlyAddedList: List<MediaItemDto> = emptyList(),
    /** 顶部「切换来源」下拉的候选；为空且 [serverName] 也为空 → 全屏空态 1。 */
    val servers: List<HomeServerOption> = emptyList(),
    val selectedServerId: String? = null,
) {
    /** 全屏空态 1：一个服务器都没配。 */
    val hasNoServer: Boolean get() = servers.isEmpty() && serverName == null

    /** 全屏空态 2：有服务器但连不上。 */
    val isOffline: Boolean get() = error != null

    /** 三个模块全空 —— 服务器正常但没有内容（兜底空态，不是异常）。 */
    val hasContent: Boolean
        get() = continueWatchingList.isNotEmpty() || libraries.isNotEmpty() || recentlyAddedList.isNotEmpty()
}

/** 顶部「切换来源」下拉项。 */
data class HomeServerOption(
    val id: String,
    val name: String,
    val baseUrl: String = "",
)

/** 一个媒体库分类块：标题行 + 内部横滑。 */
data class HomeLibrarySection(
    val id: String,
    val name: String,
    val items: List<MediaItemDto> = emptyList(),
    /** 库内总数（> [items] 的条数时「查看全部」更有意义）。 */
    val totalCount: Int = items.size,
)

/**
 * 续播剩余时长文案（「剩余 23 分钟」）。
 *
 * 与 [app.myplayer.core.model.formatDuration] 不同：这里要的是**人话短文案**，
 * 不用「1小时23分45秒」那种精确到秒的表达式（卡片底部只有一行）。
 * 已看完 / 时长未知 → null（调用方据此不渲染该行）。
 */
fun MediaItemDto.remainingWatchText(): String? {
    val leftSeconds = (durationTicks.value - userData.resumePositionTicks.value) / 10_000_000L
    if (leftSeconds <= 0L) return null
    return when {
        leftSeconds >= 3600L -> {
            val hours = leftSeconds / 3600L
            val minutes = (leftSeconds % 3600L) / 60L
            if (minutes > 0L) "剩余 $hours 小时 $minutes 分" else "剩余 $hours 小时"
        }
        // 不足 1 分钟按 1 分钟算，避免出现「剩余 0 分钟」
        else -> "剩余 ${((leftSeconds + 59L) / 60L).coerceAtLeast(1L)} 分钟"
    }
}

/** 进度条占比：复用 `:core:model` 的 [app.myplayer.core.model.resumeFraction]，避免两处口径不一致。 */
internal fun MediaItemDto.progressFraction(): Float {
    if (durationTicks == Ticks.ZERO) return 0f
    return (userData.resumePositionTicks.value.toDouble() / durationTicks.value.toDouble())
        .toFloat()
        .coerceIn(0f, 1f)
}
