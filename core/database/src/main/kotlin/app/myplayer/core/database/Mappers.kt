package app.myplayer.core.database

import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.MediaLibrary
import app.myplayer.core.model.SourceType
import app.myplayer.core.model.Ticks
import app.myplayer.core.model.UserData

/**
 * DTO ↔ 实体的双向映射。两条硬约束（docs/02-技术规范.md §7.1）：
 * 1. 时间字段在实体侧是 `Long`（单位 tick），在此**显式**互转 `Ticks`（§4.4）；
 * 2. 列表字段用**不可见控制字符**编码为单列 —— 标题与演员名里都可能含逗号，不能用 `,`。
 */
private const val SEP = "\u0001"

private fun List<String>.encode() = joinToString(SEP)

private fun String?.decode(): List<String> =
    if (isNullOrEmpty()) emptyList() else split(SEP)

fun MediaItemDto.toEntity() = MediaItemEntity(
    serverId = serverId, itemId = id, sourceType = sourceType.name, title = title,
    sortTitle = title.lowercase(), seriesName = seriesName, seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    durationTicks = durationTicks.value,          // Ticks → Long（Room 边界）
    sizeBytes = sizeBytes,
    bitrate = bitrate, width = width, height = height, videoCodec = videoCodec,
    audioCodecs = audioCodecs.encode(), container = container, dateCreated = dateCreated,
    communityRating = communityRating, officialRating = officialRating, playCount = playCount,
    isPlayed = userData.isPlayed,
    resumePositionTicks = userData.resumePositionTicks.value,   // Ticks → Long（Room 边界）
    isFavorite = userData.isFavorite, genres = genres.encode(), cast = cast.encode(),
    libraryId = libraryId, hasPoster = hasPoster, trickplayAvailable = trickplayAvailable,
)

fun MediaItemEntity.toDto() = MediaItemDto(
    id = itemId, serverId = serverId, sourceType = SourceType.valueOf(sourceType), title = title,
    seriesName = seriesName, seasonNumber = seasonNumber, episodeNumber = episodeNumber,
    durationTicks = Ticks(durationTicks),         // Long → Ticks
    sizeBytes = sizeBytes, bitrate = bitrate, width = width,
    height = height, videoCodec = videoCodec, audioCodecs = audioCodecs.decode(), container = container,
    dateCreated = dateCreated, communityRating = communityRating, officialRating = officialRating,
    playCount = playCount,
    userData = UserData(
        isPlayed = isPlayed,
        resumePositionTicks = Ticks(resumePositionTicks),
        isFavorite = isFavorite,
    ),
    genres = genres.decode(), cast = cast.decode(), libraryId = libraryId,
    hasPoster = hasPoster, trickplayAvailable = trickplayAvailable,
)

fun LibraryEntity.toModel() =
    MediaLibrary(id = libraryId, name = name, itemCount = itemCount, collectionType = collectionType)

fun MediaLibrary.toEntity(serverId: String) =
    LibraryEntity(serverId, id, name, collectionType, itemCount)
