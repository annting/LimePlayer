package app.myplayer.source.emby

import app.myplayer.core.model.SourceType
import app.myplayer.core.model.StreamKind
import app.myplayer.core.model.Ticks
import app.myplayer.source.httpapi.BaseItemDto
import app.myplayer.source.httpapi.MediaSourceInfoDto
import app.myplayer.source.httpapi.MediaStreamDto
import app.myplayer.source.httpapi.PersonDto
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

    @Test fun `maps detail-only fields`() {
        val item = mapper.map(
            BaseItemDto(id = "i3", name = "沙丘", productionYear = 2021, overview = "沙漠星球的故事"),
            libraryId = null,
        )
        assertThat(item.productionYear).isEqualTo(2021)
        assertThat(item.overview).isEqualTo("沙漠星球的故事")
    }

    @Test fun `detail-only fields stay null when absent`() {
        val item = mapper.map(BaseItemDto(id = "i4", name = "无详情"), libraryId = null)
        assertThat(item.productionYear).isNull()
        assertThat(item.overview).isNull()
    }

    /** 实测（Emby 4.10）：MediaSources 层只有 Container/Size/Bitrate，编码与分辨率在 MediaStreams 里。 */
    @Test fun `codec and resolution fall back to media streams`() {
        val dto = BaseItemDto(
            id = "i5", name = "锻炼腹肌",
            mediaSources = listOf(
                MediaSourceInfoDto(
                    id = "ms1", size = 39_219_535L, bitrate = 1_035_605, container = "webm",
                    // 层上没有 Width/Height/VideoCodec/AudioCodec —— 全在流里
                    mediaStreams = listOf(
                        MediaStreamDto(type = "Video", codec = "vp8", width = 720, height = 480),
                        MediaStreamDto(type = "Audio", codec = "vorbis"),
                        MediaStreamDto(type = "Audio", codec = "vorbis"),  // 同编码去重
                    ),
                ),
            ),
        )
        val item = mapper.map(dto, libraryId = null)
        assertThat(item.container).isEqualTo("webm")
        assertThat(item.videoCodec).isEqualTo("vp8")
        assertThat(item.audioCodecs).containsExactly("vorbis")
        assertThat(item.width).isEqualTo(720)
        assertThat(item.height).isEqualTo(480)
    }

    /** 两处都没有（如流信息为空）时保持 null，不崩。 */
    @Test fun `codec and resolution stay null when streams are empty`() {
        val item = mapper.map(
            BaseItemDto(id = "i6", name = "裸条目", mediaSources = listOf(MediaSourceInfoDto(id = "ms1"))),
            libraryId = null,
        )
        assertThat(item.videoCodec).isNull()
        assertThat(item.audioCodecs).isEmpty()
        assertThat(item.width).isNull()
        assertThat(item.height).isNull()
    }

    // ---- 详情页二期：people / mediaStreams / canDelete（`02` §4.2.1）----

    /** 演职员：只收 Type=Actor 且有 Id 的（无 Id 无法进人物页）；cast 仍收全部演员姓名。 */
    @Test fun `maps people to cast members and filters non-actors`() {
        val dto = BaseItemDto(
            id = "i7", name = "沙丘",
            people = listOf(
                PersonDto(id = "p1", name = "提莫西", role = "保罗", type = "Actor", primaryImageTag = "tag1"),
                PersonDto(id = "p2", name = "维伦纽瓦", role = "导演", type = "Director"),
                PersonDto(name = "无Id演员", type = "Actor"),   // 无 Id：进不了人物页，剔除
            ),
        )
        val item = mapper.map(dto, libraryId = null)
        assertThat(item.cast).containsExactly("提莫西", "无Id演员")   // cast 口径不变
        assertThat(item.people).hasSize(1)
        assertThat(item.people.first().id).isEqualTo("p1")
        assertThat(item.people.first().name).isEqualTo("提莫西")
        assertThat(item.people.first().role).isEqualTo("保罗")
        assertThat(item.people.first().imageTag).isEqualTo("tag1")
    }

    /** 逐流明细：三分区按 Type 分流；未知类型（Data）剔除。 */
    @Test fun `maps media streams by kind and skips unknown types`() {
        val dto = BaseItemDto(
            id = "i8", name = "沙丘",
            mediaSources = listOf(
                MediaSourceInfoDto(
                    id = "ms1",
                    mediaStreams = listOf(
                        MediaStreamDto(type = "Video", codec = "hevc", width = 3840, height = 2160, averageFrameRate = 23.976f, bitDepth = 10, bitRate = 20_000_000),
                        MediaStreamDto(type = "Audio", codec = "dts", language = "chs", channels = 6, sampleRate = 48_000, isDefault = true, displayTitle = "国配 5.1"),
                        MediaStreamDto(type = "Subtitle", codec = "srt", language = "eng", isExternal = true, title = "English SDH"),
                        MediaStreamDto(type = "Data", codec = "bin"),   // 未知类型不展示
                    ),
                ),
            ),
        )
        val streams = mapper.map(dto, libraryId = null).mediaStreams
        assertThat(streams).hasSize(3)
        val (v, a, s) = streams
        assertThat(v.kind).isEqualTo(StreamKind.VIDEO)
        assertThat(v.codec).isEqualTo("hevc")
        assertThat(v.width).isEqualTo(3840)
        assertThat(v.frameRate).isEqualTo(23.976f)
        assertThat(v.bitDepth).isEqualTo(10)
        assertThat(v.bitrate).isEqualTo(20_000_000)
        assertThat(a.kind).isEqualTo(StreamKind.AUDIO)
        assertThat(a.language).isEqualTo("chs")
        assertThat(a.channels).isEqualTo(6)
        assertThat(a.sampleRate).isEqualTo(48_000)
        assertThat(a.displayTitle).isEqualTo("国配 5.1")
        assertThat(a.isDefault).isTrue()
        assertThat(s.kind).isEqualTo(StreamKind.SUBTITLE)
        assertThat(s.displayTitle).isEqualTo("English SDH")   // DisplayTitle 缺失时用 Title 兜底
        assertThat(s.isExternal).isTrue()
    }

    @Test fun `maps canDelete from server flag`() {
        val item = mapper.map(BaseItemDto(id = "i9", name = "可删条目", canDelete = true), libraryId = null)
        assertThat(item.canDelete).isTrue()
        assertThat(mapper.map(BaseItemDto(id = "i10", name = "不可删"), libraryId = null).canDelete).isFalse()
    }
}
