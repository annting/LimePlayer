@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.myplayer.feature.player

import app.myplayer.core.model.AdjacentEpisodes
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
import app.myplayer.core.model.PlaybackProgress
import app.myplayer.core.model.PlaybackStore
import app.myplayer.core.model.SessionEndReason
import app.myplayer.core.model.SourceType
import app.myplayer.core.model.TrickplaySpec
import app.myplayer.core.playback.EngineState
import app.myplayer.core.playback.EngineStatus
import app.myplayer.core.playback.PlaybackEngine
import app.myplayer.core.playback.TrackKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import kotlin.time.Duration

/**
 * 播放层写本地断点的行为测试（`docs/10-数据存储方案.md` §7.2 的三个写入时机）。
 *
 * 覆盖的是**时机**而不是存储细节（存储由 `:core:database` 的 `PlaybackStoreTest` 负责）：
 * - 起播立刻写一次（防止刚开场退出什么都没留下）；
 * - 按位置差每 10s 写一次，不是每次状态回调都写；
 * - 暂停立刻写；
 * - 播完写 `completed=true`（位置清零由 store 保证）；
 * - 退页补写（[PlayerViewModel.flushLocalProgress]）。
 */
class PlayerLocalProgressTest {

    @get:Rule
    val mainRule = object : TestWatcher() {
        override fun starting(description: Description) = Dispatchers.setMain(testDispatcher)
        override fun finished(description: Description) = Dispatchers.resetMain()
    }

    // ---- 替身 ----

    private class FakeEngine : PlaybackEngine {
        private val _state = MutableStateFlow(EngineState())
        override val state: StateFlow<EngineState> = _state
        override fun prepare(plan: PlaybackPlan, startPositionMs: Long) {
            _state.value = EngineState(status = EngineStatus.BUFFERING)
        }

        override fun play() { _state.value = _state.value.copy(isPlaying = true) }
        override fun pause() { _state.value = _state.value.copy(isPlaying = false) }
        override fun seekTo(positionMs: Long) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun selectTrack(kind: TrackKind, trackId: String?) = Unit
        override fun setVideoSurface(surface: android.view.Surface?) = Unit
        override fun release() = Unit
        fun emit(state: EngineState) { _state.value = state }
    }

    /** 只记写入，不做真实存储。 */
    private class RecordingStore : PlaybackStore {
        data class Write(val sourceId: String, val mediaKey: String, val pos: Long, val dur: Long, val completed: Boolean)

        // 退页补写跑在 GlobalScope(IO) 上，与断言线程并发 → 必须用并发安全的容器
        val writes = java.util.concurrent.CopyOnWriteArrayList<Write>()

        override suspend fun recordPosition(
            sourceId: String,
            mediaKey: String,
            positionMs: Long,
            durationMs: Long,
            completed: Boolean,
        ) {
            writes += Write(sourceId, mediaKey, positionMs, durationMs, completed)
        }

        override suspend fun progressOf(sourceId: String, mediaKey: String): PlaybackProgress? = null
        override suspend fun progressOf(sourceId: String, mediaKeys: Collection<String>) = emptyMap<String, PlaybackProgress>()
        override suspend fun continueWatching(sourceId: String, limit: Int) = emptyList<PlaybackProgress>()
        override suspend fun clear(sourceId: String, mediaKey: String) = Unit

        // ---- 观看历史（P1）：播放层会走 begin/endSession，替身同样只记录 ----
        data class End(val sourceId: String, val mediaKey: String, val pos: Long, val reason: SessionEndReason)

        /** beginSession 计数：一次播放会话应当恰好开一次。 */
        val begins = java.util.concurrent.atomic.AtomicInteger(0)
        val ends = java.util.concurrent.CopyOnWriteArrayList<End>()

        override suspend fun beginSession(
            sourceId: String,
            mediaKey: String,
            title: String,
            startPositionMs: Long,
            durationMs: Long,
            playMethod: String?,
        ): String {
            begins.incrementAndGet()
            return "session-$begins"
        }

        override suspend fun endSession(
            sessionId: String?,
            sourceId: String,
            mediaKey: String,
            endPositionMs: Long,
            durationMs: Long,
            completed: Boolean,
            reason: SessionEndReason,
        ) {
            ends += End(sourceId, mediaKey, endPositionMs, reason)
        }

        override suspend fun recentWatched(sourceId: String, limit: Int) = emptyList<app.myplayer.core.model.WatchRecord>()
        override suspend fun repairOpenSessions(nowMs: Long, withinMs: Long) = 0
    }

    private class SmbSource(private val detail: MediaItemDto) : MediaSource {
        override val type = SourceType.SMB
        override val serverId = "srv-smb"
        override suspend fun getLibraries(): List<MediaLibrary> = emptyList()
        override suspend fun getItems(query: ItemQuery): PagedResult<MediaItemDto> =
            PagedResult(emptyList(), 0, 0)
        override suspend fun getItemDetail(id: String): MediaItemDto = detail
        override suspend fun image(item: MediaItemDto, kind: ImageKind, size: ImageSize): ImageSource =
            ImageSource.None
        override suspend fun planPlayback(item: MediaItemDto, caps: ClientCapabilities): PlaybackPlan =
            PlaybackPlan.Direct(uri = "http://127.0.0.1/x", mediaSourceId = "ms")
        override suspend fun getTrickplay(item: MediaItemDto, width: Int): TrickplaySpec? = null
        override suspend fun getAdjacentEpisodes(item: MediaItemDto) = AdjacentEpisodes()
        override suspend fun reportProgress(item: MediaItemDto, pos: Duration, state: PlayState) = Unit
    }

    private val caps = ClientCapabilities(
        supportedVideoCodecs = setOf("h264"), supportedAudioCodecs = setOf("aac"),
        maxWidth = 3840, maxHeight = 2160, supportsHdr = true, deviceName = "t", deviceId = "d",
    )

    private fun smbItem(path: String = "Movies/沙丘.mkv") = MediaItemDto(
        id = "i1", serverId = "srv-smb", sourceType = SourceType.SMB, title = "沙丘", path = path,
    )

    private fun ready(pos: Long, playing: Boolean = true, dur: Long = 600_000L) =
        EngineState(status = EngineStatus.READY, isPlaying = playing, positionMs = pos, durationMs = dur)

    // ---- 用例 ----

    @Test fun `start writes an immediate checkpoint`() = runTest(testDispatcher) {
        val engine = FakeEngine()
        val store = RecordingStore()
        val vm = PlayerViewModel(engine, reporterFactory = null, playbackStore = store)
        vm.start("i1", SmbSource(smbItem()), caps)

        engine.emit(ready(pos = 0))

        assertThat(store.writes).hasSize(1)
        assertThat(store.writes.first().mediaKey).isEqualTo("Movies/沙丘.mkv")
        assertThat(store.writes.first().sourceId).isEqualTo("srv-smb")
    }

    @Test fun `writes every 10s of progress, not on every tick`() = runTest(testDispatcher) {
        val engine = FakeEngine()
        val store = RecordingStore()
        val vm = PlayerViewModel(engine, reporterFactory = null, playbackStore = store)
        vm.start("i1", SmbSource(smbItem()), caps)

        engine.emit(ready(pos = 0))          // 起播写 #1
        engine.emit(ready(pos = 4_000))      // 不足 10s，不写
        engine.emit(ready(pos = 9_000))      // 仍不足，不写
        engine.emit(ready(pos = 10_500))     // 够 10s，写 #2
        engine.emit(ready(pos = 15_000))     // 不写
        engine.emit(ready(pos = 21_000))     // 写 #3

        assertThat(store.writes.map { it.pos }).containsExactly(0L, 10_500L, 21_000L).inOrder()
    }

    @Test fun `pause writes immediately`() = runTest(testDispatcher) {
        val engine = FakeEngine()
        val store = RecordingStore()
        val vm = PlayerViewModel(engine, reporterFactory = null, playbackStore = store)
        vm.start("i1", SmbSource(smbItem()), caps)

        engine.emit(ready(pos = 0))
        engine.emit(ready(pos = 3_000))
        engine.emit(ready(pos = 3_000, playing = false))   // 暂停：即使位置没变也要写

        assertThat(store.writes.map { it.pos }).containsExactly(0L, 3_000L).inOrder()
    }

    @Test fun `ended writes completed once`() = runTest(testDispatcher) {
        val engine = FakeEngine()
        val store = RecordingStore()
        val vm = PlayerViewModel(engine, reporterFactory = null, playbackStore = store)
        vm.start("i1", SmbSource(smbItem()), caps)

        engine.emit(ready(pos = 0))
        engine.emit(
            EngineState(status = EngineStatus.ENDED, isPlaying = false, positionMs = 600_000, durationMs = 600_000)
        )
        // 播完 → 走 endSession（一个事务里闭合历史 + 更新断点），不再单独 recordPosition
        assertThat(store.ends).hasSize(1)
        assertThat(store.ends.single().reason).isEqualTo(SessionEndReason.ENDED)
        assertThat(store.ends.single().pos).isEqualTo(600_000)
        // 引擎会持续发 ENDED：只允许闭合一次
        engine.emit(
            EngineState(status = EngineStatus.ENDED, isPlaying = false, positionMs = 600_000, durationMs = 600_000)
        )
        assertThat(store.ends).hasSize(1)
        // 会话也只开过一次（首个 PLAYING）
        assertThat(store.begins.get()).isEqualTo(1)
    }

    @Test fun `source without path does not use local progress`() = runTest(testDispatcher) {
        val engine = FakeEngine()
        val store = RecordingStore()
        val vm = PlayerViewModel(engine, reporterFactory = null, playbackStore = store)
        // Emby 条目：断点是服务端权威值，不许写本地（用户口径「不混排」）
        val emby = smbItem().copy(sourceType = SourceType.EMBY, path = null)
        vm.start("i1", SmbSource(emby), caps)

        engine.emit(ready(pos = 0))
        engine.emit(ready(pos = 30_000))

        assertThat(store.writes).isEmpty()
    }

    @Test fun `flush on exit writes the last known position`() = runBlocking {
        val engine = FakeEngine()
        val store = RecordingStore()
        val vm = PlayerViewModel(engine, reporterFactory = null, playbackStore = store)
        vm.start("i1", SmbSource(smbItem()), caps)

        engine.emit(ready(pos = 0))
        engine.emit(ready(pos = 42_000))

        vm.flushLocalProgress()   // 等价于用户按返回 → onCleared

        // flush 走 GlobalScope(IO)：轮询等它落地（真实线程，不是虚拟时间）
        withTimeout(3_000) {
            while (store.writes.none { it.pos == 42_000L }) delay(20)
        }
        assertThat(store.writes.map { it.pos }).contains(42_000L)
    }

    private companion object {
        val scheduler = TestCoroutineScheduler()
        val testDispatcher = UnconfinedTestDispatcher(scheduler)
    }
}
