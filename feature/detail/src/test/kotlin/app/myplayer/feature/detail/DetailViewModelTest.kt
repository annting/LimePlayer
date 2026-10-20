@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)   // setMain / UnconfinedTestDispatcher

package app.myplayer.feature.detail

import app.myplayer.core.model.ClientCapabilities
import app.myplayer.core.model.ImageKind
import app.myplayer.core.model.ImageSize
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.ItemQuery
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.MediaLibrary
import app.myplayer.core.model.MediaSource
import app.myplayer.core.model.PagedResult
import app.myplayer.core.model.PlayState
import app.myplayer.core.model.PlaybackPlan
import app.myplayer.core.model.SourceType
import app.myplayer.core.model.TrickplaySpec
import app.myplayer.core.model.Ticks
import app.myplayer.core.model.UserData
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import kotlin.time.Duration

class DetailViewModelTest {

    @get:Rule
    val mainRule = object : TestWatcher() {
        override fun starting(description: Description) = Dispatchers.setMain(UnconfinedTestDispatcher())
        override fun finished(description: Description) = Dispatchers.resetMain()
    }

    private fun item(
        id: String = "i1",
        title: String = "沙丘",
        seriesId: String? = null,
        season: Int? = null,
        episode: Int? = null,
    ) = MediaItemDto(
        id = id, serverId = "srv", sourceType = SourceType.EMBY, title = title,
        seriesId = seriesId, seasonNumber = season, episodeNumber = episode,
    )

    private inner class FakeSource(
        var detail: MediaItemDto? = null,
        var detailError: Throwable? = null,
        var episodes: List<MediaItemDto> = emptyList(),
        var episodesError: Throwable? = null,
    ) : MediaSource {
        var episodeCalls = 0
            private set

        // 写操作桩（`02` §5.1）
        var markPlayedResponse: UserData? = null
        var markPlayedError: Throwable? = null
        var deleteResult: Boolean = true
        var deleteError: Throwable? = null
        var lastMarkPlayedArg: Boolean? = null
            private set
        var deleteCalls = 0
            private set

        override val type = SourceType.EMBY
        override val serverId = "srv"
        override val canMarkPlayed: Boolean get() = true
        override suspend fun getLibraries() = emptyList<MediaLibrary>()
        override suspend fun getItems(query: ItemQuery) = PagedResult(emptyList<MediaItemDto>(), 0, 0)
        override suspend fun getItemDetail(id: String): MediaItemDto =
            detailError?.let { throw it } ?: requireNotNull(detail)
        override suspend fun image(item: MediaItemDto, kind: ImageKind, size: ImageSize) = ImageSource.None
        override suspend fun planPlayback(item: MediaItemDto, caps: ClientCapabilities): PlaybackPlan = error("unused")
        override suspend fun getTrickplay(item: MediaItemDto, width: Int): TrickplaySpec? = null
        override suspend fun reportProgress(item: MediaItemDto, pos: Duration, state: PlayState) = Unit
        override suspend fun getEpisodes(seriesId: String): List<MediaItemDto> {
            episodeCalls++
            episodesError?.let { throw it }
            return episodes
        }

        override suspend fun markPlayed(item: MediaItemDto, played: Boolean): UserData {
            lastMarkPlayedArg = played
            markPlayedError?.let { throw it }
            return requireNotNull(markPlayedResponse)
        }

        override suspend fun deleteItem(item: MediaItemDto): Boolean {
            deleteCalls++
            deleteError?.let { throw it }
            return deleteResult
        }
    }

    @Test fun `电影不拉分集且直接进入内容态`() = runTest {
        val source = FakeSource(detail = item())
        val vm = DetailViewModel(source, "i1")
        vm.load()

        val state = vm.state.value
        assertThat(state).isInstanceOf(DetailUiState.Content::class.java)
        assertThat((state as DetailUiState.Content).item.title).isEqualTo("沙丘")
        assertThat(state.seasons).isEmpty()
        assertThat(source.episodeCalls).isEqualTo(0)   // 非剧集不请求分集
    }

    @Test fun `剧集条目拉分集并按季升序分组`() = runTest {
        val source = FakeSource(
            detail = item(seriesId = "s1", season = 1, episode = 1),
            episodes = listOf(
                item("e3", "S2E1", "s1", season = 2, episode = 1),
                item("e1", "S1E1", "s1", season = 1, episode = 1),
                item("e2", "S1E2", "s1", season = 1, episode = 2),
            ),
        )
        val vm = DetailViewModel(source, "i1")
        vm.load()

        val seasons = (vm.state.value as DetailUiState.Content).seasons
        assertThat(seasons.map { it.seasonNumber }).containsExactly(1, 2).inOrder()
        assertThat(seasons.first().episodes.map { it.title }).containsExactly("S1E1", "S1E2").inOrder()
        assertThat(source.episodeCalls).isEqualTo(1)
    }

    @Test fun `详情失败进入错误态且重试可恢复`() = runTest {
        val source = FakeSource(detailError = RuntimeException("网络不可达"))
        val vm = DetailViewModel(source, "i1")
        vm.load()
        assertThat(vm.state.value).isEqualTo(DetailUiState.Error("网络不可达"))

        // 网络恢复后重试：同一 VM 再次 load 应回到内容态
        source.detailError = null
        source.detail = item(title = "恢复")
        vm.load()
        assertThat((vm.state.value as DetailUiState.Content).item.title).isEqualTo("恢复")
    }

    @Test fun `分集拉取失败不影响详情展示`() = runTest {
        val source = FakeSource(
            detail = item(seriesId = "s1"),
            episodesError = RuntimeException("分集超时"),
        )
        val vm = DetailViewModel(source, "s1-test-episodes")
        vm.load()

        val state = vm.state.value
        assertThat(state).isInstanceOf(DetailUiState.Content::class.java)   // 退化为无剧集区，而不是错误页
        assertThat((state as DetailUiState.Content).seasons).isEmpty()
    }

    // ---- 写操作（`02` §5.1 / `03` §6.3，详情页二期）----

    /** 标记成功：服务端权威 UserData 就地更新 + 写回回调被调用。 */
    @Test fun `标记已看成功后用服务端权威数据更新状态并写回`() = runTest {
        val authoritative = UserData(isPlayed = true, resumePositionTicks = Ticks.ZERO)
        val source = FakeSource(detail = item()).apply { markPlayedResponse = authoritative }
        val written = mutableListOf<UserData>()
        val vm = DetailViewModel(source, "i1", onUserDataChanged = { _, _, ud -> written += ud })
        vm.load()

        vm.markPlayed(played = true)
        assertThat((vm.state.value as DetailUiState.Content).item.userData).isEqualTo(authoritative)
        assertThat(source.lastMarkPlayedArg).isTrue()
        assertThat(written).containsExactly(authoritative)
        assertThat(vm.userMessage.value).isNull()
    }

    /** 取消已看：同一入口，played=false 透传给源。 */
    @Test fun `取消已看透传 false 给源`() = runTest {
        val source = FakeSource(item().copy(userData = UserData(isPlayed = true))).apply {
            markPlayedResponse = UserData(isPlayed = false)
        }
        val vm = DetailViewModel(source, "i1")
        vm.load()

        vm.markPlayed(played = false)
        assertThat(source.lastMarkPlayedArg).isFalse()
        assertThat((vm.state.value as DetailUiState.Content).item.userData.isPlayed).isFalse()
    }

    /** 失败仅提示，不改变本地状态（`03` §6.3）。 */
    @Test fun `标记失败仅置提示且不改变本地状态`() = runTest {
        val source = FakeSource(detail = item()).apply { markPlayedError = RuntimeException("网络不可达") }
        val vm = DetailViewModel(source, "i1")
        vm.load()

        vm.markPlayed(played = true)
        val content = vm.state.value as DetailUiState.Content
        assertThat(content.item.userData.isPlayed).isFalse()   // 本地状态不变
        assertThat(vm.userMessage.value).isEqualTo("操作失败，请稍后重试")

        vm.consumeUserMessage()
        assertThat(vm.userMessage.value).isNull()
    }

    /** 删除成功：回调 onDeleted（路由层清缓存 + 返回），不置提示。 */
    @Test fun `删除成功后触发回调`() = runTest {
        val source = FakeSource(detail = item())
        var deleted: MediaItemDto? = null
        val vm = DetailViewModel(source, "i1", onDeleted = { deleted = it })
        vm.load()

        vm.delete()
        assertThat(source.deleteCalls).isEqualTo(1)
        assertThat(deleted?.id).isEqualTo("i1")
        assertThat(vm.userMessage.value).isNull()
    }

    /** 删除失败（如无权限 403）：不回调，仅提示错误信息。 */
    @Test fun `删除失败仅提示且不回调`() = runTest {
        val source = FakeSource(detail = item()).apply { deleteError = RuntimeException("没有权限") }
        var deleted = 0
        val vm = DetailViewModel(source, "i1", onDeleted = { deleted++ })
        vm.load()

        vm.delete()
        assertThat(deleted).isEqualTo(0)
        assertThat(vm.userMessage.value).isEqualTo("没有权限")
    }

    /** 源声明不支持删除（返回 false）：提示但不回调。 */
    @Test fun `源不支持删除时仅提示`() = runTest {
        val source = FakeSource(detail = item()).apply { deleteResult = false }
        var deleted = 0
        val vm = DetailViewModel(source, "i1", onDeleted = { deleted++ })
        vm.load()

        vm.delete()
        assertThat(deleted).isEqualTo(0)
        assertThat(vm.userMessage.value).isEqualTo("该源不支持删除")
    }
}
