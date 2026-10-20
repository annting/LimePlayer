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

/** 人物页装载（`03` §6.3.1）：作品列表 / 空态 / 失败退化为空态。 */
class PersonViewModelTest {

    @get:Rule
    val mainRule = object : TestWatcher() {
        override fun starting(description: Description) = Dispatchers.setMain(UnconfinedTestDispatcher())
        override fun finished(description: Description) = Dispatchers.resetMain()
    }

    private class FakeSource(var works: List<MediaItemDto> = emptyList(), var error: Throwable? = null) : MediaSource {
        override val type = SourceType.EMBY
        override val serverId = "srv"
        override suspend fun getLibraries() = emptyList<MediaLibrary>()
        override suspend fun getItems(query: ItemQuery) = PagedResult(emptyList<MediaItemDto>(), 0, 0)
        override suspend fun getItemDetail(id: String) = error("unused")
        override suspend fun image(item: MediaItemDto, kind: ImageKind, size: ImageSize) = ImageSource.None
        override suspend fun planPlayback(item: MediaItemDto, caps: ClientCapabilities): PlaybackPlan = error("unused")
        override suspend fun getTrickplay(item: MediaItemDto, width: Int): TrickplaySpec? = null
        override suspend fun reportProgress(item: MediaItemDto, pos: Duration, state: PlayState) = Unit
        override suspend fun getPersonWorks(personId: String): List<MediaItemDto> {
            error?.let { throw it }
            return works
        }
    }

    private fun work(id: String) = MediaItemDto(id = id, serverId = "srv", sourceType = SourceType.EMBY, title = id)

    @Test fun `有作品时进入内容态`() = runTest {
        val vm = PersonViewModel(FakeSource(listOf(work("m1"), work("m2"))), "p1")
        vm.load()
        val state = vm.state.value
        assertThat(state).isInstanceOf(PersonUiState.Content::class.java)
        assertThat((state as PersonUiState.Content).works.map { it.id }).containsExactly("m1", "m2")
    }

    @Test fun `无作品时进入空态`() = runTest {
        val vm = PersonViewModel(FakeSource(emptyList()), "p1")
        vm.load()
        assertThat(vm.state.value).isEqualTo(PersonUiState.Empty)
    }

    /** 加载失败（网络问题）同样退化为空态并仍可返回（`03` §6.3.1）。 */
    @Test fun `加载失败退化为空态`() = runTest {
        val vm = PersonViewModel(FakeSource(error = RuntimeException("超时")), "p1")
        vm.load()
        assertThat(vm.state.value).isEqualTo(PersonUiState.Empty)
    }
}
