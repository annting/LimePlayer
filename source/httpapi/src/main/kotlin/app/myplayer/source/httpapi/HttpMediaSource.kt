package app.myplayer.source.httpapi

import app.myplayer.core.model.*
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
    override suspend fun planPlayback(item: MediaItemDto, caps: ClientCapabilities): PlaybackPlan {
        val userId = requireNotNull(api.config.userId) { "登录后才能播放" }
        val info = api.getPlaybackInfo(item.id, userId, buildDeviceProfile(caps))
        val source = info.mediaSources.firstOrNull()
        val mediaSourceId = source?.id ?: item.id
        val token = api.config.token
        val transcodingUrl = source?.transcodingUrl?.takeIf { it.isNotBlank() }?.let(::playbackUrl)

        return when {
            source?.supportsDirectPlay == true ->
                PlaybackPlan.Direct(
                    uri = api.directStreamUrl(item.id, mediaSourceId, token),
                    mediaSourceId = mediaSourceId,
                )
            transcodingUrl != null ->
                PlaybackPlan.Transcode(transcodingUrl, isRemuxOnly = source.supportsDirectStream)
            source?.supportsTranscoding == true ->
                // 服务端说支持转码但没回地址：按固定入口自拼 HLS
                PlaybackPlan.Transcode(
                    playbackUrl("/Videos/${item.id}/master.m3u8?MediaSourceId=$mediaSourceId"),
                    isRemuxOnly = false,
                )
            else -> error("该条目既不能直放也不能转码：${item.id}")
        }
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

    override suspend fun getTrickplay(item: MediaItemDto, width: Int): TrickplaySpec? = null // M3 实现

    override suspend fun reportProgress(item: MediaItemDto, pos: Duration, state: PlayState) = Unit // M2 实现
}
