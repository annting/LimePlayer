package app.myplayer.feature.library.home

import app.myplayer.core.datastore.HomeModuleSettings
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.SourceType
import app.myplayer.core.model.Ticks

/**
 * 首页的两种形态（2026-10-24）。
 *
 * - [EMBY]：网络媒体服务器（Emby / Jellyfin / 飞牛）—— 库由服务端给，条目有海报。
 * - [SMB]：本地扫描缓存（SMB 共享）—— 没有「媒体库」概念，**顶层非空文件夹即分类**，
 *   条目只有文件名与 LastWriteTime，没有海报。
 *
 * 两种形态共用同一套卡片与分区块，只有「模块二」的语义与样式不同（库封面 vs 文件夹图标）。
 */
enum class HomeSourceType { EMBY, SMB }

/**
 * 首页（`docs/03` §6.0）的状态契约。
 *
 * 只有一个 [HomeUiState]，页面里**不允许**再散落 `remember { mutableStateOf }` 的业务状态：
 * 首屏 / 下拉刷新 / 失败 / 空态全部由这里的字段推导（见 [hasNoServer] / [isOffline] / [hasContent]）。
 */
data class HomeUiState(
    /** 当前数据源形态：决定模块二的标题与卡片样式。 */
    val sourceType: HomeSourceType = HomeSourceType.EMBY,
    /**
     * 首页设置（已按 [sourceType] 选好 Emby / SMB 对应那份）。
     *
     * 由 `HomeViewModel` 从 `HomeSettingsRepository` 的流合并进来 ——
     * 设置页改开关 → DataStore 写入 → 这个字段发新值 → 首页**无刷新**重组。
     */
    val settings: HomeModuleSettings = HomeModuleSettings(),
    /** 首屏加载（骨架屏）。下拉刷新走 [isRefreshing]，不会让内容消失。 */
    val isLoading: Boolean = false,
    /** 下拉刷新中（顶部转圈，内容保留）。 */
    val isRefreshing: Boolean = false,
    /** 加载失败 / 离线的一句话原因；非 null 即进入全屏空态 2。 */
    val error: String? = null,
    /** 当前服务器名（顶部栏左侧）。null = 没有可用服务器。 */
    val serverName: String? = null,
    val continueWatchingList: List<MediaItemDto> = emptyList(),
    /**
     * 模块二：Emby = 库（封面 + 库名）；SMB = 顶层非空文件夹（文件夹图标 + 名字）。
     *
     * 与 [dynamicSections] 的区别：这里是「入口目录」，一个库/文件夹一张卡；
     * 那边是「每个库/文件夹一行内容」。
     */
    val myLibrariesOrFolders: List<HomeLibraryCard> = emptyList(),
    /** 模块三：每个库（Emby）/ 每个非空文件夹（SMB）一行。 */
    val dynamicSections: List<HomeLibrarySection> = emptyList(),
    val recentlyAddedList: List<MediaItemDto> = emptyList(),
    /** 顶部「切换来源」下拉的候选；为空且 [serverName] 也为空 → 全屏空态 1。 */
    val servers: List<HomeServerOption> = emptyList(),
    val selectedServerId: String? = null,
    /**
     * 档二（2026-10-29）：本屏内被标了隐私的 key 集合 —— 文件真实路径 + 文件夹相对路径
     * （`smb:` 前缀剥掉）。仅在 SMB/LOCAL 形态下非空；卡片据此画左上角标志与菜单项文案。
     */
    val privateKeys: Set<String> = emptySet(),
) {
    /** 全屏空态 1：一个服务器都没配。 */
    val hasNoServer: Boolean get() = servers.isEmpty() && serverName == null

    /** 全屏空态 2：有服务器但连不上。 */
    val isOffline: Boolean get() = error != null

    /**
     * 首页设置把 4 个内容模块**全部关闭** → 首页没有可渲染的东西，
     * 显示「首页内容已隐藏」空态（而不是一片空白）。
     */
    val allModulesHidden: Boolean
        get() = !settings.showContinueWatching &&
            !settings.showLibraryEntries &&
            !settings.showDynamicSections &&
            !settings.showRecentlyAdded

    /**
     * 是否有**可渲染**的内容 = 模块开关 AND 数据非空。
     *
     * ⚠️ 必须带上开关（2026-10-24 修）：只看数据的话，「只开最近添加」而该模块被
     * 时间窗口过滤空时，`hasContent` 仍是 true（其它模块的数据还在），于是页面
     * 既不渲染任何区块、也不进兜底空态 —— 用户看到的是一片**纯空白**。
     */
    val hasContent: Boolean
        get() = (settings.showContinueWatching && continueWatchingList.isNotEmpty()) ||
            (settings.showLibraryEntries && myLibrariesOrFolders.isNotEmpty()) ||
            (settings.showDynamicSections && dynamicSections.any { it.items.isNotEmpty() }) ||
            (settings.showRecentlyAdded && recentlyAddedList.isNotEmpty())

    /**
     * 只开了「最近添加」一个模块。
     *
     * 空态文案要针对它给建议（放宽天数），而不是笼统说「服务器上还没有影片」——
     * 用户明明刚入库了片，只是被 7 天窗口裁掉了。
     */
    val onlyRecentlyAdded: Boolean
        get() = settings.showRecentlyAdded &&
            !settings.showContinueWatching &&
            !settings.showLibraryEntries &&
            !settings.showDynamicSections
}

/** 顶部「切换来源」下拉项。 */
data class HomeServerOption(
    val id: String,
    val name: String,
    val baseUrl: String = "",
    /** 服务器类型：决定下拉里那枚小 logo（Emby 绿 / Jellyfin 紫 / 飞牛 橙 / SMB 蓝）。 */
    val sourceType: SourceType = SourceType.EMBY,
)

/**
 * 「我的媒体库」的一张 16:9 卡片。
 *
 * 与 [HomeLibrarySection] 的区别：
 * - section = 首页里「一个库一行片子」的动态分类块；
 * - card = 库本身（库名 + 影片数 + 封面），点进去就是该库的完整列表。
 *
 * [cover] 取该库第一条的缩略图 —— `MediaSource` 没有「库封面」接口，
 * 用首条缩略图既真实又零额外请求；空库为 null，卡片退回渐变占位。
 */
data class HomeLibraryCard(
    val id: String,
    val name: String,
    val itemCount: Int = 0,
    val cover: MediaItemDto? = null,
    /** SMB 文件夹卡：深灰底 + 居中文件夹图标，不加载封面。 */
    val isFolder: Boolean = false,
)

/** 一个媒体库分类块：标题行 + 内部横滑。 */
data class HomeLibrarySection(
    val id: String,
    val name: String,
    val items: List<MediaItemDto> = emptyList(),
    /** 库内总数（> [items] 的条数时「查看全部」更有意义）。 */
    val totalCount: Int = items.size,
)

/**
 * SMB 扫描结果里的一个**顶层文件夹**（未来 `source:smb` 的投影；当前只有 Mock 用）。
 *
 * 规矩：**空文件夹不进首页**。判定标准是「里面有没有受支持的视频文件」
 * （.mp4/.mkv/.avi/.ts/.mov…，由扫描器决定），这里用 [items] 是否为空表达。
 */
data class SmbFolder(
    val name: String,
    val items: List<MediaItemDto> = emptyList(),
)

/**
 * 文件夹 → 模块二卡片（空文件夹直接丢弃）。
 */
internal fun List<SmbFolder>.toFolderCards(): List<HomeLibraryCard> =
    filter { it.items.isNotEmpty() }.map { folder ->
        HomeLibraryCard(
            id = "smb:${folder.name}",
            name = folder.name,
            itemCount = folder.items.size,
            cover = null,          // 文件夹不用封面，走深灰底 + 图标
            isFolder = true,
        )
    }

/**
 * 文件夹 → 模块三分区（同样过滤空文件夹；每行只取最新的 [limit] 个）。
 */
internal fun List<SmbFolder>.toFolderSections(limit: Int = 20): List<HomeLibrarySection> =
    filter { it.items.isNotEmpty() }.map { folder ->
        HomeLibrarySection(
            id = "smb:${folder.name}",
            name = folder.name,
            items = folder.items.take(limit),
            totalCount = folder.items.size,
        )
    }

/**
 * 续播剩余时长文案（「剩余 23 分钟」）。
 *
 * 与 [app.myplayer.core.model.formatDuration] 不同：这里要的是**人话短文案**，
 * 不用「1小时23分45秒」那种精确到秒的表达式（卡片底部只有一行）。
 * 已看完 / 时长未知 → null（调用方据此不渲染该行）。
 */
fun MediaItemDto.remainingWatchText(): String? {
    val leftSeconds = (durationTicks.value - userData.resumePositionTicks.value) / 10_000_000L
    if (leftSeconds <= 0L) return null
    return when {
        leftSeconds >= 3600L -> {
            val hours = leftSeconds / 3600L
            val minutes = (leftSeconds % 3600L) / 60L
            if (minutes > 0L) "剩余 $hours 小时 $minutes 分" else "剩余 $hours 小时"
        }
        // 不足 1 分钟按 1 分钟算，避免出现「剩余 0 分钟」
        else -> "剩余 ${((leftSeconds + 59L) / 60L).coerceAtLeast(1L)} 分钟"
    }
}

/** 进度条占比：复用 `:core:model` 的 [app.myplayer.core.model.resumeFraction]，避免两处口径不一致。 */
internal fun MediaItemDto.progressFraction(): Float {
    if (durationTicks == Ticks.ZERO) return 0f
    return (userData.resumePositionTicks.value.toDouble() / durationTicks.value.toDouble())
        .toFloat()
        .coerceIn(0f, 1f)
}
