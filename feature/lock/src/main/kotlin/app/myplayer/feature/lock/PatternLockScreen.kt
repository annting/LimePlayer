package app.myplayer.feature.lock

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.myplayer.core.designsystem.AppShape
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.designsystem.Spacing
import kotlin.math.roundToInt

/** UI 测试定位用：Canvas 本身不含语义，需要 testTag 才能被测试框架找到。 */
const val PatternLockScreenTestTag = "pattern-lock"

/**
 * 九宫格锁屏（`docs/03` §7 / 设计板 ②⑬）。
 * 视觉：暖 / 冷双径向光晕背景 + 品牌徽标 + 标题与提示行 + 自适应九宫格。
 * 动效：连线跟手；错误红色抖动 320ms 后自动复位；冷却态整体降透明度并禁用交互。
 */
@Composable
fun PatternLockScreen(
    state: LockUiState,
    onPointTouched: (Int) -> Unit,
    onDrawEnd: () -> Unit,
    modifier: Modifier = Modifier,
    onErrorAnimationDone: () -> Unit = {},
) {
    val density = LocalDensity.current
    val hitRadiusPx = with(density) { 24.dp.toPx() }
    val dotRadiusPx = with(density) { 8.dp.toPx() }

    val scheme = MaterialTheme.colorScheme
    val app = LocalAppColors.current
    val cooldown = state.status == LockStatus.COOLDOWN

    // 错误抖动（320ms，结束后通知 VM 复位状态）
    val shake = remember { Animatable(0f) }
    LaunchedEffect(state.status) {
        if (state.status == LockStatus.ERROR) {
            shake.snapTo(0f)
            shake.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = 320
                    0f at 0
                    -9f at 70
                    9f at 150
                    -6f at 230
                    0f at 320
                },
            )
            onErrorAnimationDone()
        }
    }
    val canvasAlpha by animateFloatAsState(
        targetValue = if (cooldown) 0.4f else 1f,
        label = "canvas-alpha",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            // 不透明底色：本组件现在作为**覆盖层**叠在导航内容之上（任务 2-5 覆盖式锁屏），
            // 只画光晕会导致下方内容透出（真机实测：底部导航与视频列表可见）。
            .background(MaterialTheme.colorScheme.background)
            // 拦截全部触摸：覆盖层没有可点击节点时事件会穿透到下层（底部导航仍可点）。
            // 在 Final 阶段消费 —— 子级（九宫格绘制）的 Main 阶段处理已经完成，不影响解锁手势。
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Final)
                            .changes.forEach { it.consume() }
                    }
                }
            }
            .drawBehind {
                // 顶部暖光 + 底部冷光（设计板 .veil，双主题共用固定色）
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(app.lockGlowWarm, Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height * 0.18f),
                        radius = size.width * 1.15f,
                    )
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(app.lockGlowCool, Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height * 1.02f),
                        radius = size.width,
                    )
                )
            },
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LockMark()
            Spacer(Modifier.height(Spacing.xl.dp))
            Text("绘制解锁图案", style = MaterialTheme.typography.titleLarge, color = scheme.onSurface)
            Spacer(Modifier.height(Spacing.sm.dp))
            val hint = state.message ?: "连接至少 4 个点"
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = when (state.status) {
                    LockStatus.ERROR, LockStatus.COOLDOWN -> scheme.error
                    else -> app.text3
                },
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.xl.dp))

            // ---- 九宫格画布：宽度自适应（0.74 屏宽，上限 320dp），命中与绘制同源 ----
            var sidePx by remember { mutableStateOf(0f) }
            val hit = remember(sidePx, hitRadiusPx) { PatternHitTest(sidePx, hitRadiusPx) }
            val latestHit by rememberUpdatedState(hit)
            val latestOnPointTouched by rememberUpdatedState(onPointTouched)
            val latestOnDrawEnd by rememberUpdatedState(onDrawEnd)

            val errorColor = scheme.error
            val activeColor = scheme.primary
            val idleColor = scheme.outline
            val glowColor = app.brandGlow

            Canvas(
                modifier = Modifier
                    .fillMaxWidth(0.74f)
                    .widthIn(max = 320.dp)
                    .aspectRatio(1f)
                    .alpha(canvasAlpha)
                    .offset { IntOffset(shake.value.roundToInt(), 0) }
                    .testTag(PatternLockScreenTestTag)
                    // 尺寸从布局回调写入：pointerInput 块启动时布局可能尚未完成（size 为 0），
                    // 在那里读 size 会让命中器以 0 边长构造，所有触摸全部 miss（历史 bug）。
                    .onSizeChanged { sidePx = it.width.toFloat() }
                    // key 用 Unit 而非 state.status：手势进行中状态必然变化（IDLE→DRAWING），
                    // 以状态为 key 会取消并重启手势协程，正在进行的拖动被掐断、抬手事件丢失。
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            latestHit.indexAt(down.position.x, down.position.y)?.let(latestOnPointTouched)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUpIgnoreConsumed()) { latestOnDrawEnd(); break }
                                if (change.positionChanged()) {
                                    latestHit.indexAt(change.position.x, change.position.y)?.let(latestOnPointTouched)
                                }
                            }
                        }
                    }
            ) {
                val error = state.status == LockStatus.ERROR
                val lineColor = if (error) errorColor else activeColor

                for (i in 0 until 9) {
                    val (cx, cy) = hit.pointCenter(i)
                    val selected = i in state.selected
                    if (selected) {
                        // 光晕保护环 + 放大实心点（设计板：选中 scale 1.35 + brand-glow 环）
                        drawCircle(glowColor, radius = dotRadiusPx * 1.9f, center = Offset(cx, cy))
                        drawCircle(lineColor, radius = dotRadiusPx * 1.35f, center = Offset(cx, cy))
                    } else {
                        drawCircle(idleColor, radius = dotRadiusPx, center = Offset(cx, cy))
                    }
                }
                state.selected.zipWithNext { a, b ->
                    val (ax, ay) = hit.pointCenter(a)
                    val (bx, by) = hit.pointCenter(b)
                    drawLine(lineColor, Offset(ax, ay), Offset(bx, by), strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
                }
            }

            Spacer(Modifier.height(Spacing.xl.dp))
            Text(
                "轻点或按住拖动均可",
                style = MaterialTheme.typography.labelSmall,
                color = app.text3,
            )
        }
    }
}

/** 品牌徽标（设计板 `.lock-mark`）：58dp 渐变圆角方块 + 播放三角。 */
@Composable
private fun LockMark() {
    val brand = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onPrimary
    Box(
        modifier = Modifier
            .size(58.dp)
            .clip(AppShape.card)
            .drawBehind {
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(brand, lerp(brand, Color.Black, 0.5f)),
                    )
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(26.dp)) {
            val path = Path().apply {
                moveTo(size.width * 0.28f, size.height * 0.16f)
                lineTo(size.width * 0.28f, size.height * 0.84f)
                lineTo(size.width * 0.84f, size.height * 0.5f)
                close()
            }
            drawPath(path, color = ink)
        }
    }
}
