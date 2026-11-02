package app.myplayer.feature.library

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import app.myplayer.core.designsystem.MyPlayerTheme
import app.myplayer.core.model.ImageSource
import app.myplayer.feature.library.home.HomeCardKind
import app.myplayer.feature.library.home.StandardHorizontalCard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 首页 16:9 视频卡（继续观看 / 最近添加 / 库分类）的「无图」观感。
 *
 * 2026-11-02 用户反馈：Emby「最近添加」的卡片还在显示文件名首字。根因不是 `image == None`
 * 那条分支 —— 那些条目的图片地址**是存在的**（`ImageSource.Remote`），只是请求 404
 * （Emby 条目 Thumb / Backdrop / Primary 全缺，或缓存里 `imageTags` 为空、按 kind 直取到空图），
 * 于是走了 `HomeImage`，露出它底层的首字。
 *
 * 现在视频卡的底层固定是「渐变 + 视频图标」，`AsyncImage` 只在图真到了才盖上去，
 * 所以无论加载中、404、还是本来就没图，都不该出现单字标题。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeVideoCardPlaceholderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun render(image: ImageSource, kind: HomeCardKind = HomeCardKind.VIDEO) {
        compose.setContent {
            MyPlayerTheme {
                StandardHorizontalCard(
                    title = "测试影片",
                    image = image,
                    onClick = {},
                    kind = kind,
                )
            }
        }
    }

    /** 地址存在但会 404：这正是 Emby「最近添加」露字母的那条路径。 */
    @Test
    fun `图片地址存在但加载不出来时也不显示首字`() {
        render(ImageSource.Remote("http://127.0.0.1:1/none.png"))
        compose.onAllNodesWithText("测").assertCountEquals(0)
    }

    /** 压根没有图（无海报、也抽不出帧）时同样只有图标。 */
    @Test
    fun `完全没有图片源时不显示首字`() {
        render(ImageSource.None)
        compose.onAllNodesWithText("测").assertCountEquals(0)
    }

    /** 媒体库卡（Emby / 飞牛）同样不画首字 —— 之前它走 HomeImage，缺封面就露出大字母。 */
    @Test
    fun `媒体库卡没有封面时不显示首字`() {
        render(ImageSource.None, kind = HomeCardKind.LIBRARY)
        compose.onAllNodesWithText("测").assertCountEquals(0)
    }

    /** 文件夹卡（SMB）同样只有文件夹图标。 */
    @Test
    fun `文件夹卡不显示首字`() {
        render(ImageSource.None, kind = HomeCardKind.FOLDER)
        compose.onAllNodesWithText("测").assertCountEquals(0)
    }
}
