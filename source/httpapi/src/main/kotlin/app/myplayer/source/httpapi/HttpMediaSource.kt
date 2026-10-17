package app.myplayer.source.httpapi

import app.myplayer.core.model.*
import java.util.UUID
import kotlin.time.Duration

/**
 * Emby / Jellyfin 共用的 `MediaSource` 骨架。
 * 子类需要实现的只有 [mapItem] 与 [buildDeviceProfile]；
 * 其余差异（路径前缀、鉴权头形态）通过构造时的 [SourceConfig] 传入，不改本类。
 */
abstract class HttpMediaSource(
    protected val api: HttpMediaApi,
) : MediaSource {

    override val serverId: String get() = api.config.serverId

    /** 当前播放会话（`planPlayback` 时建立；进度上报需要其中的 PlayMethod / PlaySessionId / MediaSourceId）。 */
    private var session: PlaybackSession? = null
    private var reportedStart = false

    abstract fun mapItem(dto: BaseItemDto, libraryId: String?): MediaItemDto
    abstract fun buildDeviceProfile(caps: ClientCapabilities): String

    override suspend fun getLibraries(): List<MediaLibrary> {
        val page = api.getViews()
        return page.items.map {
            MediaLibrary(id = it.id, name = it.name ?: "", itemCount = it.childCount, collectionType = it.collectionType)
        }
    }

    override suspend fun getItems(query: ItemQuery): PagedResult<MediaItemDto> {
        val page = api.getItems(query.libraryId, query.startIndex, query.limit, query.includeItemTypes, query.searchTerm)
        return PagedResult(page.items.map { mapItem(it, query.libraryId) }, page.totalCount, page.startIndex)
    }

    override suspend fun getItemDetail(id: String): MediaItemDto {
        val userId = requireNotNull(api.config.userId) { "登录后才能获取详情" }
        return mapItem(api.getItemDetail(id, userId), libraryId = null)
    }

    /** 无海报时直接返回 [ImageSource.None]；有海报时 URL 不带令牌，令牌走 headers（由 Coil 注入）。 */
    override suspend fun image(item: MediaItemDto, kind: ImageKind, size: ImageSize): ImageSource {
        if (kind == ImageKind.POSTER && !item.hasPoster) return ImageSource.None
        val kindName = when (kind) {
            ImageKind.POSTER -> "Primary"
            ImageKind.BACKDROP -> "Backdrop"
            ImageKind.THUMB -> "Thumb"
            ImageKind.LOGO -> "Logo"
        }
        return ImageSource.Remote(
            url = api.imageUrl(item.id, kindName, size.px),
            headers = api.authHeaders(),
        )
    }

    /**
     * 严格按 spec §6.1 的三档降级：直放 → 仅重封装/换音轨（isRemuxOnly=true）→ 完整转码。
     * **前两档都不匹配时不允许默默回退成直连**——那会把服务端明确声明「不支持直放」的
     * 原文件流交给播放器，结果是播放失败且原因难查；连转码地址都拿不到才抛错，由错误态 UI 呈现。
     */
    override suspend fun planPlayback(item: MediaItemDto, caps: ClientCapabilities): PlaybackPlan =
        plan(item, caps, forceTranscode = false)

    /** 直放失败降级（`03` §5.8）：强制走转码分支。 */
    override suspend fun planTranscode(item: MediaItemDto, caps: ClientCapabilities): PlaybackPlan =
        plan(item, caps, forceTranscode = true)

    /** 同季相邻集（连播）：拉本剧集全部集，按 季-集 排序后取前后邻居。 */
    override suspend fun getAdjacentEpisodes(item: MediaItemDto): AdjacentEpisodes {
        val seriesId = item.seriesId ?: return AdjacentEpisodes()
        val userId = api.config.userId ?: return AdjacentEpisodes()
        return runCatching {
            val episodes = api.getEpisodes(seriesId, userId)
                .map { mapItem(it, libraryId = null) }
                .sortedWith(compareBy({ it.seasonNumber ?: 0 }, { it.episodeNumber ?: 0 }))
            val idx = episodes.indexOfFirst { it.id == item.id }
            if (idx < 0) AdjacentEpisodes()
            else AdjacentEpisodes(
                previous = episodes.getOrNull(idx - 1),
                next = episodes.getOrNull(idx + 1),
            )
        }.getOrElse { AdjacentEpisodes() }   // 网络失败不阻塞播放
    }

    private suspend fun plan(
        item: MediaItemDto,
        caps: ClientCapabilities,
        forceTranscode: Boolean,
    ): PlaybackPlan {
        val userId = requireNotNull(api.config.userId) { "登录后才能播放" }
        val info = api.getPlaybackInfo(item.id, userId, buildDeviceProfile(caps))
        val source = info.mediaSources.firstOrNull()
        val mediaSourceId = source?.id ?: item.id
        val token = api.config.token
        val transcodingUrl = source?.transcodingUrl?.takeIf { it.isNotBlank() }?.let(::playbackUrl)

        val plan = when {
            !forceTranscode && source?.supportsDirectPlay == true ->
                PlaybackPlan.Direct(
                    uri = api.directStreamUrl(item.id, mediaSourceId, token),
                    mediaSourceId = mediaSourceId,
                )
            transcodingUrl != null ->
                PlaybackPlan.Transcode(transcodingUrl, isRemuxOnly = source.supportsDirectStream)
            source?.supportsTranscoding == true || forceTranscode ->
                // 服务端说支持转码但没回地址（或强制降级）：按固定入口自拼 HLS
                PlaybackPlan.Transcode(
                    playbackUrl("/Videos/${item.id}/master.m3u8?MediaSourceId=$mediaSourceId"),
                    isRemuxOnly = false,
                )
            else -> error("该条目既不能直放也不能转码：${item.id}")
        }

        // 记录会话上下文，供 reportProgress 上报（PlayMethod / PlaySessionId / MediaSourceId）
        session = PlaybackSession(
            itemId = item.id,
            mediaSourceId = mediaSourceId,
            playMethod = plan.playMethod(),
            playSessionId = info.playSessionId ?: UUID.randomUUID().toString(),
        )
        reportedStart = false
        return plan
    }

    /**
     * `PlaybackInfo` 回的 `TranscodingUrl` 是**相对路径**（形如 `/videos/x/master.m3u8?...`），
     * 必须补上 `baseUrl + prefix` 才能交给 ExoPlayer。
     */
    protected fun playbackUrl(raw: String): String {
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        val base = "${api.config.baseUrl}${api.config.prefix}".trimEnd('/')
        return if (raw.startsWith("/")) "$base$raw" else "$base/$raw"
    }

    /**
     * trickplay 分片清单（`05` §6 / 任务 3-1）。
     * 服务端未开启预生成 / 端点不存在 → 返回 null（UI 降级为无预览，`02` §R4）。
     */
    override suspend fun getTrickplay(item: MediaItemDto, width: Int): TrickplaySpec? = runCatching {
        val text = api.getTrickplayPlaylist(item.id, width)
        val parsed = TrickplayPlaylist.parse(text) ?: return null
        TrickplaySpec(
            width = width,
            tileWidth = parsed.tileWidth,
            tileHeight = parsed.tileHeight,
            tileCount = parsed.segmentCount,
            intervalMs = parsed.intervalMs,
            playlistUrl = api.trickplayPlaylistUrl(item.id, width),
        )
    }.getOrNull()

    /**
     * 进度上报（`02` §6.4）。语义映射（ProgressReporter 编排，见 `:core:playback`）：
     * - 首次 [PlayState.PLAYING] → `POST /Sessions/Playing`（开始播放，一次性）；
     * - 之后 [PlayState.PLAYING] → `POST /Sessions/Playing/Progress`（播放中每 10s）；
     * - [PlayState.PAUSED] → `Progress`（isPaused=true，暂停立即）；
     * - [PlayState.STOPPED] → `Stopped`（退出 / 结束）。
     * 未经过 [planPlayback]（无会话）时不发任何请求。
     */
    override suspend fun reportProgress(item: MediaItemDto, pos: Duration, state: PlayState) {
        val s = session
        android.util.Log.d("LimeReport", "reportProgress state=$state sessionNull=${s == null} pos=${pos.inWholeSeconds}s")
        val sess = s ?: return
        val request = PlaybackReportRequest(
            itemId = item.id,
            mediaSourceId = sess.mediaSourceId,
            positionTicks = pos.inWholeMilliseconds * 10_000L,
            isPaused = state == PlayState.PAUSED,
            playMethod = sess.playMethod,
            playSessionId = sess.playSessionId,
        )
        when (state) {
            PlayState.PLAYING -> if (!reportedStart) {
                api.reportPlaybackStart(request)
                reportedStart = true
            } else {
                api.reportPlaybackProgress(request)
            }
            PlayState.PAUSED -> api.reportPlaybackProgress(request)
            PlayState.STOPPED -> {
                api.reportPlaybackStopped(request)
                reportedStart = false
            }
        }
    }
}

/** 播放会话上下文（上报所需，`planPlayback` 时建立）。 */
private data class PlaybackSession(
    val itemId: String,
    val mediaSourceId: String,
    val playMethod: String,
    val playSessionId: String,
)

/** `PlaybackPlan` → Emby 的 PlayMethod 值。 */
private fun PlaybackPlan.playMethod(): String = when (this) {
    is PlaybackPlan.Direct -> "DirectPlay"
    is PlaybackPlan.Transcode -> if (isRemuxOnly) "DirectStream" else "Transcode"
}
