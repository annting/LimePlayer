package app.myplayer.feature.library

import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.SourceType
import app.myplayer.core.model.Ticks
import app.myplayer.core.model.UserData
import androidx.compose.runtime.saveable.SaverScope
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 「排序与筛选」面板的状态层用例：[FilterState.Saver] 往返 + 两个过滤纯函数。
 *
 * 过滤字段里有几个**列表查询不携带**的（分辨率 / 年份），这里钉住「null = 不匹配」的口径 ——
 * 一旦改口径（比如 null 视为不限），用户看到的筛选结果会突然变多，必须显式决策。
 */
class FilterStateTest {

    private fun item(
        id: String,
        title: String = id,
        played: Boolean = false,
        progress: Long = 0L,
        favorite: Boolean = false,
        height: Int? = null,
        year: Int? = null,
        rating: Float? = null,
        dateCreated: Long? = null,
        seriesId: String? = null,
    ) = MediaItemDto(
        id = id,
        serverId = "srv",
        sourceType = SourceType.EMBY,
        title = title,
        seriesId = seriesId,
        durationTicks = Ticks(36_000_000_000L),
        dateCreated = dateCreated,
        communityRating = rating,
        productionYear = year,
        height = height,
        userData = UserData(
            isPlayed = played,
            resumePositionTicks = Ticks(progress),
            isFavorite = favorite,
        ),
    )

    // ---- Saver 往返 ----
    // `save` 是「SaverScope 的扩展、receiver 是 Saver」——需要两个 receiver，嵌套 with 调用

    private fun saveState(state: FilterState): Any =
        SaverScope { true }.run { with(FilterState.Saver) { save(state) } }!!

    private fun restoreState(saved: Any): FilterState = FilterState.Saver.restore(saved)!!


    @Test fun `FilterState Saver 往返保真`() {
        val original = FilterState(
            sortField = DetailSortField.RATING,
            sortDirection = DetailSortDirection.ASC,
            watchStatus = DetailWatchStatus.WATCHING,
            mediaKind = DetailMediaKind.SERIES,
            minResolution = DetailMinResolution.P1080,
            yearRange = DetailYearRange.Y2010,
            favoritesOnly = true,
            resultCount = 42,
        )
        val restored = restoreState(saveState(original))
        assertThat(restored).isEqualTo(original)
    }

    @Test fun `resultCount 为 null 时 Saver 往返仍是 null`() {
        val restored = restoreState(saveState(FilterState()))
        assertThat(restored.resultCount).isNull()
        assertThat(restored.sortField).isEqualTo(DetailSortField.DATE_ADDED)
    }

    // ---- Emby 过滤 ----

    @Test fun `观看状态三分支按已看与进度区分`() {
        val items = listOf(
            item("unwatched"),
            item("watching", progress = 10_000_000_000L),
            item("watched", played = true),
        )
        assertThat(applyDetailFilter(items, FilterState(watchStatus = DetailWatchStatus.UNWATCHED)).map { it.id })
            .containsExactly("unwatched")
        assertThat(applyDetailFilter(items, FilterState(watchStatus = DetailWatchStatus.WATCHING)).map { it.id })
            .containsExactly("watching")
        assertThat(applyDetailFilter(items, FilterState(watchStatus = DetailWatchStatus.WATCHED)).map { it.id })
            .containsExactly("watched")
        assertThat(applyDetailFilter(items, FilterState(watchStatus = DetailWatchStatus.ALL))).hasSize(3)
    }

    @Test fun `列表查询缺失的分辨率与年份不匹配（null = 不匹配）`() {
        val items = listOf(item("no-fields"), item("ok", height = 1080, year = 2015))
        val filtered = applyDetailFilter(
            items,
            FilterState(minResolution = DetailMinResolution.P1080, yearRange = DetailYearRange.Y2010),
        )
        // height/productionYear 为 null 的条目被过滤掉 —— 口径见函数 KDoc
        assertThat(filtered.map { it.id }).containsExactly("ok")
    }

    @Test fun `仅显示收藏`() {
        val items = listOf(item("a"), item("b", favorite = true))
        assertThat(applyDetailFilter(items, FilterState(favoritesOnly = true)).map { it.id })
            .containsExactly("b")
    }

    @Test fun `排序维度与方向`() {
        val items = listOf(
            item("c", rating = 8f, dateCreated = 300L),
            item("a", rating = 9f, dateCreated = 100L),
            item("b", rating = 7f, dateCreated = 200L),
        )
        val byDateAsc = applyDetailFilter(items, FilterState(sortField = DetailSortField.DATE_ADDED, sortDirection = DetailSortDirection.ASC))
        assertThat(byDateAsc.map { it.id }).containsExactly("a", "b", "c").inOrder()
        val byRatingDesc = applyDetailFilter(items, FilterState(sortField = DetailSortField.RATING, sortDirection = DetailSortDirection.DESC))
        assertThat(byRatingDesc.map { it.id }).containsExactly("a", "c", "b").inOrder()
        val byTitleAsc = applyDetailFilter(items, FilterState(sortField = DetailSortField.TITLE, sortDirection = DetailSortDirection.ASC))
        assertThat(byTitleAsc.map { it.id }).containsExactly("a", "b", "c").inOrder()
    }

    // ---- SMB 过滤 ----

    @Test fun `SMB 只应用观看状态与时间名字排序`() {
        val videos = listOf(
            SmbVideoItem("/m/b.mkv", "b.mkv", lastModified = 200L),
            SmbVideoItem("/m/a.mkv", "a.mkv", lastModified = 300L, watched = true),
            SmbVideoItem("/m/c.mkv", "c.mkv", lastModified = 100L),
        )
        val unwatched = applySmbFilter(videos, FilterState(watchStatus = DetailWatchStatus.UNWATCHED, sortField = DetailSortField.TITLE, sortDirection = DetailSortDirection.ASC))
        // watched 的 a 被滤掉；名字升序
        assertThat(unwatched.map { it.name }).containsExactly("b.mkv", "c.mkv").inOrder()
        // 时长 / 评分维度对 SMB 不适用：保持扫描顺序
        val keepOrder = applySmbFilter(videos, FilterState(sortField = DetailSortField.DURATION, sortDirection = DetailSortDirection.DESC))
        assertThat(keepOrder.map { it.name }).containsExactly("b.mkv", "a.mkv", "c.mkv").inOrder()
    }
}
