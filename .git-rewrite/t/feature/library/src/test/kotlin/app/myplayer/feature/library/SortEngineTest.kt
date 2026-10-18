package app.myplayer.feature.library

import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.SourceType
import app.myplayer.core.model.Ticks
import app.myplayer.core.model.UserData
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Calendar

class SortEngineTest {

    private fun item(
        id: String,
        title: String,
        durationSec: Long = 100,
        size: Long? = 1_000,
        bitrate: Int? = 1_000_000,
        height: Int? = 1080,
        rating: Float? = 7f,
        played: Boolean = false,
        resumeSec: Long = 0,
        favorite: Boolean = false,
        year: Int? = 2020,
        playCount: Int = 0,
        episode: Int? = null,
        seriesName: String? = null,
    ) = MediaItemDto(
        id = id,
        serverId = "s",
        sourceType = SourceType.EMBY,
        title = title,
        seriesName = seriesName,
        episodeNumber = episode,
        durationTicks = Ticks(durationSec * 10_000_000L),
        sizeBytes = size,
        bitrate = bitrate,
        height = height,
        communityRating = rating,
        playCount = playCount,
        dateCreated = year?.let {
            Calendar.getInstance().apply {
                clear()
                set(it, 0, 1)
            }.timeInMillis
        },
        userData = UserData(
            isPlayed = played,
            resumePositionTicks = Ticks(resumeSec * 10_000_000L),
            isFavorite = favorite,
        ),
    )

    @Test
    fun `默认按添加时间降序`() {
        val items = listOf(item("a", "A", year = 1999), item("b", "B", year = 2024), item("c", "C", year = 2010))
        val out = SortEngine.apply(items)
        assertThat(out.map { it.id }).containsExactly("b", "c", "a").inOrder()
    }

    @Test
    fun `按时长升序并保持缺值在末尾`() {
        val items = listOf(
            item("long", "L", durationSec = 300),
            item("none", "N", durationSec = 0),
            item("short", "S", durationSec = 30),
        )
        val out = SortEngine.apply(items, SortSpec(key = SortKey.DURATION, ascending = true))
        assertThat(out.map { it.id }).containsExactly("short", "long", "none").inOrder()
    }

    @Test
    fun `标题忽略大小写升序`() {
        val items = listOf(item("b", "banana"), item("a", "Apple"), item("c", "cherry"))
        val out = SortEngine.apply(items, SortSpec(key = SortKey.TITLE, ascending = true))
        assertThat(out.map { it.title }).containsExactly("Apple", "banana", "cherry").inOrder()
    }

    @Test
    fun `主序相等时按次序键标题升序`() {
        val items = listOf(
            item("b", "B", size = 500),
            item("a", "A", size = 500),
        )
        val out = SortEngine.apply(items, SortSpec(key = SortKey.SIZE, ascending = false))
        assertThat(out.map { it.id }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `筛选观看状态 未看 在看 已看`() {
        val items = listOf(
            item("new", "N"),
            item("mid", "M", resumeSec = 60),
            item("done", "D", played = true),
        )
        assertThat(SortEngine.apply(items, filter = FilterSpec(watchState = WatchState.UNWATCHED)).map { it.id })
            .containsExactly("new")
        assertThat(SortEngine.apply(items, filter = FilterSpec(watchState = WatchState.IN_PROGRESS)).map { it.id })
            .containsExactly("mid")
        assertThat(SortEngine.apply(items, filter = FilterSpec(watchState = WatchState.WATCHED)).map { it.id })
            .containsExactly("done")
    }

    @Test
    fun `筛选类型 收藏 分辨率 年份`() {
        val items = listOf(
            item("movie", "M", height = 2160, favorite = true, year = 2023),
            item("ep", "E", height = 720, year = 2005, episode = 1, seriesName = "S"),
        )
        assertThat(SortEngine.apply(items, filter = FilterSpec(kinds = setOf(ItemKind.EPISODE))).map { it.id })
            .containsExactly("ep")
        assertThat(SortEngine.apply(items, filter = FilterSpec(favoritesOnly = true)).map { it.id })
            .containsExactly("movie")
        assertThat(SortEngine.apply(items, filter = FilterSpec(minHeight = 1080)).map { it.id })
            .containsExactly("movie")
        assertThat(SortEngine.apply(items, filter = FilterSpec(yearFrom = 2010)).map { it.id })
            .containsExactly("movie")
    }

    @Test
    fun `徽标计数只统计非默认条件`() {
        assertThat(FilterSpec().activeCount).isEqualTo(0)
        assertThat(FilterSpec().isDefault).isTrue()
        assertThat(
            FilterSpec(
                kinds = setOf(ItemKind.MOVIE),
                watchState = WatchState.UNWATCHED,
                favoritesOnly = true,
                minHeight = 1080,
                yearFrom = 2010,
            ).activeCount
        ).isEqualTo(5)
    }

    @Test
    fun `类型推断`() {
        assertThat(SortEngine.kindOf(item("m", "M"))).isEqualTo(ItemKind.MOVIE)
        assertThat(SortEngine.kindOf(item("s", "S", seriesName = "S"))).isEqualTo(ItemKind.SERIES)
        assertThat(SortEngine.kindOf(item("e", "E", seriesName = "S", episode = 2))).isEqualTo(ItemKind.EPISODE)
    }
}
