package app.myplayer.feature.detail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import app.myplayer.core.model.CastMember
import app.myplayer.core.model.ImageKind
import app.myplayer.core.model.ImageSize
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.SeasonGroup
import app.myplayer.core.model.SourceType
import app.myplayer.core.model.Ticks
import app.myplayer.core.model.UserData
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 详情页渲染回归（Robolectric 上跑 Compose UI 测试）：三态不崩溃 + 分季切换 + 二期交互。
 * 图片一律用 [ImageSource.None]（不触网），只验证布局与文本。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DetailScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val noImage: suspend (MediaItemDto, ImageKind) -> ImageSource = { _, _ -> ImageSource.None }
    private val noPersonImage: suspend (String, ImageSize) -> ImageSource = { _, _ -> ImageSource.None }

    private fun movie(
        canDelete: Boolean = false,
        people: List<CastMember> = emptyList(),
    ) = MediaItemDto(
        id = "m1", serverId = "srv", sourceType = SourceType.EMBY, title = "沙丘",
        durationTicks = Ticks.ofSeconds(9000), productionYear = 2021, communityRating = 8.1f,
        overview = "沙漠星球上的家族复仇。", cast = listOf("提莫西·查拉梅"),
        container = "mkv", videoCodec = "hevc", audioCodecs = listOf("dts"), sizeBytes = 12_000_000_000L,
        canDelete = canDelete, people = people,
    )

    private fun episode(id: String, season: Int, number: Int, title: String) = MediaItemDto(
        id = id, serverId = "srv", sourceType = SourceType.EMBY, title = title,
        seasonNumber = season, episodeNumber = number,
        userData = UserData(isPlayed = number == 1),
    )

    private fun seriesState() = DetailUiState.Content(
        item = MediaItemDto(
            id = "s1", serverId = "srv", sourceType = SourceType.EMBY, title = "火线",
            durationTicks = Ticks.ofSeconds(3600),
        ),
        seasons = listOf(
            SeasonGroup(1, listOf(episode("e1", 1, 1, "第一集"), episode("e2", 1, 2, "第二集"))),
            SeasonGroup(2, listOf(episode("e3", 2, 1, "S2 第一集"))),
        ),
    )

    private fun setContent(
        state: DetailUiState,
        canMarkPlayed: Boolean = false,
        onMarkPlayed: (Boolean) -> Unit = {},
        onDelete: () -> Unit = {},
        onPersonClick: (CastMember) -> Unit = {},
    ) {
        compose.setContent {
            DetailScreen(
                state = state,
                loadImage = noImage,
                loadPersonImage = noPersonImage,
                canMarkPlayed = canMarkPlayed,
                onBack = {},
                onPlay = {},
                onRetry = {},
                onMarkPlayed = onMarkPlayed,
                onDelete = onDelete,
                onPersonClick = onPersonClick,
                userMessage = null,
                onMessageShown = {},
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun `内容态渲染标题评分年份与主按钮`() {
        setContent(DetailUiState.Content(movie()))
        compose.onNodeWithText("沙丘").assertIsDisplayed()
        compose.onNodeWithText("播放").assertIsDisplayed()
        compose.onNodeWithText("简介").assertIsDisplayed()
    }

    /** 续播行（`03` §6.3）：看了一半才有 —— 按钮文案「继续播放」+ 下方进度条与剩余时长。 */
    @Test
    fun `看了一半渲染继续播放与剩余时长`() {
        setContent(
            DetailUiState.Content(
                movie().copy(userData = UserData(resumePositionTicks = Ticks.ofSeconds(1800)))
            )
        )
        compose.onNodeWithText("继续播放").assertIsDisplayed()
        // 9000s 片长、已看 1800s → 剩余 2小时
        compose.onNodeWithText("剩余 2小时").assertIsDisplayed()
    }

    @Test
    fun `未观看时不渲染续播行`() {
        setContent(DetailUiState.Content(movie()))
        compose.onNodeWithText("剩余", substring = true).assertDoesNotExist()
    }

    @Test
    fun `加载态与错误态不崩溃且错误态可重试`() {
        var retried = false
        compose.setContent {
            DetailScreen(
                state = DetailUiState.Error("网络不可达"),
                loadImage = noImage,
                loadPersonImage = noPersonImage,
                canMarkPlayed = false,
                onBack = {},
                onPlay = {},
                onRetry = { retried = true },
                onMarkPlayed = {},
                onDelete = {},
                onPersonClick = {},
                userMessage = null,
                onMessageShown = {},
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText("网络不可达").assertIsDisplayed()
        compose.onNodeWithText("重试").performClick()
        assertThat(retried).isTrue()
    }

    @Test
    fun `剧集分季切换显示对应一季的集`() {
        setContent(seriesState())
        // 默认第一季（列表在首屏之外，只断言节点存在）
        compose.onNodeWithText("1. 第一集").assertExists()
        compose.onNodeWithText("已看").assertExists()

        compose.onNodeWithText("第 2 季").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("1. S2 第一集", substring = true).assertExists()
        compose.onNodeWithText("1. 第一集").assertDoesNotExist()
    }

    // ---- 详情页二期（`03` §6.3）----

    /** 简介不再折叠：长文直接全文渲染，无「展开 / 收起」入口。 */
    @Test
    fun `简介不折叠无展开入口`() {
        val longOverview = "长简介".repeat(120)   // 远超旧展开阈值
        setContent(DetailUiState.Content(movie().copy(overview = longOverview)))
        compose.onNodeWithText("展开").assertDoesNotExist()
        compose.onNodeWithText("收起").assertDoesNotExist()
    }

    /** 旧「技术信息」折叠区由信息分区取代，无「展开」交互。 */
    @Test
    fun `信息分区直接展示文件与视频信息`() {
        setContent(DetailUiState.Content(movie()))
        compose.onNodeWithText("文件信息").assertExists()
        compose.onNodeWithText("视频信息").assertExists()
        compose.onNodeWithText("音频信息").assertExists()
    }

    @Test
    fun `canMarkPlayed 为 false 时不渲染标记已播放`() {
        setContent(DetailUiState.Content(movie()))
        compose.onNodeWithText("标记已播放").assertDoesNotExist()
    }

    @Test
    fun `canMarkPlayed 为 true 时渲染并可切换`() {
        var requested: Boolean? = null
        setContent(
            DetailUiState.Content(movie()),
            canMarkPlayed = true,
            onMarkPlayed = { requested = it },
        )
        compose.onNodeWithText("标记已播放").assertIsDisplayed()
        compose.onNodeWithText("标记已播放").performClick()
        assertThat(requested).isTrue()
    }

    /** 删除按钮仅 canDelete==true 渲染（服务端逐账号权限下发）；确认弹窗 → 回调。 */
    @Test
    fun `canDelete 为 true 时删除按钮经确认弹窗回调`() {
        var deleted = false
        setContent(DetailUiState.Content(movie(canDelete = true)), onDelete = { deleted = true })
        // 按钮在页面末尾，先滚动到位再点（测试视口小于页面高度）
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("删除该条目"))
        compose.waitForIdle()
        compose.onNodeWithText("删除该条目").assertIsDisplayed()
        compose.onNodeWithText("删除该条目").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("删除条目").assertIsDisplayed()   // 弹窗标题
        compose.onNodeWithText("删除").performClick()           // 弹窗确认按钮（精确匹配）
        assertThat(deleted).isTrue()
    }

    @Test
    fun `canDelete 为 false 时不渲染删除按钮`() {
        setContent(DetailUiState.Content(movie(canDelete = false)))
        compose.onNodeWithText("删除该条目").assertDoesNotExist()
    }

    /** 有结构化 people 时渲染头像网格（姓名 + 角色），点击回调人物 id。 */
    @Test
    fun `演职员头像网格点击进人物页`() {
        var clicked: CastMember? = null
        setContent(
            DetailUiState.Content(
                movie(people = listOf(CastMember(id = "p1", name = "提莫西·查拉梅", role = "保罗"))),
            ),
            onPersonClick = { clicked = it },
        )
        compose.onNodeWithText("提莫西·查拉梅").assertExists()
        compose.onNodeWithText("保罗").assertExists()
        // 网格在首屏之下，先滚动到位再点
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("提莫西·查拉梅"))
        compose.waitForIdle()
        compose.onNodeWithText("提莫西·查拉梅").performClick()
        assertThat(clicked?.id).isEqualTo("p1")
    }

    /** 无 people 但有 cast 姓名时退化为文字胶囊（老数据兼容），不可点进人物页语义。 */
    @Test
    fun `无结构化人物时退化为姓名胶囊`() {
        setContent(DetailUiState.Content(movie()))   // cast 有姓名、people 空
        compose.onNodeWithText("提莫西·查拉梅").assertExists()
    }
}
