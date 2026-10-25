package app.myplayer.feature.library.home

import app.myplayer.core.datastore.HomeModuleSettings
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.SourceType
import app.myplayer.core.model.Ticks
import app.myplayer.core.model.UserData
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 首页的纯逻辑测试（不需要 Robolectric）：状态派生、进度/剩余时长文案、回弹位移折算。
 *
 * 这几条都是「算错了 UI 也不会崩、但看起来就是不对」的地方，所以单独钉住。
 */
class HomeStateTest {

    // ---- 状态派生 ----

    @Test
    fun `没有伺服器时 hasNoServer 为真`() {
        val state = HomeUiState(isLoading = false)
        assertThat(state.hasNoServer).isTrue()
        assertThat(state.isOffline).isFalse()
        assertThat(state.hasContent).isFalse()
    }

    @Test
    fun `只有一个服务器名也算已配置`() {
        val state = HomeUiState(serverName = "客厅 Emby")
        assertThat(state.hasNoServer).isFalse()
    }

    @Test
    fun `有错误即离线态`() {
        val state = HomeUiState(serverName = "客厅 Emby", error = "连接超时")
        assertThat(state.isOffline).isTrue()
    }

    @Test
    fun `任意一个有数据的模块就算有内容`() {
        val item = mockItem()
        assertThat(HomeUiState(continueWatchingList = listOf(item)).hasContent).isTrue()
        assertThat(HomeUiState(dynamicSections = listOf(HomeLibrarySection("lib", "电影", listOf(item)))).hasContent).isTrue()
        assertThat(HomeUiState(recentlyAddedList = listOf(item)).hasContent).isTrue()
        // 「我的媒体库」只有库、没有片子时，页面也该显示出来（不是空态）
        assertThat(HomeUiState(myLibrariesOrFolders = listOf(HomeLibraryCard("lib", "电影", 0))).hasContent).isTrue()
    }

    @Test
    fun `只开最近添加且该模块为空时不算有内容（否则页面全空白）`() {
        val onlyRecent = HomeModuleSettings(
            showContinueWatching = false,
            showLibraryEntries = false,
            showDynamicSections = false,
            showRecentlyAdded = true,
        )
        val item = mockItem()
        val state = HomeUiState(
            settings = onlyRecent,
            // 其它模块的数据都还在，只是开关关掉了 —— 它们不该让 hasContent 变真
            continueWatchingList = listOf(item),
            myLibrariesOrFolders = listOf(HomeLibraryCard("lib", "电影", 3, cover = item)),
            dynamicSections = listOf(HomeLibrarySection("lib", "电影", listOf(item))),
            recentlyAddedList = emptyList(),
        )
        assertThat(state.allModulesHidden).isFalse()
        assertThat(state.hasContent).isFalse()
        assertThat(state.onlyRecentlyAdded).isTrue()

        // 该模块有数据 → 立刻算有内容
        assertThat(state.copy(recentlyAddedList = listOf(item)).hasContent).isTrue()
    }

    @Test
    fun `模块开关关闭时它的数据不参与 hasContent`() {
        val item = mockItem()
        val closed = HomeModuleSettings(showRecentlyAdded = false)
        assertThat(HomeUiState(settings = closed, recentlyAddedList = listOf(item)).hasContent).isFalse()
        assertThat(HomeUiState(settings = closed, recentlyAddedList = listOf(item)).onlyRecentlyAdded).isFalse()
    }

    // ---- 进度 / 剩余时长 ----
    @Test
    fun `续播进度按时长比例计算`() {
        val item = mockItem(durationMinutes = 100, watchedFraction = 0.32f)
        assertThat(item.progressFraction()).isWithin(0.001f).of(0.32f)
    }

    @Test
    fun `时长未知时进度为 0 而不是崩`() {
        val item = mockItem(durationMinutes = 0, watchedFraction = 0.5f)
        assertThat(item.progressFraction()).isEqualTo(0f)
    }

    @Test
    fun `剩余时长按分钟取整且至少 1 分钟`() {
        assertThat(mockItem(durationMinutes = 45, watchedFraction = 0.6f).remainingWatchText()).isEqualTo("剩余 18 分钟")
        // 只剩 20 秒：不能显示「剩余 0 分钟」
        assertThat(mockItemSeconds(total = 600, resume = 580).remainingWatchText()).isEqualTo("剩余 1 分钟")
    }

    @Test
    fun `超过一小时显示小时与分`() {
        assertThat(mockItem(durationMinutes = 166, watchedFraction = 0.5f).remainingWatchText())
            .isEqualTo("剩余 1 小时 23 分")
    }

    @Test
    fun `已看完没有剩余时长`() {
        assertThat(mockItem(durationMinutes = 90, watchedFraction = 1f).remainingWatchText()).isNull()
    }

    // ---- SMB 文件夹映射（空文件夹过滤） ----

    @Test
    fun `SMB 空文件夹被过滤，非空文件夹同时变成卡片与分区`() {
        val folders = listOf(
            SmbFolder("Movies", listOf(mockItem())),
            SmbFolder("Downloads", emptyList()),   // 没有受支持的视频文件
            SmbFolder("Music", emptyList()),
        )

        val cards = folders.toFolderCards()
        assertThat(cards.map { it.name }).containsExactly("Movies")
        // 文件夹卡：不走封面、用深灰底 + 图标
        assertThat(cards.single().isFolder).isTrue()
        assertThat(cards.single().cover).isNull()
        assertThat(cards.single().itemCount).isEqualTo(1)

        val sections = folders.toFolderSections()
        assertThat(sections.map { it.name }).containsExactly("Movies")
        assertThat(sections.single().items).hasSize(1)
    }

    @Test
    fun `SMB 每个文件夹一行最多取 limit 条`() {
        val items = (1..30).map {
            MediaItemDto(
                id = "f-$it",
                serverId = "smb",
                sourceType = SourceType.LOCAL,
                title = "第 $it 个",
            )
        }
        val sections = listOf(SmbFolder("Movies", items)).toFolderSections(limit = 20)

        assertThat(sections.single().items).hasSize(20)
        // totalCount 仍是真实总数（「查看全部」据此判断还有多少）
        assertThat(sections.single().totalCount).isEqualTo(30)
    }

    // ---- 回弹位移 ----

    @Test
    fun `回弹位移按阻尼衰减并夹在最大值`() {
        val max = 200f
        // 起始：只走 55%
        assertThat(bounceOffsetAfterDrag(0f, 100f, max)).isWithin(0.01f).of(55f)
        // 已经拉出 100：再拉同样距离只会走一半左右（越拉越难）
        assertThat(bounceOffsetAfterDrag(100f, 100f, max)).isWithin(0.01f).of(127.5f)
        // 超过上限直接被夹住
        assertThat(bounceOffsetAfterDrag(190f, 500f, max)).isEqualTo(max)
        assertThat(bounceOffsetAfterDrag(-190f, -500f, max)).isEqualTo(-max)
    }

    @Test
    fun `最大拉伸为 0 时不产生位移`() {
        assertThat(bounceOffsetAfterDrag(0f, 300f, 0f)).isEqualTo(0f)
    }

    // ---- Mock 数据自检（防止预览/真机空屏） ----

    @Test
    fun `mock 数据三个模块都不为空且库总数不小于列表长度`() {
        assertThat(MockHomeData.continueWatching).isNotEmpty()
        assertThat(MockHomeData.libraries).isNotEmpty()
        assertThat(MockHomeData.recentlyAdded).isNotEmpty()
        MockHomeData.libraries.forEach { lib ->
            assertThat(lib.totalCount).isAtLeast(lib.items.size)
            assertThat(lib.items).isNotEmpty()
        }
    }

    @Test
    fun `两台服务器的内容不同`() {
        assertThat(MockHomeData.fnosContinueWatching.map { it.id })
            .isNotEqualTo(MockHomeData.continueWatching.map { it.id })
    }

    private fun mockItem(durationMinutes: Int = 90, watchedFraction: Float = 0.3f): MediaItemDto =
        mockItemSeconds(total = durationMinutes * 60L, resume = (durationMinutes * 60L * watchedFraction).toLong())

    private fun mockItemSeconds(total: Long, resume: Long) = MediaItemDto(
        id = "t-$total-$resume",
        serverId = "srv",
        sourceType = SourceType.EMBY,
        title = "测试影片",
        durationTicks = Ticks.ofSeconds(total),
        userData = UserData(resumePositionTicks = Ticks.ofSeconds(resume)),
    )
}
