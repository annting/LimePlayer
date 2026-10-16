package app.myplayer.feature.lock

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 手势链路的回归测试（Robolectric 上跑 Compose UI 测试，无需真机）。
 *
 * 历史 bug：Canvas 的 pointerInput 以 `state.status` 为 key —— 第一下触摸会把状态
 * 从 IDLE 改成 DRAWING，Compose 随即取消并重启手势协程，正在进行的拖动被掐断：
 * 只会点亮第一个点，后续移动与抬起全部丢失（真机上表现为「无法滑动」）。
 *
 * 本测试模拟真实链路（每次 onPointTouched 都更新状态并重组），锁住该行为：
 * 按下 → 拖过 0/1/2 三点 → 抬起，四个事件必须全部上报。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PatternLockScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `按下后拖过三点应在抬起时全部上报`() {
        val events = mutableListOf<String>()
        val selected = mutableStateOf(emptyList<Int>())
        val status = mutableStateOf(LockStatus.IDLE)

        compose.setContent {
            PatternLockScreen(
                state = LockUiState(status = status.value, selected = selected.value),
                onPointTouched = { index ->
                    events += "touch:$index"
                    selected.value = selected.value + index
                    status.value = LockStatus.DRAWING // 与真实 ViewModel 一致：触摸立即改状态
                },
                onDrawEnd = { events += "end" },
            )
        }

        val node = compose.onNodeWithTag(PatternLockScreenTestTag)
        val side = node.fetchSemanticsNode().size.width.toFloat()
        // 与 PatternHitTest 相同的几何口径：margin = side/5，点间距 = (side - 2*margin)/2
        fun point(index: Int): Offset {
            val margin = side / 5f
            val spacing = (side - margin * 2f) / 2f
            return Offset(margin + (index % 3) * spacing, margin + (index / 3) * spacing)
        }

        node.performTouchInput {
            down(point(0))
            moveTo(point(1))
            moveTo(point(2))
            up()
        }
        compose.waitForIdle()

        assertThat(events)
            .containsExactly("touch:0", "touch:1", "touch:2", "end")
            .inOrder()
    }

    @Test
    fun `轻点单个点也应上报并在抬起时结束`() {
        val events = mutableListOf<String>()
        val selected = mutableStateOf(emptyList<Int>())

        compose.setContent {
            PatternLockScreen(
                state = LockUiState(selected = selected.value),
                onPointTouched = { index ->
                    events += "touch:$index"
                    selected.value = selected.value + index
                },
                onDrawEnd = { events += "end" },
            )
        }

        val node = compose.onNodeWithTag(PatternLockScreenTestTag)
        val side = node.fetchSemanticsNode().size.width.toFloat()
        val margin = side / 5f

        node.performTouchInput {
            down(Offset(margin, margin))
            up()
        }
        compose.waitForIdle()

        // 组件层只负责上报触摸与结束；「点数不足不验证」由 ViewModel 判定（LockViewModelTest 覆盖）
        assertThat(events).containsExactly("touch:0", "end").inOrder()
    }
}
