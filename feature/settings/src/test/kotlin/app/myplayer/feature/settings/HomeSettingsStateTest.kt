package app.myplayer.feature.settings

import app.myplayer.core.datastore.InMemoryServerConfigStore
import app.myplayer.core.datastore.InMemorySettingsStore
import app.myplayer.core.datastore.InMemoryTokenStore
import app.myplayer.core.datastore.HomeModuleSettings
import app.myplayer.core.datastore.PlayerSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 首页设置（`HomeSettingsScreen`）的状态层用例。
 *
 * 文案映射是重点：SMB 的副标题里出现「服务器」、或 Emby 里出现「文件夹」，
 * 只有逐字比对才能发现 —— 用户提示词把两组文案写死，这里逐行钉住。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeSettingsStateTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    // ---- 文案映射 ----

    @Test fun `Emby 文案是服务器语义`() {
        val rows = moduleRows(HomeConfigTarget.EMBY)
        assertThat(rows).hasSize(4)
        assertThat(rows.map { it.title }).containsExactly(
            "继续观看", "我的媒体库", "媒体库分类动态展示", "最近添加",
        ).inOrder()
        assertThat(rows[0].description).isEqualTo("展示服务器未看完的视频")
        assertThat(rows[1].description).isEqualTo("展示服务器的库入口")
        assertThat(rows[2].description).isEqualTo("按库展示最新内容")
        assertThat(rows[3].description).isEqualTo("展示最新入库的视频")
    }

    @Test fun `SMB 文案是文件夹语义`() {
        val rows = moduleRows(HomeConfigTarget.SMB)
        assertThat(rows).hasSize(4)
        assertThat(rows.map { it.title }).containsExactly(
            "继续观看", "我的文件夹", "文件夹内容动态展示", "最近添加",
        ).inOrder()
        assertThat(rows[0].description).isEqualTo("展示本设备未看完的视频")
        assertThat(rows[1].description).isEqualTo("展示扫描到的非空文件夹")
        assertThat(rows[2].description).isEqualTo("按文件夹展示最新文件")
        assertThat(rows[3].description).isEqualTo("展示最新修改的视频文件")
    }

    // ---- 写回隔离：改一套不能碰另一套 ----

    @Test fun `改 Emby 配置不影响 SMB`() = runTest {
        val settings = InMemorySettingsStore()
        val vm = SettingsViewModel(settings, InMemoryServerConfigStore(), InMemoryTokenStore())

        // SMB 先改成有辨识度的值
        val smbCustom = HomeModuleSettings(
            showContinueWatching = false,
            showLibraryEntries = false,
            showDynamicSections = false,
            showRecentlyAdded = false,
            recentlyAddedLimit = 10,
            recentlyAddedDays = 3,
        )
        vm.setSmbHomeSettings(smbCustom)

        // 只动 Emby
        vm.setEmbyHomeSettings(
            HomeModuleSettings(
                showContinueWatching = false,
                recentlyAddedLimit = 30,
                recentlyAddedDays = 15,
            ),
        )

        val st: PlayerSettings = settings.settings.first()
        assertThat(st.embyHomeSettings.showContinueWatching).isFalse()
        assertThat(st.embyHomeSettings.recentlyAddedLimit).isEqualTo(30)
        assertThat(st.embyHomeSettings.recentlyAddedDays).isEqualTo(15)
        // SMB 完全没被这次修改碰到
        assertThat(st.smbHomeSettings).isEqualTo(smbCustom)
    }

    @Test fun `改 SMB 配置不影响 Emby`() = runTest {
        val settings = InMemorySettingsStore()
        val vm = SettingsViewModel(settings, InMemoryServerConfigStore(), InMemoryTokenStore())

        val embyBefore = settings.settings.first().embyHomeSettings
        vm.setSmbHomeSettings(HomeModuleSettings(showRecentlyAdded = false, recentlyAddedDays = 30))

        val st = settings.settings.first()
        assertThat(st.smbHomeSettings.showRecentlyAdded).isFalse()
        assertThat(st.smbHomeSettings.recentlyAddedDays).isEqualTo(30)
        assertThat(st.embyHomeSettings).isEqualTo(embyBefore)
    }

    // ---- 默认值与档位 ----

    @Test fun `默认值全部开启且数量为 20 条 7 天`() {
        val d = HomeModuleSettings()
        assertThat(d.showContinueWatching).isTrue()
        assertThat(d.showLibraryEntries).isTrue()
        assertThat(d.showDynamicSections).isTrue()
        assertThat(d.showRecentlyAdded).isTrue()
        assertThat(d.recentlyAddedLimit).isEqualTo(20)
        assertThat(d.recentlyAddedDays).isEqualTo(7)
    }

    @Test fun `关闭最近添加后档位值仍保留`() {
        // 页面把第三组置灰，但值不重置 —— 重新打开时用户上次选的档位还在
        val s = HomeModuleSettings(recentlyAddedLimit = 30, recentlyAddedDays = 15)
            .copy(showRecentlyAdded = false)
            .copy(showRecentlyAdded = true)
        assertThat(s.recentlyAddedLimit).isEqualTo(30)
        assertThat(s.recentlyAddedDays).isEqualTo(15)
    }

    @Test fun `下拉档位与提示词一致`() {
        assertThat(RecentlyAddedLimitOptions).containsExactly(10, 20, 30).inOrder()
        assertThat(RecentlyAddedDaysOptions).containsExactly(3, 7, 15, 30).inOrder()
    }
}
