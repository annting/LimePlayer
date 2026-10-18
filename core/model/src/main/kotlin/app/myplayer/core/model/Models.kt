package app.myplayer.core.model

/**
 * 领域模型（三源统一）。
 *
 * 字段契约见 `docs/02-技术规范.md` §4：类型与单位是契约的一部分，
 * 新增字段需同时更新规范、`05-接口契约.md` 的字段映射与数据库表结构。
 */

enum class SourceType { EMBY, JELLYFIN, /** 飞牛影视（fnOS Media Center，私有 API + authx 签名）。 */ FNOS, LOCAL }

enum class ImageKind { POSTER, BACKDROP, THUMB, LOGO }

/** 值即请求的 `maxWidth`（px）。 */
enum class ImageSize(val px: Int) { SMALL(200), MEDIUM(400), LARGE(800) }

/** 进度上报用。 */
enum class PlayState { PLAYING, PAUSED, STOPPED }

/**
 * 外挂字幕轨（任务：字幕渲染）。[url] 必须带鉴权（api_key 或请求头），
 * [mimeType] 见 ExoPlayer 支持的文本格式（application/x-subrip、text/vtt、text/x-ssa…）。
 */
data class ExternalSubtitle(
    val url: String,
    val mimeType: String,
    val language: String? = null,
    val label: String? = null,
    val isDefault: Boolean = false,
)

data class UserData(
    val isPlayed: Boolean = false,
    val resumePositionTicks: Ticks = Ticks.ZERO,
    val isFavorite: Boolean = false,
)

data class MediaItemDto(
    val id: String,
    val serverId: String,
    val sourceType: SourceType,
    val title: String,
    val seriesName: String? = null,
    /** 所属剧集 id（连播取相邻集用；电影 / 单集视频为 null）。 */
    val seriesId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    /** 100ns tick；单位口径见 [Ticks]。 */
    val durationTicks: Ticks = Ticks.ZERO,
    val sizeBytes: Long? = null,
    val bitrate: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val videoCodec: String? = null,
    val audioCodecs: List<String> = emptyList(),
    val container: String? = null,
    val dateCreated: Long? = null,
    val communityRating: Float? = null,
    val officialRating: String? = null,
    val playCount: Int = 0,
    val userData: UserData = UserData(),
    /** 是否有可用海报；false 时 `image()` **必须**返回 [ImageSource.None]，UI 走占位而不是请求一个必然 404 的 URL。 */
    val hasPoster: Boolean = false,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val libraryId: String? = null,
    val trickplayAvailable: Boolean = false,
)

/**
 * 续播进度占比：`resumePositionTicks / durationTicks`；
 * `durationTicks` 为 0 时返回 0，结果裁剪到 `[0,1]`。
 */
fun MediaItemDto.resumeFraction(): Float {
    if (durationTicks.value <= 0L) return 0f
    return (userData.resumePositionTicks.value.toDouble() / durationTicks.value.toDouble())
        .toFloat().coerceIn(0f, 1f)
}

data class MediaLibrary(
    val id: String,
    val name: String,
    val itemCount: Int? = null,
    val collectionType: String? = null,
)

data class ItemQuery(
    val libraryId: String? = null,
    val searchTerm: String? = null,
    val startIndex: Int = 0,
    val limit: Int = 200,
    val includeItemTypes: List<String> = listOf("Movie", "Series", "Episode", "Video"),
)

data class PagedResult<T>(val items: List<T>, val totalCount: Int, val startIndex: Int) {
    val hasMore: Boolean get() = startIndex + items.size < totalCount
}

/**
 * 图片地址的三种形态。
 *
 * **安全约束**：令牌只放 [Remote.headers]，**绝不进 URL**（见 02 §5 与 reference 避坑表）。
 */
sealed interface ImageSource {
    data class Remote(val url: String, val headers: Map<String, String> = emptyMap()) : ImageSource
    data class Local(val uri: String) : ImageSource
    data object None : ImageSource
}

/**
 * 播放协商结果。
 *
 * [Transcode.hlsUrl] 必须是**绝对 URL**（服务端返回的是相对路径，适配器负责拼接）。
 */
sealed interface PlaybackPlan {
    data class Direct(
        val uri: String,
        val headers: Map<String, String> = emptyMap(),
        val mediaSourceId: String? = null,
        /** 外挂字幕轨（服务端按需转格式下发；任务：字幕渲染）。 */
        val subtitles: List<ExternalSubtitle> = emptyList(),
    ) : PlaybackPlan

    data class Transcode(
        val hlsUrl: String,
        val headers: Map<String, String> = emptyMap(),
        val isRemuxOnly: Boolean = false,
        val subtitles: List<ExternalSubtitle> = emptyList(),
    ) : PlaybackPlan
}

/** 声明本机能力，用于构造服务端 `DeviceProfile`。 */
data class ClientCapabilities(
    val supportedVideoCodecs: Set<String>,
    val supportedAudioCodecs: Set<String>,
    val maxWidth: Int,
    val maxHeight: Int,
    val supportsHdr: Boolean,
    val deviceName: String,
    val deviceId: String,
)

/** 预览缩略图（trickplay）规格；`getTrickplay()` 返回 null 表示不可用 → 走降级。 */
data class TrickplaySpec(
    val width: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val tileCount: Int,
    val intervalMs: Long,
    val playlistUrl: String,
    /** 每片横向格数（`#EXT-X-TILES:LAYOUT=NxM` 的 N）；用于反算 (col,row)。 */
    val columns: Int = 1,
    /** 每片纵向格数。 */
    val rows: Int = 1,
) {
    /** 播放位置 → 分片索引（越界收敛）。 */
    fun tileIndexAt(positionMs: Long): Int =
        ((positionMs.coerceAtLeast(0L) / intervalMs).toInt()).coerceIn(0, (tileCount - 1).coerceAtLeast(0))

    /** 分片索引 → (列, 行)。 */
    fun cellOf(index: Int): Pair<Int, Int> = (index % columns) to (index / columns)
}
