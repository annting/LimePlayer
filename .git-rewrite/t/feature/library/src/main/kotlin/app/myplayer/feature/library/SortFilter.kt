package app.myplayer.feature.library

import app.myplayer.core.model.MediaItemDto
import java.util.Calendar

/**
 * 排序与筛选（`02` §6.3 / 任务 3-4）。
 *
 * 全部在**本地已同步列表**上完成 —— 不产生网络请求，切换条件瞬时生效（无 loading）。
 */
enum class SortKey(val label: String) {
    DATE_ADDED("添加时间"),
    TITLE("标题"),
    DURATION("时长"),
    SIZE("文件大小"),
    BITRATE("码率"),
    RESOLUTION("分辨率"),
    RATING("评分"),
    PLAY_COUNT("播放次数"),
    YEAR("年份"),
}

/**
 * 排序规格：主序 + 次序（`02` §6.3「主序 + 次序组合」，如「时长降序 + 标题升序」）。
 * [secondary] 为主序相等时的次序键，默认按标题。
 */
data class SortSpec(
    val key: SortKey = SortKey.DATE_ADDED,
    val ascending: Boolean = false,
    val secondary: SortKey = SortKey.TITLE,
)

/** 条目类型（领域模型没有显式类型字段，按剧集/集数信息推断）。 */
enum class ItemKind(val label: String) {
    MOVIE("电影"),
    SERIES("剧集"),
    EPISODE("集数"),
}

/** 观看状态筛选。 */
enum class WatchState(val label: String) {
    ALL("全部"),
    UNWATCHED("未看"),
    IN_PROGRESS("在看"),
    WATCHED("已看"),
}

/**
 * 筛选条件。默认全部放开 —— [activeCount] 用于「筛选」按钮上的数量徽标（0 = 不显示）。
 */
data class FilterSpec(
    val kinds: Set<ItemKind> = ItemKind.entries.toSet(),
    val watchState: WatchState = WatchState.ALL,
    val favoritesOnly: Boolean = false,
    /** 年份区间（含端点，按 `dateCreated` 的年份；缺日期视为不命中区间筛选）。 */
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    /** 分辨率下限（如 720 / 1080 / 2160，按视频高度判定）。 */
    val minHeight: Int? = null,
) {
    /** 生效中的筛选条数（徽标数字）。 */
    val activeCount: Int
        get() = listOfNotNull(
            if (kinds.size < ItemKind.entries.size) 1 else null,
            if (watchState != WatchState.ALL) 1 else null,
            if (favoritesOnly) 1 else null,
            if (yearFrom != null || yearTo != null) 1 else null,
            if (minHeight != null) 1 else null,
        ).size

    val isDefault: Boolean get() = activeCount == 0
}

object SortEngine {

    /** 条目类型推断（无显式类型字段时的约定）。 */
    fun kindOf(item: MediaItemDto): ItemKind = when {
        item.episodeNumber != null -> ItemKind.EPISODE
        item.seriesName != null -> ItemKind.SERIES
        else -> ItemKind.MOVIE
    }

    fun yearOf(item: MediaItemDto): Int? =
        item.dateCreated?.let {
            Calendar.getInstance().apply { timeInMillis = it }.get(Calendar.YEAR)
        }

    /** 筛选 + 排序；返回新列表（不改原列表）。 */
    fun apply(
        items: List<MediaItemDto>,
        sort: SortSpec = SortSpec(),
        filter: FilterSpec = FilterSpec(),
    ): List<MediaItemDto> {
        val filtered = items.filter { filter.matches(it) }
        return filtered.sortedWith(comparator(sort.key, sort.ascending).then(comparator(sort.secondary, true)))
    }

    private fun FilterSpec.matches(item: MediaItemDto): Boolean {
        if (kindOf(item) !in kinds) return false
        if (favoritesOnly && !item.userData.isFavorite) return false
        when (watchState) {
            WatchState.ALL -> Unit
            WatchState.WATCHED -> if (!item.userData.isPlayed) return false
            WatchState.IN_PROGRESS ->
                if (item.userData.isPlayed || item.userData.resumePositionTicks.value <= 0L) return false
            WatchState.UNWATCHED ->
                if (item.userData.isPlayed || item.userData.resumePositionTicks.value > 0L) return false
        }
        val year = yearOf(item)
        if (yearFrom != null && (year == null || year < yearFrom)) return false
        if (yearTo != null && (year == null || year > yearTo)) return false
        if (minHeight != null && (item.height ?: 0) < minHeight) return false
        return true
    }

    /** 单键比较器：缺值一律排最后（无论升降序），避免 null 占满列表头部。 */
    private fun comparator(key: SortKey, ascending: Boolean): Comparator<MediaItemDto> =
        Comparator { a, b ->
            val aNull = isNull(key, a)
            val bNull = isNull(key, b)
            when {
                aNull && bNull -> 0
                aNull -> 1
                bNull -> -1
                else -> {
                    val c = compareNonNull(key, a, b)
                    if (ascending) c else -c
                }
            }
        }

    private fun isNull(key: SortKey, item: MediaItemDto): Boolean = when (key) {
        SortKey.DATE_ADDED -> item.dateCreated == null
        SortKey.TITLE -> false
        SortKey.DURATION -> item.durationTicks.value <= 0L
        SortKey.SIZE -> item.sizeBytes == null
        SortKey.BITRATE -> item.bitrate == null
        SortKey.RESOLUTION -> item.height == null
        SortKey.RATING -> item.communityRating == null
        SortKey.PLAY_COUNT -> false
        SortKey.YEAR -> yearOf(item) == null
    }

    /** 前提：两侧都非空。 */
    private fun compareNonNull(key: SortKey, a: MediaItemDto, b: MediaItemDto): Int = when (key) {
        SortKey.DATE_ADDED -> a.dateCreated!!.compareTo(b.dateCreated!!)
        SortKey.TITLE -> a.title.compareTo(b.title, ignoreCase = true)
        SortKey.DURATION -> a.durationTicks.value.compareTo(b.durationTicks.value)
        SortKey.SIZE -> a.sizeBytes!!.compareTo(b.sizeBytes!!)
        SortKey.BITRATE -> a.bitrate!!.compareTo(b.bitrate!!)
        SortKey.RESOLUTION -> a.height!!.compareTo(b.height!!)
        SortKey.RATING -> a.communityRating!!.compareTo(b.communityRating!!)
        SortKey.PLAY_COUNT -> a.playCount.compareTo(b.playCount)
        SortKey.YEAR -> yearOf(a)!!.compareTo(yearOf(b)!!)
    }
}
