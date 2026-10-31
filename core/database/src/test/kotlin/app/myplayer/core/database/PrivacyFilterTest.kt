package app.myplayer.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.myplayer.core.model.FileSeen
import app.myplayer.core.model.MutablePrivacyVisibilityPolicy
import app.myplayer.core.model.SessionEndReason
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 统一可见性策略的行为级验证（`docs/隐私模式方案.md` §三 P0#2 / 决策 21）。
 *
 * 钉住两件事，都是**泄露级**的：
 * 1. 档二私有文档在普通模式下对 `FileCatalogStore` **完全不存在**（列表/详情/批量取都查不到）；
 * 2. 进度与历史只有 `mediaKey`，**不能只按 `sourceId` 过滤** —— 必须靠 SQL 关联
 *    `file_media_items.isPrivate` 排除，否则退出隐私后私文档仍会从「继续观看 / 最近看过」漏出。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrivacyFilterTest {

    private lateinit var db: MediaDatabase
    private lateinit var privacy: MutablePrivacyVisibilityPolicy

    private var now = T0

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, MediaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        privacy = MutablePrivacyVisibilityPolicy()
        now = T0
    }

    @After fun tearDown() = db.close()

    private fun catalog() = RoomFileCatalog(db, privacy) { now }
    private fun store() = RoomPlaybackStore(db, privacy) { now }

    private fun seen(path: String) = FileSeen(
        sourceId = SRC,
        path = path,
        name = path.substringAfterLast('/'),
        title = path.substringBeforeLast('.'),
        sizeBytes = 1_000L,
        fileModifiedAt = T0 - 5_000,
        libraryFolder = "Movies",
    )

    /** 登记三条、把 [SECRET] 标成隐私。 */
    private suspend fun seed() {
        val catalog = catalog()
        catalog.recordSeen(listOf(seen(A), seen(B), seen(SECRET)))
        assertThat(catalog.setPrivate(SRC, listOf(SECRET), true)).isEqualTo(1)
    }

    // ---- 档二：文件条目 ----

    @Test fun `普通模式查不到隐私文档 进隐私才查得到`() = runTest {
        seed()
        val catalog = catalog()

        assertThat(catalog.entryOf(SRC, SECRET)).isNull()
        assertThat(catalog.entryOf(SRC, A)).isNotNull()

        privacy.enter()
        val hidden = catalog.entryOf(SRC, SECRET)
        assertThat(hidden).isNotNull()
        assertThat(hidden!!.isPrivate).isTrue()
    }

    @Test fun `批量取条目时隐私文档不出现`() = runTest {
        seed()
        val catalog = catalog()

        val map = catalog.entriesOf(SRC, listOf(A, B, SECRET))
        assertThat(map.keys).containsExactly(A, B)

        privacy.enter()
        assertThat(catalog.entriesOf(SRC, listOf(A, B, SECRET)).keys)
            .containsExactly(A, B, SECRET)
    }

    @Test fun `最近添加与昨日新增都排除隐私文档`() = runTest {
        seed()
        val catalog = catalog()

        assertThat(catalog.newest(SRC, 10).map { it.path }).containsExactly(A, B)
        assertThat(catalog.newlyAdded(SRC, T0 - 1, T0 + 1).map { it.path }).containsExactly(A, B)
        // 统计口径同步缩小：只算可见的两条，否则「昨日新增 3 部」会泄露数量
        assertThat(catalog.newlyAddedStats(SRC, T0 - 1, T0 + 1).single().count).isEqualTo(2)

        privacy.enter()
        assertThat(catalog.newest(SRC, 10)).hasSize(3)
        assertThat(catalog.newlyAddedStats(SRC, T0 - 1, T0 + 1).single().count).isEqualTo(3)
    }

    @Test fun `重扫不会把已标记的隐私文档冲回可见`() = runTest {
        seed()
        now = T0 + 10_000
        // 重扫命中走 touch()，白名单里没有 isPrivate
        catalog().recordSeen(listOf(seen(SECRET)))

        assertThat(catalog().entryOf(SRC, SECRET)).isNull()
        privacy.enter()
        assertThat(catalog().entryOf(SRC, SECRET)!!.isPrivate).isTrue()
    }

    @Test fun `普通模式也能取消隐私标记`() = runTest {
        seed()
        // 写路径不受可见性策略限制：标记/取消标记是「后台事实」，不是「现在给谁看」
        assertThat(catalog().setPrivate(SRC, listOf(SECRET), false)).isEqualTo(1)
        assertThat(catalog().entryOf(SRC, SECRET)).isNotNull()
    }

    // ---- 档二：进度 / 历史（只有 mediaKey，必须关联过滤）----

    @Test fun `继续观看不返回隐私文档的进度`() = runTest {
        seed()
        val s = store()
        s.recordPosition(SRC, A, positionMs = 60_000, durationMs = 600_000)
        now = T0 + 1_000
        s.recordPosition(SRC, SECRET, positionMs = 90_000, durationMs = 600_000)

        assertThat(s.continueWatching(SRC).map { it.mediaKey }).containsExactly(A)

        privacy.enter()
        // 隐私模式按 lastPlayedAt 倒序：隐私那条更晚
        assertThat(s.continueWatching(SRC).map { it.mediaKey })
            .containsExactly(SECRET, A).inOrder()
    }

    @Test fun `最近看过不返回隐私文档的历史`() = runTest {
        seed()
        val s = store()
        listOf(A, SECRET).forEach { key ->
            now += 1_000
            val id = s.beginSession(SRC, key, title = key, startPositionMs = 0, durationMs = 600_000)
            now += 1_000
            s.endSession(id, SRC, key, 30_000, 600_000, completed = false, reason = SessionEndReason.USER_EXIT)
        }

        assertThat(s.recentWatched(SRC).map { it.mediaKey }).containsExactly(A)
        // 精确 key 的读不受影响：播放层需要它来续播/写回
        assertThat(s.progressOf(SRC, SECRET)).isNotNull()

        privacy.enter()
        assertThat(s.recentWatched(SRC).map { it.mediaKey }).containsExactly(A, SECRET)
    }

    // ---- 列举层过滤（SMB 目录是实时读的，必须在这里也挡掉）----

    @Test fun `privatePaths 报出私有路径 供列表层过滤`() = runTest {
        seed()
        val catalog = catalog()

        // 普通模式下 entriesOf 查不到私有行（这是对的），所以列举层要靠这个布尔集合
        assertThat(catalog.entriesOf(SRC, listOf(A, B, SECRET)).keys).containsExactly(A, B)
        assertThat(catalog.privatePaths(SRC, listOf(A, B, SECRET))).containsExactly(SECRET)

        // 进了隐私模式，事实不变：私有标记与当前模式无关
        privacy.enter()
        assertThat(catalog.privatePaths(SRC, listOf(A, B, SECRET))).containsExactly(SECRET)
    }

    @Test fun `privatePaths 空输入或未知源返回空集`() = runTest {
        seed()
        val catalog = catalog()
        assertThat(catalog.privatePaths(SRC, emptyList())).isEmpty()
        assertThat(catalog.privatePaths("other", listOf(A, SECRET))).isEmpty()
    }

    // ---- 档二：目录级递归隐私（2026-10-29）----

    @Test fun `目录标记后子路径文件都被 privatePaths 命中`() = runTest {
        val catalog = catalog()
        catalog.recordSeen(listOf(seen(A), seen(B), seen("Private/x.mkv"), seen("Private/sub/y.mkv")))
        // 把 Private/ 标成隐私目录
        assertThat(catalog.setFolderPrivate(SRC, listOf("Private"), true)).isEqualTo(1)

        // 普通模式：目录下的文件（含子目录）与目录自身路径都命中
        val hidden = catalog.privatePaths(SRC, listOf(A, B, "Private", "Private/x.mkv", "Private/sub/y.mkv"))
        assertThat(hidden).containsExactly("Private", "Private/x.mkv", "Private/sub/y.mkv")
        // 目录外的文件不受影响
        assertThat(catalog.privatePaths(SRC, listOf(A, B))).isEmpty()
    }

    @Test fun `新落进私有目录的文件无需继承标记即被隐藏`() = runTest {
        val catalog = catalog()
        catalog.recordSeen(listOf(seen(A)))
        catalog.setFolderPrivate(SRC, listOf("Private"), true)

        // 「新文件」：目录标记之后才登记进来的（没有写 isPrivate）
        catalog.recordSeen(listOf(seen("Private/late.mkv")))

        assertThat(catalog.privatePaths(SRC, listOf("Private/late.mkv")))
            .containsExactly("Private/late.mkv")
        assertThat(catalog.privateFolders(SRC)).containsExactly("Private")
    }

    @Test fun `取消目录隐私后子路径文件恢复可见`() = runTest {
        val catalog = catalog()
        catalog.recordSeen(listOf(seen("Private/x.mkv")))
        catalog.setFolderPrivate(SRC, listOf("Private"), true)
        assertThat(catalog.privatePaths(SRC, listOf("Private/x.mkv"))).isNotEmpty()

        catalog.setFolderPrivate(SRC, listOf("Private"), false)
        assertThat(catalog.privatePaths(SRC, listOf("Private/x.mkv"))).isEmpty()
        assertThat(catalog.privateFolders(SRC)).isEmpty()
    }

    @Test fun `目录行不混入 newest 与统计`() = runTest {
        val catalog = catalog()
        catalog.recordSeen(listOf(seen(A), seen(B)))
        catalog.setFolderPrivate(SRC, listOf("Movies"), true)

        // 目录行 kind=FOLDER，不该出现在「最近添加 / 昨日新增」的文件口径里
        privacy.enter()
        assertThat(catalog.newest(SRC, 10).map { it.path }).containsExactly(A, B)
        assertThat(catalog.newlyAddedStats(SRC, T0 - 1, T0 + 1).single().count).isEqualTo(2)
    }

    private companion object {
        const val T0 = 1_700_000_000_000L
        const val SRC = "smb-1"
        const val A = "Movies/a.mkv"
        const val B = "Movies/b.mkv"
        const val SECRET = "Movies/secret.mkv"
    }
}
