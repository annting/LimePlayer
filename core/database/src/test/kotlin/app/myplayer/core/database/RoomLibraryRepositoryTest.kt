package app.myplayer.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.myplayer.core.model.*
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 分页同步循环的行为测试（docs/02-技术规范.md §7.2）：
 * 「先处理空页，再累加偏移，最后判 `hasMore`」——避免 `TotalRecordCount` 偏大时空转。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomLibraryRepositoryTest {
    private lateinit var db: MediaDatabase
    private lateinit var repo: RoomLibraryRepository

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, MediaDatabase::class.java).allowMainThreadQueries().build()
        repo = RoomLibraryRepository(db)
    }
    @After fun tearDown() = db.close()

    private fun item(id: String, lib: String = "lib1") = MediaItemDto(
        id = id, serverId = "srv", sourceType = SourceType.EMBY, title = "T$id", libraryId = lib,
    )

    /**
     * 按真实服务端语义分页：`startIndex` 超过实际条数时返回空页。
     * [reportedTotal] 可单独设定，用来模拟 `TotalRecordCount` 偏大的情况。
     */
    private class FakeSource(
        private val allItems: List<MediaItemDto>,
        private val reportedTotal: Int,
    ) : MediaSource {
        val requestedStarts = mutableListOf<Int>()

        override val type = SourceType.EMBY
        override val serverId = "srv"

        override suspend fun getLibraries(): List<MediaLibrary> =
            listOf(MediaLibrary(id = "lib1", name = "电影", itemCount = 3, collectionType = "movies"))

        override suspend fun getItems(query: ItemQuery): PagedResult<MediaItemDto> {
            requestedStarts += query.startIndex
            val from = query.startIndex.coerceAtMost(allItems.size)
            val to = (query.startIndex + query.limit).coerceAtMost(allItems.size)
            return PagedResult(allItems.subList(from, to), reportedTotal, query.startIndex)
        }

        override suspend fun getItemDetail(id: String): MediaItemDto = error("not used")
        override suspend fun image(item: MediaItemDto, kind: ImageKind, size: ImageSize): ImageSource =
            ImageSource.None
        override suspend fun planPlayback(item: MediaItemDto, caps: ClientCapabilities): PlaybackPlan =
            error("not used")
        override suspend fun getTrickplay(item: MediaItemDto, width: Int): TrickplaySpec? = null
        override suspend fun reportProgress(item: MediaItemDto, pos: Duration, state: PlayState) = Unit
    }

    @Test fun `syncItems pages until hasMore is false`() = runTest {
        val source = FakeSource(allItems = (1..250).map { item("i$it") }, reportedTotal = 250)
        repo.syncItems(source, "lib1")
        assertThat(source.requestedStarts).containsExactly(0, 200).inOrder()
        assertThat(db.mediaItemDao().count("srv", "lib1")).isEqualTo(250)
    }

    @Test fun `syncItems stops on empty page even if totalCount overstates`() = runTest {
        val source = FakeSource(allItems = (1..3).map { item("i$it") }, reportedTotal = 1000)
        repo.syncItems(source, "lib1")
        // 第 2 次请求（startIndex=3）返回空页 → 立即停止；只多一次请求，不空转、不死循环
        assertThat(source.requestedStarts).containsExactly(0, 3).inOrder()
        assertThat(db.mediaItemDao().count("srv", "lib1")).isEqualTo(3)
    }

    @Test fun `syncLibraries upserts and observes`() = runTest {
        repo.syncLibraries(FakeSource(allItems = emptyList(), reportedTotal = 0))
        val libs = repo.observeLibraries("srv").first()
        assertThat(libs.map { it.name }).containsExactly("电影")
        assertThat(libs.first().itemCount).isEqualTo(3)
        assertThat(libs.first().collectionType).isEqualTo("movies")
    }

    // ---- 详情页写操作写回（`02` §5.1，详情页二期）----

    @Test fun `updateUserData 只改观看三字段且其余列不变`() = runTest {
        val original = item("i1").copy(cast = listOf("提莫西"))
        db.mediaItemDao().upsertAll(listOf(original.toEntity()))

        repo.updateUserData("srv", "i1", UserData(isPlayed = true, resumePositionTicks = Ticks.ofSeconds(30)))

        val after = db.mediaItemDao().get("srv", "i1")!!.toDto()
        assertThat(after.userData.isPlayed).isTrue()
        assertThat(after.userData.resumePositionTicks).isEqualTo(Ticks.ofSeconds(30))
        // 其余列（标题 / 演职员）不受影响
        assertThat(after.title).isEqualTo("Ti1")
        assertThat(after.cast).containsExactly("提莫西")
    }

    @Test fun `updateUserData 条目不在缓存时静默跳过`() = runTest {
        // 详情直查的条目可能从未进过列表缓存 —— 不允许 INSERT，也不能崩
        repo.updateUserData("srv", "ghost", UserData(isPlayed = true))
        assertThat(db.mediaItemDao().get("srv", "ghost")).isNull()
    }

    @Test fun `deleteItem 清理单条且不影响同库其他条目`() = runTest {
        repo.syncItems(FakeSource(allItems = listOf(item("i1"), item("i2")), reportedTotal = 2), "lib1")

        repo.deleteItem("srv", "i1")

        assertThat(db.mediaItemDao().get("srv", "i1")).isNull()
        assertThat(db.mediaItemDao().get("srv", "i2")).isNotNull()
    }
}
