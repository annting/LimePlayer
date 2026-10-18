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
}
