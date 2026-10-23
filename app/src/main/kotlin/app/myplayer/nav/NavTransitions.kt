package app.myplayer.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.unit.IntOffset

/**
 * 全局页面转场 —— **唯一真相**。
 *
 * 所有 `NavHost`（根级 [AppNavHost] 与设置页那个嵌套 `subNav`）都必须从这里取参数，
 * 不要再在各个 `composable {}` 里各写一套：之前就是因为根级是淡入淡出、播放页是下滑、
 * 设置子导航压根没配（走 navigation 默认 700ms 淡化），才出现「同一台手机上三种退场」。
 *
 * ## 为什么是「新页整屏滑入 + 旧页只让 1/4」
 * 两张纸同时整屏平移会显得画面在「飞」；旧页只退 1/4 屏，视觉上是被新页「压下去」，
 * 这是 iOS 导航栈的标准做法，也让 300ms 的时长看起来比实际更快。
 *
 * ## 几何自检（为什么不会露出窗口背景）
 * push 到进度 p 时：新页左边缘 = (1-p)·W，旧页右边缘 = W - p·W/4。
 * 因为 (1-p)·W ≤ W - p·W/4 对 p ≥ 0 恒成立，两页始终有重叠，中间不会漏出背景。
 * pop 时是同一条式子的镜像（旧页在上层整屏右移，下层的右边缘始终在它左边）。
 *
 * ## 时长
 * 统一 **300ms + [FastOutSlowInEasing]**。navigation-compose 的默认值是 700ms 淡化，
 * 返回时目标页要等最长的那条转场跑完才真正 dispose（2026-10-20 实测 pop 后 731ms），
 * 300ms 既够看清方向，又不会让播放页/详情页的销毁拖到可感知。
 *
 * ## 预测性返回
 * `AndroidManifest.xml` 里 `<activity>` 显式置了 `android:enableOnBackInvokedCallback="false"`。
 * 打开预测性返回时（targetSdk ≥ 36 在 Android 16 上默认开启），navigation-compose 会把手势
 * **按手指位移 seek 我们的转场**，并叠加系统自己的「退出 App 时整窗缩小」动画 ——
 * 那正是「跟手变透明 / 跟手缩小」的来源，也让手势与返回按钮永远不可能一致。
 * 关掉之后手势走的是同一条 `popBackStack()`，跑同一段 300ms 动画。
 *
 * ## 播放页为什么不能用「设备方向」判断横竖屏（踩过两次）
 * 播放页的横屏是 **把内容整体转 90°**（`PlayerScreen` 的 `rotationZ`），系统方向自始至终是竖屏 ——
 * 这是 2026-10-20 定的 VidHub 式设计，好处是进出播放页没有旋转动作、也不黑闪。
 * 所以 `resources.configuration.orientation`（以及 `LocalConfiguration`）在这里**恒为竖屏**：
 * 拿它判断横竖屏，横屏退场永远走不到纵向分支。
 * 正解是让播放页把「当前是否横向」**上报**给导航层（见 [AppNavHost] 的 `playerLandscape`），
 * 转场 lambda 通过一个稳定的 `MutableState` 读实时值。
 */
internal object NavTransitions {

    /** 所有转场统一时长（毫秒）。 */
    const val DURATION_MS = 300

    /**
     * 播放页起滑前的等待（毫秒）。
     *
     * 播放页 pop 出去时，目标页（详情 / 首页）是**重新组合**的，首帧要重新拉数据 + 海报；
     * 起滑瞬间那块区域还是未绘制状态，会露出一片纯背景色（2026-10-20 真机踩过）。
     * 60ms ≈ 4~7 帧，足够目标页画出首帧，肉眼察觉不到延迟。
     */
    private const val PLAYER_POP_DELAY_MS = 60

    /** 下层页面的视差幅度：只让出 1/4 屏。 */
    private const val PARALLAX_DIVISOR = 4

    private val spec = tween<IntOffset>(DURATION_MS, easing = FastOutSlowInEasing)

    private val playerExitSpec = tween<IntOffset>(
        durationMillis = DURATION_MS,
        delayMillis = PLAYER_POP_DELAY_MS,
        easing = FastOutSlowInEasing,
    )

    /** 进入新页面：从**右侧整屏**滑入。 */
    fun enter(): EnterTransition = slideInHorizontally(spec) { it }

    /** 被压下去的旧页面：**向左让出 1/4 屏**（视差，不是整屏飞出）。 */
    fun exit(): ExitTransition = slideOutHorizontally(spec) { -it / PARALLAX_DIVISOR }

    /**
     * pop 时下层的上一页：从**左侧 1/4 处**归位。
     */
    fun popEnter(): EnterTransition = slideInHorizontally(spec) { -it / PARALLAX_DIVISOR }

    /** pop 时当前页面：**向右整屏**滑出。 */
    fun popExit(): ExitTransition = slideOutHorizontally(spec) { it }

    /**
     * NavHost 级的 popEnter：播放页**纵向**退场时，下层页面保持原位不动。
     *
     * 横屏（或宽画幅）播放页是向下滑走的，下层再横移一次会显得很乱；
     * 「下层往里缩一下再从左边归位」只在横向退场（其余所有页面）时才有意义。
     *
     * @param fromRoute 这次 pop 里**正在离开**的那一页的路由（`initialState.destination.route`）
     * @param playerLandscape 播放页当前是否横向（由播放页上报，见类注释）
     */
    fun popEnterFor(fromRoute: String?, playerLandscape: Boolean): EnterTransition =
        if (playerLandscape && isPlayerRoute(fromRoute)) {
            EnterTransition.None
        } else {
            popEnter()
        }

    /**
     * 播放页退场：横向画面**向下**滑出、非横向**向右**滑出。
     *
     * @param fakeRotation 横向是「假横屏」（窗口竖着、内容转 90°）实现的 ——
     *   此时「向下」要落在窗口的 X 轴上，且方向取负（见实现注释）。
     *
     * 向下滑出对齐 VidHub / YouTube 的「移出滑动」（2026-10-20 定稿）：
     * 全屏视频向下移出时画面全程不透明，不会像淡出那样先整体变暗再出现列表。
     */
    fun playerPopExit(landscape: Boolean, fakeRotation: Boolean): ExitTransition = when {
        !landscape -> slideOutHorizontally(playerExitSpec) { it }
        // 假横屏：窗口是竖的、内容顺时针转了 90°，所以「画面向下」= **窗口向左**。
        // 用 slideOutVertically 的话，用户看到的是「向右滑」（2026-10-24 方案换算）。
        fakeRotation -> slideOutHorizontally(playerExitSpec) { -it }
        else -> slideOutVertically(playerExitSpec) { it }
    }
}

/**
 * 这个路由是不是播放页。
 *
 * 用 `route == "play" || route.startsWith("play/")` 而不是简单 `startsWith("play")`：
 * 后者会把将来可能出现的 `playlist` 之类路由误判成播放页。
 */
internal fun isPlayerRoute(route: String?): Boolean {
    if (route == null) return false
    return route == Routes.PLAYER || route.startsWith("${Routes.PLAYER}/")
}
