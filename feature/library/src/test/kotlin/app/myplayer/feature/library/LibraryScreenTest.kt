package app.myplayer.feature.library

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.MediaLibrary
import app.myplayer.core.model.SourceType
import com.google.common.truth.Truth.assertThat
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
                onOpenDetail = {},
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
                onOpenDetail = {},
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
                onOpenDetail = {},
            )
        }
        compose.waitForIdle()
        compose.onRoot().assertExists()
    }

    /** 两级入口（`03` §6.1）：中央播放按钮直接播放，卡片其余区域进详情，两者互不串台。 */
    @Test
    fun `点中央播放按钮直播_点卡片其他区域进详情`() {
        var played = ""
        var opened = ""
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
                onPlay = { played = it.id },
                onOpenDetail = { opened = it.id },
            )
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("播放 沙丘").performClick()
        assertThat(played).isEqualTo("i1")
        assertThat(opened).isEmpty()          // 内层按钮消费点击，不冒泡到卡片

        // 「卡片其他区域」取标题行：卡片 clickable 合并了子节点语义，合并树里的卡片节点
        // 几何中心正好压在海报中央的播放按钮上，只有未合并树的标题节点才落在按钮之外。
        compose.onNodeWithText("沙丘", useUnmergedTree = true).performClick()
        assertThat(opened).isEqualTo("i1")
    }
}
