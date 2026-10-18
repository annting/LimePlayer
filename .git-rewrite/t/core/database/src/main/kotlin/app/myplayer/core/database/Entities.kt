package app.myplayer.core.database

import androidx.room.Entity
import androidx.room.Index

@Entity(tableName = "libraries", primaryKeys = ["serverId", "libraryId"])
data class LibraryEntity(
    val serverId: String,
    val libraryId: String,
    val name: String,
    val collectionType: String?,
    val itemCount: Int?,
)

@Entity(
    tableName = "media_items",
    primaryKeys = ["serverId", "itemId"],
    indices = [Index("serverId"), Index("libraryId"), Index("sortTitle")],
)
data class MediaItemEntity(
    val serverId: String,
    val itemId: String,
    val sourceType: String,
    val title: String,
    val sortTitle: String,
    val seriesName: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    /** 单位：Emby/Jellyfin tick（100ns）。领域层用 `Ticks`，此处为 Room 边界，见 Mappers.kt 的显式转换。 */
    val durationTicks: Long,
    val sizeBytes: Long?,
    val bitrate: Int?,
    val width: Int?,
    val height: Int?,
    val videoCodec: String?,
    val audioCodecs: String,
    val container: String?,
    val dateCreated: Long?,
    val communityRating: Float?,
    val officialRating: String?,
    val playCount: Int,
    val isPlayed: Boolean,
    /** 单位同上。 */
    val resumePositionTicks: Long,
    val isFavorite: Boolean,
    val genres: String,
    val cast: String,
    val libraryId: String?,
    val hasPoster: Boolean,
    val trickplayAvailable: Boolean,
)
