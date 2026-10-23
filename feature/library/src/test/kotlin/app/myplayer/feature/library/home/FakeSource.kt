package app.myplayer.feature.library.home

import app.myplayer.core.model.ClientCapabilities
import app.myplayer.core.model.ImageKind
import app.myplayer.core.model.ImageSize
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.ItemQuery
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.MediaLibrary
import app.myplayer.core.model.MediaSource
import app.myplayer.core.model.PagedResult
import app.myplayer.core.model.PlayState
import app.myplayer.core.model.PlaybackPlan
import app.myplayer.core.model.SourceType
import app.myplayer.core.model.TrickplaySpec
import kotlin.time.Duration

/**
 * 手写替身：只实现首页用到的几个方法，其余按接口默认值即可。
 *
 * `libraryCalls` 用来数「真的向服务器要过几次库列表」——
 * 首页 tab 来回切不该让它增长（BUG-2026-002 的验收口径）。
 */
internal class FakeSource(
    private val libraries: List<MediaLibrary> = emptyList(),
    private val onItems: suspend (ItemQuery) -> List<MediaItemDto> = { emptyList() },
    private val resume: List<MediaItemDto> = emptyList(),
    private val latest: List<MediaItemDto> = emptyList(),
    private val failLibraries: Boolean = false,
    private val failResume: Boolean = false,
    private val failLatest: Boolean = false,
    private val failLibraryIds: Set<String> = emptySet(),
) : MediaSource {

    override val type: SourceType = SourceType.EMBY
    override val serverId: String = "srv-test"

    var lastImageSize: ImageSize? = null
        private set

    /** `getLibraries()` 被调用次数（= 首页整屏取数的次数）。 */
    var libraryCalls: Int = 0
        private set

    override suspend fun getLibraries(): List<MediaLibrary> {
        libraryCalls++
        if (failLibraries) throw IllegalStateException("连接被拒绝")
        return libraries
    }

    override suspend fun getItems(query: ItemQuery): PagedResult<MediaItemDto> {
        if (query.libraryId in failLibraryIds) throw IllegalStateException("该库取数失败")
        val items = onItems(query)
        return PagedResult(items, items.size, 0)
    }

    override suspend fun getItemDetail(id: String): MediaItemDto = throw UnsupportedOperationException()

    override suspend fun image(item: MediaItemDto, kind: ImageKind, size: ImageSize): ImageSource {
        lastImageSize = size
        return ImageSource.None
    }

    override suspend fun planPlayback(item: MediaItemDto, caps: ClientCapabilities): PlaybackPlan =
        throw UnsupportedOperationException()

    override suspend fun getTrickplay(item: MediaItemDto, width: Int): TrickplaySpec? = null

    override suspend fun reportProgress(item: MediaItemDto, pos: Duration, state: PlayState) = Unit

    override suspend fun getResumeItems(limit: Int): List<MediaItemDto> {
        if (failResume) throw IllegalStateException("该源不支持续播")
        return resume
    }

    override suspend fun getLatestItems(limit: Int): List<MediaItemDto> {
        if (failLatest) throw IllegalStateException("该源不支持最近添加")
        return latest
    }
}
