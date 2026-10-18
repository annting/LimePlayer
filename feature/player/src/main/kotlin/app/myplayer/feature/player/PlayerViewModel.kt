package app.myplayer.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.myplayer.core.model.AdjacentEpisodes
import app.myplayer.core.model.ClientCapabilities
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.MediaSource
import app.myplayer.core.model.PlayState
import app.myplayer.core.model.PlaybackPlan
import app.myplayer.core.playback.EngineError
import app.myplayer.core.playback.EngineState
import app.myplayer.core.playback.EngineStatus
import app.myplayer.core.playback.PlaybackEngine
import app.myplayer.core.playback.ProgressReporter
import app.myplayer.core.playback.TrackKind
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
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val tracks: List<app.myplayer.core.playback.TrackInfo> = emptyList(),
    val controlsVisible: Boolean = true,
    val locked: Boolean = false,
    val sheetVisible: Boolean = false,
    val scaleMode: ScaleMode = ScaleMode.FIT,
    val scrubbing: Boolean = false,
    val scrubPositionMs: Long = 0L,
    val error: EngineError? = null,
    /** 连播浮层（`03` §5.7：90% 出现「下一集 + 10s 倒计时」）。 */
    val nextUpVisible: Boolean = false,
    val nextUpCountdown: Int = 0,
    /** 顶部降级提示（直放失败切换转码时短暂显示）。 */
    val fallbackNotice: String? = null,
    /** 播完且没有下一集：路由据此回列表。 */
    val finished: Boolean = false,
    /** 连播浮层里要显示的下一集（null = 没有下一集）。 */
    val nextEpisode: MediaItemDto? = null,
    /** trickplay 预览规格（null = 该源 / 该条目无预览，`02` §R4 降级）。 */
    val trickplay: app.myplayer.core.model.TrickplaySpec? = null,
) {
    /** 进度条应显示的位置：拖动中显示预览位置（commit-on-release，`03` §5.4）。 */
    val displayPositionMs: Long get() = if (scrubbing) scrubPositionMs else positionMs

    /** 0f..1f 的播放进度（时长未知时为 0）。 */
    val progress: Float
        get() = if (durationMs > 0L) (displayPositionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * 画面比例（`03` §5.6「适应 / 填充 / 裁剪 / 原始」；自定义缩放留待后续）。
 * 只影响视频面在屏幕上的摆放方式，不改分辨率。
 */
enum class ScaleMode(val label: String) {
    /** 保持比例完整显示，多余留黑边（默认）。 */
    FIT("适应"),
    /** 拉伸铺满（不保持比例，用户显式选择才用）。 */
    FILL("填充"),
    /** 保持比例放大至铺满，超出部分裁切。 */
    CROP("裁剪"),
    /** 按视频原始像素尺寸显示，四周留黑。 */
    ORIGINAL("原始"),
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
    private var sessionCaps: ClientCapabilities? = null

    // ---- 2-7 连播与降级 ----
    /** 同季相邻集（连播数据源）。 */
    private var adjacent: AdjacentEpisodes = AdjacentEpisodes()
    private var currentPlan: PlaybackPlan? = null
    /** 直放已降级过一次转码（避免错误风暴里反复重试）。 */
    private var transcodeRetried = false
    private var nextUpJob: Job? = null
    private var autoPlayNextEnabled = true

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
                        videoWidth = es.videoWidth,
                        videoHeight = es.videoHeight,
                        tracks = es.tracks,
                        error = es.error,
                    )
                }
                onEngineState(es)
            }
        }
    }

    /** 状态驱动的连播 / 结束 / 降级判定（`03` §5.7–§5.8）。 */
    private fun onEngineState(es: EngineState) {
        if (es.error != null) {
            maybeFallbackToTranscode()
            return
        }
        val dur = es.durationMs
        val next = adjacent.next
        if (dur > 0 && es.status == EngineStatus.READY && !_ui.value.nextUpVisible &&
            next != null && es.positionMs >= dur * NEXT_UP_THRESHOLD_PERCENT / 100
        ) {
            if (autoPlayNextEnabled) startNextUpCountdown() else showNextUpStatic()
        }
        if (es.status == EngineStatus.ENDED) {
            if (next != null && autoPlayNextEnabled) startNext(next)
            else _ui.update { it.copy(finished = true) }
        }
    }

    /** 装载并开始播放。[itemId] 来自库页点击；同一 item 重复调用幂等（重组安全）。 */
    fun start(
        itemId: String,
        source: MediaSource,
        caps: ClientCapabilities,
        autoPlayNext: Boolean = true,
    ) {
        if (startedItemId == itemId) return
        startedItemId = itemId
        sessionCaps = caps
        autoPlayNextEnabled = autoPlayNext
        viewModelScope.launch {
            try {
                val item = source.getItemDetail(itemId)
                _ui.update { it.copy(item = item, nextUpVisible = false, nextUpCountdown = 0, finished = false) }
                val plan = source.planPlayback(item, caps)
                android.util.Log.d("LimePlay", "协商完成 uri=${plan.let { if (it is app.myplayer.core.model.PlaybackPlan.Direct) it.uri.take(80) else "transcode" }}")
                currentPlan = plan
                transcodeRetried = false
                engine.prepare(plan, item.userData.resumePositionTicks.millis)
                engine.play()
                // 相邻集异步加载（不阻塞起播；网络失败就等于没有连播）
                adjacent = runCatching { source.getAdjacentEpisodes(item) }.getOrElse { AdjacentEpisodes() }
                // trickplay 预览规格异步加载（服务端未预生成 → null，进度条无预览但不影响播放）
                val spec = runCatching { source.getTrickplay(item, TRICKPLAY_WIDTH) }.getOrNull()
                _ui.update { it.copy(trickplay = spec) }
                scheduleAutoHide()
                // 启动进度上报（开始 / 每 10s / 暂停 / 结束，随 ViewModel 生命周期）
                sessionSource = source
                sessionItem = item
                reporter = reporterFactory?.invoke(source, item)?.also { it.start(viewModelScope) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("LimePlay", "准备阶段失败", e)
                // 取详情 / 协商播放地址失败（超时、签名失效、服务端报错）都不能崩：
                // 落到错误卡片，用户可「重试」（`03` §5.8）
                _ui.update {
                    it.copy(error = EngineError(SOURCE_ERROR_CODE, e.message ?: e::class.java.simpleName))
                }
            }
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
        // 乐观更新（任务 3-3）：松手立刻把进度条落到目标位置，
        // 不等引擎回调 —— 关键帧对齐的落点可能有 ±数秒误差，等引擎会先跳回旧位置。
        _ui.update { it.copy(scrubbing = false, positionMs = target) }
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

    // ---- 设置弹层（`03` §5.6 / 任务 2-6）----

    /** 打开底部弹层；打开期间不自动隐藏控制层。 */
    fun openSheet() {
        if (_ui.value.locked) return
        autoHideJob?.cancel()
        _ui.update { it.copy(sheetVisible = true, controlsVisible = true) }
    }

    fun closeSheet() {
        _ui.update { it.copy(sheetVisible = false) }
        scheduleAutoHide()
    }

    /** 画面比例：只影响视频面摆放，立即生效。 */
    fun setScaleMode(mode: ScaleMode) {
        _ui.update { it.copy(scaleMode = mode) }
    }

    /** 音轨 / 字幕切换；[trackId] 为 null 表示关闭该类型（如关闭字幕）。 */
    fun selectTrack(kind: TrackKind, trackId: String?) {
        engine.selectTrack(kind, trackId)
        notifyInteraction()
    }

    // ---- 连播（`03` §5.7）----

    /** 90% 起倒计时自动连播。 */
    private fun startNextUpCountdown() {
        _ui.update {
            it.copy(
                nextUpVisible = true,
                nextUpCountdown = NEXT_UP_COUNTDOWN_SECONDS,
                nextEpisode = adjacent.next,
            )
        }
        nextUpJob?.cancel()
        nextUpJob = viewModelScope.launch {
            var left = NEXT_UP_COUNTDOWN_SECONDS
            while (left > 0) {
                delay(1_000)
                left--
                _ui.update { it.copy(nextUpCountdown = left) }
            }
            adjacent.next?.let { startNext(it) }
        }
    }

    /** 关闭「自动连播」时只显示浮层，不倒计时。 */
    private fun showNextUpStatic() {
        _ui.update { it.copy(nextUpVisible = true, nextUpCountdown = 0, nextEpisode = adjacent.next) }
    }

    /** 用户取消连播浮层（继续看当前集）。 */
    fun cancelNextUp() {
        nextUpJob?.cancel()
        _ui.update { it.copy(nextUpVisible = false, nextUpCountdown = 0) }
    }

    fun playNext() {
        adjacent.next?.let { startNext(it) }
    }

    fun playPrevious() {
        adjacent.previous?.let { startNext(it) }
    }

    fun hasPreviousEpisode(): Boolean = adjacent.previous != null

    private fun startNext(next: MediaItemDto) {
        val src = sessionSource ?: return
        val caps = sessionCaps ?: return
        nextUpJob?.cancel()
        _ui.update { it.copy(nextUpVisible = false, nextUpCountdown = 0) }
        startedItemId = null            // 允许同 VM 换集重载
        start(next.id, src, caps, autoPlayNextEnabled)
    }

    // ---- 降级与重试（`03` §5.8）----

    /**
     * 直放失败 → 自动切转码（顶部提示一次）；非直放失败或已降级过 → 保持错误卡片。
     * 转码也失败时错误卡片带「重试」。
     */
    private fun maybeFallbackToTranscode() {
        if (transcodeRetried || currentPlan !is PlaybackPlan.Direct) return
        val src = sessionSource ?: return
        val item = sessionItem ?: return
        val caps = sessionCaps ?: return
        transcodeRetried = true
        _ui.update { it.copy(fallbackNotice = "直放失败，正在切换转码…") }
        viewModelScope.launch {
            runCatching { src.planTranscode(item, caps) }
                .onSuccess { plan ->
                    if (plan == null) {
                        _ui.update { it.copy(fallbackNotice = null, error = EngineError(-1, "该源不支持转码降级")) }
                        return@onSuccess
                    }
                    currentPlan = plan
                    engine.prepare(plan, _ui.value.positionMs)
                    engine.play()
                    delay(2_500)
                    _ui.update { it.copy(fallbackNotice = null) }
                }
                .onFailure { e ->
                    _ui.update {
                        it.copy(
                            fallbackNotice = null,
                            error = EngineError(-1, "转码协商失败：${e.message ?: "未知错误"}"),
                        )
                    }
                }
        }
    }

    /** 错误卡片「重试」：清掉本次会话标记并从断点重新起播。 */
    fun retry() {
        val src = sessionSource ?: return
        val caps = sessionCaps ?: return
        val item = sessionItem ?: return
        _ui.update { it.copy(error = null, fallbackNotice = null, finished = false) }
        transcodeRetried = false
        startedItemId = null
        start(item.id, src, caps, autoPlayNextEnabled)
    }

    /** 拖动到某位置时该显示的预览分片图（无 trickplay 时返回 null）。 */
    suspend fun previewTile(positionMs: Long): app.myplayer.core.model.ImageSource? {
        val spec = _ui.value.trickplay ?: return null
        val item = sessionItem ?: return null
        val src = sessionSource ?: return null
        return runCatching { src.trickplayTile(item, spec.width, spec.tileIndexAt(positionMs)) }.getOrNull()
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
        /** 连播浮层出现阈值（`03` §5.7：播至 90%）。 */
        const val NEXT_UP_THRESHOLD_PERCENT = 90
        const val NEXT_UP_COUNTDOWN_SECONDS = 10
        /** 取详情 / 协商播放地址失败的错误码（与引擎错误码区分：负数段）。 */
        const val SOURCE_ERROR_CODE = -1
        /** trickplay 请求宽度档位（Emby 常见预生成档：320）。 */
        const val TRICKPLAY_WIDTH = 320
    }
}
