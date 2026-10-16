package app.myplayer.core.database

import app.myplayer.core.model.*
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MappersTest {
    private val dto = MediaItemDto(
        id = "42", serverId = "srv", sourceType = SourceType.EMBY, title = "星际穿越",
        durationTicks = Ticks(9_000_000_000L), sizeBytes = 8_000_000_000L, bitrate = 12_000_000,
        width = 3840, height = 2160, videoCodec = "hevc", audioCodecs = listOf("dts", "ac3"),
        container = "mkv", communityRating = 8.6f, playCount = 3,
        userData = UserData(isPlayed = false, resumePositionTicks = Ticks(600_000_000L), isFavorite = true),
        genres = listOf("科幻", "剧情"), cast = listOf("诺兰"), libraryId = "lib1", hasPoster = true,
    )

    @Test fun `round trip preserves scalar fields`() {
        val back = dto.toEntity().toDto()
        assertThat(back.id).isEqualTo(dto.id)
        assertThat(back.sizeBytes).isEqualTo(dto.sizeBytes)
        assertThat(back.bitrate).isEqualTo(dto.bitrate)
        assertThat(back.durationTicks).isEqualTo(Ticks(9_000_000_000L))
        assertThat(back.userData.resumePositionTicks).isEqualTo(Ticks(600_000_000L))
        assertThat(back.sourceType).isEqualTo(SourceType.EMBY)
        assertThat(back.hasPoster).isTrue()
    }

    @Test fun `round trip preserves list fields`() {
        val back = dto.toEntity().toDto()
        assertThat(back.audioCodecs).isEqualTo(listOf("dts", "ac3"))
        assertThat(back.genres).isEqualTo(listOf("科幻", "剧情"))
        assertThat(back.cast).isEqualTo(listOf("诺兰"))
    }

    @Test fun `null size bytes survives round trip`() {
        assertThat(dto.copy(sizeBytes = null).toEntity().toDto().sizeBytes).isNull()
    }
}
