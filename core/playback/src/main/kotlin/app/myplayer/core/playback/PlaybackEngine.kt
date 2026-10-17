package app.myplayer.core.playback

import app.myplayer.core.model.PlaybackPlan
import kotlinx.coroutines.flow.StateFlow

/**
 * 播放内核抽象（[`02` §3.1 模块表](../../../../../../docs/02-技术规范.md)）。
 *
 * 存在的意义：把「播放」与「Media3」解耦 —— 播放页、进度上报只依赖本接口，
 * 未来 L2 接入 libmpv（极限兼容模式）时新增一个实现即可，不改上层。
 *
 * ## 线程约定
 * 实现必须在**主线程**调用其内部播放器 API（Media3 / libmpv 均要求）。
 * 调用方（播放页 / ViewModel）在主线程调用 [prepare] / [play] 等方法即可。
 *
 * ## 状态模型
 * [state] 是唯一事实来源：结构性变化（状态、轨道、错误、视频尺寸）由播放器回调即时更新；
 * 高频的 position / buffered 由实现层节流轮询（250ms）。播放页与进度上报都观察它。
 */
interface PlaybackEngine {

    /** 当前引擎状态。UI 与进度上报均以此为唯一数据源。 */
    val state: StateFlow<EngineState>

    /**
     * 装载并开始准备播放。[startPositionMs] 为断点续播位置（毫秒，0 表示从头）。
     *
     * 调用后引擎进入 [EngineStatus.BUFFERING]（或直接 [EngineStatus.READY]）；
     * 上一次会话的 position / error / tracks 全部重置。
     */
    fun prepare(plan: PlaybackPlan, startPositionMs: Long = 0L)

    /** 开始/继续播放（在 [prepare] 之后调用）。 */
    fun play()

    /** 暂停（进度上报方需在此后立即上报一次，见 `02` §6.4）。 */
    fun pause()

    /** 跳转到指定位置（毫秒）。 */
    fun seekTo(positionMs: Long)

    /** 设置倍速（0.5–3.0，含音调补偿由实现负责）。 */
    fun setSpeed(speed: Float)

    /**
     * 选择音轨 / 字幕轨。[trackId] 为 [EngineState.tracks] 中的 id；
     * 传 `null` 表示关闭该类型（如关闭字幕）。
     */
    fun selectTrack(kind: TrackKind, trackId: String?)

    /**
     * 绑定 / 解绑视频输出面（UI 提供 SurfaceView 的 Surface；libmpv 同样以绑定面渲染）。
     * 传 `null` 表示解绑（Surface 销毁时）。可在 [prepare] 之前调用（Surface 先于媒体就绪）。
     */
    fun setVideoSurface(surface: android.view.Surface?)

    /**
     * 结束当前播放会话（退出播放页）：停止播放并清空媒体，但**不释放引擎**。
     * 与 [release] 的区别：引擎实例可能被前台服务（媒体会话）长期持有，
     * 退出页面只是「换片/收摊」，不是「销毁」。
     */
    fun stopSession() {}

    /** 释放全部资源。幂等（重复调用安全）。释放后不应再调用其它方法。 */
    fun release()
}

/** 引擎状态（节流后）。position / buffered 以毫秒计 —— 与 Media3 一致，避免频繁跨单位换算。 */
data class EngineState(
    val status: EngineStatus = EngineStatus.IDLE,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val speed: Float = 1f,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val error: EngineError? = null,
    val tracks: List<TrackInfo> = emptyList(),
)

enum class EngineStatus {
    /** 尚未装载媒体，或已释放。 */
    IDLE,
    BUFFERING,
    READY,
    ENDED,
    FAILED,
}

/** 播放错误（包装引擎的原始错误码，供 `03` §5.8 的错误卡片展示）。 */
data class EngineError(
    val code: Int,
    val message: String,
)

enum class TrackKind { AUDIO, SUBTITLE }

/**
 * 一条可选择的媒体轨道（`02` §6.6 的设置抽屉数据源）。
 * [isSupported] 为 false 时 UI 应置灰并给说明，不允许静默失效。
 */
data class TrackInfo(
    val id: String,
    val kind: TrackKind,
    val label: String,
    val codec: String?,
    val isSelected: Boolean,
    val isSupported: Boolean,
)
