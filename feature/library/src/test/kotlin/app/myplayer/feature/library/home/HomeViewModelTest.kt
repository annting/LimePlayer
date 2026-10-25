@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.myplayer.feature.library.home

import app.myplayer.core.datastore.InMemorySettingsStore
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.MediaLibrary
import app.myplayer.core.model.SourceType
import app.myplayer.feature.library.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.Test

/**
 * 首页状态机测试：首屏 / 下拉刷新 / 失败 / 切换来源四条路径。
 *
 * 用 [MainDispatcherRule] 顶掉 `Dispatchers.Main`（`viewModelScope` 依赖它），
 * 再手动推进虚拟时间，验证 isLoading / isRefreshing / error 的先后顺序 ——
 * 这三者错位就是真机上「下拉刷新把内容清空」或「骨架屏一闪」的根因。
 */
class HomeViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private val settings = InMemorySettingsStore()

    /** 统一构造：补上首页设置 repository（与本文件无关的旧用例零改动语义）。 */
    private fun homeVm(data: MockHomeDataSource) =
        HomeViewModel(MockHomeData.EMBY_ID, MockHomeData.servers, data, HomeSettingsRepository(settings))

    @Test
    fun `首屏加载完成后落数据并结束骨架屏`() {
        val vm = homeVm(MockHomeDataSource(latencyMs = 0))
        val state = vm.state.value

        assertThat(state.isLoading).isFalse()
        assertThat(state.error).isNull()
        assertThat(state.serverName).isEqualTo(MockHomeData.EMBY_NAME)
        assertThat(state.continueWatchingList).isNotEmpty()
        assertThat(state.dynamicSections).isNotEmpty()
        assertThat(state.recentlyAddedList).isNotEmpty()
    }

    @Test
    fun `下拉刷新期间内容保留且 isRefreshing 为真`() {
        val vm = homeVm(MockHomeDataSource(latencyMs = 100))
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(vm.state.value.isLoading).isFalse()

        vm.refresh()
        // 刷新中：内容还在，只是顶部转圈
        assertThat(vm.state.value.isRefreshing).isTrue()
        assertThat(vm.state.value.continueWatchingList).isNotEmpty()

        dispatcher.scheduler.advanceUntilIdle()
        assertThat(vm.state.value.isRefreshing).isFalse()
    }

    @Test
    fun `加载失败落 error 且退出加载态`() {
        val vm = HomeViewModel(
            MockHomeData.EMBY_ID,
            MockHomeData.servers,
            MockHomeDataSource(latencyMs = 0, failFor = setOf(MockHomeData.EMBY_ID)),
            HomeSettingsRepository(settings),
        )
        val state = vm.state.value

        assertThat(state.isLoading).isFalse()
        assertThat(state.isRefreshing).isFalse()
        assertThat(state.error).contains("连接超时")
        assertThat(state.isOffline).isTrue()
        // 失败不能把服务器信息也清掉，否则页面会退化成「无服务器」空态
        assertThat(state.hasNoServer).isFalse()
    }

    @Test
    fun `切换来源会换成另一台服务器的内容`() {
        val vm = homeVm(MockHomeDataSource(latencyMs = 0))
        val embyLibIds = vm.state.value.dynamicSections.map { it.id }
        assertThat(embyLibIds).contains("lib-movie")

        vm.selectServer(MockHomeData.FNOS_ID)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(vm.state.value.selectedServerId).isEqualTo(MockHomeData.FNOS_ID)
        assertThat(vm.state.value.serverName).isEqualTo(MockHomeData.FNOS_NAME)
        assertThat(vm.state.value.dynamicSections.map { it.id }).contains("fn-movie")
        assertThat(vm.state.value.dynamicSections.map { it.id }).doesNotContain("lib-movie")
    }

    @Test
    fun `重复选择同一台服务器不会重复建连`() {
        val vm = homeVm(MockHomeDataSource(latencyMs = 0))
        vm.selectServer(MockHomeData.EMBY_ID)
        // 没有触发 isLoading 说明直接 return 了
        assertThat(vm.state.value.isLoading).isFalse()
        assertThat(vm.state.value.selectedServerId).isEqualTo(MockHomeData.EMBY_ID)
    }

    @Test
    fun `数据源被换成新实例后重新取数`() {
        val vm = homeVm(MockHomeDataSource(latencyMs = 0))
        assertThat(vm.state.value.dynamicSections).isNotEmpty()

        // 重新登录 / 改端点后 source 会重建 → 旧实例绑的地址与令牌都不能再用
        vm.rebind(MockHomeDataSource(latencyMs = 0, failFor = setOf(MockHomeData.EMBY_ID)))
        assertThat(vm.state.value.error).contains("连接超时")
    }

    @Test
    fun `同一个数据源实例 rebind 不触发重复请求`() {
        val data = MockHomeDataSource(latencyMs = 0)
        val vm = homeVm(data)

        vm.rebind(data)

        assertThat(vm.state.value.isLoading).isFalse()
        assertThat(vm.state.value.error).isNull()
    }

    @Test
    fun `同一个底层源重新包装后 rebind 不重新加载`() {
        // BUG-2026-002：tab 切走再切回时 HomeDestination 会重新 remember 出一个新 wrapper，
        // 底层 MediaSource 其实没变 —— 不能把它当成「换源」而整页塌成骨架屏
        val source = FakeSource(
            libraries = listOf(MediaLibrary("lib-movie", "电影", 486)),
            onItems = { q -> listOf(dto("${q.libraryId}-1")) },
        )
        val vm = HomeViewModel(
            MockHomeData.EMBY_ID,
            MockHomeData.servers,
            ServerHomeDataSource(source, "客厅 Emby"),
            HomeSettingsRepository(settings),
        )
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(source.libraryCalls).isEqualTo(1)

        vm.rebind(ServerHomeDataSource(source, "客厅 Emby"))
        dispatcher.scheduler.advanceUntilIdle()

        // 没有第二次整屏取数，也没有回到骨架屏
        assertThat(source.libraryCalls).isEqualTo(1)
        assertThat(vm.state.value.isLoading).isFalse()
        assertThat(vm.state.value.dynamicSections.map { it.id }).containsExactly("lib-movie")
    }

    @Test
    fun `换底层源（重新登录或改端点）会重新加载`() {
        val first = FakeSource(
            libraries = listOf(MediaLibrary("lib-movie", "电影", 486)),
            onItems = { q -> listOf(dto("${q.libraryId}-1")) },
        )
        val vm = HomeViewModel(
            MockHomeData.EMBY_ID,
            MockHomeData.servers,
            ServerHomeDataSource(first, "客厅 Emby"),
            HomeSettingsRepository(settings),
        )
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(first.libraryCalls).isEqualTo(1)

        // 重新登录后 MainActivity 的 remember(activeConfig) 会造出新的 source 实例
        val second = FakeSource(
            libraries = listOf(MediaLibrary("lib-tv", "电视剧", 214)),
            onItems = { q -> listOf(dto("${q.libraryId}-1")) },
        )
        vm.rebind(ServerHomeDataSource(second, "客厅 Emby"))
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(second.libraryCalls).isEqualTo(1)
        assertThat(vm.state.value.dynamicSections.map { it.id }).containsExactly("lib-tv")
    }

    /** 库横滑需要至少一个条目，否则 `ServerHomeDataSource` 会把空库过滤掉。 */
    private fun dto(id: String) = MediaItemDto(
        id = id,
        serverId = MockHomeData.EMBY_ID,
        sourceType = SourceType.EMBY,
        title = id,
        hasPoster = true,
    )
}
