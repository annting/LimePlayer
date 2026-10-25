package app.myplayer.feature.library.home

import app.myplayer.core.model.ImageKind
import app.myplayer.core.model.ImageSize
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.SourceType
import app.myplayer.core.model.Ticks
import app.myplayer.core.model.UserData
import kotlinx.coroutines.delay

/**
 * 首页取数契约。
 *
 * 生产实现是 [ServerHomeDataSource]（真机数据：库列表 + 续播 + 最新入库）；
 * [MockHomeDataSource] 保留给单元测试与 `@Preview`（无网络时也能渲染完整一屏）。
 */
interface HomeDataSource {

    /**
     * 数据源的**身份**：同一台服务器被重新包装出来的新实例必须返回同一个值。
     *
     * 为什么需要它：首页 tab 切走时 `HomeDestination` 退出组合、`remember` 槽位销毁，
     * 切回来工厂会**重新造一个 wrapper 实例**（底层 `MediaSource` 其实没变）。
     * `HomeViewModel.rebind` 若按 wrapper 的实例相等判定「换源了」，每次切 tab 都会
     * 误判成换源 → `isLoading = true` → 整页塌成骨架屏再重载一遍（BUG-2026-002）。
     */
    val sourceKey: Any

    /** 拉一屏数据；失败时抛异常（由 ViewModel 翻成 [HomeUiState.error]）。 */
    suspend fun load(serverId: String): HomeSnapshot

    /** 与 `MediaSource.image()` 同签名，页面拿到的永远是已经解析好的 [ImageSource]。 */
    suspend fun image(item: MediaItemDto, kind: ImageKind): ImageSource
}

/** 一屏首页数据。 */
data class HomeSnapshot(
    val serverName: String,
    /** 数据源形态：Emby 系 = 库，SMB = 顶层文件夹。 */
    val sourceType: HomeSourceType = HomeSourceType.EMBY,
    val continueWatching: List<MediaItemDto> = emptyList(),
    /** 模块二入口：库或文件夹。 */
    val myLibrariesOrFolders: List<HomeLibraryCard> = emptyList(),
    /** 模块三：每个库/文件夹一行。 */
    val dynamicSections: List<HomeLibrarySection> = emptyList(),
    val recentlyAdded: List<MediaItemDto> = emptyList(),
)

/**
 * 假数据源（单测与 `@Preview` 用；生产走 [ServerHomeDataSource]）。
 *
 * - 两台服务器返回不同内容，用来验证顶部「切换来源」真的会换数据；
 * - [failFor] 里的 serverId 会走失败分支，方便在真机上看「无法连接到服务器」空态；
 * - 图片走 picsum 占位站：连不上时卡片底层的渐变占位会兜住，不会出现白块。
 */
class MockHomeDataSource(
    private val latencyMs: Long = 550L,
    private val failFor: Set<String> = emptySet(),
) : HomeDataSource {

    /** Mock 无底层源：实例自身就是身份（换新实例 = 换源，与测试预期一致）。 */
    override val sourceKey: Any get() = this

    val servers: List<HomeServerOption> = MockHomeData.servers

    override suspend fun load(serverId: String): HomeSnapshot {
        delay(latencyMs)
        if (serverId in failFor) throw IllegalStateException("连接超时：192.168.31.15:8096 无响应")
        val name = servers.firstOrNull { it.id == serverId }?.name ?: MockHomeData.EMBY_NAME

        // SMB：没有媒体库概念 —— 顶层非空文件夹即分类，空文件夹在映射时被丢掉
        if (serverId == MockHomeData.SMB_ID) {
            return HomeSnapshot(
                serverName = name,
                sourceType = HomeSourceType.SMB,
                continueWatching = MockHomeData.smbContinueWatching,
                myLibrariesOrFolders = MockHomeData.smbFolders.toFolderCards(),
                dynamicSections = MockHomeData.smbFolders.toFolderSections(),
                recentlyAdded = MockHomeData.smbRecentlyAdded,
            )
        }

        val isFnos = serverId == MockHomeData.FNOS_ID
        return HomeSnapshot(
            serverName = name,
            sourceType = HomeSourceType.EMBY,
            continueWatching = if (isFnos) MockHomeData.fnosContinueWatching else MockHomeData.continueWatching,
            myLibrariesOrFolders = if (isFnos) MockHomeData.fnosLibraryCards else MockHomeData.libraryCards,
            dynamicSections = if (isFnos) MockHomeData.fnosLibraries else MockHomeData.libraries,
            recentlyAdded = if (isFnos) MockHomeData.fnosRecentlyAdded else MockHomeData.recentlyAdded,
        )
    }

    override suspend fun image(item: MediaItemDto, kind: ImageKind): ImageSource {
        // 16:9 用宽屏种子图，2:3 用竖版；种子固定 → 同一部片每次都是同一张图
        val (w, h) = when (kind) {
            ImageKind.POSTER -> 300 to 450
            else -> 480 to 270
        }
        return ImageSource.Remote("https://picsum.photos/seed/${item.id}/$w/$h")
    }
}

/**
 * 首页假数据。
 *
 * 全部是 `MediaItemDto`（真实领域模型），不是专门的 Mock 类型 —— 这样接真实源时
 * 页面一行都不用改，连海报/进度/剩余时长的计算路径都是同一套。
 */
object MockHomeData {

    const val EMBY_ID = "srv-emby"
    const val FNOS_ID = "srv-fnos"
    const val SMB_ID = "srv-smb"
    const val EMBY_NAME = "客厅 Emby"
    const val FNOS_NAME = "飞牛影视"
    const val SMB_NAME = "客厅 NAS · SMB"

    val servers = listOf(
        HomeServerOption(EMBY_ID, EMBY_NAME, "http://192.168.31.15:8096"),
        HomeServerOption(FNOS_ID, FNOS_NAME, "http://192.168.31.16:5666"),
        HomeServerOption(SMB_ID, SMB_NAME, "smb://192.168.31.20/media"),
    )

    // ---- Emby（主数据） ----

    val continueWatching: List<MediaItemDto> = listOf(
        episode("cw-1", "沙丘 2", totalMinutes = 166, watched = 0.32f, rating = 8.6f),
        episode("cw-2", "我们与恶的距离", series = "三体", season = 1, number = 5, totalMinutes = 45, watched = 0.61f),
        episode("cw-3", "奥本海默", totalMinutes = 180, watched = 0.08f, rating = 8.9f),
        episode("cw-4", "繁花", series = "繁花", season = 1, number = 8, totalMinutes = 47, watched = 0.74f),
        episode("cw-5", "星际穿越", totalMinutes = 169, watched = 0.45f, rating = 9.4f),
        episode("cw-6", "狂飙", series = "狂飙", season = 1, number = 12, totalMinutes = 46, watched = 0.15f),
    )

    val libraries: List<HomeLibrarySection> = listOf(
        HomeLibrarySection(
            id = "lib-movie",
            name = "电影",
            items = listOf(
                poster("mv-1", "沙丘 2", 2024, 8.6f),
                poster("mv-2", "奥本海默", 2023, 8.9f),
                poster("mv-3", "星际穿越", 2014, 9.4f),
                poster("mv-4", "盗梦空间", 2010, 9.3f),
                poster("mv-5", "银翼杀手 2049", 2017, 8.5f),
                poster("mv-6", "布达佩斯大饭店", 2014, 8.9f),
                poster("mv-7", "寄生虫", 2019, 8.7f),
                poster("mv-8", "蝙蝠侠：黑暗骑士", 2008, 9.2f),
            ),
            totalCount = 486,
        ),
        HomeLibrarySection(
            id = "lib-tv",
            name = "电视剧",
            items = listOf(
                poster("tv-1", "三体", 2023, 8.7f),
                poster("tv-2", "繁花", 2023, 8.5f),
                poster("tv-3", "漫长的季节", 2023, 9.4f),
                poster("tv-4", "狂飙", 2023, 8.5f),
                poster("tv-5", "隐秘的角落", 2020, 8.8f),
                poster("tv-6", "白夜追凶", 2017, 8.4f),
                poster("tv-7", "冰血暴", 2014, 9.0f),
                poster("tv-8", "唐顿庄园", 2010, 9.1f),
            ),
            totalCount = 214,
        ),
        HomeLibrarySection(
            id = "lib-doc",
            name = "纪录片",
            items = listOf(
                poster("dc-1", "地球脉动", 2006, 9.7f),
                poster("dc-2", "蓝色星球 II", 2017, 9.8f),
                poster("dc-3", "人类星球", 2011, 9.6f),
                poster("dc-4", "宇宙时空之旅", 2014, 9.5f),
                poster("dc-5", "舌尖上的中国", 2012, 9.3f),
            ),
            totalCount = 68,
        ),
    )

    /** 「我的媒体库」：封面取各库第一条的缩略图；最后一个空库用来验证封面兜底。 */
    val libraryCards: List<HomeLibraryCard> = listOf(
        HomeLibraryCard("lib-movie", "电影", 486, libraries[0].items.firstOrNull()),
        HomeLibraryCard("lib-tv", "电视剧", 214, libraries[1].items.firstOrNull()),
        HomeLibraryCard("lib-doc", "纪录片", 68, libraries[2].items.firstOrNull()),
        HomeLibraryCard("lib-mv", "音乐 MV", 0, null),
    )

    val recentlyAdded: List<MediaItemDto> = listOf(
        poster("ra-1", "沙丘 2", 2024, 8.6f),
        poster("ra-2", "奥本海默", 2023, 8.9f),
        poster("ra-3", "繁花", 2023, 8.5f),
        poster("ra-4", "蓝色星球 II", 2017, 9.8f),
        poster("ra-5", "银翼杀手 2049", 2017, 8.5f),
        poster("ra-6", "漫长的季节", 2023, 9.4f),
        poster("ra-7", "布达佩斯大饭店", 2014, 8.9f),
        poster("ra-8", "地球脉动", 2006, 9.7f),
        poster("ra-9", "寄生虫", 2019, 8.7f),
    ).mapIndexed { index, item ->
        // 入库时间错开 2~18 天（每条 +2 天）：首页设置的「时间范围 7 天」档正好演示物理过滤
        item.copy(dateCreated = System.currentTimeMillis() - (index + 1L) * 2 * DAY_MS)
    }

    // ---- 飞牛（第二台，用来验证切换来源） ----

    val fnosContinueWatching: List<MediaItemDto> = listOf(
        episode("fn-cw-1", "庆余年 第二季", series = "庆余年", season = 2, number = 3, totalMinutes = 46, watched = 0.42f),
        episode("fn-cw-2", "海上钢琴师", totalMinutes = 165, watched = 0.66f, rating = 9.3f),
        episode("fn-cw-3", "琅琊榜", series = "琅琊榜", season = 1, number = 21, totalMinutes = 45, watched = 0.28f),
        episode("fn-cw-4", "沙丘", totalMinutes = 155, watched = 0.12f, rating = 8.0f),
    )

    val fnosLibraries: List<HomeLibrarySection> = listOf(
        HomeLibrarySection(
            id = "fn-movie",
            name = "电影",
            items = listOf(
                poster("fn-mv-1", "海上钢琴师", 1998, 9.3f, FNOS_ID),
                poster("fn-mv-2", "沙丘", 2021, 8.0f, FNOS_ID),
                poster("fn-mv-3", "消失的爱人", 2014, 8.7f, FNOS_ID),
                poster("fn-mv-4", "爆裂鼓手", 2014, 8.7f, FNOS_ID),
            ),
            totalCount = 132,
        ),
        HomeLibrarySection(
            id = "fn-tv",
            name = "剧集",
            items = listOf(
                poster("fn-tv-1", "庆余年", 2019, 8.0f, FNOS_ID),
                poster("fn-tv-2", "琅琊榜", 2015, 9.4f, FNOS_ID),
                poster("fn-tv-3", "大明王朝 1566", 2007, 9.8f, FNOS_ID),
            ),
            totalCount = 41,
        ),
    )

    val fnosLibraryCards: List<HomeLibraryCard> = listOf(
        HomeLibraryCard("fn-movie", "电影", 132, fnosLibraries[0].items.firstOrNull()),
        HomeLibraryCard("fn-tv", "剧集", 41, fnosLibraries[1].items.firstOrNull()),
    )

    val fnosRecentlyAdded: List<MediaItemDto> = listOf(
        poster("fn-ra-1", "庆余年 第二季", 2024, 7.8f, FNOS_ID),
        poster("fn-ra-2", "海上钢琴师", 1998, 9.3f, FNOS_ID),
        poster("fn-ra-3", "爆裂鼓手", 2014, 8.7f, FNOS_ID),
        poster("fn-ra-4", "大明王朝 1566", 2007, 9.8f, FNOS_ID),
        poster("fn-ra-5", "消失的爱人", 2014, 8.7f, FNOS_ID),
        poster("fn-ra-6", "琅琊榜", 2015, 9.4f, FNOS_ID),
    )

    // ---- SMB（第三个「服务器」，用来验证文件夹形态） ----
    //
    // 注意：SMB 还没有真实数据源（source:smb 未实现），这批数据只服务 @Preview 与单测。
    // 两个空文件夹（Downloads / Music）是**故意**放的：映射时应当被过滤掉。

    val smbFolders: List<SmbFolder> = listOf(
        SmbFolder(
            name = "Movies",
            items = listOf(
                poster("smb-mv-1", "沙丘 2", 2024, 8.6f, SMB_ID),
                poster("smb-mv-2", "奥本海默", 2023, 8.9f, SMB_ID),
                poster("smb-mv-3", "星际穿越", 2014, 9.4f, SMB_ID),
                poster("smb-mv-4", "盗梦空间", 2010, 9.3f, SMB_ID),
                poster("smb-mv-5", "银翼杀手 2049", 2017, 8.5f, SMB_ID),
            ),
        ),
        SmbFolder(
            name = "TV Shows",
            items = listOf(
                poster("smb-tv-1", "三体", 2023, 8.7f, SMB_ID),
                poster("smb-tv-2", "漫长的季节", 2023, 9.4f, SMB_ID),
                poster("smb-tv-3", "繁花", 2023, 8.5f, SMB_ID),
            ),
        ),
        SmbFolder(
            name = "Documentaries",
            items = listOf(
                poster("smb-dc-1", "地球脉动", 2006, 9.7f, SMB_ID),
                poster("smb-dc-2", "蓝色星球 II", 2017, 9.8f, SMB_ID),
            ),
        ),
        // 空文件夹：里面没有受支持的视频文件 → 首页不展示
        SmbFolder(name = "Downloads", items = emptyList()),
        SmbFolder(name = "Music", items = emptyList()),
    )

    val smbContinueWatching: List<MediaItemDto> = listOf(
        episode("smb-cw-1", "沙丘 2", totalMinutes = 166, watched = 0.28f),
        episode("smb-cw-2", "三体", series = "三体", season = 1, number = 3, totalMinutes = 45, watched = 0.55f),
    ).map { it.copy(serverId = SMB_ID) }

    val smbRecentlyAdded: List<MediaItemDto> = listOf(
        poster("smb-ra-1", "沙丘 2", 2024, 8.6f, SMB_ID),
        poster("smb-ra-2", "三体", 2023, 8.7f, SMB_ID),
        poster("smb-ra-3", "地球脉动", 2006, 9.7f, SMB_ID),
        poster("smb-ra-4", "星际穿越", 2014, 9.4f, SMB_ID),
        poster("smb-ra-5", "繁花", 2023, 8.5f, SMB_ID),
    ).mapIndexed { index, item ->
        item.copy(dateCreated = System.currentTimeMillis() - (index + 1L) * 2 * DAY_MS)
    }

    // ---- 构造器 ----

    private fun poster(
        id: String,
        title: String,
        year: Int,
        rating: Float,
        serverId: String = EMBY_ID,
    ) = MediaItemDto(
        id = id,
        serverId = serverId,
        sourceType = SourceType.EMBY,
        title = title,
        durationTicks = Ticks.ofSeconds(45 * 60L),
        productionYear = year,
        communityRating = rating,
        hasPoster = true,
    )

    private fun episode(
        id: String,
        title: String,
        series: String? = null,
        season: Int? = null,
        number: Int? = null,
        totalMinutes: Int,
        watched: Float,
        rating: Float? = null,
    ): MediaItemDto {
        val total = Ticks.ofSeconds(totalMinutes * 60L)
        return MediaItemDto(
            id = id,
            serverId = EMBY_ID,
            sourceType = SourceType.EMBY,
            title = title,
            seriesName = series,
            seasonNumber = season,
            episodeNumber = number,
            durationTicks = total,
            communityRating = rating,
            hasPoster = true,
            userData = UserData(
                isPlayed = false,
                resumePositionTicks = Ticks.ofSeconds((totalMinutes * 60L * watched).toLong()),
            ),
        )
    }
}
