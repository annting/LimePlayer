package app.myplayer.core.playback

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
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Duration

class ProgressReporterTest {

    private class FakeEngine : PlaybackEngine {
        private val _state = MutableStateFlow(EngineState())
        override val state: StateFlow<EngineState> = _state

        override fun prepare(plan: PlaybackPlan, startPositionMs: Long) = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun selectTrack(kind: TrackKind, trackId: String?) = Unit
        override fun setVideoSurface(surface: android.view.Surface?) = Unit
        override fun release() = Unit

        fun emit(s: EngineState) { _state.value = s }
    }

    private class FakeSource : MediaSource {
        val reports = mutableListOf<Pair<PlayState, Long>>()   // state → positionMs
        override val type = SourceType.EMBY
        override val serverId = "srv"

        override suspend fun reportProgress(item: MediaItemDto, pos: Duration, state: PlayState) {
            reports += state to pos.inWholeMilliseconds
        }

        override suspend fun getLibraries(): List<MediaLibrary> = error("unused")
        override suspend fun getItems(query: ItemQuery): PagedResult<MediaItemDto> = error("unused")
        override suspend fun getItemDetail(id: String): MediaItemDto = error("unused")
        override suspend fun image(item: MediaItemDto, kind: ImageKind, size: ImageSize): ImageSource = error("unused")
        override suspend fun planPlayback(item: MediaItemDto, caps: ClientCapabilities): PlaybackPlan = error("unused")
        override suspend fun getTrickplay(item: MediaItemDto, width: Int): TrickplaySpec? = null
    }

    private val item = MediaItemDto(id = "i1", serverId = "srv", sourceType = SourceType.EMBY, title = "T")

    private fun setup(): Triple<FakeEngine, FakeSource, ProgressReporter> {
        val engine = FakeEngine()
        val source = FakeSource()
        return Triple(engine, source, ProgressReporter(engine, source, item))
    }

    @Test
    fun `开始播放立即上报 PLAYING`() = runTest {
        val (engine, source, reporter) = setup()
        reporter.start(backgroundScope)

        engine.emit(EngineState(status = EngineStatus.READY, isPlaying = true, positionMs = 0))
        runCurrent()

        assertThat(source.reports).containsExactly(PlayState.PLAYING to 0L)
    }

    @Test
    fun `暂停立即上报 PAUSED`() = runTest {
        val (engine, source, reporter) = setup()
        reporter.start(backgroundScope)
        engine.emit(EngineState(status = EngineStatus.READY, isPlaying = true, positionMs = 30_000))
        runCurrent()

        engine.emit(EngineState(status = EngineStatus.READY, isPlaying = false, positionMs = 30_000))
        runCurrent()

        assertThat(source.reports.last()).isEqualTo(PlayState.PAUSED to 30_000L)
    }

    @Test
    fun `播放结束上报 STOPPED`() = runTest {
        val (engine, source, reporter) = setup()
        reporter.start(backgroundScope)
        engine.emit(EngineState(status = EngineStatus.READY, isPlaying = true, positionMs = 100_000))
        runCurrent()

        engine.emit(EngineState(status = EngineStatus.ENDED, isPlaying = false, positionMs = 100_000))
        runCurrent()

        assertThat(source.reports.last()).isEqualTo(PlayState.STOPPED to 100_000L)
    }

    @Test
    fun `缓冲与空闲不上报`() = runTest {
        val (engine, source, reporter) = setup()
        reporter.start(backgroundScope)

        engine.emit(EngineState(status = EngineStatus.IDLE))
        engine.emit(EngineState(status = EngineStatus.BUFFERING))
        runCurrent()

        assertThat(source.reports).isEmpty()
    }

    @Test
    fun `播放中每 10 秒节流上报一次`() = runTest {
        val (engine, source, reporter) = setup()
        reporter.start(backgroundScope)
        engine.emit(EngineState(status = EngineStatus.READY, isPlaying = true, positionMs = 0))
        runCurrent()
        val reportsAfterStart = source.reports.size   // = 1（开始）

        // 模拟播放推进（position 变化驱动 sample 的上游发射）
        engine.emit(EngineState(status = EngineStatus.READY, isPlaying = true, positionMs = 5_000))
        advanceTimeBy(10_001)
        runCurrent()

        // 10s 后应有周期性上报（PLAYING，位置为最近值 5000）
        assertThat(source.reports.size).isAtLeast(reportsAfterStart + 1)
        assertThat(source.reports.drop(reportsAfterStart)).contains(PlayState.PLAYING to 5_000L)
    }
}
