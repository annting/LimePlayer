package app.myplayer.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.myplayer.core.model.ClientCapabilities
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.MediaSource
import app.myplayer.core.playback.EngineError
import app.myplayer.core.playback.EngineStatus
import app.myplayer.core.playback.PlaybackEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 播放页 UI 状态（`03` §5.1 状态机）。
 *
 * 控制层显隐由 [controlsVisible] + [locked] 表达：
 * - `controlsVisible=true` → Controls 态；`false` 且未锁定 → 沉浸 Idle 态；
 * - [scrubbing] 为 true → Scrubbing 态（[displayPositionMs] 取 [scrubPositionMs]）；
 * - [locked] 为 true → Locked 态（控制层隐藏、全部手势禁用）。
 */
data class PlayerUiState(
    val item: MediaItemDto? = null,
    val status: EngineStatus = EngineStatus.IDLE,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val speed: Float = 1f,
    val controlsVisible: Boolean = true,
    val locked: Boolean = false,
    val scrubbing: Boolean = false,
    val scrubPositionMs: Long = 0L,
    val error: EngineError? = null,
) {
    /** 进度条应显示的位置：拖动中显示预览位置（commit-on-release，`03` §5.4）。 */
    val displayPositionMs: Long get() = if (scrubbing) scrubPositionMs else positionMs

    /** 0f..1f 的播放进度（时长未知时为 0）。 */
    val progress: Float
        get() = if (durationMs > 0L) (displayPositionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * 播放页 ViewModel：把 [PlaybackEngine] 与 [MediaSource] 组合成一个可测的状态机。
 *
 * 装载顺序为契约（`02` §5.1）：`getItemDetail`（取最新断点位置）→ `planPlayback` → `prepare` → `play`。
 * 倍速在本 VM 内是**会话级**的（`03` §5.6：退出后回落全局默认值，长按 2× 不写默认值）。
 */
class PlayerViewModel(
    private val engine: PlaybackEngine,
) : ViewModel() {

    private val _ui = MutableStateFlow(PlayerUiState())
    val ui: StateFlow<PlayerUiState> = _ui.asStateFlow()

    private var autoHideJob: Job? = null
    private var startedItemId: String? = null

    init {
        viewModelScope.launch {
            engine.state.collect { es ->
                _ui.update {
                    it.copy(
                        status = es.status,
                        isPlaying = es.isPlaying,
                        positionMs = es.positionMs,
                        durationMs = es.durationMs,
                        bufferedMs = es.bufferedMs,
                        speed = es.speed,
                        error = es.error,
                    )
                }
            }
        }
    }

    /** 装载并开始播放。[itemId] 来自库页点击；同一 item 重复调用幂等（重组安全）。 */
    fun start(itemId: String, source: MediaSource, caps: ClientCapabilities) {
        if (startedItemId == itemId) return
        startedItemId = itemId
        viewModelScope.launch {
            val item = source.getItemDetail(itemId)
            _ui.update { it.copy(item = item) }
            val plan = source.planPlayback(item, caps)
            engine.prepare(plan, item.userData.resumePositionTicks.millis)
            engine.play()
            scheduleAutoHide()
        }
    }

    /** 单击画面：切换控制层（Locked 态禁用）。 */
    fun toggleControls() {
        if (_ui.value.locked) return
        val visible = !_ui.value.controlsVisible
        _ui.update { it.copy(controlsVisible = visible) }
        if (visible) scheduleAutoHide() else autoHideJob?.cancel()
    }

    /** 任意用户交互（按钮 / 手势）重置自动隐藏计时。 */
    fun notifyInteraction() {
        if (_ui.value.controlsVisible && !_ui.value.locked) scheduleAutoHide()
    }

    fun togglePlayPause() {
        if (_ui.value.isPlaying) engine.pause() else engine.play()
        notifyInteraction()
    }

    /** 双击快进 / 快退（相对当前进度；`03` §5.5 默认 ±10s）。 */
    fun seekBy(deltaMs: Long) {
        if (_ui.value.locked) return
        val d = _ui.value.durationMs
        val raw = _ui.value.positionMs + deltaMs
        engine.seekTo(if (d > 0L) raw.coerceIn(0L, d) else raw.coerceAtLeast(0L))
        notifyInteraction()
    }

    // ---- 进度条拖动（Scrubbing 态）----

    fun beginScrub() {
        if (_ui.value.locked) return
        _ui.update { it.copy(scrubbing = true, scrubPositionMs = it.positionMs) }
        autoHideJob?.cancel()   // 拖动期间不自动隐藏
    }

    fun scrubTo(positionMs: Long) {
        if (!_ui.value.scrubbing) return
        val d = _ui.value.durationMs
        _ui.update {
            it.copy(scrubPositionMs = if (d > 0L) positionMs.coerceIn(0L, d) else positionMs.coerceAtLeast(0L))
        }
    }

    /** 松手提交：向引擎发**一次** seek（拖全程不发）。 */
    fun commitScrub() {
        if (!_ui.value.scrubbing) return
        val target = _ui.value.scrubPositionMs
        _ui.update { it.copy(scrubbing = false) }
        engine.seekTo(target)
        scheduleAutoHide()
    }

    /** 手势中断（如旋转）：不提交、回到真实位置。 */
    fun cancelScrub() {
        _ui.update { it.copy(scrubbing = false) }
        scheduleAutoHide()
    }

    /** 锁定 / 解锁（`03` §5.1 的 Locked 态：隐藏控制层并屏蔽全部手势）。 */
    fun toggleLock() {
        val nowLocked = !_ui.value.locked
        _ui.update { it.copy(locked = nowLocked, controlsVisible = !nowLocked) }
        if (nowLocked) autoHideJob?.cancel() else scheduleAutoHide()
    }

    /** 会话级倍速（0.5–3.0，步进由 UI 控制）。 */
    fun setSpeed(speed: Float) {
        engine.setSpeed(speed.coerceIn(0.5f, 3.0f))
        notifyInteraction()
    }

    /** 视频输出面绑定：SurfaceView 的生命周期回调经此透传给引擎。 */
    fun attachVideoSurface(surface: android.view.Surface?) = engine.setVideoSurface(surface)

    override fun onCleared() {
        engine.release()
    }

    private fun scheduleAutoHide() {
        autoHideJob?.cancel()
        autoHideJob = viewModelScope.launch {
            delay(CONTROLS_AUTO_HIDE_MS)
            _ui.update { it.copy(controlsVisible = false) }
        }
    }

    private companion object {
        const val CONTROLS_AUTO_HIDE_MS = 3_000L
    }
}
