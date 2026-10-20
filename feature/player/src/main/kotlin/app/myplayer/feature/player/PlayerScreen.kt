package app.myplayer.feature.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.view.Surface
import android.view.TextureView
import android.graphics.SurfaceTexture
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.myplayer.core.designsystem.MonoLabelStyle
import app.myplayer.core.designsystem.components.BackIcon
import app.myplayer.core.designsystem.components.CheckIcon
import app.myplayer.core.designsystem.components.MoreVerticalIcon
import app.myplayer.core.designsystem.components.LockOpenIcon
import app.myplayer.core.designsystem.components.PauseIcon
import app.myplayer.core.designsystem.components.PictureInPictureIcon
import app.myplayer.core.designsystem.components.PlayIcon
import app.myplayer.core.designsystem.components.ScreenRotateIcon
import app.myplayer.core.designsystem.components.SeekBack10Icon
import app.myplayer.core.designsystem.components.SeekForward10Icon
import app.myplayer.core.designsystem.components.SettingsIcon
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.playback.EngineError
import app.myplayer.core.playback.EngineStatus
import app.myplayer.core.playback.TrackKind
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

    // 横竖屏切换：**只转播放器内容，不动系统方向**
    //（对齐 VidHub「播放器内切换横竖屏、无需旋转设备」，2026-10-20）。
    // 视频面因此必须是 TextureView —— SurfaceView 的画面在独立图层，实测不跟随任何 View/Compose 变换；
    // 好处是退出播放页时系统方向从未变过，**没有任何旋转动作、也没有黑屏闪一下**。
    var videoRotated by remember { mutableStateOf(false) }
    val activity = context.findActivity()
    // 旋转态隐藏系统栏：系统栏永远按设备方向绘制，不隐藏会横在画面中间
    LaunchedEffect(videoRotated) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (videoRotated) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    // 退出兜底：把系统栏还回去（旋转态退出时更要）
    DisposableEffect(Unit) {
        onDispose {
            activity?.window?.let {
                WindowCompat.getInsetsController(it, it.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // 回前台恢复：先解锁、再恢复（应用锁开启时，解锁完成才触发；幂等）
    if (appUnlocked != null) {
        LaunchedEffect(Unit) {
            appUnlocked.collect { unlocked ->
                if (unlocked) vm.resumeFromBackground()
            }
        }
    }

    // 「首帧未就绪」共两种：IDLE = 还在取详情 / 协商播放地址，BUFFERING = 已 prepare 等数据。
    // 这一整段都只该看到加载动画 —— 播放 / 快进按钮此时无意义，且会盖住动画（2026-10-19 真机反馈）。
    // 出错时不显示转圈（错误卡片自带重试）。
    val loading = state.error == null &&
        (state.status == EngineStatus.IDLE || state.status == EngineStatus.BUFFERING)

    Box(modifier.fillMaxSize().background(Color.Black).clipToBounds()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // 容器宽高对调 + 整体转 90°：等价于「按横屏尺寸布局」，但系统方向一点没变。
            // 带过渡动画（VidHub 切换时有明显旋转，瞬切太生硬）：角度与尺寸一起插值。
            val rotateProgress by animateFloatAsState(
                targetValue = if (videoRotated) 1f else 0f,
                animationSpec = tween(durationMillis = ROTATE_ANIM_MS, easing = FastOutSlowInEasing),
                label = "videoRotate",
            )
            val stageW = lerp(maxWidth, maxHeight, rotateProgress)
            val stageH = lerp(maxHeight, maxWidth, rotateProgress)
            Box(
                Modifier
                    .align(Alignment.Center)
                    // requiredSize：对调后比父容器宽，用 size() 会被父约束压成方块（真机 bug）
                    .requiredSize(stageW, stageH)
                    .graphicsLayer { rotationZ = 90f * rotateProgress },
            ) {
        // 视频面按**画面比例模式**摆放（`03` §5.6 / 任务 2-6）：
        // · 适应：保持视频宽高比完整显示，多余方向留黑边（默认）
        // · 填充：拉伸铺满（用户显式选择才用）
        // · 裁剪：保持比例放大到铺满，超出屏幕部分裁掉
        // · 原始：按视频原始像素尺寸显示
        // 宽高比未知（装载中）时先占满，就绪后按比例重排（surface 保持绑定，不黑屏）。
            val vw = state.videoWidth
            val vh = state.videoHeight
            val density = androidx.compose.ui.platform.LocalDensity.current
            val videoModifier = if (vw <= 0 || vh <= 0) {
                Modifier.fillMaxSize()
            } else {
                val videoRatio = vw.toFloat() / vh
                val screenRatio = stageW / stageH
                when (state.scaleMode) {
                    ScaleMode.FIT ->
                        if (videoRatio >= screenRatio) Modifier.fillMaxWidth().aspectRatio(videoRatio)
                        else Modifier.fillMaxHeight().aspectRatio(videoRatio)
                    ScaleMode.FILL -> Modifier.fillMaxSize()
                    ScaleMode.CROP -> {
                        // 覆盖：取两个方向都铺满的最小尺寸
                        val w = maxOf(stageW, stageH * videoRatio)
                        Modifier.width(w).height(w / videoRatio)
                    }
                    ScaleMode.ORIGINAL -> with(density) {
                        Modifier.size(vw.toDp(), vh.toDp())
                    }
                }
            }
            VideoSurface(
                sink = vm::attachVideoSurface,
                modifier = Modifier.align(Alignment.Center).then(videoModifier),
            )
            // 字幕渲染层（任务：字幕渲染）：Media3 SubtitleView 直接消费引擎 cues，
            // 与视频面同区域对齐，随画面比例模式一起缩放。
            //
            // 字号与位置必须显式指定（2026-10-19 真机反馈）：
            // · 不设字号时 SubtitleView 跟随系统字幕大小，用户系统里调过就会「特别大」；
            //   固定 16sp 并忽略内嵌字号（外挂 srt 本身也没有字号）。
            // · cue 默认贴在视图最底，即视频画面下沿；加 bottomPaddingFraction 抬到
            //   画面下方约 10% 处，既避开系统手势区也不压住控制栏。
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    androidx.media3.ui.SubtitleView(ctx).apply {
                        setApplyEmbeddedFontSizes(false)
                        setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                        setBottomPaddingFraction(0.10f)
                    }
                },
                update = { view -> vm.attachSubtitleView(view) },
                modifier = Modifier.align(Alignment.Center).then(videoModifier),
            )

        GestureLayer(vm = vm, locked = state.locked, onIndicator = { indicator = it })

        // 缓冲指示放到控制层**之上**（见下方），此处不再重复绘制

        // 亮度 / 音量指示器
        SystemIndicatorOverlay(indicator, Modifier.align(Alignment.Center))

        // 双击 seek 提示（`03` §5.5）：正中闪现 1s 的「+10 秒 / -10 秒」。
        // 上抬 76dp 是为了避开中部控制层的播放按钮 —— 控制层可见时双击同样要给得出反馈。
        state.seekHint?.let { hint ->
            Text(
                hint,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = (-76).dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 22.dp, vertical = 14.dp),
            )
        }

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
                onOpenSheet = vm::openSheet,
                pipSupported = pipSupported,
                landscape = videoRotated,
                onToggleRotate = { videoRotated = !videoRotated },
            )
        }

        // 缓冲指示（在控制层**之上**）：首帧协商 / 拖动后的 loading 不能被任何按钮压住
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White.copy(alpha = 0.9f),
                strokeWidth = 3.dp,
            )
        }

        // 设置弹层（`03` §5.6）：画面比例 / 倍速 / 音轨 / 字幕
        if (state.sheetVisible) {
            PlayerSettingsSheet(
                state = state,
                rotated = videoRotated,
                onDismiss = vm::closeSheet,
                onScaleMode = vm::setScaleMode,
                onSpeed = vm::setSpeed,
                onTrack = vm::selectTrack,
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

        // 直放降级提示（`03` §5.8：顶部小卡片，自动消失）
        state.fallbackNotice?.let { notice ->
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(notice, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium)
            }
        }

        // 拖动预览（`03` §5.4 / 任务 3-2）：有 trickplay 才显示，无预览不影响拖动
        if (state.scrubbing) {
            state.trickplay?.let { spec ->
                TrickplayPreview(
                    vm = vm,
                    spec = spec,
                    positionMs = state.scrubPositionMs,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 132.dp),
                )
            }
        }

        // 连播浮层（`03` §5.7：90% 出现「下一集 + 10s 倒计时」）
        if (state.nextUpVisible) {
            NextUpCard(
                nextEpisode = state.nextEpisode,
                countdown = state.nextUpCountdown,
                hasPrevious = vm.hasPreviousEpisode(),
                onPlayNext = vm::playNext,
                onPlayPrevious = vm::playPrevious,
                onCancel = vm::cancelNextUp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 96.dp),
            )
        }

        // 错误卡片（`03` §5.8：错误码 + 重试）
        state.error?.let { error ->
            PlaybackErrorOverlay(
                error = error,
                onBack = onBack,
                onRetry = vm::retry,
                modifier = Modifier.align(Alignment.Center),
            )
        }
            }
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
        modifier = modifier,
        factory = { ctx ->
            // ⚠️ 必须是 TextureView，不能是 SurfaceView：SurfaceView 的画面在**独立图层**上，
            // 实测 `setRotation` 和 Compose 的 `graphicsLayer` 都只转 View 的绘制/位置，
            // **不转 surface 里的视频**（2026-10-20 实测）；TextureView 画在窗口里，能跟随任意变换。
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    var surface: Surface? = null
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        surface = Surface(st).also { sink(it) }
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        sink(null)
                        surface?.release()
                        surface = null
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
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
                        if (offset.x < size.width / 2f) vm.seekByWithHint(-DOUBLE_TAP_SEEK_MS)
                        else vm.seekByWithHint(DOUBLE_TAP_SEEK_MS)
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
    onOpenSheet: () -> Unit,
    pipSupported: Boolean,
    /** 舞台当前是横屏版式（窗口内旋转，不是系统方向）。 */
    landscape: Boolean,
    onToggleRotate: () -> Unit,
) {
    // 「剩余时长」只在横屏版式出现（`03` §5.2 调整）
    val isLandscape = landscape
    // 当前展开的快捷浮层（速率 / 字幕 / 画面比例），null = 无
    var openPanel by remember { mutableStateOf<QuickPanel?>(null) }

    // 面板展开期间保活控制层：否则 3s 自动隐藏会把面板一起带走
    LaunchedEffect(openPanel) {
        while (openPanel != null) {
            vm.notifyInteraction()
            delay(800)
        }
    }

    // 快捷浮层必须画在**同一个坐标系**里（Popup 是独立窗口，不会跟着播放器一起转），
    // 所以记下舞台坐标、量出芯片位置，面板按它定位。
    var stageCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // 每个入口各自的位置（舞台坐标系）：按钮布局不变时 onGloballyPositioned 不会再回调，
    // 所以必须在**每次布局**都记下来，不能只在展开时记。
    val chipAnchors = remember { androidx.compose.runtime.mutableStateMapOf<QuickPanel, androidx.compose.ui.geometry.Rect>() }
    // 系统返回键：面板打开时先关面板，而不是直接退播放页
    androidx.activity.compose.BackHandler(enabled = openPanel != null) { openPanel = null }

    Box(Modifier.fillMaxSize().onGloballyPositioned { stageCoords = it }) {
        // 点空白处关面板（最底层，按钮行仍可点）
        if (openPanel != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { openPanel = null }
            )
        }

        // ── 顶部：返回 + 标题 + 锁定 ──
        Row(
            Modifier
                .align(Alignment.TopCenter)
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
            // 锁与「更多」收紧到 34dp：两者图标只有 18dp，40dp 时中间空了 22dp（2026-10-19 反馈）
            PlayerIconButton(onClick = vm::toggleLock, size = 34.dp, background = Color.Transparent) {
                LockOpenIcon(tint = Color.White)
            }
            // 完整设置面板入口（右上角、锁定按钮右侧）
            PlayerIconButton(onClick = onOpenSheet, size = 34.dp, background = Color.Transparent) {
                MoreVerticalIcon(tint = Color.White)
            }
        }

        // ── 右侧中部：画中画 + 横竖屏切换 ──
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (pipSupported) {
                PlayerIconButton(
                    onClick = { vm.notifyInteraction(); onEnterPip() },
                    size = 40.dp,
                    background = Color.White.copy(alpha = 0.10f),
                ) {
                    PictureInPictureIcon(tint = Color.White.copy(alpha = 0.9f))
                }
            }
            PlayerIconButton(
                onClick = {
                    vm.notifyInteraction()
                    // **只转播放器内容**，不动系统方向（VidHub 同款做法）
                    onToggleRotate()
                },
                size = 40.dp,
                background = Color.White.copy(alpha = 0.10f),
            ) {
                ScreenRotateIcon(tint = Color.White.copy(alpha = 0.9f))
            }
        }

        // ── 底部三行：进度条 / 播放时间 / 功能按钮 ──
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))
                    )
                )
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 26.dp, bottom = 6.dp),
        ) {
            // 第一行：进度条
            PlayerProgressBar(
                state = state,
                onScrubStart = vm::beginScrub,
                onScrub = vm::scrubTo,
                onScrubCommit = vm::commitScrub,
                onScrubCancel = vm::cancelScrub,
            )

            // 第二行：左「当前」/ 右「总时长」；横屏在总时长左侧追加「-剩余」
            // 与进度条只隔 2dp：这两个信息是一组，贴紧读起来才顺。
            Spacer(Modifier.height(2.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(formatTime(state.displayPositionMs), color = Color.White, style = MonoLabelStyle)
                Spacer(Modifier.weight(1f))
                if (isLandscape) {
                    val remainMs = (state.durationMs - state.displayPositionMs).coerceAtLeast(0L)
                    Text(
                        "-${formatTime(remainMs)}",
                        color = Color.White.copy(alpha = 0.58f),
                        style = MonoLabelStyle,
                    )
                    Spacer(Modifier.width(16.dp))
                }
                Text(
                    formatTime(state.durationMs),
                    color = Color.White.copy(alpha = 0.72f),
                    style = MonoLabelStyle,
                )
            }

            // 第三行：左「快退 / 播放暂停 / 快进」，右「速率 / 字幕 / 画面比例」。
            // 全部**无底色**（2026-10-19 用户要求）：图标与文字直接压在渐变上，画面更干净。
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 三个按钮不留额外间距（图标自带 6~11dp 内边距，视觉已经够分开）。
                // 与其它图标**同时出现**：退场重叠已由「先解绑 SurfaceView + 无转场」解决，
                // 不再需要等首帧就绪才显示（2026-10-19 用户反馈）。
                    PlayerIconButton(
                        onClick = { vm.seekBy(-DOUBLE_TAP_SEEK_MS) },
                        size = 36.dp,
                        background = Color.Transparent,
                    ) {
                        SeekBack10Icon(tint = Color.White)
                    }
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable { vm.togglePlayPause() },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.isPlaying) PauseIcon(tint = Color.White) else PlayIcon(tint = Color.White)
                    }
                    PlayerIconButton(
                        onClick = { vm.seekBy(DOUBLE_TAP_SEEK_MS) },
                        size = 36.dp,
                        background = Color.Transparent,
                    ) {
                        SeekForward10Icon(tint = Color.White)
                    }
                Spacer(Modifier.weight(1f))
                ControlChip(
                    text = speedText(state.speed),
                    active = openPanel == QuickPanel.SPEED,
                    stageCoords = stageCoords,
                    onAnchor = { chipAnchors[QuickPanel.SPEED] = it },
                    onClick = {
                        vm.notifyInteraction()
                        openPanel = if (openPanel == QuickPanel.SPEED) null else QuickPanel.SPEED
                    },
                )
                ControlChip(
                    text = "字幕",
                    active = openPanel == QuickPanel.SUBTITLE,
                    stageCoords = stageCoords,
                    onAnchor = { chipAnchors[QuickPanel.SUBTITLE] = it },
                    onClick = {
                        vm.notifyInteraction()
                        openPanel = if (openPanel == QuickPanel.SUBTITLE) null else QuickPanel.SUBTITLE
                    },
                )
                ControlChip(
                    text = state.scaleMode.label,
                    active = openPanel == QuickPanel.SCALE,
                    stageCoords = stageCoords,
                    onAnchor = { chipAnchors[QuickPanel.SCALE] = it },
                    onClick = {
                        vm.notifyInteraction()
                        openPanel = if (openPanel == QuickPanel.SCALE) null else QuickPanel.SCALE
                    },
                )
            }
        }

        // ── 快捷浮层：右下角贴**被点那颗按钮**的右上角，随播放器一起转 90° ──
        val panel = openPanel
        val anchor = panel?.let { chipAnchors[it] }
        if (panel != null && anchor != null) {
            var panelSize by remember(panel) { mutableStateOf(IntSize.Zero) }
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            x = (anchor.right - panelSize.width).roundToInt().coerceAtLeast(0),
                            y = (anchor.top - panelSize.height - 6.dp.toPx()).roundToInt()
                                .coerceAtLeast(0),
                        )
                    }
                    .onSizeChanged { panelSize = it },
            ) {
                QuickListPanel(
                    title = when (panel) {
                        QuickPanel.SPEED -> "播放倍速"
                        QuickPanel.SUBTITLE -> "字幕"
                        QuickPanel.SCALE -> "画面比例"
                    },
                    // 竖屏够高：倍速一屏 9 档；横屏版式矮，只放 5 行靠滚动
                    maxHeight = when (panel) {
                        QuickPanel.SPEED -> if (isLandscape) 212.dp else 364.dp
                        else -> 220.dp
                    },
                    options = when (panel) {
                        QuickPanel.SPEED -> Speeds.map { (value, label) ->
                            QuickOption(label, kotlin.math.abs(state.speed - value) < 0.01f) {
                                vm.setSpeed(value)
                                openPanel = null
                            }
                        }
                        QuickPanel.SUBTITLE -> subtitleOptions(state, vm) { openPanel = null }
                        QuickPanel.SCALE -> ScaleMode.entries.map { mode ->
                            QuickOption(mode.label, state.scaleMode == mode) {
                                vm.setScaleMode(mode)
                                openPanel = null
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * 底部右侧的快捷入口（速率 / 字幕 / 画面比例）：无底色，展开时文字提亮。
 *
 * 只负责「报告自己在舞台里的位置」——面板由 [PlayerControls] 在同一坐标系里绘制，
 * 这样播放器整体旋转时浮层也跟着转（Popup 是独立窗口，做不到这一点）。
 */
@Composable
private fun ControlChip(
    text: String,
    active: Boolean,
    stageCoords: LayoutCoordinates?,
    onAnchor: (androidx.compose.ui.geometry.Rect) -> Unit,
    onClick: () -> Unit,
) {
    Box(
        Modifier.onGloballyPositioned { coords ->
            if (stageCoords != null) {
                runCatching { stageCoords.localBoundingBoxOf(coords) }.getOrNull()?.let(onAnchor)
            }
        }
    ) {
        Text(
            text,
            color = if (active) Color.White else Color.White.copy(alpha = 0.86f),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/** 底部右侧快捷浮层的三种内容。 */
private enum class QuickPanel { SPEED, SUBTITLE, SCALE }

private data class QuickOption(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
)

/** 字幕选项：第一项固定「关闭」，其后是服务端下发的每条文本字幕轨。 */
private fun subtitleOptions(
    state: PlayerUiState,
    vm: PlayerViewModel,
    onPicked: () -> Unit,
): List<QuickOption> {
    val tracks = state.tracks.filter { it.kind == TrackKind.SUBTITLE }
    val offSelected = tracks.none { it.isSelected }
    return buildList {
        add(
            QuickOption("关闭", offSelected) {
                vm.selectTrack(TrackKind.SUBTITLE, null)
                onPicked()
            }
        )
        tracks.forEach { track ->
            add(
                QuickOption(track.label, track.isSelected) {
                    vm.selectTrack(TrackKind.SUBTITLE, track.id)
                    onPicked()
                }
            )
        }
    }
}

/** 速率文案：`1x` / `1.25x`（**小写 x、紧贴数字**，2026-10-19 用户要求）。 */
private fun speedText(speed: Float): String {
    val number = if (speed % 1f == 0f) "${speed.toInt()}" else "$speed"
    return "${number}x"
}

/** 浮层每行固定高度：面板高度取整数行，最后一行不会被裁掉半截。 */
private val QuickRowHeight = 36.dp

/** 标题块高度（labelSmall 一行 + 上下内边距），用于算可见行数。 */
private val QuickHeaderHeight = 28.dp

/**
 * 黑色半透明快捷浮层：标题 + **单列列表**（每行一项，选中项前面对号 + 文字高亮）。
 *
 * 高度策略（2026-10-19 用户反馈：横屏时最后一项被切一半）：
 * 行高固定 [QuickRowHeight]，可见行数 = ⌊(maxHeight − 标题块) / 行高⌋，
 * 列表容器高度**正好等于整数行**——所以永远是完整的若干行，滚动时也不会出现残行；
 * 面板底部再留 6dp，选项不贴死圆角边缘。
 */
@Composable
private fun QuickListPanel(
    title: String,
    maxHeight: Dp,
    options: List<QuickOption>,
) {
    val maxRows = ((maxHeight - QuickHeaderHeight) / QuickRowHeight).toInt().coerceAtLeast(3)
    val visibleRows = options.size.coerceAtMost(maxRows)
    Column(
        Modifier
            // 宽度**由内容决定**：固定宽度时短选项（0.5x / 适应）右侧会留一大片空白
            .widthIn(min = 88.dp)
            .clip(RoundedCornerShape(14.dp))
            // 黑灰而非纯黑：视频下缘本身就是黑的，纯黑浮层看不出边界（2026-10-19 反馈）
            .background(Color(0xFF2B2B2F))
            .padding(bottom = 6.dp),
    ) {
        Text(
            title,
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
        )
        Column(
            Modifier
                .height(QuickRowHeight * visibleRows)
                .verticalScroll(rememberScrollState()),
        ) {
            options.forEach { option -> QuickListRow(option) }
        }
    }
}

@Composable
private fun QuickListRow(option: QuickOption) {
    Row(
        Modifier
            .height(QuickRowHeight)
            .clickable(onClick = option.onClick)
            .padding(start = 12.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 对号占位：未选中也留同样宽度，文字左边缘保持对齐
        Box(Modifier.width(18.dp)) {
            if (option.selected) {
                CheckIcon(tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
        Text(
            option.label,
            color = if (option.selected) Color.White else Color.White.copy(alpha = 0.68f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (option.selected) FontWeight.SemiBold else FontWeight.Normal,
        )
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
    onRetry: () -> Unit,
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
        error.message?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                it,
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row {
            Text(
                "重试",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "返回",
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onBack)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * 连播浮层（`03` §5.7）：下一集标题 + 倒计时 + 上一集 / 下一集 / 取消。
 * 关闭「自动连播」时 [countdown] 为 0，只显示按钮。
 */
@Composable
private fun NextUpCard(
    nextEpisode: app.myplayer.core.model.MediaItemDto?,
    countdown: Int,
    hasPrevious: Boolean,
    onPlayNext: () -> Unit,
    onPlayPrevious: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .width(260.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.84f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            if (countdown > 0) "${countdown}s 后播放下一集" else "下一集",
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            nextEpisode?.title ?: "下一集",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (hasPrevious) {
                Text(
                    "上一集",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable(onClick = onPlayPrevious)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                "播放下一集",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(onClick = onPlayNext)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "取消",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
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

/** 从 Compose 的 Context 往上找 Activity（播放页内部与 PlayerRoute 共用）。 */
internal fun Context.findActivity(): Activity? {
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

/** 横竖屏切换的过渡时长（毫秒）。 */
private const val ROTATE_ANIM_MS = 320
