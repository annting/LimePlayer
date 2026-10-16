@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)   // setMain / UnconfinedTestDispatcher

package app.myplayer.feature.library

import app.myplayer.core.model.*
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import kotlin.time.Duration

/** `viewModelScope` 依赖 `Dispatchers.Main`：JVM 单测里用 TestDispatcher 顶替，否则直接崩。 */
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

private class FakeRepo(
    private val libs: List<MediaLibrary>,
    private val items: List<MediaItemDto>,
) : LibraryRepository {
    val calls = mutableListOf<String>()
    override fun observeLibraries(serverId: String) = MutableStateFlow(libs)
    override fun observeItems(serverId: String, libraryId: String) =
        MutableStateFlow(items.filter { it.libraryId == libraryId })
    override suspend fun syncLibraries(source: MediaSource) { calls += "syncLibraries" }
    override suspend fun syncItems(source: MediaSource, libraryId: String) { calls += "syncItems:$libraryId" }
}

private class NoopSource : MediaSource {
    override val type = SourceType.EMBY
    override val serverId = "srv"
    override suspend fun getLibraries() = emptyList<MediaLibrary>()
    override suspend fun getItems(query: ItemQuery) = PagedResult(emptyList<MediaItemDto>(), 0, 0)
    override suspend fun getItemDetail(id: String): MediaItemDto = error("unused")
    override suspend fun image(item: MediaItemDto, kind: ImageKind, size: ImageSize) = ImageSource.None
    override suspend fun planPlayback(item: MediaItemDto, caps: ClientCapabilities): PlaybackPlan = error("unused")
    override suspend fun getTrickplay(item: MediaItemDto, width: Int): TrickplaySpec? = null
    override suspend fun reportProgress(item: MediaItemDto, pos: Duration, state: PlayState) = Unit
}

class LibraryViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test fun `loads libraries and selects first`() = runTest {
        val vm = LibraryViewModel(FakeRepo(
            libs = listOf(MediaLibrary("lib1", "电影"), MediaLibrary("lib2", "剧集")),
            items = listOf(MediaItemDto("i1", "srv", SourceType.EMBY, "Dune", libraryId = "lib1")),
        ), serverId = "srv")
        val state = vm.state.first { it.libraries.isNotEmpty() }
        assertThat(state.selectedLibraryId).isEqualTo("lib1")
        assertThat(state.items.map { it.title }).containsExactly("Dune")
    }

    @Test fun `refresh syncs libraries first then each library's items`() = runTest {
        val repo = FakeRepo(
            libs = listOf(MediaLibrary("lib1", "电影"), MediaLibrary("lib2", "剧集")),
            items = emptyList(),
        )
        val vm = LibraryViewModel(repo, serverId = "srv")
        vm.refresh(NoopSource())
        assertThat(repo.calls).containsExactly("syncLibraries", "syncItems:lib1", "syncItems:lib2").inOrder()
    }
}
