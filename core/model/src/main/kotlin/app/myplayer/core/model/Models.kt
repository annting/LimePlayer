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
    /**
     * 媒体文件在服务端上的完整路径（如 `/volume3/video/Test3/xxx.avi`）。
     * **详情专属字段：只在 `getItemDetail()` 路径填充、不同步入库**。
     * 排障时非常有用（确认播放的是哪个文件、字幕命名是否匹配）。
     */
    val path: String? = null,
    val dateCreated: Long? = null,
    val communityRating: Float? = null,
    val officialRating: String? = null,
    /** 年份。**详情专属字段：只在 `getItemDetail()` 路径填充、不同步入库**（见 `02` §4.2）。 */
    val productionYear: Int? = null,
    /** 简介长文。**详情专属字段：只在 `getItemDetail()` 路径填充、不同步入库**（见 `02` §4.2）。 */
    val overview: String? = null,
    /**
     * 分辨率档位（如 `480p`）。飞牛只给档位字符串、无精确像素时用；Emby/Jellyfin 用 [width]×[height]。
     * 不同步入库（`02` §4.2 例外）。
     */
    val resolutionLabel: String? = null,
    val playCount: Int = 0,
    val userData: UserData = UserData(),
    /** 是否有可用海报；false 时 `image()` **必须**返回 [ImageSource.None]，UI 走占位而不是请求一个必然 404 的 URL。 */
    val hasPoster: Boolean = false,
    val genres: List<String> = emptyList(),
    /** 演职员姓名（**入库**，列表同步携带）。结构化版本见 [people]。 */
    val cast: List<String> = emptyList(),
    /** 结构化演职员（**详情专属、不入库**，`02` §4.2.1）：头像 + 人物页依赖。 */
    val people: List<CastMember> = emptyList(),
    /** 逐流明细（**详情专属、不入库**，`02` §4.2.1）：信息分区数据源。 */
    val mediaStreams: List<MediaStreamInfo> = emptyList(),
    /** 服务端是否允许删除该条目（逐账号权限下发）；详情页删除按钮以此为准。 */
    val canDelete: Boolean = false,
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

/** 详情页分季 Tab 的数据单元；[seasonNumber] 为 null 表示未标注季（如特典）。 */
data class SeasonGroup(
    val seasonNumber: Int?,
    val episodes: List<MediaItemDto>,
)

/**
 * 剧集按季分组（`03` §6.3 详情页分季 Tab）：季号升序，**季号缺失的一组排在最后**；
 * 组内按集号升序，集号缺失的按标题排。
 *
 * 纯函数，无网络与 Compose 依赖。季 Tab 的显示文案（「第 N 季」/「其他」）由 UI 层决定，
 * 模型层不带展示文案。
 */
fun List<MediaItemDto>.groupBySeason(): List<SeasonGroup> =
    groupBy { it.seasonNumber }
        .map { (season, episodes) ->
            SeasonGroup(
                seasonNumber = season,
                episodes = episodes.sortedWith(
                    compareBy<MediaItemDto> { it.episodeNumber ?: Int.MAX_VALUE }.thenBy { it.title }
                ),
            )
        }
        .sortedWith(compareBy { it.seasonNumber ?: Int.MAX_VALUE })

/** 演职员（详情页头像网格 + 人物页入口，`02` §4.2.1）。 */
data class CastMember(
    /** 人物在源内的 id；`getPersonWorks` / `personImage` 依赖它。 */
    val id: String,
    val name: String,
    val role: String? = null,
    /** 头像版本号（ETag 语义，入图片缓存键）。 */
    val imageTag: String? = null,
)

enum class StreamKind { VIDEO, AUDIO, SUBTITLE }

/** 单条媒体流（详情页「文件 / 视频 / 音频 / 字幕信息」分区的数据源，`02` §4.2.1）。只做展示，不参与播放决策。 */
data class MediaStreamInfo(
    val kind: StreamKind,
    val codec: String? = null,
    val language: String? = null,
    val displayTitle: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Float? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
    val sampleRate: Int? = null,
    val bitrate: Int? = null,
    /** 字幕是否外挂。 */
    val isExternal: Boolean = false,
    val isDefault: Boolean = false,
)

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
