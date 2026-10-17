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
    suspend fun reportProgress(item: MediaItemDto, pos: Duration, state: PlayState)

    /**
     * 直放失败时的**强制转码**重试（`03` §5.8 降级链）。
     * 返回 null 表示该源不支持（UI 直接进错误卡片）。默认不支持 —— 老实现无需改动。
     */
    suspend fun planTranscode(item: MediaItemDto, caps: ClientCapabilities): PlaybackPlan? = null

    /**
     * 同季相邻集（`03` §5.7 连播用）。默认无相邻集 —— 电影 / 非剧集内容天然返回空。
     */
    suspend fun getAdjacentEpisodes(item: MediaItemDto): AdjacentEpisodes = AdjacentEpisodes()
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
    suspend fun syncLibraries(source: MediaSource)
    suspend fun syncItems(source: MediaSource, libraryId: String)
}
