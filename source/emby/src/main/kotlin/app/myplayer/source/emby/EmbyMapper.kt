package app.myplayer.source.emby

import app.myplayer.core.model.CastMember
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.MediaStreamInfo
import app.myplayer.core.model.SourceType
import app.myplayer.core.model.StreamKind
import app.myplayer.core.model.Ticks
import app.myplayer.core.model.UserData
import app.myplayer.source.httpapi.BaseItemDto
import app.myplayer.source.httpapi.MediaStreamDto

class EmbyMapper(private val serverId: String) {
    fun map(dto: BaseItemDto, libraryId: String?): MediaItemDto {
        val src = dto.mediaSources?.firstOrNull()
        // 实测（Emby 4.10）：MediaSources 层可能只有 Container/Size/Bitrate，
        // 编码与分辨率在 MediaStreams 里 —— 逐项兜底，两处都没有才留 null。
        val streams = src?.mediaStreams.orEmpty()
        val video = streams.firstOrNull { it.type == "Video" }
        val audio = streams.filter { it.type == "Audio" }
        return MediaItemDto(
            id = dto.id, serverId = serverId, sourceType = SourceType.EMBY,
            title = dto.name ?: "", seriesName = dto.seriesName, seriesId = dto.seriesId,
            seasonNumber = dto.parentIndexNumber, episodeNumber = dto.indexNumber,
            durationTicks = Ticks(dto.runTimeTicks ?: 0L),        // Long(tick) → Ticks
            sizeBytes = src?.size, bitrate = src?.bitrate,
            width = src?.width ?: video?.width, height = src?.height ?: video?.height,
            videoCodec = src?.videoCodec ?: video?.codec,
            audioCodecs = listOfNotNull(src?.audioCodec).ifEmpty { audio.mapNotNull { it.codec }.distinct() },
            container = src?.container,
            path = src?.path,
            communityRating = dto.communityRating, officialRating = dto.officialRating,
            productionYear = dto.productionYear, overview = dto.overview,
            playCount = dto.userData?.playCount ?: 0,
            userData = UserData(
                isPlayed = dto.userData?.played ?: false,
                resumePositionTicks = Ticks(dto.userData?.playbackPositionTicks ?: 0L),
                isFavorite = dto.userData?.isFavorite ?: false,
            ),
            genres = dto.genres ?: emptyList(),
            cast = dto.people?.filter { it.type == "Actor" }?.mapNotNull { it.name } ?: emptyList(),
            // 详情专属（`02` §4.2.1）：结构化演职员（人物 id 缺失时无法进人物页，剔除）。
            people = dto.people
                ?.filter { it.type == "Actor" && it.id != null }
                ?.map { CastMember(id = it.id!!, name = it.name ?: "", role = it.role, imageTag = it.primaryImageTag) }
                ?: emptyList(),
            mediaStreams = streams.mapNotNull { it.toStreamInfo() },
            canDelete = dto.canDelete,
            libraryId = libraryId,
            hasPoster = dto.imageTags?.containsKey("Primary") == true,
        )
    }
}

/** 单条媒体流 → 展示模型（`02` §4.2.1）；Data 等未知流类型不参与展示，返回 null 剔除。 */
private fun MediaStreamDto.toStreamInfo(): MediaStreamInfo? {
    val kind = when (type) {
        "Video" -> StreamKind.VIDEO
        "Audio" -> StreamKind.AUDIO
        "Subtitle" -> StreamKind.SUBTITLE
        else -> return null
    }
    return MediaStreamInfo(
        kind = kind,
        codec = codec,
        language = language,
        displayTitle = displayTitle ?: title,
        width = width, height = height,
        frameRate = averageFrameRate,
        bitDepth = bitDepth,
        channels = channels,
        sampleRate = sampleRate,
        bitrate = bitRate,
        isExternal = isExternal,
        isDefault = isDefault,
    )
}
