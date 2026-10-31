package app.myplayer.core.model

import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow

/**
 * 三类媒体源（Emby / Jellyfin(飞牛) / 本机）的统一接口。
 *
 * 方法契约与失败模式见 `docs/02-技术规范.md` §5：
 * - `getLibraries()` 走 `/Users/{userId}/Views`；**userId 缺失时必须立即报错**，不允许拼出 `/Users/null/...`；
 * - `getItems()` 列表查询**不请求 `People`**（重字段留给详情页）；
 * - `item.hasPoster == false` 且 `kind == POSTER` ⇒ `image()` 必须返回 [ImageSource.None]；
 * - `planPlayback()` 的降级决策表见 02 §6.1，**不允许**三档都不满足时硬回退成直连；
 * - `getTrickplay()` 返回 `null` 表示不可用 ⇒ 走降级（不禁用播放）。
 *
 * 单位口径：`MediaItemDto` 侧一律 [Ticks]；[reportProgress] 的 `pos` 用 [Duration]（ms），
 * 因为调用方是播放层（ExoPlayer 以 ms 为单位）——两者只在 `:core:playback` 边界互转（P2 实现）。
 */
interface MediaSource {
    val type: SourceType
    val serverId: String
    suspend fun getLibraries(): List<MediaLibrary>
    suspend fun getItems(query: ItemQuery): PagedResult<MediaItemDto>
    suspend fun getItemDetail(id: String): MediaItemDto
    suspend fun image(item: MediaItemDto, kind: ImageKind, size: ImageSize): ImageSource
    suspend fun planPlayback(item: MediaItemDto, caps: ClientCapabilities): PlaybackPlan
    suspend fun getTrickplay(item: MediaItemDto, width: Int): TrickplaySpec?

    /** trickplay 分片图（[index] 来自 [TrickplaySpec.tileIndexAt]）；不支持时返回 [ImageSource.None]。 */
    suspend fun trickplayTile(item: MediaItemDto, width: Int, index: Int): ImageSource = ImageSource.None
    suspend fun reportProgress(item: MediaItemDto, pos: Duration, state: PlayState)

    /**
     * 现场探测媒体轨道（视频 / 音频 / 内嵌字幕流）+ 时长。
     *
     * 只有**没有元数据服务**的源需要实现：Emby / Jellyfin / 飞牛 在 DTO 里就带了 `mediaStreams`，
     * 而 SMB 是纯文件共享，只能读文件头自己解析（实现见 `:source:smb` 的 `MediaExtractor`）。
     * 默认 `null` = 该源不支持（详情页自动隐藏这几个分区，老实现无需改动）。
     *
     * ⚠️ **只在详情页调用**：探测要读文件头（MP4 还要跳到文件尾找 moov），放到起播链路会明显拖慢首帧。
     */
    suspend fun probeMedia(item: MediaItemDto): MediaProbeInfo? = null

    /**
     * 直放失败时的**强制转码**重试（`03` §5.8 降级链）。
     * 返回 null 表示该源不支持（UI 直接进错误卡片）。默认不支持 —— 老实现无需改动。
     */
    suspend fun planTranscode(item: MediaItemDto, caps: ClientCapabilities): PlaybackPlan? = null

    /**
     * 同季相邻集（`03` §5.7 连播用）。默认无相邻集 —— 电影 / 非剧集内容天然返回空。
     */
    suspend fun getAdjacentEpisodes(item: MediaItemDto): AdjacentEpisodes = AdjacentEpisodes()

    /**
     * 剧集详情页的分季列表（`03` §6.3）。默认返回空列表 —— 电影 / 非剧集内容天然为空，
     * 老实现无需改动（与 [getAdjacentEpisodes] 同风格，见 `02` §5.1）。
     */
    suspend fun getEpisodes(seriesId: String): List<MediaItemDto> = emptyList()

    /**
     * 首页「继续观看」（`03` §6.0）：服务端续播列表，按最近观看倒序。
     *
     * Emby / Jellyfin 走 `/Users/{userId}/Items/Resume`；**默认空** —— 没有续播概念的源
     * （飞牛 / 本机）无需实现，首页会自动隐藏这一模块。
     */
    suspend fun getResumeItems(limit: Int = 20): List<MediaItemDto> = emptyList()

    /**
     * 首页「最近添加」：跨库最新入库条目（Emby / Jellyfin `/Users/{userId}/Items/Latest`）。
     *
     * **默认空**：不支持该端点的源返回空列表，首页退回只用各库的 `getItems()`。
     */
    suspend fun getLatestItems(limit: Int = 30): List<MediaItemDto> = emptyList()

    /**
     * 文件路径 → 详情页条目 id（观看历史页「点一条记录 → 进详情」用）。
     *
     * 只有**文件型源**（SMB / 本地）有真实的路径→id 映射；在线源的 id 不是路径，
     * 返回默认 `null`（历史页对在线源本来就没有本地记录 —— 本地历史只来自文件型源）。
     */
    fun idForPath(path: String): String? = null

    /**
     * 写操作能力位：是否支持「标记已播放」（`02` §5.1）。UI 据此显隐按钮；
     * 不支持的源（飞牛 / 本机）保持默认 false。支持位为 false 时 [markPlayed] 不应被调用。
     */
    val canMarkPlayed: Boolean get() = false

    /**
     * 标记 / 取消「已播放」，返回**服务端权威 [UserData]** 供本地缓存写回（`02` §5.1）。
     * 默认实现直接抛 —— [canMarkPlayed] == false 的源不应走到这里。
     */
    suspend fun markPlayed(item: MediaItemDto, played: Boolean): UserData =
        throw UnsupportedOperationException("该源不支持标记已看")

    /**
     * 从服务器删除条目（`02` §5.1）。返回 false = 该源不支持（UI 不显示按钮，与 `item.canDelete` 双重把关）；
     * true = 已删除，调用方随后清理本地缓存。默认不支持。
     */
    suspend fun deleteItem(item: MediaItemDto): Boolean = false

    /** 人物页作品列表（`03` §6.3.1）。默认空 —— 无人物数据的源（飞牛）天然为空。 */
    suspend fun getPersonWorks(personId: String): List<MediaItemDto> = emptyList()

    /** 人物头像（默认 [ImageSource.None] ⇒ UI 用姓名首字占位）。 */
    suspend fun personImage(personId: String, size: ImageSize): ImageSource = ImageSource.None
}

/** 同季相邻集（连播数据源）；不存在的方向为 null。 */
data class AdjacentEpisodes(
    val previous: MediaItemDto? = null,
    val next: MediaItemDto? = null,
)

/** 本地缓存读写门面：UI 只观察它，同步由 `sync*` 触发。 */
interface LibraryRepository {
    fun observeLibraries(serverId: String): Flow<List<MediaLibrary>>
    fun observeItems(serverId: String, libraryId: String): Flow<List<MediaItemDto>>

    /** 该服务器下全部库的条目（搜索索引用）。默认空 —— 老实现无需改动。 */
    fun observeAllItems(serverId: String): Flow<List<MediaItemDto>> = kotlinx.coroutines.flow.flowOf(emptyList())
    suspend fun syncLibraries(source: MediaSource)
    suspend fun syncItems(source: MediaSource, libraryId: String)

    /**
     * 观看状态单条写回（`02` §5.1，详情页二期）：标记已看成功后用**服务端权威 [UserData]**
     * 更新本地缓存，让列表页的「已看」角标与进度即时一致。默认空实现（老实现无需改动）。
     */
    suspend fun updateUserData(serverId: String, itemId: String, userData: UserData) = Unit

    /** 服务端删除成功后清理本地缓存（详情页二期）。默认空实现（老实现无需改动）。 */
    suspend fun deleteItem(serverId: String, itemId: String) = Unit
}
