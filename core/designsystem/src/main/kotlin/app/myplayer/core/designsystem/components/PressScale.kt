package app.myplayer.core.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale

/**
 * Press 缩放动效（`docs/03-UI设计规范.md` §4）。
 *
 * `1.0 → 0.95 → 1.0`，`spring(dampingRatio = 0.6, stiffness = Spring.StiffnessMedium)`，
 * 松手时有一次轻微回弹，全程约 300ms（< 400ms 上限）。
 *
 * 用法：把**同一个** `interactionSource` 交给 `clickable` 和这个 Modifier，否则收不到按下状态。
 *
 * ```kotlin
 * val interaction = remember { MutableInteractionSource() }
 * Box(
 *     Modifier
 *         .pressScale(interaction)
 *         .clickable(interactionSource = interaction, indication = null, onClick = onClick)
 * )
 * ```
 *
 * ⚠️ 挂在**需要缩的元素本体**上（按钮、Chip、图标），不要挂在带阴影的父容器上。
 */
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.95f,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val current by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "press-scale",
    )
    scale(current)
}

/**
 * 一次性「弹跳」（§4 悬浮导航栏切换）：[active] 变为 true 时跑一遍 `1 → [peak] → 1`。
 *
 * 与 [pressScale] 的区别：按压是**持续**状态（按下期间保持 0.95），
 * 弹跳是**一次性**关键帧动画，用 [Animatable] + [keyframes]，耗时 260ms。
 */
@Composable
fun bounceScale(active: Boolean, peak: Float = 1.16f): Float {
    val anim = remember { Animatable(1f) }
    LaunchedEffect(active) {
        if (active) {
            anim.snapTo(1f)
            anim.animateTo(
                targetValue = 1f,
                animationSpec = keyframes {
                    durationMillis = 260
                    1f at 0
                    peak at 110
                    1f at 260
                },
            )
        }
    }
    return anim.value
}
