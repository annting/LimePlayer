package app.myplayer.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.time.Duration

/** 新契约的默认行为（`02` §5.1）：不支持的源天然降级，老实现零改动。 */
class MediaSourceDefaultsTest {

    private val src = object : MediaSource {
        override val type = SourceType.EMBY
        override val serverId = "srv"
        override suspend fun getLibraries() = emptyList<MediaLibrary>()
        override suspend fun getItems(query: ItemQuery) = PagedResult(emptyList<MediaItemDto>(), 0, 0)
        override suspend fun getItemDetail(id: String): MediaItemDto = error("unused")
        override suspend fun image(item: MediaItemDto, kind: ImageKind, size: ImageSize) = ImageSource.None
        override suspend fun planPlayback(item: MediaItemDto, caps: ClientCapabilities): PlaybackPlan = error("unused")
        override suspend fun getTrickplay(item: MediaItemDto, width: Int): TrickplaySpec? = null
        override suspend fun reportProgress(item: MediaItemDto, pos: Duration, state: PlayState) = Unit
    }

    private val item = MediaItemDto(id = "i1", serverId = "srv", sourceType = SourceType.EMBY, title = "沙丘")

    @Test fun `默认不支持标记已看_能力位为 false`() {
        assertThat(src.canMarkPlayed).isFalse()
        assertThrows(UnsupportedOperationException::class.java) {
            runBlocking { src.markPlayed(item, played = true) }
        }
    }

    @Test fun `默认不支持删除`() = runBlocking {
        assertThat(src.deleteItem(item)).isFalse()
    }

    @Test fun `默认无人物作品与人物头像`() = runBlocking {
        assertThat(src.getPersonWorks("p1")).isEmpty()
        assertThat(src.personImage("p1", ImageSize.MEDIUM)).isEqualTo(ImageSource.None)
    }
}
