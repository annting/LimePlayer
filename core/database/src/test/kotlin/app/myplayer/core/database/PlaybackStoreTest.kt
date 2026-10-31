package app.myplayer.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.myplayer.core.model.FileSeen
import app.myplayer.core.model.MediaProbeInfo
import app.myplayer.core.model.MediaStreamInfo
import app.myplayer.core.model.ProbeState
import app.myplayer.core.model.SessionEndReason
import app.myplayer.core.model.StreamKind
import app.myplayer.core.model.Ticks
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 本地断点 + 文件库存的行为级测试。
 *
 * 重点钉住三条不变量（`docs/10-数据存储方案.md` F3 / §6.5 / §6.3）：
 * 1. 重复扫描**不得**改写 `firstSeenAt`，也不得冲掉探测结果（否则「昨日新增」口径漂移、
 *    且每次重扫都要重新读文件头）；
 * 2. 看完 ⇒ `positionMs = 0` 且 `playCount` 只 +1（退页补写不能把计数打高）；
 * 3. 「继续观看」只出未看完、位置 > 0 的条目，按最后播放时间倒序。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlaybackStoreTest {

    private lateinit var db: MediaDatabase
    private var now = T0

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, MediaDatabase::class.java).allowMainThreadQueries().build()
        now = T0
    }

    @After fun tearDown() = db.close()

    private fun catalog() = RoomFileCatalog(db) { now }
    private fun store() = RoomPlaybackStore(db) { now }

    private fun seen(path: String, size: Long = 1_000L) = FileSeen(
        sourceId = SRC,
        path = path,
        name = path.substringAfterLast('/'),
        title = path.substringBeforeLast('.'),
        sizeBytes = size,
        fileModifiedAt = T0 - 5_000,
        libraryFolder = "Movies",
    )

    private fun probeInfo() = MediaProbeInfo(
        streams = listOf(
            MediaStreamInfo(kind = StreamKind.VIDEO, codec = "h264", width = 1920, height = 1080, bitrate = 4_000_000),
            MediaStreamInfo(kind = StreamKind.AUDIO, codec = "aac", channels = 2, sampleRate = 48_000),
            MediaStreamInfo(kind = StreamKind.SUBTITLE, codec = "subrip", language = "chi"),
        ),
        durationTicks = Ticks.ofMillis(60_000),
    )

    // ---- 文件库存 ----

    @Test fun `rescan keeps firstSeenAt and probe result`() = runTest {
        val catalog = catalog()
        catalog.recordSeen(listOf(seen("Movies/a.mkv")))

        now = T0 + 1_000
        catalog.recordProbe(SRC, "Movies/a.mkv", probeInfo(), ProbeState.OK)

        now = T0 + 2_000
        // 重扫（文件大小也变了：验证 touch 白名单只更新该更新的列）
        catalog.recordSeen(listOf(seen("Movies/a.mkv", size = 2_000L)))

        val entry = catalog.entryOf(SRC, "Movies/a.mkv")!!
        assertThat(entry.firstSeenAt).isEqualTo(T0)          // ←「昨日新增」口径不漂
        assertThat(entry.sizeBytes).isEqualTo(2_000L)        // ← 会变的列确实更新了
        assertThat(entry.probeState).isEqualTo(ProbeState.OK)
        assertThat(entry.durationMs).isEqualTo(60_000L)      // ← 探测结果没被重扫冲掉
    }

    @Test fun `probe round trips every stream`() = runTest {
        val catalog = catalog()
        catalog.recordSeen(listOf(seen("Movies/a.mkv")))
        catalog.recordProbe(SRC, "Movies/a.mkv", probeInfo(), ProbeState.OK)

        val info = catalog.probeOf(SRC, "Movies/a.mkv")!!
        assertThat(info.streams).hasSize(3)
        assertThat(info.streams.map { it.kind })
            .containsExactly(StreamKind.VIDEO, StreamKind.AUDIO, StreamKind.SUBTITLE)
        assertThat(info.streams.map { it.codec }).containsExactly("h264", "aac", "subrip")
        assertThat(info.durationTicks).isEqualTo(Ticks.ofMillis(60_000))
    }

    @Test fun `unsupported probe is remembered so it is not retried`() = runTest {
        val catalog = catalog()
        catalog.recordSeen(listOf(seen("Movies/old.avi")))
        catalog.recordProbe(SRC, "Movies/old.avi", null, ProbeState.UNSUPPORTED)

        val entry = catalog.entryOf(SRC, "Movies/old.avi")!!
        assertThat(entry.probeState).isEqualTo(ProbeState.UNSUPPORTED)
        // 读回 null = 没有可用技术信息；但状态是 UNSUPPORTED ⇒ 调用方不再去探
        assertThat(catalog.probeOf(SRC, "Movies/old.avi")).isNull()
    }

    // ---- 断点 ----

    @Test fun `recordPosition round trips and is idempotent`() = runTest {
        val s = store()
        s.recordPosition(SRC, "Movies/a.mkv", positionMs = 30_000, durationMs = 600_000)
        s.recordPosition(SRC, "Movies/a.mkv", positionMs = 45_000, durationMs = 600_000)

        val p = s.progressOf(SRC, "Movies/a.mkv")!!
        assertThat(p.positionMs).isEqualTo(45_000)
        assertThat(p.completed).isFalse()
        assertThat(p.playCount).isEqualTo(0)
        // 不是整行 Upsert：同一行必须还在，不能插出第二行
        assertThat(s.continueWatching(SRC)).hasSize(1)
    }

    @Test fun `completed clears position and counts the play once`() = runTest {
        val s = store()
        s.recordPosition(SRC, "Movies/a.mkv", positionMs = 500_000, durationMs = 600_000)
        s.recordPosition(SRC, "Movies/a.mkv", positionMs = 600_000, durationMs = 600_000, completed = true)
        // 退页 / 重复 STOPPED 会再写一次 completed —— 不允许把 playCount 打高
        s.recordPosition(SRC, "Movies/a.mkv", positionMs = 600_000, durationMs = 600_000, completed = true)

        val p = s.progressOf(SRC, "Movies/a.mkv")!!
        assertThat(p.completed).isTrue()
        assertThat(p.positionMs).isEqualTo(0)
        assertThat(p.playCount).isEqualTo(1)
        // 看完的片子不该挂在「继续观看」里
        assertThat(s.continueWatching(SRC)).isEmpty()
    }

    @Test fun `continue watching is ordered by last played and skips untouched`() = runTest {
        val s = store()
        now = T0
        s.recordPosition(SRC, "Movies/a.mkv", 10_000, 600_000)
        now = T0 + 5_000
        s.recordPosition(SRC, "Movies/b.mkv", 20_000, 600_000)
        // 位置 0 = 只点开过没播：不进入「继续观看」
        s.recordPosition(SRC, "Movies/c.mkv", 0, 600_000)

        val rows = s.continueWatching(SRC)
        assertThat(rows.map { it.mediaKey }).containsExactly("Movies/b.mkv", "Movies/a.mkv").inOrder()
    }

    @Test fun `progress is isolated per source`() = runTest {
        val s = store()
        s.recordPosition("srv-a", "Movies/a.mkv", 10_000, 600_000)
        s.recordPosition("srv-b", "Movies/a.mkv", 90_000, 600_000)

        assertThat(s.progressOf("srv-a", "Movies/a.mkv")!!.positionMs).isEqualTo(10_000)
        assertThat(s.progressOf("srv-b", "Movies/a.mkv")!!.positionMs).isEqualTo(90_000)
        // 不混排：每个源只看到自己的
        assertThat(s.continueWatching("srv-a")).hasSize(1)
    }

    @Test fun `batch lookup returns only existing keys`() = runTest {
        val s = store()
        s.recordPosition(SRC, "Movies/a.mkv", 10_000, 600_000)

        val map = s.progressOf(SRC, listOf("Movies/a.mkv", "Movies/missing.mkv"))
        assertThat(map).hasSize(1)
        assertThat(map).containsKey("Movies/a.mkv")
    }

    // ---- 观看历史（P1）----

    @Test fun `endSession closes history and updates progress in one step`() = runTest {
        val s = store()
        now = T0
        val sid = s.beginSession(SRC, "Movies/a.mkv", title = "A", startPositionMs = 0, durationMs = 600_000)
        now = T0 + 60_000
        s.endSession(sid, SRC, "Movies/a.mkv", endPositionMs = 55_000, durationMs = 600_000, completed = false,
            reason = SessionEndReason.USER_EXIT)

        val record = s.recentWatched(SRC).single()
        assertThat(record.sessionId).isEqualTo(sid)
        assertThat(record.endedAt).isEqualTo(T0 + 60_000)     // 闭合时刻 = 注入的 now
        assertThat(record.endPositionMs).isEqualTo(55_000)
        assertThat(record.endedReason).isEqualTo(SessionEndReason.USER_EXIT)
        // 同一事务里断点也更新了
        assertThat(s.progressOf(SRC, "Movies/a.mkv")!!.positionMs).isEqualTo(55_000)
    }

    @Test fun `recentWatched dedupes by media key keeping the newest session`() = runTest {
        val s = store()
        now = T0
        val s1 = s.beginSession(SRC, "Movies/a.mkv", "A", 0, 600_000)
        now = T0 + 1_000
        s.endSession(s1, SRC, "Movies/a.mkv", 10_000, 600_000, false, SessionEndReason.USER_EXIT)
        now = T0 + 2_000
        val s2 = s.beginSession(SRC, "Movies/a.mkv", "A", 0, 600_000)
        now = T0 + 3_000
        s.endSession(s2, SRC, "Movies/a.mkv", 30_000, 600_000, false, SessionEndReason.USER_EXIT)
        now = T0 + 4_000
        val s3 = s.beginSession(SRC, "Movies/b.mkv", "B", 0, 600_000)
        now = T0 + 5_000
        s.endSession(s3, SRC, "Movies/b.mkv", 5_000, 600_000, false, SessionEndReason.USER_EXIT)

        val rows = s.recentWatched(SRC)
        assertThat(rows).hasSize(2)                          // 两条历史 → 按条目去重 = 2 部
        assertThat(rows.map { it.mediaKey }).containsExactly("Movies/b.mkv", "Movies/a.mkv").inOrder()
        assertThat(rows.last().endPositionMs).isEqualTo(30_000)   // a 取的是第二次会话
    }

    @Test fun `repairOpenSessions closes killed sessions with latest progress`() = runTest {
        val s = store()
        now = T0
        val sid = s.beginSession(SRC, "Movies/a.mkv", "A", 0, 600_000)
        // 进程被杀：会话从未闭合，但 10s 周期写库留下了断点
        s.recordPosition(SRC, "Movies/a.mkv", 42_000, 600_000)
        now = T0 + 10_000

        val fixed = s.repairOpenSessions(nowMs = now)
        assertThat(fixed).isEqualTo(1)

        val record = s.recentWatched(SRC).single()
        assertThat(record.sessionId).isEqualTo(sid)
        assertThat(record.endedReason).isEqualTo(SessionEndReason.APP_KILLED)
        assertThat(record.endPositionMs).isEqualTo(42_000)   // 用最新断点补
        assertThat(record.endedAt).isEqualTo(T0)             // = 断点的 lastPlayedAt（最后一次写库时刻）
    }

    @Test fun `stale open sessions are closed as UNKNOWN not repaired`() = runTest {
        val s = store()
        now = T0
        s.beginSession(SRC, "Movies/old.mkv", "Old", 0, 600_000)
        // 走到 8 天后（> 7 天窗口）
        now = T0 + 8L * 24 * 3600 * 1000
        val fixed = s.repairOpenSessions(nowMs = now)
        assertThat(fixed).isEqualTo(0)

        val record = s.recentWatched(SRC).single()
        assertThat(record.endedReason).isEqualTo(SessionEndReason.UNKNOWN)
        assertThat(record.endedAt).isEqualTo(now)
    }

    // ---- 昨日新增 + 缺失标记（P1）----

    @Test fun `newlyAdded filters by firstSeenAt window and reports stats`() = runTest {
        val catalog = catalog()
        now = T0
        catalog.recordSeen(listOf(seen("Movies/in1.mkv"), seen("Movies/in2.mkv")))
        now = T0 + 24 * 3600 * 1000
        catalog.recordSeen(listOf(seen("Movies/later.mkv")))

        val yesterday = catalog.newlyAdded(SRC, fromMs = T0, toMs = T0 + DAY)
        assertThat(yesterday.map { it.path }).containsExactly("Movies/in1.mkv", "Movies/in2.mkv")

        val stats = catalog.newlyAddedStats(SRC, fromMs = T0, toMs = T0 + DAY).single()
        assertThat(stats.kind).isEqualTo("VIDEO")
        assertThat(stats.count).isEqualTo(2)
        assertThat(stats.bytes).isEqualTo(2_000L)
        assertThat(stats.durationMs).isEqualTo(0L)           // 未探测：不计时长，不报错
    }

    @Test fun `markMissing flags only entries unseen in this scan`() = runTest {
        val catalog = catalog()
        now = T0
        catalog.recordSeen(listOf(seen("Movies/still.mkv"), seen("Movies/gone.mkv")))

        now = T0 + DAY
        // 本轮扫描只看见 still.mkv
        catalog.recordSeen(listOf(seen("Movies/still.mkv")))
        val flagged = catalog.markMissing(SRC, scanStartedAt = now)
        assertThat(flagged).isEqualTo(1)
        assertThat(catalog.entryOf(SRC, "Movies/gone.mkv")!!.missingAt).isEqualTo(now)
        assertThat(catalog.entryOf(SRC, "Movies/still.mkv")!!.missingAt).isNull()

        // 复活：重新看见的文件 missingAt 被清掉（touch 白名单里有 missingAt = NULL）
        now = T0 + 2 * DAY
        catalog.recordSeen(listOf(seen("Movies/gone.mkv")))
        assertThat(catalog.entryOf(SRC, "Movies/gone.mkv")!!.missingAt).isNull()
    }

    private companion object {
        const val SRC = "srv-smb"
        const val T0 = 1_700_000_000_000L
        const val DAY = 24L * 3600 * 1000
    }
}
