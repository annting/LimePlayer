@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.myplayer.feature.library.home

import app.myplayer.core.datastore.InMemorySettingsStore
import app.myplayer.core.datastore.HomeModuleSettings
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.SourceType
import app.myplayer.core.model.Ticks
import app.myplayer.feature.library.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * 首页设置的**端到端联动**用例：设置页写 DataStore → `HomeSettingsRepository` →
 * `HomeViewModel` 的 `combine` → [HomeUiState.settings] / 最近添加物理过滤。
 *
 * 这条链路是「改设置返回首页立即生效、无需刷新」的骨架 —— 每一环断了都表现为
 * 「设置不生效」或「设置生效但内容不对」，用真 VM + 真 InMemory Store 全链路钉住。
 */
class HomeSettingsLinkTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private val settingsStore = InMemorySettingsStore()
    private val repository = HomeSettingsRepository(settingsStore)

    private fun homeVm(serverId: String = MockHomeData.EMBY_ID) =
        HomeViewModel(serverId, MockHomeData.servers, MockHomeDataSource(latencyMs = 0), repository)

    // ---- Repository：写回隔离 ----

    @Test
    fun `repository 写 Emby 不碰 SMB`() = runTest {
        repository.update(HomeSourceType.EMBY) { it.copy(showContinueWatching = false) }
        val ps = settingsStore.settings.first()
        assertThat(ps.embyHomeSettings.showContinueWatching).isFalse()
        assertThat(ps.smbHomeSettings.showContinueWatching).isTrue()
    }

    @Test
    fun `observe 跟随源形态切换到对应那份`() = runTest {
        repository.update(HomeSourceType.SMB) { it.copy(showDynamicSections = false) }
        val smbSettings = repository.observe(kotlinx.coroutines.flow.flowOf(HomeSourceType.SMB)).first()
        assertThat(smbSettings.showDynamicSections).isFalse()
        val embySettings = repository.observe(kotlinx.coroutines.flow.flowOf(HomeSourceType.EMBY)).first()
        assertThat(embySettings.showDynamicSections).isTrue()
    }

    // ---- VM 联动：改设置立即反映在 state 上 ----

    @Test
    fun `关闭模块开关后 state 立即变化（无需刷新）`() = runTest {
        val vm = homeVm()
        assertThat(vm.state.value.settings.showContinueWatching).isTrue()

        // 设置页写入 → DataStore → combine → 新 UiState，全程没有调用 vm.refresh()
        repository.update(HomeSourceType.EMBY) { it.copy(showContinueWatching = false, showDynamicSections = false) }

        val state = vm.state.value
        assertThat(state.settings.showContinueWatching).isFalse()
        assertThat(state.settings.showDynamicSections).isFalse()
        // 数据本身还在（显隐由 UI 的 if 决定），关掉开关不丢内容
        assertThat(state.continueWatchingList).isNotEmpty()
        assertThat(state.dynamicSections).isNotEmpty()
    }

    @Test
    fun `最近添加按设置物理过滤（天数 + 数量）`() = runTest {
        val vm = homeVm()
        // Mock 数据入库时间错开 2~18 天，默认 7 天窗口 → 只剩 2/4/6 三条
        assertThat(vm.state.value.recentlyAddedList).hasSize(3)

        // 放宽到 30 天 → 9 条全回来；也不用刷新
        repository.update(HomeSourceType.EMBY) { it.copy(recentlyAddedDays = 30, recentlyAddedLimit = 20) }
        assertThat(vm.state.value.recentlyAddedList).hasSize(9)

        // 收紧到 2 条 → 倒序取前 2
        repository.update(HomeSourceType.EMBY) { it.copy(recentlyAddedLimit = 2) }
        val latest = vm.state.value.recentlyAddedList
        assertThat(latest).hasSize(2)
        // 倒序：最新的在前
        assertThat(latest[0].dateCreated).isAtLeast(latest[1].dateCreated ?: 0L)
    }

    @Test
    fun `SMB 形态走 smb 那份设置`() = runTest {
        val vm = homeVm(serverId = MockHomeData.SMB_ID)
        assertThat(vm.state.value.sourceType).isEqualTo(HomeSourceType.SMB)

        repository.update(HomeSourceType.SMB) { it.copy(showLibraryEntries = false) }
        assertThat(vm.state.value.settings.showLibraryEntries).isFalse()
        // Emby 那份不受影响
        assertThat(settingsStore.settings.first().embyHomeSettings.showLibraryEntries).isTrue()
    }

    // ---- 「全关」空态 ----

    @Test
    fun `四个模块全关时 allModulesHidden 为真，其余组合为假`() = runTest {
        val all = HomeModuleSettings(
            showContinueWatching = false,
            showLibraryEntries = false,
            showDynamicSections = false,
            showRecentlyAdded = false,
        )
        // 走一遍 VM：设置全关 → state.allModulesHidden 立即为真（联动同一链条）
        val vm = homeVm()
        repository.update(HomeSourceType.EMBY) { all }
        assertThat(vm.state.value.allModulesHidden).isTrue()
        // 数据仍然在（显隐由 UI 决定，重开开关即恢复）
        assertThat(vm.state.value.continueWatchingList).isNotEmpty()

        // 任意一个打开 → 不再是全关
        repository.update(HomeSourceType.EMBY) { all.copy(showRecentlyAdded = true) }
        assertThat(vm.state.value.allModulesHidden).isFalse()

        // 默认值全开 → 不是全关
        assertThat(HomeUiState().allModulesHidden).isFalse()
    }

    // ---- 物理过滤纯函数 ----

    @Test
    fun `dateCreated 为 null 的条目保留（源没有入库时间时不能整块消失）`() {
        val now = 1_000_000_000_000L
        val items = listOf(
            dto("fresh", dateCreated = now - DAY_MS),
            dto("stale", dateCreated = now - 10 * DAY_MS),
            dto("unknown", dateCreated = null),
        )
        val out = applyRecentlyAddedWindow(items, HomeModuleSettings(recentlyAddedDays = 7, recentlyAddedLimit = 10), nowMs = now)
        // 过期条目照旧被裁；时间未知的排在有时间的之后
        assertThat(out.map { it.id }).containsExactly("fresh", "unknown").inOrder()
    }

    @Test
    fun `limit 为 0 时最近添加为空但不是错误`() {
        val now = 1_000_000_000_000L
        val out = applyRecentlyAddedWindow(
            listOf(dto("a", dateCreated = now)),
            HomeModuleSettings(recentlyAddedLimit = 0),
            nowMs = now,
        )
        assertThat(out).isEmpty()
    }

    private fun dto(id: String, dateCreated: Long?) = MediaItemDto(
        id = id,
        serverId = "srv",
        sourceType = SourceType.EMBY,
        title = id,
        durationTicks = Ticks(36_000_000_000L),
        dateCreated = dateCreated,
    )
}
