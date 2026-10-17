package app.myplayer.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.myplayer.core.model.ClientCapabilities
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.MediaSource
import app.myplayer.core.model.PlayState
import app.myplayer.core.playback.EngineError
import app.myplayer.core.playback.EngineStatus
import app.myplayer.core.playback.PlaybackEngine
import app.myplayer.core.playback.ProgressReporter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

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
    private val reporterFactory: ((MediaSource, MediaItemDto) -> ProgressReporter?)? = { source, item ->
        ProgressReporter(engine, source, item)
    },
) : ViewModel() {

    private val _ui = MutableStateFlow(PlayerUiState())
    val ui: StateFlow<PlayerUiState> = _ui.asStateFlow()

    private var autoHideJob: Job? = null
    private var startedItemId: String? = null
    private var reporter: ProgressReporter? = null

    /** 会话上下文快照：供 onCleared 在 viewModelScope 取消后补报 STOPPED。 */
    private var sessionSource: MediaSource? = null
    private var sessionItem: MediaItemDto? = null

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
            // 启动进度上报（开始 / 每 10s / 暂停 / 结束，随 ViewModel 生命周期）
            sessionSource = source
            sessionItem = item
            reporter = reporterFactory?.invoke(source, item)?.also { it.start(viewModelScope) }
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

    // ---- 切后台自动暂停（`02` §6.7 / 任务 2-5）----

    /** 因切后台而暂停的标记：回前台且应用已解锁时恢复。 */
    private var pausedByBackground = false

    /**
     * 切到后台（锁屏 / 切走 / 最近任务，生命周期 ON_STOP）：默认**立即暂停**。
     * [keepBackgroundPlayback] 为「保持后台播放」设置项（默认关）—— 开启时由前台服务承载，跳过暂停。
     * 画中画不触发 ON_STOP，天然继续播放。
     */
    fun onAppBackgrounded(keepBackgroundPlayback: Boolean) {
        if (keepBackgroundPlayback) return
        if (_ui.value.isPlaying) {
            engine.pause()
            pausedByBackground = true
        }
    }

    /**
     * 恢复播放：**先解锁、再恢复**（调用方须确认应用锁已解除 —— 观察 appUnlocked 流）。
     * 幂等：未因后台暂停过时 no-op。
     */
    fun resumeFromBackground() {
        if (!pausedByBackground) return
        pausedByBackground = false
        engine.play()
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
        // viewModelScope 此时已取消：用独立 scope 把 STOPPED 补报出去（fire-and-forget）。
        // 不做的话服务端没有播放结束记录，PlayCount 不涨、「继续观看」只能靠最后一次 10s 周期上报兜底。
        val src = sessionSource
        val item = sessionItem
        if (src != null && item != null) {
            val pos = _ui.value.positionMs
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    src.reportProgress(item, pos.milliseconds, PlayState.STOPPED)
                }
            }
        }
        // 引擎是进程级单例（前台服务持有）：只结束会话，不 release
        engine.stopSession()
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
