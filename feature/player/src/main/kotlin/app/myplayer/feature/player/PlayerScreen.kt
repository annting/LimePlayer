package app.myplayer.feature.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.myplayer.core.designsystem.MonoLabelStyle
import app.myplayer.core.designsystem.components.BackIcon
import app.myplayer.core.designsystem.components.LockOpenIcon
import app.myplayer.core.designsystem.components.PauseIcon
import app.myplayer.core.designsystem.components.PictureInPictureIcon
import app.myplayer.core.designsystem.components.PlayIcon
import app.myplayer.core.designsystem.components.SeekBackIcon
import app.myplayer.core.designsystem.components.SeekForwardIcon
import app.myplayer.core.designsystem.components.SettingsIcon
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.playback.EngineError
import app.myplayer.core.playback.EngineStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 播放页（`03` §5.1–§5.5）：
 * 视频面 → 手势层（单击 / 双击 / 左亮度右音量）→ 亮度音量指示器 → 控制层（AnimatedVisibility）。
 * 控制层 3s 自动隐藏；锁定态只留一个解锁入口。
 */
@Composable
fun PlayerScreen(
    vm: PlayerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    appUnlocked: kotlinx.coroutines.flow.StateFlow<Boolean>? = null,
    keepBackgroundPlayback: Boolean = false,
) {
    val state by vm.ui.collectAsStateWithLifecycle()
    var indicator by remember { mutableStateOf<SystemIndicator?>(null) }

    // 画中画入口（手动触发；自动触发与「切后台自动暂停」冲突，2026-10-18 用户要求改为按钮）
    val context = androidx.compose.ui.platform.LocalContext.current
    val pipSupported = remember(context) {
        context.findActivity() != null &&
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    // 切后台自动暂停（`02` §6.7）：ON_STOP = 锁屏 / 切走 / 最近任务（画中画不停，因为仍可见）
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                vm.onAppBackgrounded(keepBackgroundPlayback)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 回前台恢复：先解锁、再恢复（应用锁开启时，解锁完成才触发；幂等）
    if (appUnlocked != null) {
        LaunchedEffect(Unit) {
            appUnlocked.collect { unlocked ->
                if (unlocked) vm.resumeFromBackground()
            }
        }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        VideoSurface(sink = vm::attachVideoSurface)

        GestureLayer(vm = vm, locked = state.locked, onIndicator = { indicator = it })

        // 缓冲指示（控制层之下、手势层之上）
        if (state.status == EngineStatus.BUFFERING) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White.copy(alpha = 0.9f),
                strokeWidth = 3.dp,
            )
        }

        // 亮度 / 音量指示器
        SystemIndicatorOverlay(indicator, Modifier.align(Alignment.Center))

        // 控制层
        AnimatedVisibility(
            visible = state.controlsVisible && !state.locked,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            PlayerControls(
                state = state,
                vm = vm,
                onBack = onBack,
                onEnterPip = { context.findActivity()?.let { act -> enterPip(act, state) } },
                pipSupported = pipSupported,
            )
        }

        // 锁定态：只留解锁入口
        if (state.locked) {
            PlayerIconButton(
                onClick = vm::toggleLock,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp),
            ) {
                LockOpenIcon(tint = Color.White)
            }
        }

        // 错误卡片（完整降级交互见任务 2-7）
        state.error?.let { error ->
            PlaybackErrorOverlay(
                error = error,
                onBack = onBack,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

// ---- 视频面 ----

@Composable
private fun VideoSurface(sink: (Surface?) -> Unit, modifier: Modifier = Modifier) {
    DisposableEffect(Unit) {
        onDispose { sink(null) }   // 组件销毁兜底解绑
    }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) { sink(h.surface) }
                    override fun surfaceDestroyed(h: SurfaceHolder) { sink(null) }
                    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                })
            }
        },
    )
}

// ---- 手势层 ----

/** 系统操作指示（亮度 / 音量）。 */
private data class SystemIndicator(val kind: Kind, val value: Float) {
    enum class Kind { BRIGHTNESS, VOLUME }
}

@Composable
private fun GestureLayer(
    vm: PlayerViewModel,
    locked: Boolean,
    onIndicator: (SystemIndicator?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audio = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    val scope = rememberCoroutineScope()
    var hideJob by remember { mutableStateOf<Job?>(null) }

    fun showIndicator(value: SystemIndicator) {
        onIndicator(value)
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(900)
            onIndicator(null)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(locked) {
                if (locked) return@pointerInput
                detectTapGestures(
                    onTap = {
                        vm.toggleControls()
                        vm.notifyInteraction()
                    },
                    onDoubleTap = { offset ->
                        if (offset.x < size.width / 2f) vm.seekBy(-DOUBLE_TAP_SEEK_MS)
                        else vm.seekBy(DOUBLE_TAP_SEEK_MS)
                    },
                )
            }
            .pointerInput(locked) {
                if (locked) return@pointerInput
                var startValue = 0f
                var left = true
                var accumulated = 0f
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        left = offset.x < size.width / 2f
                        startValue = if (left) currentBrightness(activity) else currentVolumeRatio(audio)
                        accumulated = 0f
                    },
                    onVerticalDrag = { _, dragAmount ->
                        accumulated += dragAmount
                        val v = verticalDragValue(startValue, accumulated, size.height)
                        if (left) setBrightness(activity, v) else setVolumeRatio(audio, v)
                        showIndicator(
                            SystemIndicator(
                                kind = if (left) SystemIndicator.Kind.BRIGHTNESS else SystemIndicator.Kind.VOLUME,
                                value = v,
                            )
                        )
                    },
                )
            },
    )
}

@Composable
private fun SystemIndicatorOverlay(indicator: SystemIndicator?, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = indicator != null, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Column(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 22.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (indicator?.kind == SystemIndicator.Kind.BRIGHTNESS) "亮度" else "音量",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .width(140.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.25f)),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(indicator?.value ?: 0f)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

// ---- 控制层 ----

@Composable
private fun PlayerControls(
    state: PlayerUiState,
    vm: PlayerViewModel,
    onBack: () -> Unit,
    onEnterPip: () -> Unit,
    pipSupported: Boolean,
) {
    Column(Modifier.fillMaxSize()) {
        // 顶部：返回 + 标题 + 锁定
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent)
                    )
                )
                .statusBarsPadding()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerIconButton(onClick = onBack, size = 40.dp, background = Color.Transparent) {
                BackIcon(tint = Color.White)
            }
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Text(
                    state.item?.title.orEmpty(),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                episodeLine(state.item)?.let {
                    Text(
                        it,
                        color = Color.White.copy(alpha = 0.62f),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
            }
            PlayerIconButton(onClick = vm::toggleLock, size = 40.dp, background = Color.Transparent) {
                LockOpenIcon(tint = Color.White)
            }
        }

        Spacer(Modifier.weight(1f))

        // 中部控制：快退 / 播放暂停 / 快进
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerIconButton(onClick = { vm.seekBy(-DOUBLE_TAP_SEEK_MS) }, size = 54.dp) {
                SeekBackIcon(tint = Color.White)
            }
            Spacer(Modifier.width(30.dp))
            Box(
                Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable { vm.togglePlayPause() },
                contentAlignment = Alignment.Center,
            ) {
                if (state.isPlaying) PauseIcon(tint = Color.Black) else PlayIcon(tint = Color.Black)
            }
            Spacer(Modifier.width(30.dp))
            PlayerIconButton(onClick = { vm.seekBy(DOUBLE_TAP_SEEK_MS) }, size = 54.dp) {
                SeekForwardIcon(tint = Color.White)
            }
        }

        Spacer(Modifier.weight(1f))

        // 底部：进度条 + 时间 + 设置
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))
                    )
                )
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 10.dp),
        ) {
            PlayerProgressBar(
                state = state,
                onScrubStart = vm::beginScrub,
                onScrub = vm::scrubTo,
                onScrubCommit = vm::commitScrub,
                onScrubCancel = vm::cancelScrub,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(state.displayPositionMs), color = Color.White, style = MonoLabelStyle)
                Spacer(Modifier.weight(1f))
                PlayerIconButton(
                    onClick = { /* TODO(2-6): 字幕 / 音轨 / 倍速抽屉 */ },
                    size = 40.dp,
                    background = Color.Transparent,
                ) {
                    SettingsIcon(tint = Color.White.copy(alpha = 0.85f))
                }
                // 画中画入口（任务 2-4 调整：自动进 PiP 与后台自动暂停冲突，改手动）
                if (pipSupported) {
                    Spacer(Modifier.width(4.dp))
                    PlayerIconButton(
                        onClick = { vm.notifyInteraction(); onEnterPip() },
                        size = 40.dp,
                        background = Color.Transparent,
                    ) {
                        PictureInPictureIcon(tint = Color.White.copy(alpha = 0.85f))
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    formatTime(state.durationMs),
                    color = Color.White.copy(alpha = 0.72f),
                    style = MonoLabelStyle,
                )
            }
        }
    }
}

/** 播放器覆盖层按钮：半透明圆底 + 按压微缩放。 */
@Composable
private fun PlayerIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    background: Color = Color.White.copy(alpha = 0.12f),
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 900f),
        label = "player-icon-scale",
    )
    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(background)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun PlaybackErrorOverlay(
    error: EngineError,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.82f))
            .padding(horizontal = 28.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("播放失败", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "错误码 ${error.code}",
            color = Color.White.copy(alpha = 0.6f),
            style = MonoLabelStyle,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "返回",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onBack)
                .padding(horizontal = 18.dp, vertical = 8.dp),
        )
    }
}

// ---- 小工具 ----

private fun episodeLine(item: MediaItemDto?): String? {
    if (item == null) return null
    val parts = buildList {
        item.seriesName?.let(::add)
        if (item.seasonNumber != null && item.episodeNumber != null) {
            add("S%02dE%02d".format(item.seasonNumber, item.episodeNumber))
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** 进入画中画：宽高比取视频实际尺寸，越界收敛到 PiP 允许的 2.39:1 ～ 1:2.39。 */
private fun enterPip(activity: Activity, state: PlayerUiState) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
    val ratio = if (state.videoWidth > 0 && state.videoHeight > 0) {
        val r = android.util.Rational(state.videoWidth, state.videoHeight)
        when {
            r.toFloat() > 2.39f -> android.util.Rational(239, 100)
            r.toFloat() < 1f / 2.39f -> android.util.Rational(100, 239)
            else -> r
        }
    } else {
        android.util.Rational(16, 9)
    }
    runCatching {
        val ok = activity.enterPictureInPictureMode(
            android.app.PictureInPictureParams.Builder().setAspectRatio(ratio).build()
        )
        android.util.Log.d("LimePip", "enterPictureInPictureMode ratio=${ratio.numerator}/${ratio.denominator} → $ok")
    }.onFailure { android.util.Log.w("LimePip", "enterPip 失败", it) }
}

private fun currentBrightness(activity: Activity?): Float {
    val b = activity?.window?.attributes?.screenBrightness ?: -1f
    return if (b in 0f..1f) b else 0.5f   // -1 = 跟随系统，视作 0.5 起点
}

private fun setBrightness(activity: Activity?, value: Float) {
    val w = activity?.window ?: return
    w.attributes = w.attributes.apply { screenBrightness = value.coerceIn(0.01f, 1f) }
}

private fun currentVolumeRatio(audio: AudioManager?): Float {
    val max = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: return 0.5f
    if (max <= 0) return 0.5f
    return audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
}

private fun setVolumeRatio(audio: AudioManager?, value: Float) {
    val max = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: return
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, (value * max).roundToInt().coerceIn(0, max), 0)
}
