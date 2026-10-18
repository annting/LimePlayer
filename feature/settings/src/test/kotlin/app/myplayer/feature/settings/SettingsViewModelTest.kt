package app.myplayer.feature.settings

import app.myplayer.core.datastore.InMemoryServerConfigStore
import app.myplayer.core.datastore.InMemorySettingsStore
import app.myplayer.core.datastore.InMemoryTokenStore
import app.myplayer.core.datastore.PlayerSettings
import app.myplayer.core.datastore.ServerConfig
import app.myplayer.core.datastore.ThemeMode
import app.myplayer.core.model.SourceType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var settings: InMemorySettingsStore
    private lateinit var configs: InMemoryServerConfigStore
    private lateinit var tokens: InMemoryTokenStore
    private lateinit var vm: SettingsViewModel

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        settings = InMemorySettingsStore()
        configs = InMemoryServerConfigStore()
        tokens = InMemoryTokenStore()
        vm = SettingsViewModel(settings, configs, tokens)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private suspend fun addServers() {
        configs.upsert(ServerConfig("s1", SourceType.EMBY, "家里", "http://a:8096", userId = "u1"))
        configs.upsert(ServerConfig("s2", SourceType.FNOS, "飞牛", "http://b:5666", userId = "u2"))
        tokens.put("s1", "TK1")
        tokens.put("s2", "TK2")
    }

    @Test fun `未设置 activeServerId 时列表第一个为当前`() = runTest {
        addServers()
        val rows = vm.state.value.servers
        assertThat(rows.map { it.config.id }).containsExactly("s1", "s2").inOrder()
        assertThat(rows.first { it.config.id == "s1" }.active).isTrue()
    }

    @Test fun `切换当前服务器`() = runTest {
        addServers()
        vm.setActiveServer("s2")
        assertThat(settings.settings.first().activeServerId).isEqualTo("s2")
        assertThat(vm.state.value.servers.first { it.config.id == "s2" }.active).isTrue()
        assertThat(vm.state.value.servers.first { it.config.id == "s1" }.active).isFalse()
    }

    @Test fun `删除当前服务器后交回自动选择`() = runTest {
        addServers()
        vm.setActiveServer("s2")
        vm.deleteServer("s2")
        assertThat(configs.observeAll().first().map { it.id }).containsExactly("s1")
        assertThat(tokens.get("s2")).isNull()          // 令牌成对清除
        assertThat(settings.settings.first().activeServerId).isNull()   // 交回自动选择
    }

    @Test fun `设置项写入存储`() = runTest {
        vm.setThemeMode(ThemeMode.DARK)
        vm.setAutoPlayNext(false)
        vm.setDoubleTapSeekMillis(30_000L)
        vm.setSubtitleStyle { it.copy(fontScale = 1.8f) }

        val st: PlayerSettings = settings.settings.first()
        assertThat(st.themeMode).isEqualTo(ThemeMode.DARK)
        assertThat(st.autoPlayNext).isFalse()
        assertThat(st.doubleTapSeekMillis).isEqualTo(30_000L)
        assertThat(st.subtitleStyle.fontScale).isEqualTo(1.8f)
    }
}
