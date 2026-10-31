package app.myplayer.core.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.myplayer.core.model.PlaybackPlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * [PlaybackEngine] 的 Media3（ExoPlayer）实现 —— 当前唯一实现。
 *
 * - **进度**：Media3 无位置回调，按官方实践用主线程 [Handler] 以 [POSITION_TICK_MS] 节流轮询；
 *   暂停时同样轮询（StateFlow 对同值更新去重，开销可忽略）。
 * - **请求头**：`PlaybackPlan.headers` 经 [ResolvingDataSource] 注入 —— 当前 Emby 直连 / 转码
 *   均在 URL 携带 `api_key`，headers 为空；该通道为未来无 `api_key` 的服务端预留。
 *   本引擎同一时刻只承载一个 plan，故用单一 `currentHeaders` 即可（连播场景见任务 2-7）。
 * - **字幕 / 音轨**：只做「选择」，不解析、不渲染（渲染由播放页的 SubtitleView / Compose 承担）。
 */
class Media3PlaybackEngine(context: Context) : PlaybackEngine {

    /** per-prepare 的请求头；单 plan 播放器，直接字段即可。 */
    @Volatile
    private var currentHeaders: Map<String, String> = emptyMap()

    private val dataSourceFactory = ResolvingDataSource.Factory(
        DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory()),
    ) { spec ->
        if (currentHeaders.isEmpty()) spec else spec.withRequestHeaders(currentHeaders)
    }

    // 字幕：引擎只把**文本**抛进状态，排版交给播放页（2026-10-26 起自绘，
    // 因为 SubtitleView 只能整块贴底，做不了上对齐 / 放黑边）。
    private val cueListener = object : Player.Listener {
        override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
            _state.update { st ->
                st.copy(
                    cues = cueGroup.cues.mapNotNull { cue ->
                        cue.text?.toString()?.takeIf { it.isNotBlank() }
                    },
                )
            }
        }
    }

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .setHandleAudioBecomingNoisy(true)   // 耳机拔出自动暂停
        // 缓冲调参（任务 3-3）：局域网直放 / NAS 转码都能吃得下更大的前向缓冲，
        // 拖动后更少出现「缓冲圈」。移动网络下由系统自适应回落。
        .setLoadControl(
            androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 15_000,
                    /* maxBufferMs = */ 60_000,
                    /* bufferForPlaybackMs = */ 1_500,
                    /* bufferForPlaybackAfterRebufferMs = */ 3_000,
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        )
        .build()

    init {
        // seek 策略（任务 3-3）：对齐最近关键帧而非精确帧 —— 拖动后起播最快，
        // 代价是落点可能偏几秒；语义符合「拖动即预览、松手即播」的播放器惯例。
        player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
        // 音频焦点（`02` §6.4 / 任务 2-4）：请求 MEDIA 焦点，来电 / 其它应用抢焦点时自动暂停恢复
        player.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )
    }

    /** 系统播放器实例 —— 供媒体会话（通知栏 / 锁屏控制）包装。 */
    val platformPlayer: Player get() = player

    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var released = false

    /** 隐私模式：prepare 时把暴露给系统的媒体元数据换成占位（见 [toMediaItem] 的 redact）。 */
    @Volatile
    private var privacyMode = false

    /** 是否已释放（持有者据此重建引擎，避免「已释放的引擎不能再 prepare」）。 */
    val isReleased: Boolean get() = released

    /** 位置轮询（主线程，250ms）。 */
    private val ticker = object : Runnable {
        override fun run() {
            syncDynamic()
            mainHandler.postDelayed(this, POSITION_TICK_MS)
        }
    }

    init {
        player.addListener(cueListener)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) = syncStructure()

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onPlayerError(error: PlaybackException) {
                _state.update {
                    it.copy(
                        status = EngineStatus.FAILED,
                        error = EngineError(error.errorCode, error.message ?: "播放失败"),
                    )
                }
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                _state.update { it.copy(videoWidth = videoSize.width, videoHeight = videoSize.height) }
            }

            override fun onTracksChanged(tracks: Tracks) {
                _state.update { it.copy(tracks = tracks.toTrackInfos()) }
            }
        })
    }

    override fun prepare(plan: PlaybackPlan, startPositionMs: Long) {
        check(!released) { "已释放的引擎不能再 prepare" }
        // P1-4：重置会话级状态，避免上一个条目的倍速 / 音轨 override 串到新条目。
        // VM 的 start() 在 prepare 之后会重新 setSpeed(defaultSpeed)，此处重置不会被吞。
        player.setPlaybackSpeed(1f)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverrides().build()
        currentHeaders = when (plan) {
            is PlaybackPlan.Direct -> plan.headers
            is PlaybackPlan.Transcode -> plan.headers
        }
        _state.value = EngineState()   // 新会话：清空上一次的 error / tracks / position
        val subs = when (plan) {
            is PlaybackPlan.Direct -> plan.subtitles
            is PlaybackPlan.Transcode -> plan.subtitles
        }
        player.setMediaItem(plan.toMediaItem(subs, redact = privacyMode), startPositionMs)
        player.prepare()
        syncStructure()
        mainHandler.removeCallbacks(ticker)
        mainHandler.postDelayed(ticker, POSITION_TICK_MS)
    }

    /**
     * 隐私模式的媒体会话占位（方案 §9.6）。
     *
     * 只影响**暴露给系统**的元数据（锁屏 / 通知 / 蓝牙 AVRCP / Android Auto 读到的
     * `MediaMetadata.mediaId` 与标题），不影响真实播放地址 —— 播放器内部仍用原 URI。
     * 隐私模式只在起播前进入（进隐私才会看到私有源），因此这里只翻开关，
     * 不去替换正在播的媒体项（替换会触发重新 prepare，得不偿失）。
     */
    override fun setPrivacyMode(enabled: Boolean) {
        privacyMode = enabled
    }

    override fun play() {
        if (!released) player.play()
    }

    override fun pause() {
        if (!released) player.pause()
    }

    override fun seekTo(positionMs: Long) {
        if (!released) player.seekTo(positionMs.coerceAtLeast(0L))
    }

    override fun setSpeed(speed: Float) {
        if (released) return
        player.setPlaybackSpeed(speed)
        _state.update { it.copy(speed = speed) }
    }

    override fun selectTrack(kind: TrackKind, trackId: String?) {
        if (released) return
        val type = kind.toMedia3Type()
        val builder = player.trackSelectionParameters.buildUpon()
        if (trackId == null) {
            builder.setTrackTypeDisabled(type, true)
        } else {
            builder.setTrackTypeDisabled(type, false)
            // 必须遍历**所有**同类型 group：外挂字幕（MediaItem.SubtitleConfiguration）
            // 在 ExoPlayer 里是各自独立的 TrackGroup，只查第一个 group 会导致
            // 第二条及以后的字幕点了没反应（真机实测：中文可选、英文无响应）。
            val sameType = player.currentTracks.groups.withIndex().filter { it.value.type == type }
            val position = resolveTrackPosition(
                groups = sameType.map { it.value.mediaTrackGroup.id to it.value.length },
                trackId = trackId,
            )
            if (position != null) {
                val group = sameType[position.first].value
                builder.setOverrideForType(
                    TrackSelectionOverride(group.mediaTrackGroup, position.second)
                )
            }
        }
        player.trackSelectionParameters = builder.build()
    }

    override fun setVideoSurface(surface: android.view.Surface?) {
        if (!released) player.setVideoSurface(surface)
    }

    override fun stopSession() {
        if (released) return
        // 先断开视频面：SurfaceView 是独立窗口图层，Compose 的转场 / alpha 影响不到它；
        // 退出播放页时若还挂着 surface，就会看到视频残留在下层页面上方（2026-10-19 真机 bug）。
        player.setVideoSurface(null)
        _state.update { EngineState() }   // 会话结束：清空上一次的 error / tracks / position
        // stop() 会同步等待内部播放线程收尾（网络流可达数百毫秒），放到下一帧执行：
        // 先把 Back 的导航让出去，避免"点返回卡一下"。
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (!released) {
                player.stop()
                player.clearMediaItems()
            }
        }
    }

    override fun release() {
        if (released) return
        released = true
        mainHandler.removeCallbacks(ticker)
        player.release()
    }

    // ---- 内部映射 ----

    private fun syncStructure() {
        if (released) return
        val status = when (player.playbackState) {
            Player.STATE_BUFFERING -> EngineStatus.BUFFERING
            Player.STATE_READY -> EngineStatus.READY
            Player.STATE_ENDED -> EngineStatus.ENDED
            else -> if (_state.value.error != null) EngineStatus.FAILED else EngineStatus.IDLE
        }
        _state.update {
            it.copy(
                status = status,
                isPlaying = player.isPlaying,
                durationMs = player.duration.validOrZero(),
                bufferedMs = player.bufferedPosition.coerceAtLeast(0L),
            )
        }
    }

    private fun syncDynamic() {
        if (released) return
        _state.update {
            it.copy(
                positionMs = player.currentPosition.coerceAtLeast(0L),
                bufferedMs = player.bufferedPosition.coerceAtLeast(0L),
                durationMs = player.duration.validOrZero().takeIf { d -> d > 0L } ?: it.durationMs,
            )
        }
    }

    private fun Long.validOrZero(): Long = if (this == C.TIME_UNSET || this < 0L) 0L else this

    private companion object {
        const val POSITION_TICK_MS = 250L
    }
}

// ---- PlaybackPlan → MediaItem ----

/**
 * `Transcode` 显式标注 HLS MIME —— 转码 URL 的 path 是 `master.m3u8` 且带 query，
 * 不依赖 [DefaultMediaSourceFactory] 的扩展名推断，避免路径变体（如服务端改写 URL）导致回退到渐进式解析。
 *
 * [redact] = 隐私模式：媒体项只带**占位** id 与标题。真实 URI 仍交给播放器（必然要能播），
 * 但 `MediaMetadata.mediaId` 默认就是 URI/路径 —— SMB 的 mediaSourceId 是完整路径，
 * 会原样出现在锁屏/通知/车机/`dumpsys media_session` 里。隐私模式下必须换成常量。
 */
internal fun PlaybackPlan.toMediaItem(
    subtitles: List<app.myplayer.core.model.ExternalSubtitle> = emptyList(),
    redact: Boolean = false,
): MediaItem {
    // 外挂字幕轨（任务：字幕渲染）：服务端按需转 srt/vtt 下发，ExoPlayer 侧载后
    // 会出现在 TRACK_TYPE_TEXT 轨道里，播放器菜单即可切换。
    val subtitleConfigs = subtitles.map { sub ->
        MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(sub.url))
            .setMimeType(sub.mimeType)
            .setLanguage(sub.language)
            .setLabel(sub.label)
            .setSelectionFlags(if (sub.isDefault) C.SELECTION_FLAG_DEFAULT else 0)
            .build()
    }
    val item = when (this) {
    is PlaybackPlan.Direct -> MediaItem.Builder()
        .setUri(uri)
        .setSubtitleConfigurations(subtitleConfigs)
        .build()

    is PlaybackPlan.Transcode -> MediaItem.Builder()
        .setUri(hlsUrl)
        .setMimeType(MimeTypes.APPLICATION_M3U8)
        .setSubtitleConfigurations(subtitleConfigs)
        .build()
    }
    if (!redact) return item
    return item.buildUpon()
        .setMediaId(PRIVATE_MEDIA_ID)
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setTitle(PRIVATE_TITLE)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
        )
        .build()
}

/** 隐私模式下媒体会话暴露的占位标题（不含任何真实条目信息）。 */
const val PRIVATE_TITLE: String = "LimePlayer"

/** 隐私模式下媒体项 id 的占位值（正常模式是 URI 或 SMB 路径）。 */
const val PRIVATE_MEDIA_ID: String = "limeplayer-private"

// ---- Tracks → TrackInfo ----

private fun TrackKind.toMedia3Type(): Int = when (this) {
    TrackKind.AUDIO -> C.TRACK_TYPE_AUDIO
    TrackKind.SUBTITLE -> C.TRACK_TYPE_TEXT
}

/** 只暴露音轨与字幕轨；id 约定：`"${group.id}:$index"`（与 [Media3PlaybackEngine.selectTrack] 的查找一致）。 */
private fun Tracks.toTrackInfos(): List<TrackInfo> {
    val out = mutableListOf<TrackInfo>()
    for (group in groups) {
        val kind = when (group.type) {
            C.TRACK_TYPE_AUDIO -> TrackKind.AUDIO
            C.TRACK_TYPE_TEXT -> TrackKind.SUBTITLE
            else -> continue
        }
        for (i in 0 until group.length) {
            val format = group.getTrackFormat(i)
            out += TrackInfo(
                id = "${group.mediaTrackGroup.id}:$i",
                kind = kind,
                label = format.label ?: format.language ?: format.sampleMimeType ?: "轨道 ${i + 1}",
                codec = format.sampleMimeType,
                isSelected = group.isTrackSelected(i),
                isSupported = group.isTrackSupported(i),
            )
        }
    }
    return out
}

/**
 * 在「同类型 track group 列表」里定位 [trackId] 对应的 (group 序号, 组内轨序号)。
 *
 * 抽成纯函数是为了能被 JVM 单测覆盖：外挂字幕在 ExoPlayer 里是**每轨一个 group**，
 * 只在第一个 group 里找会导致第二条之后的字幕点了没反应（真机实测 bug）。
 *
 * [groups] 每项为 (group.id, 组内轨数)，顺序须与 UI 展示顺序一致。
 */
internal fun resolveTrackPosition(
    groups: List<Pair<String, Int>>,
    trackId: String,
): Pair<Int, Int>? {
    groups.forEachIndexed { groupIndex, (groupId, count) ->
        for (i in 0 until count) {
            if ("$groupId:$i" == trackId) return groupIndex to i
        }
    }
    return null
}
