package app.myplayer.feature.library

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.MediaLibrary
import app.myplayer.core.model.SourceType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 库页渲染回归测试（Robolectric 上跑 Compose UI 测试）。
 *
 * 历史 bug：登录成功后第一次进入库页时 libraries 仍为空（首次同步未完成），
 * `PrimaryScrollableTabRow` 在 0 个 Tab 上测量越界崩溃
 * （`IndexOutOfBoundsException: Index 0 out of bounds for length 0`，M3 TabRow.kt），
 * 真机表现为「点登录 → 闪退回桌面」。渲染路径的问题只有 UI 测试能覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val sampleLibrary = MediaLibrary(id = "lib1", name = "电影", collectionType = "movies")
    private val sampleItem = MediaItemDto(
        id = "i1", serverId = "s1", sourceType = SourceType.EMBY, title = "沙丘",
    )

    @Test
    fun `库列表为空且加载中时不应崩溃`() {
        compose.setContent {
            LibraryScreen(
                state = LibraryUiState(libraries = emptyList(), selectedLibraryId = null, items = emptyList(), loading = true),
                loadImage = { ImageSource.None },
                onSelectLibrary = {},
                onPlay = {},
            )
        }
        compose.waitForIdle() // 完成测量与布局即视为通过
        compose.onRoot().assertExists()
    }

    @Test
    fun `库列表为空且加载完成后不应崩溃`() {
        compose.setContent {
            LibraryScreen(
                state = LibraryUiState(libraries = emptyList(), selectedLibraryId = null, items = emptyList(), loading = false),
                loadImage = { ImageSource.None },
                onSelectLibrary = {},
                onPlay = {},
            )
        }
        compose.waitForIdle()
        compose.onRoot().assertExists()
    }

    @Test
    fun `库列表非空时正常渲染Tab与海报网格`() {
        compose.setContent {
            LibraryScreen(
                state = LibraryUiState(
                    libraries = listOf(sampleLibrary),
                    selectedLibraryId = sampleLibrary.id,
                    items = listOf(sampleItem),
                    loading = false,
                ),
                loadImage = { ImageSource.None },
                onSelectLibrary = {},
                onPlay = {},
            )
        }
        compose.waitForIdle()
        compose.onRoot().assertExists()
    }
}
