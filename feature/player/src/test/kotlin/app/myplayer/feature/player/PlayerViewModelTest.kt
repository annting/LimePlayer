@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)   // setMain / UnconfinedTestDispatcher

package app.myplayer.feature.player

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
import app.myplayer.core.model.Ticks
import app.myplayer.core.model.TrickplaySpec
import app.myplayer.core.model.UserData
import app.myplayer.core.playback.EngineState
import app.myplayer.core.playback.EngineStatus
import app.myplayer.core.playback.PlaybackEngine
import app.myplayer.core.playback.TrackKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import kotlin.time.Duration

/** `viewModelScope` 依赖 `Dispatchers.Main`：JVM 单测用 TestDispatcher 顶替（与 :feature:library 同款）。 */
private val scheduler = TestCoroutineScheduler()
private val testDispatcher = UnconfinedTestDispatcher(scheduler)

class PlayerViewModelTest {

    @get:Rule
    val mainRule = object : TestWatcher() {
        override fun starting(description: Description) = Dispatchers.setMain(testDispatcher)
        override fun finished(description: Description) = Dispatchers.resetMain()
    }

    // ---- 测试替身 ----

    private class FakeEngine : PlaybackEngine {
        private val _state = MutableStateFlow(EngineState())
        override val state: StateFlow<EngineState> = _state

        var preparedPlan: PlaybackPlan? = null
        var preparedStartMs: Long? = null
        var playCalls = 0
        val seeks = mutableListOf<Long>()
        var released = false
        var speed: Float? = null

        override fun prepare(plan: PlaybackPlan, startPositionMs: Long) {
            preparedPlan = plan
            preparedStartMs = startPositionMs
            _state.value = EngineState(status = EngineStatus.BUFFERING)
        }

        override fun play() {
            playCalls++
            _state.value = _state.value.copy(isPlaying = true)
        }

        override fun pause() {
            _state.value = _state.value.copy(isPlaying = false)
        }

        override fun seekTo(positionMs: Long) {
            seeks += positionMs
        }

        override fun setSpeed(speed: Float) {
            this.speed = speed
            _state.value = _state.value.copy(speed = speed)
        }

        override fun selectTrack(kind: TrackKind, trackId: String?) {
            trackSelections += kind to trackId
        }

        val trackSelections = mutableListOf<Pair<TrackKind, String?>>()
        override fun setVideoSurface(surface: android.view.Surface?) = Unit
        override fun release() { released = true }

        /** 测试驱动引擎状态变化。 */
        fun emit(state: EngineState) { _state.value = state }
    }

    private class FakeSource(
        private val detail: MediaItemDto,
        private val plan: PlaybackPlan,
    ) : MediaSource {
        override val type = SourceType.EMBY
        override val serverId = "srv"

        override suspend fun getLibraries(): List<MediaLibrary> = error("unused")
        override suspend fun getItems(query: ItemQuery): PagedResult<MediaItemDto> = error("unused")
        override suspend fun getItemDetail(id: String): MediaItemDto = detail
        override suspend fun image(item: MediaItemDto, kind: ImageKind, size: ImageSize): ImageSource = error("unused")
        override suspend fun planPlayback(item: MediaItemDto, caps: ClientCapabilities): PlaybackPlan = plan
        override suspend fun getTrickplay(item: MediaItemDto, width: Int): TrickplaySpec? = null
        override suspend fun reportProgress(item: MediaItemDto, pos: Duration, state: PlayState) = Unit
    }

    private fun item(resumeTicks: Long = 0L) = MediaItemDto(
        id = "i1", serverId = "srv", sourceType = SourceType.EMBY, title = "沙丘",
        durationTicks = Ticks.ofSeconds(7200),
        userData = UserData(resumePositionTicks = Ticks(resumeTicks)),
    )

    private val plan = PlaybackPlan.Direct(uri = "http://x/stream", mediaSourceId = "ms1")
    private val caps = ClientCapabilities(
        supportedVideoCodecs = setOf("h264"), supportedAudioCodecs = setOf("aac"),
        maxWidth = 3840, maxHeight = 2160, supportsHdr = true, deviceName = "test", deviceId = "d1",
    )

    private fun vm(engine: FakeEngine, source: FakeSource): PlayerViewModel =
        // reporterFactory = null：进度上报协程是无限 collect，会卡住 runTest；这里只测播放状态机
        PlayerViewModel(engine, reporterFactory = null).also { it.start("i1", source, caps) }

    // ---- 用例 ----

    @Test
    fun `start 按断点位置装载并自动播放`() = runTest(testDispatcher) {
        val engine = FakeEngine()
        val src = FakeSource(detail = item(resumeTicks = 600_000_000L), plan = plan)  // 60s

        val vm = vm(engine, src)
        runCurrent()

        assertThat(engine.preparedPlan).isEqualTo(plan)
        assertThat(engine.preparedStartMs).isEqualTo(60_000L)
        assertThat(engine.playCalls).isEqualTo(1)
        assertThat(vm.ui.value.item?.title).isEqualTo("沙丘")
    }

    @Test
    fun `单击切换控制层显隐`() = runTest(testDispatcher) {
        val vm = vm(FakeEngine(), FakeSource(item(), plan))
        runCurrent()
        assertThat(vm.ui.value.controlsVisible).isTrue()

        vm.toggleControls()
        assertThat(vm.ui.value.controlsVisible).isFalse()

        vm.toggleControls()
        assertThat(vm.ui.value.controlsVisible).isTrue()
    }

    @Test
    fun `3 秒无操作后控制层自动隐藏`() = runTest(testDispatcher) {
        val vm = vm(FakeEngine(), FakeSource(item(), plan))
        runCurrent()

        advanceTimeBy(3_001)
        runCurrent()
        assertThat(vm.ui.value.controlsVisible).isFalse()
    }

    @Test
    fun `交互重置自动隐藏计时`() = runTest(testDispatcher) {
        val vm = vm(FakeEngine(), FakeSource(item(), plan))
        runCurrent()

        advanceTimeBy(2_000)
        vm.notifyInteraction()          // 重置
        advanceTimeBy(2_000)
        runCurrent()
        assertThat(vm.ui.value.controlsVisible).isTrue()   // 距上次交互仅 2s

        advanceTimeBy(1_100)
        runCurrent()
        assertThat(vm.ui.value.controlsVisible).isFalse()
    }

    @Test
    fun `双击快进以当前进度相对 seek`() = runTest(testDispatcher) {
        val engine = FakeEngine()
        val vm = vm(engine, FakeSource(item(), plan))
        runCurrent()
        engine.emit(EngineState(status = EngineStatus.READY, isPlaying = true, positionMs = 30_000, durationMs = 7_200_000))
        runCurrent()

        vm.seekBy(10_000)

        assertThat(engine.seeks).containsExactly(40_000L)
    }

    @Test
    fun `双击快退不越过 0`() = runTest(testDispatcher) {
        val engine = FakeEngine()
        val vm = vm(engine, FakeSource(item(), plan))
        runCurrent()
        engine.emit(EngineState(status = EngineStatus.READY, positionMs = 5_000))
        runCurrent()

        vm.seekBy(-10_000)

        assertThat(engine.seeks).containsExactly(0L)
    }

    @Test
    fun `拖动期间不 seek，松手只提交一次`() = runTest(testDispatcher) {
        val engine = FakeEngine()
        val vm = vm(engine, FakeSource(item(), plan))
        runCurrent()
        engine.emit(EngineState(status = EngineStatus.READY, positionMs = 10_000, durationMs = 7_200_000))
        runCurrent()

        vm.beginScrub()
        vm.scrubTo(100_000)
        vm.scrubTo(200_000)
        vm.scrubTo(300_000)
        assertThat(engine.seeks).isEmpty()
        assertThat(vm.ui.value.scrubbing).isTrue()
        assertThat(vm.ui.value.displayPositionMs).isEqualTo(300_000L)

        vm.commitScrub()

        assertThat(engine.seeks).containsExactly(300_000L)   // 只提交一次
        assertThat(vm.ui.value.scrubbing).isFalse()
    }

    @Test
    fun `取消拖动不 seek 且回到真实位置`() = runTest(testDispatcher) {
        val engine = FakeEngine()
        val vm = vm(engine, FakeSource(item(), plan))
        runCurrent()
        engine.emit(EngineState(status = EngineStatus.READY, positionMs = 10_000))
        runCurrent()

        vm.beginScrub()
        vm.scrubTo(500_000)
        vm.cancelScrub()

        assertThat(engine.seeks).isEmpty()
        assertThat(vm.ui.value.scrubbing).isFalse()
        assertThat(vm.ui.value.displayPositionMs).isEqualTo(10_000L)
    }

    @Test
    fun `锁定后单击不切换控制层 双击不 seek`() = runTest(testDispatcher) {
        val engine = FakeEngine()
        val vm = vm(engine, FakeSource(item(), plan))
        runCurrent()
        engine.emit(EngineState(status = EngineStatus.READY, positionMs = 10_000))
        runCurrent()

        vm.toggleLock()
        assertThat(vm.ui.value.locked).isTrue()
        assertThat(vm.ui.value.controlsVisible).isFalse()   // 锁定时控制层隐藏

        vm.toggleControls()
        assertThat(vm.ui.value.controlsVisible).isFalse()   // 锁定态屏蔽

        vm.seekBy(10_000)
        assertThat(engine.seeks).isEmpty()
    }

    @Test
    fun `引擎状态透传（位置 时长 结束态）`() = runTest(testDispatcher) {
        val engine = FakeEngine()
        val vm = vm(engine, FakeSource(item(), plan))
        runCurrent()

        engine.emit(
            EngineState(
                status = EngineStatus.READY, isPlaying = true,
                positionMs = 123_000, durationMs = 7_200_000, bufferedMs = 300_000,
            )
        )
        runCurrent()
        assertThat(vm.ui.value.positionMs).isEqualTo(123_000L)
        assertThat(vm.ui.value.durationMs).isEqualTo(7_200_000L)
        assertThat(vm.ui.value.progress).isWithin(0.001f).of(123_000f / 7_200_000f)

        engine.emit(EngineState(status = EngineStatus.ENDED, positionMs = 7_200_000))
        runCurrent()
        assertThat(vm.ui.value.status).isEqualTo(EngineStatus.ENDED)
    }

    @Test
    fun `切后台暂停 回前台恢复（先解锁）`() = runTest(testDispatcher) {
        val engine = FakeEngine()
        val vm = vm(engine, FakeSource(item(), plan))
        runCurrent()
        engine.emit(EngineState(status = EngineStatus.READY, isPlaying = true))
        runCurrent()

        // 切后台（默认设置）：立即暂停
        vm.onAppBackgrounded(keepBackgroundPlayback = false)
        runCurrent()
        assertThat(vm.ui.value.isPlaying).isFalse()

        // 恢复播放（应用已解锁）
        vm.resumeFromBackground()
        runCurrent()
        assertThat(vm.ui.value.isPlaying).isTrue()

        // 幂等：未因后台暂停过时恢复是 no-op
        vm.resumeFromBackground()
        runCurrent()
        assertThat(vm.ui.value.isPlaying).isTrue()
    }

    @Test
    fun `保持后台播放开启时切后台不暂停`() = runTest(testDispatcher) {
        val engine = FakeEngine()
        val vm = vm(engine, FakeSource(item(), plan))
        runCurrent()
        engine.emit(EngineState(status = EngineStatus.READY, isPlaying = true))
        runCurrent()

        vm.onAppBackgrounded(keepBackgroundPlayback = true)
        runCurrent()
        assertThat(vm.ui.value.isPlaying).isTrue()   // 前台服务承载，不暂停

        // 此时恢复调用是 no-op（没有后台暂停标记）
        vm.resumeFromBackground()
        runCurrent()
        assertThat(vm.ui.value.isPlaying).isTrue()
    }

    @Test
    fun `未播放时切后台不设恢复标记`() = runTest(testDispatcher) {
        val engine = FakeEngine()
        val vm = vm(engine, FakeSource(item(), plan))
        runCurrent()
        engine.emit(EngineState(status = EngineStatus.READY, isPlaying = false))
        runCurrent()

        vm.onAppBackgrounded(keepBackgroundPlayback = false)
        runCurrent()
        vm.resumeFromBackground()
        runCurrent()
        // 未在播放中 → 不标记 → 恢复 no-op（playCalls 不变）
        assertThat(engine.playCalls).isEqualTo(1)   // 仅 start() 里的那次
    }

    // ---- 2-6 设置弹层 ----

    @Test
    fun `弹层开关与锁定态屏蔽`() = runTest(testDispatcher) {
        val engine = FakeEngine()
        val vm = vm(engine, FakeSource(item(), plan))
        runCurrent()

        vm.openSheet()
        assertThat(vm.ui.value.sheetVisible).isTrue()
        vm.closeSheet()
        assertThat(vm.ui.value.sheetVisible).isFalse()

        vm.toggleLock()
        vm.openSheet()
        assertThat(vm.ui.value.sheetVisible).isFalse()   // 锁定态不允许打开设置
    }

    @Test
    fun `倍速与画面比例写入状态`() = runTest(testDispatcher) {
        val engine = FakeEngine()
        val vm = vm(engine, FakeSource(item(), plan))
        runCurrent()

        vm.setSpeed(1.5f)
        assertThat(engine.speed).isEqualTo(1.5f)

        vm.setSpeed(9f)                                  // 越界收敛到 3.0
        assertThat(engine.speed).isEqualTo(3f)

        vm.setScaleMode(ScaleMode.CROP)
        assertThat(vm.ui.value.scaleMode).isEqualTo(ScaleMode.CROP)
    }

    @Test
    fun `音轨与字幕选择透传引擎`() = runTest(testDispatcher) {
        val engine = FakeEngine()
        val vm = vm(engine, FakeSource(item(), plan))
        runCurrent()

        vm.selectTrack(TrackKind.AUDIO, "a:0")
        vm.selectTrack(TrackKind.SUBTITLE, null)          // 关闭字幕
        assertThat(engine.trackSelections).containsExactly(
            TrackKind.AUDIO to "a:0",
            TrackKind.SUBTITLE to null,
        )
    }
}
