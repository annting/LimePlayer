package app.myplayer.feature.library

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.myplayer.core.mediaui.PosterCard
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.MediaItemDto
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 统一文件卡（`core:media-ui/PosterCard`）的可空回调行为。
 *
 * 2026-11-02 从 `feature:library` 下沉时，`onPlay` / `onOpenDetail` 改成了可空 ——
 * 为的是让演员作品页（只有"进详情"、没有"直接播放"）也能用同一张卡。
 * 这里锁住三条：**没给播放回调就不画播放按钮**（否则是个点了没反应的按钮）、
 * **给了就画**、**点卡片触发进详情**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PosterCardTest {

    @get:Rule
    val compose = createComposeRule()

    private fun item() = MediaItemDto(
        id = "m1",
        serverId = "srv",
        libraryId = "lib",
        title = "测试影片",
        sourceType = app.myplayer.core.model.SourceType.EMBY,
        sizeBytes = 166L * 1024 * 1024,
    )

    @Test
    fun `没有播放回调时不渲染播放按钮`() {
        compose.setContent {
            PosterCard(
                item = item(),
                loadImage = { ImageSource.None },
                onOpenDetail = {},
            )
        }
        compose.onNodeWithText("测试影片").assertIsDisplayed()
        compose.onNodeWithContentDescription("播放 测试影片").assertDoesNotExist()
    }

    @Test
    fun `有播放回调时渲染播放按钮`() {
        compose.setContent {
            PosterCard(
                item = item(),
                loadImage = { ImageSource.None },
                onPlay = {},
                onOpenDetail = {},
            )
        }
        compose.onNodeWithContentDescription("播放 测试影片").assertIsDisplayed()
    }

    @Test
    fun `点击卡片进详情`() {
        var opened = 0
        compose.setContent {
            PosterCard(
                item = item(),
                loadImage = { ImageSource.None },
                onOpenDetail = { opened++ },
            )
        }
        compose.onNodeWithText("测试影片").performClick()
        assertThat(opened).isEqualTo(1)
    }

    /**
     * 无预览图时的占位：**不画文件名首字**（2026-11-02 用户定稿）。
     *
     * 首字只截一个字符，所以用精确匹配 "测" 判断 —— 卡片下方的完整标题 "测试影片"
     * 不算命中；封面区一旦退回「首字占位」，这条就会失败。
     */
    @Test
    fun `无预览图时不显示文件名首字`() {
        compose.setContent {
            PosterCard(
                item = item(),
                loadImage = { ImageSource.None },
                onOpenDetail = {},
            )
        }
        compose.onAllNodesWithText("测").assertCountEquals(0)
    }
}
