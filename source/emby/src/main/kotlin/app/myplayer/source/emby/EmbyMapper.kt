package app.myplayer.source.emby

import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.SourceType
import app.myplayer.core.model.Ticks
import app.myplayer.core.model.UserData
import app.myplayer.source.httpapi.BaseItemDto

class EmbyMapper(private val serverId: String) {
    fun map(dto: BaseItemDto, libraryId: String?): MediaItemDto {
        val src = dto.mediaSources?.firstOrNull()
        return MediaItemDto(
            id = dto.id, serverId = serverId, sourceType = SourceType.EMBY,
            title = dto.name ?: "", seriesName = dto.seriesName,
            seasonNumber = dto.parentIndexNumber, episodeNumber = dto.indexNumber,
            durationTicks = Ticks(dto.runTimeTicks ?: 0L),        // Long(tick) → Ticks
            sizeBytes = src?.size, bitrate = src?.bitrate, width = src?.width, height = src?.height,
            videoCodec = src?.videoCodec,
            audioCodecs = listOfNotNull(src?.audioCodec), container = src?.container,
            communityRating = dto.communityRating, officialRating = dto.officialRating,
            playCount = dto.userData?.playCount ?: 0,
            userData = UserData(
                isPlayed = dto.userData?.played ?: false,
                resumePositionTicks = Ticks(dto.userData?.playbackPositionTicks ?: 0L),
                isFavorite = dto.userData?.isFavorite ?: false,
            ),
            genres = dto.genres ?: emptyList(),
            cast = dto.people?.filter { it.type == "Actor" }?.mapNotNull { it.name } ?: emptyList(),
            libraryId = libraryId,
            hasPoster = dto.imageTags?.containsKey("Primary") == true,
        )
    }
}
