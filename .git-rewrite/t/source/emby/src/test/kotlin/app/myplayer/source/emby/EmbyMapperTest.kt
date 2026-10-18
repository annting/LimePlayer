package app.myplayer.source.emby

import app.myplayer.core.model.SourceType
import app.myplayer.core.model.Ticks
import app.myplayer.source.httpapi.BaseItemDto
import app.myplayer.source.httpapi.MediaSourceInfoDto
import app.myplayer.source.httpapi.UserDataDto
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EmbyMapperTest {
    private val mapper = EmbyMapper(serverId = "srv-emby")

    @Test fun `maps core fields`() {
        val dto = BaseItemDto(
            id = "i1", name = "沙丘", runTimeTicks = 9_000_000_000L,
            imageTags = mapOf("Primary" to "tag1"),
            mediaSources = listOf(MediaSourceInfoDto(id = "ms1", size = 12_000_000_000L, bitrate = 20_000_000, width = 3840, height = 2160, videoCodec = "hevc", audioCodec = "dts", container = "mkv")),
            userData = UserDataDto(played = false, playbackPositionTicks = 60_000_000L, isFavorite = true, playCount = 2),
        )
        val item = mapper.map(dto, libraryId = "lib1")
        assertThat(item.sourceType).isEqualTo(SourceType.EMBY)
        assertThat(item.serverId).isEqualTo("srv-emby")
        assertThat(item.durationTicks).isEqualTo(Ticks(9_000_000_000L))
        assertThat(item.sizeBytes).isEqualTo(12_000_000_000L)
        assertThat(item.bitrate).isEqualTo(20_000_000)
        assertThat(item.audioCodecs).containsExactly("dts")
        assertThat(item.userData.resumePositionTicks).isEqualTo(Ticks(60_000_000L))
        assertThat(item.userData.isFavorite).isTrue()
        assertThat(item.playCount).isEqualTo(2)
        assertThat(item.libraryId).isEqualTo("lib1")
        assertThat(item.hasPoster).isTrue()
    }

    @Test fun `missing media source leaves size and codecs null or empty`() {
        val item = mapper.map(BaseItemDto(id = "i2", name = "无源"), libraryId = null)
        assertThat(item.sizeBytes).isNull()
        assertThat(item.audioCodecs).isEmpty()
        assertThat(item.hasPoster).isFalse()                 // 无 ImageTags → 无海报
        assertThat(item.durationTicks).isEqualTo(Ticks.ZERO) // 无 RunTimeTicks → 0
    }
}
