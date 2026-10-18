package app.myplayer.source.httpapi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PublicSystemInfoDto(
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
    @SerialName("ProductName") val productName: String? = null,
)

@Serializable
data class AuthResultDto(
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("User") val user: UserDto,
)

@Serializable
data class UserDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
)

@Serializable
data class BaseItemDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    /** 所属剧集 id（连播取相邻集用）。 */
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("DateCreated") val dateCreated: String? = null,
    @SerialName("CommunityRating") val communityRating: Float? = null,
    @SerialName("OfficialRating") val officialRating: String? = null,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("ChildCount") val childCount: Int? = null,
    /** 有 "Primary" 键 = 有条目海报；用于 `MediaItemDto.hasPoster`，缺海报时不发无意义的图片请求。 */
    @SerialName("ImageTags") val imageTags: Map<String, String>? = null,
    @SerialName("UserData") val userData: UserDataDto? = null,
    @SerialName("MediaSources") val mediaSources: List<MediaSourceInfoDto>? = null,
    @SerialName("Genres") val genres: List<String>? = null,
    @SerialName("People") val people: List<PersonDto>? = null,
)

@Serializable
data class UserDataDto(
    @SerialName("Played") val played: Boolean = false,
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long = 0L,
    @SerialName("IsFavorite") val isFavorite: Boolean = false,
    @SerialName("PlayCount") val playCount: Int = 0,
)

@Serializable
data class PersonDto(
    @SerialName("Name") val name: String? = null,
    @SerialName("Type") val type: String? = null,
)

@Serializable
data class MediaSourceInfoDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Size") val size: Long? = null,
    @SerialName("Bitrate") val bitrate: Int? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("Width") val width: Int? = null,
    @SerialName("Height") val height: Int? = null,
    @SerialName("VideoCodec") val videoCodec: String? = null,
    @SerialName("AudioCodec") val audioCodec: String? = null,
    @SerialName("SupportsDirectPlay") val supportsDirectPlay: Boolean = false,
    @SerialName("SupportsDirectStream") val supportsDirectStream: Boolean = false,
    @SerialName("SupportsTranscoding") val supportsTranscoding: Boolean = false,
    @SerialName("TranscodingUrl") val transcodingUrl: String? = null,
    @SerialName("MediaStreams") val mediaStreams: List<MediaStreamDto> = emptyList(),
)

/** 单条媒体流（音 / 视 / 字幕，`02` §6.1）。字幕轨据此下发外挂地址。 */
@Serializable
data class MediaStreamDto(
    @SerialName("Type") val type: String? = null,
    @SerialName("Index") val index: Int = 0,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("Title") val title: String? = null,
    @SerialName("IsExternal") val isExternal: Boolean = false,
    /** 文本字幕（非图形 PGS/VobSub）——只有这类能外挂下发。 */
    @SerialName("IsTextSubtitleStream") val isTextSubtitleStream: Boolean = false,
    @SerialName("SupportsExternalStream") val supportsExternalStream: Boolean = false,
    @SerialName("IsDefault") val isDefault: Boolean = false,
)

@Serializable
data class ItemsResponseDto(
    @SerialName("Items") val items: List<BaseItemDto> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
)

@Serializable
data class PlaybackInfoResponseDto(
    @SerialName("MediaSources") val mediaSources: List<MediaSourceInfoDto> = emptyList(),
    @SerialName("PlaySessionId") val playSessionId: String? = null,
)

/** 播放进度上报请求体（`/Sessions/Playing*` 共用；`02` §6.4）。 */
@Serializable
data class PlaybackReportRequest(
    @SerialName("ItemId") val itemId: String,
    @SerialName("MediaSourceId") val mediaSourceId: String? = null,
    @SerialName("PositionTicks") val positionTicks: Long = 0,
    @SerialName("IsPaused") val isPaused: Boolean = false,
    @SerialName("IsMuted") val isMuted: Boolean = false,
    @SerialName("PlayMethod") val playMethod: String = "DirectPlay",
    @SerialName("PlaySessionId") val playSessionId: String? = null,
)
