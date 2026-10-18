package app.myplayer.core.playback

import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.MediaSource
import app.myplayer.core.model.PlayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * 播放进度上报编排（`02` §6.4）：
 * - **状态事件立即上报**：开始播放（首次 PLAYING → `/Playing`）、暂停（PAUSED）、结束 / 失败（STOPPED）；
 * - **播放中每 [PERIODIC_REPORT_MS] 节流上报**：`position` 流经 `sample` 每 10s 取一次最新位置。
 *
 * 上报的具体端点选择（`/Playing` 首次 vs `/Progress` 后续）由 `HttpMediaSource.reportProgress` 内部处理，
 * 本类只负责「何时、以什么状态」触发。失败与离线补报队列见 `02` §6.4 的健壮性说明（当前：失败由上层 log，不中断播放）。
 */
class ProgressReporter(
    private val engine: PlaybackEngine,
    private val source: MediaSource,
    private val item: MediaItemDto,
) {
    private var started = false

    /** 启动上报（幂等）。[scope] 取消即停止上报（如 ViewModel 销毁）。 */
    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch { observeEvents() }
        scope.launch { observePeriodic() }
    }

    private suspend fun observeEvents() {
        var last: PlayState? = null
        engine.state.collect { s ->
            val now = s.toPlayState() ?: return@collect
            if (now != last) {
                source.reportProgress(item, s.positionMs.milliseconds, now)
                last = now
            }
        }
    }

    private suspend fun observePeriodic() {
        engine.state
            .map { it.positionMs }
            .sample(PERIODIC_REPORT_MS)
            .collect { pos ->
                if (engine.state.value.isPlaying) {
                    source.reportProgress(item, pos.milliseconds, PlayState.PLAYING)
                }
            }
    }

    companion object {
        const val PERIODIC_REPORT_MS = 10_000L
    }
}

/**
 * 引擎状态 → 上报状态。`IDLE` / `BUFFERING` 不映射（返回 null，不触发上报）；
 * `ENDED` / `FAILED` 都视为「停止」，`READY 且未播放` 视为「暂停」。
 */
internal fun EngineState.toPlayState(): PlayState? = when {
    status == EngineStatus.ENDED || status == EngineStatus.FAILED -> PlayState.STOPPED
    isPlaying -> PlayState.PLAYING
    status == EngineStatus.READY && !isPlaying -> PlayState.PAUSED
    else -> null
}
