package app.myplayer.nav

import androidx.compose.animation.EnterTransition
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 转场方向分流的回归护栏（`NavTransitions.kt`）。
 *
 * 锁的是唯一一条「不按水平方向走」的规则：**横向画面的播放页**纵向退场，
 * 此时下层页面必须静止，否则会出现「视频向下 + 列表横移」的双重运动。
 * 这是纯视觉问题，编译器与截图都抓不到，只能靠断言钉住。
 *
 * 另一条更贵的教训也记在这里：判断横竖屏**不能用设备方向**。
 * 播放页的横屏是把内容整体转 90°，Activity 始终竖屏 —— 用
 * `resources.configuration.orientation` / `LocalConfiguration` 判断永远拿到 false，
 * 于是横屏退场永远走不到纵向分支（BUG-2026-003，两次真机复现）。
 * 所以纵向分支的入参必须来自**播放页上报的状态**，而不是任何系统属性。
 */
class NavTransitionsTest {

    @Test
    fun `横向画面的播放页退场时，下层页面保持静止`() {
        val enter = NavTransitions.popEnterFor(fromRoute = "play/12345", playerLandscape = true)
        assertThat(enter).isSameInstanceAs(EnterTransition.None)
    }

    @Test
    fun `非横向画面的播放页仍然走水平转场`() {
        // 要和 App 里其他所有页面完全一致：从左侧 1/4 处归位
        val enter = NavTransitions.popEnterFor(fromRoute = "play/12345", playerLandscape = false)
        assertThat(enter).isNotSameInstanceAs(EnterTransition.None)
    }

    @Test
    fun `播放页以外的页面即使标记为横向也照常水平转场`() {
        assertThat(NavTransitions.popEnterFor("detail/12345", playerLandscape = true))
            .isNotSameInstanceAs(EnterTransition.None)
        assertThat(NavTransitions.popEnterFor("main", playerLandscape = true))
            .isNotSameInstanceAs(EnterTransition.None)
    }

    @Test
    fun `路由为空时（起始页或参数异常）不判成播放页`() {
        assertThat(isPlayerRoute(null)).isFalse()
        assertThat(isPlayerRoute("")).isFalse()
    }

    @Test
    fun `前缀相似的路由不会被误判`() {
        // "playlist" 以 "play" 开头，但不是播放页 —— 简单 startsWith 会在这里出错
        assertThat(isPlayerRoute("playlist/1")).isFalse()
        // 只有精确的 play 或 play/{arg} 才算
        assertThat(isPlayerRoute("play")).isTrue()
        assertThat(isPlayerRoute("play/12345")).isTrue()
    }
}
