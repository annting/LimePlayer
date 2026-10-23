package app.myplayer.feature.library.home

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.overscroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 横向列表的**边界回弹**（OverScroll）。
 *
 * ## 为什么不用 `rememberOverscrollEffect()`
 * 平台默认的 overscroll 是 Android 12+ 的「拉伸光晕」（`StretchOverscrollEffect`），
 * 它是把内容**拉伸变形**再弹回，不是位移动画；在 240dp 的小卡片行上看起来像「画面抖了一下」。
 * 这里要的是 iOS 那种「整行跟着指尖滑出去、松手用 spring 拉回」，所以自己接 [NestedScrollConnection]：
 *
 * 1. `onPostScroll` —— 内层 [androidx.compose.foundation.lazy.LazyRow] 已经滚不动了，
 *    剩余的 `available.x` 才落到这里；按阻尼系数折算成位移吃掉它（同时吃掉事件，
 *    避免继续往父级 LazyVerticalGrid 传递造成竖向跟着动）；
 * 2. `onPreFling` —— **松手瞬间**。已经处于过卷状态（拖出边界了）时把速度全部吃掉，
 *    内层 LazyRow 不再启动惯性 fling，回弹从第一帧开始跑（见下面「为什么不返回 Zero」）；
 * 3. **惯性撞边界** —— 松手时位移还是 0（列表还在内容中间）、`onPreFling` 只能放行，
 *    LazyRow 会自己 fling 撞到边界；撞上后 fling **不会停**，会在 [onFlingOverscroll]
 *    里立刻接手回弹，不等它衰减完（这是 BUG-2026-001 的真正根因，见该方法注释）；
 * 4. `onPostFling` —— 兜底：fling 结束时若还有残留位移（比如中途被中断），在这里归零。
 *
 * ## 为什么 `onPreFling` 不能返回 `Velocity.Zero`
 * 返回 Zero 等于「本 connection 一点速度都没消费」，内层 LazyRow 会照常跑惯性 fling。
 * 而列表已在边界，fling 每帧的剩余量都会走到 `onPostScroll` → [dragBy] →
 * `settleJob.cancel()`：回弹 job 活不过一帧，真正的回弹只能等 fling 衰减完
 * 由 `onPostFling` 启动 —— 真机表现就是「甩到底松手后停顿 300~500ms 才弹」
 * （BUG-2026-001，见 `docs/07-问题排查.md`）。
 *
 * 位移施加在 `graphicsLayer.translationX` 上（跳过重组与重新测量，只重绘图层）。
 */
@Stable
class BounceOverscrollState internal constructor(
    private val maxStretchPx: Float,
    private val scope: CoroutineScope,
) {
    /** 当前过卷位移（px，可正可负；0 = 正常位置）。 */
    var offset by mutableFloatStateOf(0f)
        private set

    private var settleJob: Job? = null

    /** 上一次 [dragBy] 的时间戳（ms）：用来估算惯性撞上边界那一瞬的速度。 */
    private var lastDragT = 0L

    /**
     * 惯性撞边界的状态机：
     * - `0`  还没有收到惯性剩余量；
     * - `>0` 已经累积的撞击帧数；
     * - `-1` 本次 fling 已经交给回弹动画，后续剩余量只消费不再叠加。
     */
    private var flingHit = 0

    /** 一次拖拽增量（px）。多次连续调用会累加；正在回弹时立即接管。 */
    internal fun dragBy(deltaPx: Float) {
        if (deltaPx == 0f) return
        lastDragT = System.currentTimeMillis()
        settleJob?.cancel()
        offset = bounceOffsetAfterDrag(offset, deltaPx, maxStretchPx)
    }

    /** 用相邻两次剩余量的时间差反推当前速度（px/s）；间隔异常时返回 0，不做估算。 */
    private fun estimateVelocity(deltaPx: Float): Float {
        val dt = System.currentTimeMillis() - lastDragT
        return if (lastDragT != 0L && dt in 1L..40L) deltaPx / (dt / 1000f) else 0f
    }

    /**
     * 惯性（fling）撞到边界。
     *
     * ## 为什么需要这条路径（BUG-2026-001 的真正根因）
     * 松手时如果位移还是 0（列表还在内容中间，用户是「甩」过去的），`onPreFling`
     * 只能放行速度让 LazyRow 自己 fling。fling 撞到边界后**不会停** ——
     * Compose 的 `FlingBehavior` 只在 `canScrollForward/Backward` 变化前消耗速度，
     * 撞上后每一帧都把剩余量丢进 `onPostScroll`，而剩余量要等 spline 衰减完才归零。
     *
     * 于是位移被顶到 `maxStretch` 后就**完全静止**，一直等到 `onPostFling` 才启动回弹。
     * 真机实测这段静止长达 **1.2~1.7s**（一加 PKR110，甩击速度 ≈14800px/s）：
     * ```
     * 34.831  preFling off=0.0 v=-14846      ← 放行，LazyRow 开始 fling
     * 34.910  drag#1 delta=-91  off=-50      ← 撞上边界，开始累积位移
     * 35.000  drag#15           off=-249     ← 顶到上限
     * 35.0~36.4 每帧 delta≈-70~-110 反复压    ← 位移不变 = 用户看到的「卡片卡住」
     * 36.451  postFling → settle              ← 1.6s 之后才弹
     * ```
     * 用户描述的「第一次必卡、同一方向只卡一次」也由此解释：第一次是甩到边界的，
     * 之后列表已经在边界上，松手时位移非 0，走的是 `onPreFling` 立即回弹那条路。
     *
     * ## 处理方式
     * 撞上后先自然累积几帧位移（保留「冲出去」的量感，也顺便把速度估出来），
     * 随即接手回弹；之后这段 fling 剩下的剩余量只消费、不再叠加，
     * 否则每帧的 `dragBy` 都会把刚起跑的回弹 job 取消掉。
     */
    private fun onFlingOverscroll(deltaPx: Float) {
        if (flingHit < 0) return
        if (settleJob?.isActive == true) {
            flingHit = -1
            return
        }
        val velocityPx = estimateVelocity(deltaPx)
        dragBy(deltaPx)
        flingHit++
        val absorbed = flingHit >= FLING_ABSORB_FRAMES ||
            abs(offset) >= maxStretchPx * FLING_ABSORB_RATIO
        if (absorbed) {
            flingHit = -1
            settle(velocityPx)
        }
    }

    /**
     * 松手回弹（从当前 [offset] 用 spring 归零）。
     *
     * @param velocityPx 松手时的横向速度（px/s）：**只采用「继续往外冲」的分量**，
     *   所以甩得再猛也只是往外多走一点再弹回；往回的速度直接丢掉 ——
     *   否则欠阻尼 spring 会把反向速度放大成一次「越过原位弹到另一边」的过冲。
     *   初速另有限幅 [MAX_SETTLE_VELOCITY]：3000px/s 直接进 spring 会冲出去 150px 再震几次，
     *   动画总长拉到 1s 以上。
     */
    internal fun settle(velocityPx: Float = 0f) {
        settleJob?.cancel()
        // 位移小到看不见（真机上出现过 0.03px 的残留）就别起动画了，直接归零
        if (abs(offset) < MIN_SETTLE_OFFSET) {
            offset = 0f
            return
        }
        val outward = if (velocityPx * offset > 0f) velocityPx else 0f
        settleJob = scope.launch {
            animate(
                initialValue = offset,
                targetValue = 0f,
                initialVelocity = outward.coerceIn(-MAX_SETTLE_VELOCITY, MAX_SETTLE_VELOCITY),
                animationSpec = spring(
                    dampingRatio = SETTLE_DAMPING,
                    stiffness = SETTLE_STIFFNESS,
                ),
            ) { value, _ ->
                offset = value
            }
            offset = 0f
        }
    }

    internal val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            if (available.x == 0f) return Offset.Zero
            if (source == NestedScrollSource.SideEffect) {
                // 惯性撞边界：不能按手指拖拽那样无脑叠加，见 [onFlingOverscroll]
                onFlingOverscroll(available.x)
            } else {
                // 手指还在拖：新手势开始，重置惯性撞击状态
                flingHit = 0
                dragBy(available.x)
            }
            // 吃掉横向剩余量：否则会继续冒泡给父级（网格），表现为「横滑时整页跟着抖」
            return Offset(available.x, 0f)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            flingHit = 0
            if (abs(offset) < MIN_SETTLE_OFFSET) {
                // 还在内容里、没有过卷：必须放行速度让它正常 fling，
                // 撞到边界后由 [onFlingOverscroll] 接手
                return Velocity.Zero
            }
            return if (available.x * offset > 0f) {
                // 还在往边界外冲（松手时手指仍朝外）：吃掉全部速度，内层不启动 fling。
                // 这是「松手即弹」的关键 —— 见类注释「为什么 onPreFling 不能返回 Velocity.Zero」
                settle(available.x)
                available
            } else {
                // 反向甩回（越过边界后又往回拨）：让列表正常滚回去，位移用无初速 spring 归零，
                // 不要抢这份速度，否则「回拨一格」会变成「只弹回来不滚」
                settle(0f)
                Velocity.Zero
            }
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            // 兜底：fling 结束时若还有残留位移（回弹被打断 / 上面两条路都没覆盖到），在这里归零。
            // 若回弹 job 还活着就别重启，否则动画会从中间被打断。
            if (offset != 0f && settleJob?.isActive != true) settle(available.x)
            flingHit = 0
            return Velocity.Zero
        }
    }

    private companion object {
        /** 阻尼系数：指尖走 100px，内容只走 55px（越往外越小，见 [bounceOffsetAfterDrag]）。 */
        const val FRICTION = 0.55f

        /**
         * 惯性撞击边界后，先自然累积几帧位移再接手回弹。
         *
         * 3 帧 ≈ 25ms：既让「冲出去」有量感，又不会顶到上限后静止。
         * 两者取先到 —— 甩得特别猛时位移会先到 60% 上限，不必再等帧数。
         */
        const val FLING_ABSORB_FRAMES = 3

        /** 累积位移达到上限的这个比例就立刻接手，不再等帧数。 */
        const val FLING_ABSORB_RATIO = 0.6f

        /** 回弹阻尼比：0.7 会过冲一点点再停住，既有弹性又不至于晃。 */
        const val SETTLE_DAMPING = 0.7f

        /**
         * 回弹刚度（等价 `Spring.StiffnessMedium` 的一半多）。
         *
         * 用 `StiffnessMedium`(1500) 时 72dp 位移只要 12 帧 ≈ 92ms 就归零，
         * 观感是「啪」一下跳回去而不是弹回去；降到 900 后可见行程 ≈ 240ms，
         * 帧级位移曲线实测仍是单调加速起步（首帧就有 ~9% 行程，不会「先愣一下」）。
         */
        const val SETTLE_STIFFNESS = 900f

        /** 回弹初速上限（px/s）：甩动动辄 3000+，原样喂给 spring 会大过冲 + 长震荡。 */
        const val MAX_SETTLE_VELOCITY = 900f

        /** 小于这个位移（px）就当成已归位，不再起回弹动画。 */
        const val MIN_SETTLE_OFFSET = 1f
    }
}

/**
 * 纯函数（可单测）：把一次剩余的滚动量折算成新的过卷位移。
 *
 * 阻尼随位移线性衰减（`1 - |offset| / max`），所以越往外越「拉不动」，到 max 处彻底锁死。
 */
internal fun bounceOffsetAfterDrag(current: Float, deltaPx: Float, maxStretchPx: Float): Float {
    if (maxStretchPx <= 0f) return 0f
    val remaining = (1f - abs(current) / maxStretchPx).coerceIn(0f, 1f)
    val next = current + deltaPx * 0.55f * remaining
    return next.coerceIn(-maxStretchPx, maxStretchPx)
}

/**
 * 创建并记住一个回弹状态。
 *
 * @param maxStretch 最大拉伸距离（默认 72dp：约卡片行高的 1/2，再大就露出太多空白）
 */
@Composable
fun rememberBounceOverscrollState(maxStretch: Dp = 72.dp): BounceOverscrollState {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    return remember(maxStretch, scope) {
        BounceOverscrollState(
            maxStretchPx = with(density) { maxStretch.toPx() },
            scope = scope,
        )
    }
}

/**
 * 把回弹位移与 nested scroll 接到横向列表上（挂在 LazyRow 自身）。
 *
 * 三层的顺序不是随便排的：
 * 1. [overscroll] 传 `null` —— **必须先关掉平台的 overscroll**（Android 12+ 是拉伸变形，
 *    更早是边缘光晕）。它和 [connection] 抢同一份「边界剩余量」，而且**平台先手**：
 *    `ScrollingLogic` 的顺序是 `scrollBy` → `overscrollEffect.applyToScroll(available)` →
 *    `dispatchPostScroll(剩下的)`。平台会把 remaining 全部吃掉做拉伸，于是这里
 *    `onPostScroll` 收到的 `available.x` 恒为 0、`offset` 永远涨不起来，
 *    松手时 `onPreFling` 看到 `offset == 0` 直接放行 → LazyRow 交给平台自己那套
 *    ~500ms 归位动画。**手指慢拖到边界**必现，快速甩反而偶尔能漏一点量给我们，
 *    表现就是「第一次卡、换方向又卡」那种飘忽的规律（BUG-2026-001 真凶）。
 * 2. [graphicsLayer] 只写 `translationX`：位移跳过重组与重新测量，只重绘图层。
 * 3. [nestedScroll] 接住剩余量 → 折成位移。
 */
fun Modifier.bounceOverscroll(state: BounceOverscrollState): Modifier = this
    .overscroll(overscrollEffect = null)
    .graphicsLayer { translationX = state.offset }
    .nestedScroll(state.connection)
