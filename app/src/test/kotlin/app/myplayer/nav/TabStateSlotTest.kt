package app.myplayer.nav

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
 * 主界面 tab 的 saveable 槽行为（2026-10-29 真机 bug 的回归）。
 *
 * 现场：从「设置 → 关于」连点版本号进隐私模式，解锁后按问题 5 跳回首页；
 * 再点「设置」直接落在**关于**页上，而不是设置列表。
 *
 * 根因不在隐私门，也不在导航：`SettingsDestination` 里的子导航
 * （`rememberNavController` 的 back stack）被 `MainScaffold` 的
 * `SaveableStateProvider(tab.name)` 存着，切 tab 只是把它移出组合，
 * 切回来就当什么都没发生地恢复 —— 于是「列表 → 关于」这条路径一直留着。
 *
 * 修复：设置 tab 的槽 key 带版本号（[tabStateSlotKey]），隐私会话进出时递增，
 * 旧槽连同返回栈一起丢弃。下面三条测试分别锁住「机制真的如此」和「修复真的生效」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TabStateSlotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `槽 key 只随设置 tab 的版本号变化`() {
        // 设置 tab：进过一次隐私会话就该换槽
        assertThat(tabStateSlotKey(MainTab.SETTINGS, 0))
            .isNotEqualTo(tabStateSlotKey(MainTab.SETTINGS, 1))
        // 其它 tab：版本号怎么涨都不换 —— 滚动位置必须照常恢复
        assertThat(tabStateSlotKey(MainTab.HOME, 0))
            .isEqualTo(tabStateSlotKey(MainTab.HOME, 7))
        assertThat(tabStateSlotKey(MainTab.LIBRARY, 0))
            .isEqualTo(tabStateSlotKey(MainTab.LIBRARY, 7))
    }

    @Test
    fun `槽 key 不变时切走再切回，内部页面被恢复`() {
        val visible = mutableStateOf(true)
        val slot = mutableStateOf(0)
        compose.setContent {
            SlotHarness(visible, tabStateSlotKey(MainTab.SETTINGS, slot.value))
        }

        compose.onNodeWithText("page=list").performClick()
        compose.onNodeWithText("page=about").assertExists()

        compose.runOnIdle { visible.value = false }   // 切到别的 tab（内容移出组合）
        compose.runOnIdle { visible.value = true }    // 切回来

        // 这正是修复前的现场：同一个槽，关于页原样回来
        compose.onNodeWithText("page=about").assertExists()
    }

    @Test
    fun `换了槽再回来，设置回到列表页`() {
        val visible = mutableStateOf(true)
        val slot = mutableStateOf(0)
        compose.setContent {
            SlotHarness(visible, tabStateSlotKey(MainTab.SETTINGS, slot.value))
        }

        compose.onNodeWithText("page=list").performClick()
        compose.onNodeWithText("page=about").assertExists()

        // 隐私会话：入口在关于页 → 解锁成功 → 切首页 + 换槽（MainScaffold 的 privacyEnterSignal 效应）
        compose.runOnIdle {
            visible.value = false
            slot.value = 1
        }
        compose.runOnIdle { visible.value = true }

        compose.onNodeWithText("page=list").assertExists()
    }
}

/** 极简复刻：一个 tab 的内容 + 它的 saveable 槽。`page` 相当于子导航的当前路由。 */
@Composable
private fun SlotHarness(visible: MutableState<Boolean>, slotKey: String) {
    val holder = rememberSaveableStateHolder()
    if (visible.value) {
        holder.SaveableStateProvider(slotKey) {
            var page by rememberSaveable { mutableStateOf("list") }
            Text(
                text = "page=$page",
                modifier = Modifier.clickable { page = "about" },
            )
        }
    }
}
