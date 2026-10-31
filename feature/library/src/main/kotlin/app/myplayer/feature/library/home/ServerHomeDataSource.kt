package app.myplayer.feature.library.home

import app.myplayer.core.model.ImageKind
import app.myplayer.core.model.ImageSize
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.ItemQuery
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.MediaSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 真实数据源：一台 [MediaSource] → 一屏首页。
 *
 * ## 请求编排（全部并发，总耗时 ≈ 最慢的那个，而不是四者相加）
 * | 模块 | 端点 | 失败策略 |
 * |---|---|---|
 * | 库列表 | `getLibraries()` | **上抛** —— 拿不到库 = 连不上服务器，首页进「无法连接」空态 |
 * | 继续观看 | `getResumeItems()` | 吞掉，只少一个模块（飞牛等源本来就没有） |
 * | 每库横滑 | `getItems(libraryId, limit)` | 吞掉，只让那一块消失 |
 * | 最近添加 | `getLatestItems()` | 吞掉（老版本服务端可能没有 `/Items/Latest`） |
 *
 * 这样「服务器连不上」和「某个端点不支持」是两种不同的降级，不会因为后者把整页判成离线。
 *
 * ## 库横滑取的是「作品」而不是「集」
 * 优先 `Movie,Series,Video`：剧集库若按默认类型递归查询，回来的是一堆 Episode，
 * 首页就成了「按集铺开」——既和「查看全部」进库页看到的对不上，也没有剧集海报可看。
 * 只有该库里确实没有作品级条目（例如纯文件夹式视频库）时才退一步放开 Episode。
 */
class ServerHomeDataSource(
    private val source: MediaSource,
    private val serverName: String,
    private val librarySectionLimit: Int = DEFAULT_LIBRARY_LIMIT,
    private val continueWatchingLimit: Int = DEFAULT_CONTINUE_LIMIT,
    private val recentlyAddedLimit: Int = DEFAULT_RECENT_LIMIT,
) : HomeDataSource {

    /**
     * 身份 = 底层 [MediaSource] 实例本身。
     *
     * 重新登录 / 改端点后 `source` 会被重建成新实例 → 身份变 → 真的重载；
     * tab 切走切回只是重新包装了一个 wrapper（`source` 未变）→ 身份相同 → 不重载。
     */
    override val sourceKey: Any get() = source

    override suspend fun load(serverId: String): HomeSnapshot = coroutineScope {
        // 三路「一屏级」请求先并发发出去
        val libsPlan = async { source.getLibraries() }          // 故意不 catch：失败要上抛给 UI
        val resumePlan = async {
            runCatching { source.getResumeItems(continueWatchingLimit) }.getOrDefault(emptyList())
        }
        val latestPlan = async {
            runCatching { source.getLatestItems(recentlyAddedLimit) }.getOrDefault(emptyList())
        }

        val libraries = libsPlan.await()
        val sections = libraries.map { lib ->
            async {
                val items = runCatching { libraryItems(lib.id) }.getOrDefault(emptyList())
                HomeLibrarySection(
                    id = lib.id,
                    name = lib.name,
                    items = items,
                    totalCount = lib.itemCount ?: items.size,
                )
            }
        }.awaitAll()
            // 空库不出横滑行：标题 + 空 LazyRow 会在大网格里留一块莫名的空白
            .filter { it.items.isNotEmpty() }

        // 模块二（Emby 口径）：**只出非空库** —— 空库在首页没有入口意义。
        // 封面借该库首条缩略图：MediaSource 没有「库封面」接口，首条既真实又不多发请求。
        val cards = sections.map { section ->
            HomeLibraryCard(
                id = section.id,
                name = section.name,
                itemCount = section.totalCount,
                cover = section.items.firstOrNull(),
            )
        }

        HomeSnapshot(
            serverName = serverName,
            // 首页形态跟着源走：SMB / LOCAL 显示「我的文件夹」（16:9 文件卡），其它显示「我的媒体库」
            // LOCAL 一并归入文件夹形态：档二（卡片隐私标记）对两种文件源都生效
            sourceType = if (source.type == app.myplayer.core.model.SourceType.SMB ||
                source.type == app.myplayer.core.model.SourceType.LOCAL
            ) {
                HomeSourceType.SMB
            } else {
                HomeSourceType.EMBY
            },
            continueWatching = resumePlan.await(),
            myLibrariesOrFolders = cards,
            dynamicSections = sections,
            recentlyAdded = latestPlan.await(),
        )
    }

    /** 库内条目：先取作品级，取不到再放开到 Episode（见类注释）。 */
    private suspend fun libraryItems(libraryId: String): List<MediaItemDto> {
        val works = source.getItems(
            ItemQuery(libraryId = libraryId, limit = librarySectionLimit, includeItemTypes = WORKS)
        ).items
        if (works.isNotEmpty()) return works
        return source.getItems(ItemQuery(libraryId = libraryId, limit = librarySectionLimit)).items
    }

    /**
     * 图片尺寸：海报按卡片实际尺寸取 400px 档（2:3 竖卡 100dp @3x）；
     * 16:9 横卡宽 240dp @3x = 720px，取 800px 档避免放大发虚。
     */
    override suspend fun image(item: MediaItemDto, kind: ImageKind): ImageSource =
        source.image(item, kind, if (kind == ImageKind.POSTER) ImageSize.MEDIUM else ImageSize.LARGE)

    companion object {
        /** 每个库横滑预取条数：够滑两屏，又不至于让首屏请求变重。 */
        const val DEFAULT_LIBRARY_LIMIT = 12
        const val DEFAULT_CONTINUE_LIMIT = 20
        const val DEFAULT_RECENT_LIMIT = 30

        private val WORKS = listOf("Movie", "Series", "Video")
    }
}

/**
 * 源不可用时的兜底数据源（例如配置存在但登录态失效）。
 *
 * 不做任何取数，直接把原因抛给 [HomeViewModel] → 首页进「无法连接到服务器 + 重试」空态，
 * 而不是静默显示「这里还没有内容」——后者会让人以为服务器里真的没片。
 */
class OfflineHomeDataSource(private val reason: String) : HomeDataSource {

    /** 所有离线兜底都算同一个源：切 tab 来回不该被当成换源重新弹一次错误。 */
    override val sourceKey: Any get() = OFFLINE_SOURCE_KEY

    override suspend fun load(serverId: String): HomeSnapshot = throw IllegalStateException(reason)
    override suspend fun image(item: MediaItemDto, kind: ImageKind): ImageSource = ImageSource.None

    private companion object {
        val OFFLINE_SOURCE_KEY = Any()
    }
}
