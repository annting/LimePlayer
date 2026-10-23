@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.myplayer.feature.library.home

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * 边界回弹状态机的行为测试（BUG-2026-001 的回归护栏）。
 *
 * 这些用例锁的是**松手那一瞬间的契约**，也就是真机上「停顿 300~500ms 才弹」的根因：
 * 已过卷时 `onPreFling` 必须把速度全部吃掉，否则内层 LazyRow 照常 fling，
 * 每帧的剩余量都会经 `onPostScroll → dragBy → settleJob.cancel()` 把回弹 job 杀掉。
 *
 * 另外还锁「惯性撞边界」这条路：松手时没过渡卷 → 速度放行 → fling 撞上边界后
 * 剩余量每帧灌进来，位移必须立刻开始回收，**不能干等 `onPostFling`**
 * （真机实测那段静止长达 1.2~1.7s）。
 */
class BounceOverscrollTest {

    /**
     * 测试用帧钟：把 `withFrameNanos` 换成「推进 16ms 虚拟时间后给一帧」。
     * 没有它 `animate` 会直接抛「MonotonicFrameClock 不可用」，跑不到真正的回弹逻辑。
     */
    private class TestFrameClock : MonotonicFrameClock {
        private var nanos = 0L
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            delay(FRAME_MS)
            nanos += FRAME_MS * 1_000_000L
            return onFrame(nanos)
        }
    }

    @Test
    fun `已过卷时 onPreFling 吃掉全部速度，内层不再 fling`() = runTest {
        val state = BounceOverscrollState(MAX_STRETCH, backgroundScope + TestFrameClock())
        state.dragBy(80f)
        assertThat(state.offset).isGreaterThan(0f)

        val available = Velocity(3000f, 0f)

        // 全部吃掉 = 内层 LazyRow 拿到 0 速度 → 不启动惯性 → 回弹从第一帧开始
        assertThat(state.connection.onPreFling(available)).isEqualTo(available)
    }

    @Test
    fun `没拖出边界时 onPreFling 不消费速度，保留惯性`() = runTest {
        val state = BounceOverscrollState(MAX_STRETCH, backgroundScope + TestFrameClock())

        // offset 仍是 0：这是「从列表中间甩出去」的场景，惯性必须留给 LazyRow 自己跑
        assertThat(state.connection.onPreFling(Velocity(3000f, 0f))).isEqualTo(Velocity.Zero)
    }

    @Test
    fun `反向往回甩时也不抢速度`() = runTest {
        val state = BounceOverscrollState(MAX_STRETCH, backgroundScope + TestFrameClock())
        state.dragBy(80f)          // 过卷位移为正（往右拖出左边界）

        // 松手速度朝反方向（负）：用户是想滚回列表里去，速度不能被回弹抢走
        assertThat(state.connection.onPreFling(Velocity(-2000f, 0f))).isEqualTo(Velocity.Zero)
    }

    @Test
    fun `松手后位移最终归零`() = runTest {
        val state = BounceOverscrollState(MAX_STRETCH, backgroundScope + TestFrameClock())
        state.dragBy(80f)
        assertThat(state.offset).isGreaterThan(0f)

        state.connection.onPreFling(Velocity(3000f, 0f))
        // 注意：backgroundScope 里的动画不会被 `advanceUntilIdle` 推进（实测 frames=0），
        // 必须自己按帧推进虚拟时间
        repeat(SETTLE_FRAMES) { advanceTimeBy(FRAME_MS) }

        assertThat(state.offset).isEqualTo(0f)
    }

    @Test
    fun `惯性撞到边界后立刻接手回弹，不等 fling 衰减完`() = runTest {
        val state = BounceOverscrollState(MAX_STRETCH, backgroundScope + TestFrameClock())

        // 松手时位移还是 0（用户是「甩」过去的，列表还在内容里）→ 惯性必须留给 LazyRow
        assertThat(state.connection.onPreFling(Velocity(14846f, 0f))).isEqualTo(Velocity.Zero)

        // fling 撞上边界后，每帧把剩余量丢进来（source = SideEffect）
        repeat(3) {
            state.connection.onPostScroll(Offset.Zero, Offset(-100f, 0f), NestedScrollSource.SideEffect)
        }
        assertThat(state.offset).isLessThan(0f)
        assertThat(abs(state.offset)).isLessThan(MAX_STRETCH)   // 没被顶到上限后干等

        // 关键回归点：fling 还在继续丢剩余量、且 onPostFling **永远不来**，
        // 位移也必须自己收回去（旧实现会一直停在 maxStretch 上，真机卡 1.2~1.7s）
        repeat(SETTLE_FRAMES) {
            state.connection.onPostScroll(Offset.Zero, Offset(-80f, 0f), NestedScrollSource.SideEffect)
            advanceTimeBy(FRAME_MS)
        }
        assertThat(state.offset).isEqualTo(0f)
    }

    @Test
    fun `拖动阻尼随位移衰减，越往外越拉不动`() {
        val max = 200f
        val first = bounceOffsetAfterDrag(current = 0f, deltaPx = 40f, maxStretchPx = max)
        val nearEdge = bounceOffsetAfterDrag(current = 180f, deltaPx = 40f, maxStretchPx = max)

        assertThat(first).isWithin(0.01f).of(22f)              // 40 × 0.55
        assertThat(nearEdge - 180f).isLessThan(first)           // 快到极限时同样 40px 只能挪一点
        // 夹到最大值，不会无限外推
        assertThat(bounceOffsetAfterDrag(current = 199f, deltaPx = 400f, maxStretchPx = max))
            .isAtMost(max)
    }

    private companion object {
        const val MAX_STRETCH = 200f
        const val FRAME_MS = 16L

        /** 1.2s 虚拟时间：足够欠阻尼 spring 从最大拉伸收回 0（实测约 0.5s）。 */
        const val SETTLE_FRAMES = 75
    }
}
