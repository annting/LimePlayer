package app.myplayer.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaItemDtoTest {
    private fun item(duration: Ticks, resume: Ticks) = MediaItemDto(
        id = "1", serverId = "s1", sourceType = SourceType.EMBY, title = "T",
        durationTicks = duration, userData = UserData(resumePositionTicks = resume),
    )

    @Test fun `resume fraction is zero when duration unknown`() {
        assertThat(item(Ticks.ZERO, Ticks(500L)).resumeFraction()).isEqualTo(0f)
    }

    @Test fun `resume fraction is ratio of resume to duration`() {
        assertThat(item(Ticks(100L), Ticks(25L)).resumeFraction()).isEqualTo(0.25f)
    }

    @Test fun `resume fraction clamps above one`() {
        assertThat(item(Ticks(100L), Ticks(999L)).resumeFraction()).isEqualTo(1f)
    }

    @Test fun `hasPoster defaults to false`() {
        assertThat(item(Ticks.ZERO, Ticks.ZERO).hasPoster).isFalse()
    }

    @Test fun `paged result reports hasMore`() {
        assertThat(PagedResult(listOf("a", "b"), 5, 0).hasMore).isTrue()
        assertThat(PagedResult(listOf("d", "e"), 5, 3).hasMore).isFalse()
    }

    private fun episode(season: Int?, number: Int?, title: String) = MediaItemDto(
        id = title, serverId = "s1", sourceType = SourceType.EMBY, title = title,
        seasonNumber = season, episodeNumber = number,
    )

    @Test fun `detail-only fields default to null`() {
        val item = item(Ticks.ZERO, Ticks.ZERO)
        assertThat(item.overview).isNull()
        assertThat(item.productionYear).isNull()
    }

    @Test fun `seasons sort ascending and unknown season sorts last`() {
        val groups = listOf(
            episode(season = null, number = 1, title = "特典"),
            episode(season = 2, number = 1, title = "S2E1"),
            episode(season = 1, number = 2, title = "S1E2"),
            episode(season = 1, number = 1, title = "S1E1"),
        ).groupBySeason()

        assertThat(groups.map { it.seasonNumber }).containsExactly(1, 2, null).inOrder()
        assertThat(groups.first().episodes.map { it.title }).containsExactly("S1E1", "S1E2").inOrder()
    }

    @Test fun `episodes without numbers sort by title`() {
        val groups = listOf(
            episode(season = 1, number = null, title = "B"),
            episode(season = 1, number = null, title = "A"),
            episode(season = 1, number = 3, title = "C"),
        ).groupBySeason()

        assertThat(groups.single().episodes.map { it.title }).containsExactly("C", "A", "B").inOrder()
    }
}
