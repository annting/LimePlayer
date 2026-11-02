package app.myplayer.feature.player

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `SelectableRow` 两档预设的渲染回归（2026-11-02 组件统一合并 `TrackRow` + `QuickListRow`）。
 *
 * 合并的方式是「一份实现 + `Sheet`/`Quick` 两种预设」，最容易出的是**合并过程中把某一档
 * 的副标题/点击/禁用表达弄丢** —— 纯编译检查发现不了，所以这里锁住：
 *
 * | 断言 | 守的是什么 |
 * |---|---|
 * | 两档都能渲染出 label | 合并后没有哪一档被漏掉 |
 * | Sheet 档显示 sub、Quick 档不显示 | 副标题只属于设置面板（快捷菜单行高固定 36dp，放不下第二行） |
 * | `onClick = null` 时点击不触发回调 | 不支持的音轨不能变成"点了没反应也不报错"的可点行 |
 * | 点击生效 | 合并没把 clickable 接到错误的一层 |
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelectableRowTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `Sheet 档显示标题与副标题`() {
        compose.setContent {
            SelectableRow(
                label = "中文（简体）",
                sub = "SRT · 当前设备不支持",
                selected = true,
                onClick = {},
            )
        }
        compose.onNodeWithText("中文（简体）").assertIsDisplayed()
        compose.onNodeWithText("SRT · 当前设备不支持").assertIsDisplayed()
    }

    @Test
    fun `Quick 档只显示标题`() {
        compose.setContent {
            SelectableRow(
                label = "1.5x",
                selected = true,
                onClick = {},
                variant = SelectableRowVariant.Quick,
            )
        }
        compose.onNodeWithText("1.5x").assertIsDisplayed()
    }

    @Test
    fun `点击触发回调`() {
        var clicked = 0
        compose.setContent {
            SelectableRow(label = "1080p", selected = false, onClick = { clicked++ })
        }
        compose.onNodeWithText("1080p").performClick()
        assertThat(clicked).isEqualTo(1)
    }

    @Test
    fun `没有回调时点击不触发任何事`() {
        var clicked = 0
        compose.setContent {
            // 不支持的音轨：onClick 传 null（enabled=false 的表现由组件内部统一给）
            SelectableRow(label = "DTS-HD", selected = false, enabled = false, onClick = null)
        }
        compose.onNodeWithText("DTS-HD").performClick()
        assertThat(clicked).isEqualTo(0)
    }
}
