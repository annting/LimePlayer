package app.myplayer.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.myplayer.core.model.AdjacentEpisodes
import app.myplayer.core.model.ClientCapabilities
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.MediaSource
import app.myplayer.core.model.PlayState
import app.myplayer.core.model.PlaybackPlan
import app.myplayer.core.model.PlaybackStore
import app.myplayer.core.model.SessionEndReason
import app.myplayer.core.model.SourceType
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
import kotlin.math.abs
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
    /** 当前要绘制的字幕（引擎已把 cue 文本抛过来，排版在播放页自绘）。 */
    val cues: List<String> = emptyList(),
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
    /** 双击快进 / 快退的居中提示（「+10 秒」/「-10 秒」，1s 后自动消失，`03` §5.5）。 */
    val seekHint: String? = null,
    /** 播完且没有下一集：路由据此回列表。 */
    val finished: Boolean = false,
    /** 连播浮层里要显示的下一集（null = 没有下一集）。 */
    val nextEpisode: MediaItemDto? = null,
    /** trickplay 预览规格（null = 该源 / 该条目无预览，`02` §R4 降级）。 */
    val trickplay: app.myplayer.core.model.TrickplaySpec? = null,
    /** 长按 2× 临时倍速中（BUG-3）：屏幕闪「2×」浮标，松手恢复。 */
    val speedBoost: Boolean = false,
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
    /**
     * 本地进度写入口（`docs/10-数据存储方案.md` D1）。
     *
     * 只对**文件型源**生效（判据见 [localMediaKey]：条目必须带 `path`），因为在线源的断点
     * 是服务端权威值 —— 写本地会与服务端打架，也违背「不混排」（用户 2026-10-27 ②）。
     * null = 不写本地（单测 / 预览）。
     */
    private val playbackStore: PlaybackStore? = null,
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

    // ---- 本地进度（文件型源）----
    private var localProgressJob: Job? = null
    /** 本次会话对应的 `mediaKey`（SMB = 相对路径）；null = 该条目不走本地进度。 */
    private var localMediaKey: String? = null
    private var localSourceId: String? = null
    /** 最后一次写库的位置/时长快照 —— onCleared 时 `_ui` 可能已被清空，不能依赖它。 */
    private var lastLocalPositionMs = 0L
    private var lastLocalDurationMs = 0L
    private var localCompletedWritten = false
    /** 当前这次播放的会话 id（`watch_history.sessionId`）；null = 还没开始 / 已闭合。 */
    private var localSessionId: String? = null
    private var localSessionStartMs = 0L

    // ---- 2-7 连播与降级 ----
    /** 同季相邻集（连播数据源）。 */
    private var adjacent: AdjacentEpisodes = AdjacentEpisodes()
    private var currentPlan: PlaybackPlan? = null
    /** 直放已降级过一次转码（避免错误风暴里反复重试）。 */
    private var transcodeRetried = false
    /** seek 提交后到引擎到位前的抑制窗口：期间不回灌引擎 positionMs（防 thumb 跳回旧位置）。 */
    private var seekPendingTarget: Long? = null
    private var nextUpJob: Job? = null
    private var seekHintJob: Job? = null
    private var autoPlayNextEnabled = true
    /** 设置里的「画面比例」只在本次播放会话首播时套用一次（连播/重试不重置）。 */
    private var appliedInitialPrefs = false

    // ---- 字幕默认开启（设置 → 字幕设置 · 默认使用字幕）----
    private var defaultSubtitleEnabled = true
    /** 用户手动选过字幕轨（含「关闭字幕」）：本次会话不再自动开。 */
    private var subtitleTouchedByUser = false
    /** 已经自动选过一次：轨道列表会多次回调，不加锁会把用户的后续操作顶掉。 */
    private var autoSubtitleApplied = false

    init {
        viewModelScope.launch {
            engine.state.collect { es ->
                // seek pending：引擎还没 seek 到位时，别用它回灌的旧 positionMs 覆盖乐观值
                // （否则 thumb 会先弹回旧位置再跳过来）。
                val pending = seekPendingTarget
                if (pending != null) {
                    val settled = abs(es.positionMs - pending) <= SEEK_SETTLE_MS
                    if (settled || es.error != null) seekPendingTarget = null
                }
                val useEnginePosition = seekPendingTarget == null
                _ui.update {
                    it.copy(
                        status = es.status,
                        isPlaying = es.isPlaying,
                        positionMs = if (useEnginePosition) es.positionMs else it.positionMs,
                        durationMs = es.durationMs,
                        bufferedMs = es.bufferedMs,
                        speed = es.speed,
                        videoWidth = es.videoWidth,
                        videoHeight = es.videoHeight,
                        tracks = es.tracks,
                        cues = es.cues,
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
        // 字幕轨就绪后自动选一条（设置项「默认使用字幕」）
        maybeAutoSelectSubtitle(es)
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

    /**
     * 「默认使用字幕」：第一条可用的字幕轨自动选中。
     *
     * 三条退出条件缺一不可 ——
     * 1. 开关关着 / 用户本次会话手动动过字幕（含关掉）/ 已经自动选过；
     * 2. 播放器自己已经选好了一条（ExoPlayer 按语言匹配会预选），那就不抢；
     * 3. 一条字幕轨都没有（纯内嵌无字幕的片子）→ 什么都不做。
     */
    private fun maybeAutoSelectSubtitle(es: EngineState) {
        if (!defaultSubtitleEnabled || subtitleTouchedByUser || autoSubtitleApplied) return
        val subs = es.tracks.filter { it.kind == TrackKind.SUBTITLE }
        if (subs.isEmpty()) return
        autoSubtitleApplied = true
        if (subs.any { it.isSelected }) return
        val target = pickDefaultSubtitleTrack(es.tracks) ?: return
        engine.selectTrack(TrackKind.SUBTITLE, target.id)
    }

    /** 装载并开始播放。[itemId] 来自库页点击；同一 item 重复调用幂等（重组安全）。 */
    fun start(
        itemId: String,
        source: MediaSource,
        caps: ClientCapabilities,
        autoPlayNext: Boolean = true,
        /** 设置页「默认倍速」：仅**本次会话首次起播**生效（连播下一集沿用当前速度）。 */
        defaultSpeed: Float = 1f,
        /** 设置页「画面比例」：同一次会话首次起播时作为初始缩放模式。 */
        initialScaleMode: ScaleMode = ScaleMode.FIT,
        /** 设置页「默认使用字幕」：轨道出现后自动选一条字幕。 */
        defaultSubtitle: Boolean = true,
    ) {
        if (startedItemId == itemId) return
        startedItemId = itemId
        sessionCaps = caps
        autoPlayNextEnabled = autoPlayNext
        defaultSubtitleEnabled = defaultSubtitle
        // 换片即重来：上一集用户手动选过字幕，不该影响这一集
        subtitleTouchedByUser = false
        autoSubtitleApplied = false
        if (!appliedInitialPrefs) {
            // 只在**这台设备的本次播放会话**首次起播时套用设置里的画幅，
            // 之后的连播下一集 / 重试都不再重置（否则用户手动切的画幅会被打回默认）
            appliedInitialPrefs = true
            _ui.update { it.copy(scaleMode = initialScaleMode) }
        }
        viewModelScope.launch {
            try {
                val item = source.getItemDetail(itemId)
                _ui.update { it.copy(item = item, nextUpVisible = false, nextUpCountdown = 0, finished = false) }
                val plan = source.planPlayback(item, caps)
                android.util.Log.d("LimePlay", "协商完成 uri=${plan.let { if (it is app.myplayer.core.model.PlaybackPlan.Direct) it.uri.take(80) else "transcode" }}")
                currentPlan = plan
                transcodeRetried = false
                engine.prepare(plan, item.userData.resumePositionTicks.millis)
                // 「默认倍速」：prepare 之后、play 之前设，首帧就是用户要的速度。
                // 引擎侧越界会自己收敛（0.5x–3.0x），这里再夹一次免得传进脏值。
                if (defaultSpeed > 0f) engine.setSpeed(defaultSpeed.coerceIn(0.5f, 3.0f))
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
                // 换集 / 换片：先停掉上一集的 reporter。不停的话它会拿**新一集的位置**
                // 继续给**上一集**上报，把上一集断点一路污染到片尾（R4 第一个窟窿）
                reporter?.stop()
                reporter = reporterFactory?.invoke(source, item)?.also { it.start(viewModelScope) }
                // 本地断点（文件型源）：起播写一次，之后每前进 10s / 暂停 / 播完各写一次
                startLocalProgress(item)
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

    /**
     * 文件型源的 `mediaKey`：只有 SMB（将来还有本地）走本地断点。
     *
     * 在线源（Emby / Jellyfin / 飞牛）返回 null —— 它们的断点是**服务端权威值**，
     * 本地再存一份会与服务端打架，也违背「不混排」（用户 2026-10-27 ②）。
     */
    private fun localKeyOf(item: MediaItemDto): String? =
        if (item.sourceType == SourceType.SMB) item.path ?: decodeFallback(item) else null

    /** 从详情拿不到 path 时的兜底：SMB 的 id 就是路径的 Base64（与源侧同规则，URL 安全变体）。 */
    private fun decodeFallback(item: MediaItemDto): String? =
        runCatching {
            String(java.util.Base64.getUrlDecoder().decode(item.id), Charsets.UTF_8)
        }.getOrNull()

    /**
     * 起播后开始写本地断点。
     *
     * 三个写入时机（与 `docs/10 §7.2` 对齐）：
     * - **起播**（第一次 PLAYING）立刻写一次 —— 防止刚开场就退出时一条记录都没有；
     * - **每前进 10s** 写一次（按位置差而不是按时间，暂停期间不会空转）；
     * - **暂停 / 播完 / 失败** 立即写 —— 这两个时刻的位置才是用户真正离开的地方。
     *
     * 进程被杀时没有回调，最多丢 10s（下一次进来从最近一次断点续播）。
     */
    private fun startLocalProgress(item: MediaItemDto) {
        val store = playbackStore ?: return
        val key = localKeyOf(item) ?: return
        // 换集 / 换片：先把上一集按「用户退出」闭合（异步 fire-and-forget，不阻塞起播）
        flushLocalProgress()
        localProgressJob?.cancel()
        localMediaKey = key
        localSourceId = item.serverId
        lastLocalPositionMs = 0L
        lastLocalDurationMs = 0L
        localCompletedWritten = false
        localSessionId = null
        localSessionStartMs = 0L
        localProgressJob = viewModelScope.launch {
            var lastWrittenMs = -1L
            var wasPlaying = false
            engine.state.collect { es ->
                val pos = es.positionMs
                val dur = es.durationMs
                lastLocalPositionMs = pos
                if (dur > 0) lastLocalDurationMs = dur

                // 首个 PLAYING：开一次观看会话（docs/10 §7.2「起播成功」）。
                // 不在这里开的话，「播了 3 分钟就切走」在历史里完全不存在。
                if (es.isPlaying && localSessionId == null && !localCompletedWritten) {
                    localSessionStartMs = pos
                    localSessionId = runCatching {
                        store.beginSession(
                            sourceId = item.serverId,
                            mediaKey = key,
                            title = item.title,
                            startPositionMs = pos,
                            durationMs = dur,
                            // SMB 走本机 HTTP 代理，语义上既不是 DIRECT 也不是服务端转码
                            playMethod = "PROXY",
                        )
                    }.getOrNull()
                }

                val ended = es.status == EngineStatus.ENDED || es.status == EngineStatus.FAILED
                val completed = ended || (dur > 0 && pos >= dur * LOCAL_COMPLETE_PERCENT / 100)
                // 播完后引擎会持续发 ENDED，只写一次（否则 playCount 会被打高）
                if (completed && localCompletedWritten) return@collect
                val due = when {
                    completed -> true
                    es.isPlaying && lastWrittenMs < 0 -> true
                    es.isPlaying && pos - lastWrittenMs >= LOCAL_PROGRESS_STEP_MS -> true
                    !es.isPlaying && wasPlaying -> true
                    else -> false
                }
                wasPlaying = es.isPlaying
                if (!due) return@collect
                lastWrittenMs = pos
                if (completed) localCompletedWritten = true
                val sid = localSessionId
                val reason = when {
                    es.status == EngineStatus.FAILED -> SessionEndReason.ERROR
                    es.status == EngineStatus.ENDED -> SessionEndReason.ENDED
                    else -> null
                }
                runCatching {
                    if (sid != null && reason != null) {
                        // 播完 / 失败：立刻闭合会话，历史里不留悬空行（重复闭合是 no-op）
                        localSessionId = null
                        store.endSession(sid, item.serverId, key, pos, dur, completed, reason)
                    } else {
                        store.recordPosition(
                            sourceId = item.serverId,
                            mediaKey = key,
                            positionMs = pos,
                            durationMs = dur,
                            completed = completed,
                        )
                    }
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
     *
     * [autoResume] 为 false 时**只清标记、不起播** —— 应用锁场景专用（2026-10-25 用户要求）：
     * 开锁回来时画面还停在暂停帧，用户点一下才继续，避免"刚解完锁视频自己动起来"
     * （锁屏图案还没看清就开始播，也很容易误触到进度）。
     * 应用锁关闭时仍走 [autoResume] = true 的老路径。
     */
    fun resumeFromBackground(autoResume: Boolean = true) {
        if (!pausedByBackground) return
        pausedByBackground = false
        if (!autoResume) return
        engine.play()
        notifyInteraction()
    }

    /**
     * 快退 / 快进（相对当前进度；`03` §5.5 默认 ±10s）。控制层按钮用这个 —— 按钮本身可见，
     * 不需要居中提示。
     */
    fun seekBy(deltaMs: Long) = seek(deltaMs, withHint = false)

    /**
     * 双击快进 / 快退（`03` §5.5）：与 [seekBy] 同一 seek 语义，另在屏幕正中闪现 1s 的
     * 「+10 秒 / -10 秒」提示 —— 双击多半处在无控制层的沉浸态，不给反馈用户不知道跳了多远。
     */
    fun seekByWithHint(deltaMs: Long) = seek(deltaMs, withHint = true)

    private fun seek(deltaMs: Long, withHint: Boolean) {
        if (_ui.value.locked) return
        val d = _ui.value.durationMs
        val raw = _ui.value.positionMs + deltaMs
        engine.seekTo(if (d > 0L) raw.coerceIn(0L, d) else raw.coerceAtLeast(0L))
        if (withHint) {
            _ui.update { it.copy(seekHint = seekHintText(deltaMs)) }
            seekHintJob?.cancel()
            seekHintJob = viewModelScope.launch {
                delay(SEEK_HINT_DURATION_MS)
                _ui.update { it.copy(seekHint = null) }
            }
        }
        notifyInteraction()
    }

    // ---- 进度条拖动（Scrubbing 态）----

    fun beginScrub() {
        if (_ui.value.locked) return
        // controlsVisible = true：手指在画面上横向拖动时，用户必须看得到进度条，
        // 否则（没有 trickplay 预览的片源）整个拖动过程没有任何反馈。
        _ui.update {
            it.copy(scrubbing = true, scrubPositionMs = it.positionMs, controlsVisible = true)
        }
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
        // 乐观更新（任务 3-3）：松手立刻把进度条落到目标位置，不等引擎回调。
        // seekPendingTarget 在引擎真正 seek 到位前抑制 positionMs 回灌（否则 250ms 轮询
        // 会把 thumb 弹回旧位置再跳过来，2026-10-30 用户反馈）。
        _ui.update { it.copy(scrubbing = false, positionMs = target) }
        seekPendingTarget = target
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

    /** 长按 2× 前的会话倍速；null = 不在长按 2× 态（BUG-3）。 */
    private var speedBeforeLongPress: Float? = null

    /** 长按进入 2×（BUG-3）：保存当前倍速 → 切 2×。不写默认值、不重置自动隐藏。 */
    fun enterTempSpeed() {
        if (_ui.value.locked || speedBeforeLongPress != null) return
        speedBeforeLongPress = _ui.value.speed
        engine.setSpeed(2f)
        _ui.update { it.copy(speed = 2f, speedBoost = true) }
    }

    /** 松手退出 2×：恢复长按前倍速。幂等（切后台 / 旋转导致的取消也走这里）。 */
    fun exitTempSpeed() {
        val restore = speedBeforeLongPress ?: return
        speedBeforeLongPress = null
        engine.setSpeed(restore)
        _ui.update { it.copy(speed = restore, speedBoost = false) }
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
        // 用户手动选过（含「关闭字幕」）→ 本次会话不再自动开字幕，否则关掉又被顶回来
        if (kind == TrackKind.SUBTITLE) subtitleTouchedByUser = true
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
        // 把当前速度带过去：连播下一集沿用用户此刻的速度，而不是回落到 1.0x
        start(next.id, src, caps, autoPlayNextEnabled, defaultSpeed = _ui.value.speed)
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
        start(item.id, src, caps, autoPlayNextEnabled, defaultSpeed = _ui.value.speed)
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
        // 本地断点：**必须在这里再写一次**。原因有两个 ——
        // ① 用户「播到一半按返回」是最常见的退出方式，位置不能等到下一次 10s；
        // ② 位置取自 lastLocalPositionMs 而不是 _ui：onCleared 时 UI 状态可能已被清空。
        flushLocalProgress()
        localProgressJob?.cancel()
        reporter?.stop()
        // 引擎是进程级单例（前台服务持有）：只结束会话，不 release
        engine.stopSession()
    }

    /**
     * 退页 / 结束时的本地断点补写（独立成 internal 是为了单测能直接驱动 —— `onCleared` 是
     * protected，测试里只能靠 ViewModelStore.clear() 间接触发，不适合断言时序）。
     *
     * 用 `GlobalScope` 是因为 viewModelScope 此刻已取消；写入是一次 upsert，失败也只吞掉。
     */
    internal fun flushLocalProgress() {
        val store = playbackStore ?: return
        val key = localMediaKey ?: return
        val sid = localSourceId ?: return
        val pos = lastLocalPositionMs
        val dur = lastLocalDurationMs
        val completed = localCompletedWritten ||
            (dur > 0 && pos >= dur * LOCAL_COMPLETE_PERCENT / 100)
        if (completed) localCompletedWritten = true
        // 会话 id 先取走再置空：连播换集时下次 start 会重新开会话
        val sessionId = localSessionId
        localSessionId = null
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                if (sessionId != null) {
                    // 一个事务里「闭合历史 + 更新断点」，避免历史闭合了断点还停在 10s 前
                    store.endSession(sessionId, sid, key, pos, dur, completed, SessionEndReason.USER_EXIT)
                } else {
                    // 起播期就退出（还没到首个 PLAYING）：没有会话可闭合，只留断点
                    store.recordPosition(sid, key, pos, dur, completed)
                }
            }
        }
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
        /** 双击 seek 提示的显示时长（`03` §5.5：1s）。 */
        const val SEEK_HINT_DURATION_MS = 1_000L
        /** 连播浮层出现阈值（`03` §5.7：播至 90%）。 */
        const val NEXT_UP_THRESHOLD_PERCENT = 90
        const val NEXT_UP_COUNTDOWN_SECONDS = 10
        /** 取详情 / 协商播放地址失败的错误码（与引擎错误码区分：负数段）。 */
        const val SOURCE_ERROR_CODE = -1
        /** trickplay 请求宽度档位（Emby 常见预生成档：320）。 */
        const val TRICKPLAY_WIDTH = 320
        /** seek 到位判定阈值：引擎 positionMs 与目标差距 ≤ 此值即视为 seek 完成（CLOSEST_SYNC 有 ±几秒误差）。 */
        const val SEEK_SETTLE_MS = 3_000L
        /** 本地断点的写入间隔：按**位置差**算，暂停期间不会空转（`docs/10 §7.2`）。 */
        const val LOCAL_PROGRESS_STEP_MS = 10_000L
        /** 播到 95% 视为看完：位置清零 + `completed=1`，不再挂在「继续观看」里。 */
        const val LOCAL_COMPLETE_PERCENT = 95
    }
}

/**
 * 默认字幕轨：优先中文，其次第一条。
 *
 * 为什么不是「永远第一条」：很多片子内嵌 8 条语言轨，第一条常常是导演解说或英文；
 * 中文用户拿到「默认开了一条听不懂的轨」体验比不开还差。
 * 认不出来时仍然取第一条 —— 宁可语言不对，也好过「明明有字幕却不显示」。
 */
internal fun pickDefaultSubtitleTrack(
    tracks: List<app.myplayer.core.playback.TrackInfo>,
): app.myplayer.core.playback.TrackInfo? {
    val subs = tracks.filter { it.kind == TrackKind.SUBTITLE }
    if (subs.isEmpty()) return null
    val chinese = subs.firstOrNull { t ->
        val hay = (t.label + " " + (t.codec ?: "")).lowercase()
        ZH_HINTS.any { it in hay }
    }
    return chinese ?: subs.first()
}

private val ZH_HINTS = listOf("chi", "zho", "zh", "chinese", "中文", "简体", "繁体", "中字")
